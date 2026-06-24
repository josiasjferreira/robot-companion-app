package br.com.onlife.kenmotionbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.onlife.kenmotionbridge.BridgeConfig
import br.com.onlife.kenmotionbridge.R
import br.com.onlife.kenmotionbridge.ipc.IFeedbackSink
import br.com.onlife.kenmotionbridge.ipc.IRobotControl
import br.com.onlife.kenmotionbridge.mqtt.MqttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Processo `:mqtt` da ponte (Solução 1 — dual-homing por processo).
 *
 * Amarra ESTE processo à rede de INTERNET (tethering/hotspot) via [bindProcessToNetwork] — assim o
 * Paho fala com o HiveMQ normalmente (DNS + socket pela internet), sem hacks de socket. Recebe
 * `ken/motion/cmd` e repassa ao processo principal ([RobotBridgeService]) por IPC; recebe
 * feedback/telemetria de volta e publica. Garante o **heartbeat de 1 s** em `ken/motion/feedback`
 * mesmo com o chassi/robô offline (o app web desabilita os controles sem heartbeat por 5 s).
 */
class MqttBridgeService : Service() {

    companion object {
        private const val TAG = "MqttBridgeService"
        private const val CHANNEL_ID = "ken_motion_bridge"
        private const val NOTIF_ID = 1002
        /** Se não chega feedback do robô há mais que isto, publicamos heartbeat sintético offline. */
        private const val FEEDBACK_STALE_MS = 1500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    private lateinit var config: BridgeConfig
    private lateinit var mqtt: MqttManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val internetNet = AtomicReference<Network?>()
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var robot: IRobotControl? = null
    @Volatile private var lastFeedbackAt = 0L
    @Volatile private var mqttUp = false

    // ── Canal de volta exposto ao processo principal ──────────────────────────
    private val feedbackSink = object : IFeedbackSink.Stub() {
        override fun onFeedback(json: String?) {
            json ?: return
            lastFeedbackAt = System.currentTimeMillis()
            mqtt.publish(config.topicFeedback, json, qos = 0, retained = false)
        }
        override fun onTelemetry(json: String?) {
            json ?: return
            mqtt.publish(config.topicTelemetry, json, qos = 0, retained = false)
        }
    }

    private val robotConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            robot = IRobotControl.Stub.asInterface(service).also { r ->
                runCatching { r.registerFeedback(feedbackSink) }
                    .onFailure { Log.w(TAG, "registerFeedback falhou: ${it.message}") }
                // Reflete o estado atual do broker assim que ligamos ao processo principal.
                runCatching { r.reportMqttStatus(mqttUp, if (mqttUp) "" else "conectando…") }
            }
            Log.i(TAG, "Ligado ao RobotBridgeService (IPC)")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            robot = null
            Log.w(TAG, "RobotBridgeService desligou (IPC)")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = BridgeConfig.load(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("MQTT iniciando…"))
        acquireWakeLock()

        mqtt = MqttManager(
            context = this,
            config = config,
            onConnectionChanged = { up, err ->
                mqttUp = up
                runCatching { robot?.reportMqttStatus(up, err) }
            },
            onCommand = { payload -> forwardCommand(payload) },
        )

        bindInternetThenConnect()
        bindToRobot()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RobotBridgeService.ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            RobotBridgeService.ACTION_RESTART -> reconnect()
        }
        return START_STICKY
    }

    private fun forwardCommand(payload: String) {
        val r = robot
        if (r == null) { Log.w(TAG, "Comando MQTT sem IPC ativo — ignorado"); return }
        try { r.onCommand(payload) } catch (e: RemoteException) {
            Log.w(TAG, "onCommand IPC falhou: ${e.message}"); robot = null
        }
    }

    /** Amarra o processo :mqtt à rede com internet (tethering/hotspot) e conecta o broker. */
    private fun bindInternetThenConnect() {
        val cm = getSystemService(ConnectivityManager::class.java)
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mqtt.connect(); return
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = internetNet.getAndSet(network)
                runCatching { cm.bindProcessToNetwork(network) }
                    .onFailure { Log.w(TAG, "bindProcessToNetwork(internet) falhou: ${it.message}") }
                Log.i(TAG, "Internet disponível ($network) — :mqtt amarrado; conectando broker")
                if (prev == null) mqtt.connect() else mqtt.reconnectNow("rede de internet trocou")
            }
            override fun onLost(network: Network) {
                if (internetNet.compareAndSet(network, null)) {
                    Log.w(TAG, "Internet perdida — broker pode cair até nova rede")
                }
            }
        }
        runCatching { cm.requestNetwork(req, cb); netCallback = cb }
            .onFailure {
                Log.w(TAG, "requestNetwork(internet) falhou: ${it.message}; usando rota padrão")
                mqtt.connect()
            }
    }

    private fun bindToRobot() {
        val intent = Intent(this, RobotBridgeService::class.java)
        runCatching { bindService(intent, robotConn, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.e(TAG, "bindService(RobotBridgeService) falhou: ${it.message}") }
    }

    /** Heartbeat: se o robô não envia feedback há mais de [FEEDBACK_STALE_MS], publica offline. */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastFeedbackAt > FEEDBACK_STALE_MS) {
                    val fb = JSONObject().apply {
                        put("online", false)
                        put("v", JSONObject.NULL)
                        put("w", JSONObject.NULL)
                        put("front_cm", JSONObject.NULL)
                        put("ts", now)
                    }
                    mqtt.publish(config.topicFeedback, fb.toString(), qos = 0, retained = false)
                }
                updateNotification()
                delay(1000L)
            }
        }
    }

    private fun reconnect() {
        config = BridgeConfig.load(this)
        runCatching { mqtt.reconnectNow("reiniciar ponte") }
    }

    private fun updateNotification() {
        val txt = "MQTT:${if (mqttUp) "OK" else "—"}  IPC:${if (robot != null) "OK" else "—"}"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(txt))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KenMotionBridge::mqtt").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "KEN Motion Bridge", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Ponte de movimento do robô KEN" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KEN Motion Bridge — MQTT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        heartbeatJob?.cancel()
        runCatching { robot?.unregisterFeedback(feedbackSink) }
        runCatching { unbindService(robotConn) }
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        runCatching { mqtt.disconnect() }
        runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }
}
