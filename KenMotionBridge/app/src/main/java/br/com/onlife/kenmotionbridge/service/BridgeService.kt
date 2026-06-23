package br.com.onlife.kenmotionbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.onlife.kenmotionbridge.BridgeConfig
import br.com.onlife.kenmotionbridge.MainActivity
import br.com.onlife.kenmotionbridge.R
import br.com.onlife.kenmotionbridge.StatusBus
import br.com.onlife.kenmotionbridge.motion.MotionController
import br.com.onlife.kenmotionbridge.mqtt.MqttManager
import br.com.onlife.kenmotionbridge.sdk.KenMotionSdk
import br.com.onlife.kenmotionbridge.sdk.SlamwareChassis
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
 * Serviço em foreground que mantém a ponte viva com a tela apagada:
 *  - MQTT (assina ken/motion/cmd);
 *  - loop de controle ~20 Hz (MotionController.tick);
 *  - feedback 1 Hz em ken/motion/feedback + telemetria nativa em ken/sensors/telemetry.
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "ken_motion_bridge"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "br.com.onlife.kenmotionbridge.STOP"
        const val ACTION_RESTART = "br.com.onlife.kenmotionbridge.RESTART"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var feedbackJob: Job? = null

    private lateinit var config: BridgeConfig
    private lateinit var chassis: SlamwareChassis
    private lateinit var motionSdk: KenMotionSdk
    private lateinit var motion: MotionController
    private lateinit var mqtt: MqttManager
    private var wakeLock: PowerManager.WakeLock? = null
    /** true logo após onCreate, para não reconectar em dobro quando o start traz ACTION_RESTART. */
    private var freshlyCreated = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = BridgeConfig.load(this)
        buildPipeline()

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Iniciando…"))
        acquireWakeLock()

        connectAll()

        startControlLoop()
        startFeedbackLoop()
        StatusBus.update { it.copy(serviceRunning = true) }
        freshlyCreated = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // Se o serviço acabou de subir, onCreate já conectou; evita reconexão dupla.
                if (!freshlyCreated) restartConnections()
            }
        }
        freshlyCreated = false
        // START_STICKY: o sistema reinicia o serviço se for morto.
        return START_STICKY
    }

    /** (Re)constrói chassi + controlador + cliente MQTT a partir do [config] atual. */
    private fun buildPipeline() {
        // Expõe na tela o usuário/senha-mascarada que SERÃO enviados ao broker.
        StatusBus.update {
            it.copy(brokerUser = config.mqttUser, brokerPassLen = config.mqttPassword.length)
        }
        chassis = SlamwareChassis(this, config) { connected, error, bnd, classTried ->
            StatusBus.update {
                it.copy(
                    sdkConnected = connected,
                    sdkError = error,
                    sdkBound = bnd,
                    sdkClassTried = classTried,
                )
            }
        }
        // Camada central de movimento do chassi (RobotSDK CSJBot).
        motionSdk = KenMotionSdk(chassis)
        motion = MotionController(chassis, config, motionSdk)
        mqtt = MqttManager(
            context = this,
            config = config,
            onConnectionChanged = { up, err ->
                StatusBus.update {
                    it.copy(
                        brokerConnected = up,
                        brokerError = if (up) "" else (err ?: it.brokerError),
                    )
                }
            },
            onCommand = { payload -> motion.onCommand(payload) },
        )
    }

    /** Conecta chassi (SDK) e broker MQTT em background. O status do SDK chega pelo callback. */
    private fun connectAll() {
        scope.launch {
            chassis.connect()
            motionSdk.inicializarConexaoRobo()
            mqtt.connect()
        }
    }

    /** Recarrega config (settings) e reconecta tudo — usado pelo botão "Reiniciar ponte". */
    private fun restartConnections() {
        scope.launch {
            StatusBus.update { it.copy(brokerConnected = false, brokerError = "reiniciando…") }
            runCatching { mqtt.disconnect() }
            runCatching { chassis.disconnect() }
            config = BridgeConfig.load(this@BridgeService)
            buildPipeline()
            chassis.connect()
            motionSdk.inicializarConexaoRobo()
            mqtt.connect()
        }
    }

    /** Loop de controle a ~controlHz (default 20 Hz / 50 ms). */
    private fun startControlLoop() {
        val periodMs = (1000L / config.controlHz).coerceAtLeast(10L)
        loopJob = scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = (now - last) / 1_000_000_000.0
                last = now
                try {
                    motion.tick(dt)
                } catch (t: Throwable) {
                    Log.e(TAG, "Erro no tick: ${t.message}")
                }
                StatusBus.update {
                    it.copy(
                        linear = motion.currentV,
                        angular = motion.currentW,
                        frontCm = motion.frontCm,
                        lastCommand = motion.lastCommandLabel,
                    )
                }
                delay(periodMs)
            }
        }
    }

    /** Feedback + telemetria nativa a 1 Hz, com reconexão e robustez a erros. */
    private fun startFeedbackLoop() {
        feedbackJob = scope.launch {
            while (isActive) {
                // Todo o corpo é protegido: um erro de telemetria NÃO mata o loop para sempre.
                try {
                    publicarTelemetria()
                } catch (t: Throwable) {
                    Log.e(TAG, "Erro no loop de telemetria (continuando): ${t.message}", t)
                }
                delay(1000L)
            }
        }
    }

    private fun publicarTelemetria() {
        val now = System.currentTimeMillis()

        // Saúde do canal direto: se cremos estar conectados mas o DC caiu, reconectar.
        if (chassis.connected && !chassis.dcConnected()) {
            Log.w(TAG, "Canal Slamware caiu (getDCIsConnected=false) — reconectando…")
            StatusBus.update { it.copy(sdkConnected = false, sdkError = "reconectando ao chassi…") }
            chassis.connect()
        }

        val tel = chassis.readTelemetry()
        val online = chassis.connected && tel.dcConnected

        // Fonte ÚNICA de verdade: atualiza o StatusBus (UI lê daqui).
        StatusBus.update {
            it.copy(
                sdkConnected = online,
                batteryPct = tel.battery,
                charging = tel.charging,
                poseX = tel.poseX, poseY = tel.poseY, poseYawDeg = tel.poseYawDeg,
                localization = tel.localization,
                frontCm = if (tel.frontCm.isNaN()) motion.frontCm else tel.frontCm,
                telemetryAt = if (online) now else it.telemetryAt,
            )
        }

        // ken/motion/feedback — resolve o "SEM SINAL" no app web.
        val fb = JSONObject().apply {
            put("online", online)
            put("v", round3(motion.currentV))
            put("w", round3(motion.currentW))
            put("front_cm", if (tel.frontCm.isNaN()) JSONObject.NULL else round1(tel.frontCm))
            put("ts", now)
        }
        mqtt.publish(config.topicFeedback, fb.toString(), qos = 0, retained = false)

        // ken/sensors/telemetry — telemetria nativa do SDK (pose/bateria/velocidade/localização).
        if (online) {
            val tj = chassis.telemetryJson().apply { put("ts", now) }
            mqtt.publish(config.topicTelemetry, tj.toString())
        }

        updateNotification()
    }

    private fun updateNotification() {
        val s = StatusBus.state.value
        val txt = "Broker:${if (s.brokerConnected) "OK" else "—"}  " +
                "SDK:${if (s.sdkConnected) "OK" else "—"}  " +
                "v=%.2f w=%.2f".format(s.linear, s.angular)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(txt))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KenMotionBridge::wl").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "KEN Motion Bridge", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ponte de movimento do robô KEN" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KEN Motion Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        StatusBus.update { it.copy(serviceRunning = false, brokerConnected = false, sdkConnected = false) }
        loopJob?.cancel()
        feedbackJob?.cancel()
        runCatching { motion.stop() }
        runCatching { motionSdk.liberar() }
        runCatching { mqtt.disconnect() }
        runCatching { chassis.disconnect() }
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
