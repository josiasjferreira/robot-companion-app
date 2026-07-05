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
import br.com.onlife.kenmotionbridge.net.NetworkRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    private var netLoopJob: Job? = null

    private lateinit var config: BridgeConfig
    private lateinit var mqtt: MqttManager
    private val networkRouter by lazy { NetworkRouter(this) }
    private var wakeLock: PowerManager.WakeLock? = null

    /** Rede de internet atualmente amarrada ao processo :mqtt (a que alcança o broker). */
    @Volatile private var boundNet: Network? = null

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

        bindToRobot()
        startNetworkLoop()
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

    /**
     * Seleciona, por DADOS, a rede que REALMENTE alcança o broker (sonda L4 com DNS escopado),
     * amarra o processo :mqtt a ela e (re)conecta o broker. Evita o falso-positivo do "INET val=OK"
     * da rede do chassi. Reavalia a cada 5 s e quando a internet troca. Publica o diagnóstico do
     * lado MQTT na tela via [IRobotControl.reportMqttStatus].
     */
    private fun startNetworkLoop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { mqtt.reconnectNow("sem bind (API<23)"); return }
        val host = BridgeConfig.hostFromUri(config.mqttUri)
        val port = BridgeConfig.portFromUri(config.mqttUri)
        val cm = getSystemService(ConnectivityManager::class.java)
        netLoopJob = scope.launch {
            while (isActive) {
                if (!mqttUp && cm != null) {
                    val net = pickInternetNetwork(host, port)
                    if (net != null) {
                        if (net != boundNet) {
                            runCatching { cm.bindProcessToNetwork(net) }
                            boundNet = net
                            Log.i(TAG, "Internet que alcança o broker: $net — :mqtt amarrado")
                            mqtt.reconnectNow("rede de internet selecionada")
                        }
                        report(false, "conectando ao broker via $net…")
                    } else {
                        runCatching { cm.bindProcessToNetwork(null) }
                        boundNet = null
                        report(false, "sem internet — nenhuma rede alcança $host:$port (ative o tethering USB)")
                    }
                }
                delay(5000L)
            }
        }
    }

    /** Primeira rede com INTERNET cuja sonda L4 alcança o broker (DNS escopado + TCP). */
    private fun pickInternetNetwork(host: String, port: Int): Network? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = cm.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        return candidates.firstOrNull { networkRouter.probeTcp(host, port, it) == "OK" }
    }

    private fun report(connected: Boolean, msg: String) {
        runCatching { robot?.reportMqttStatus(connected, msg) }
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
        // Injeta a config recém-carregada no manager ANTES de reconectar — sem isto o
        // Paho reconectava com o host/senha do boot do processo (config velha).
        mqtt.config = config
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
        netLoopJob?.cancel()
        runCatching { robot?.unregisterFeedback(feedbackSink) }
        runCatching { unbindService(robotConn) }
        runCatching { mqtt.disconnect() }
        runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }
}
