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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var feedbackJob: Job? = null

    private lateinit var config: BridgeConfig
    private lateinit var chassis: SlamwareChassis
    private lateinit var motion: MotionController
    private lateinit var mqtt: MqttManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = BridgeConfig.load(this)
        chassis = SlamwareChassis(this, config)
        motion = MotionController(chassis, config)
        mqtt = MqttManager(
            config = config,
            onConnectionChanged = { up ->
                StatusBus.update { it.copy(brokerConnected = up) }
            },
            onCommand = { payload -> motion.onCommand(payload) },
        )

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Iniciando…"))
        acquireWakeLock()

        // Conexões (fora da thread principal).
        scope.launch {
            val sdkOk = chassis.connect()
            StatusBus.update { it.copy(sdkConnected = sdkOk) }
            mqtt.connect()
        }

        startControlLoop()
        startFeedbackLoop()
        StatusBus.update { it.copy(serviceRunning = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: o sistema reinicia o serviço se for morto.
        return START_STICKY
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

    /** Feedback + telemetria nativa a 1 Hz. */
    private fun startFeedbackLoop() {
        feedbackJob = scope.launch {
            while (isActive) {
                val frontCm = motion.frontCm
                val battery = chassis.batteryPercent()
                StatusBus.update { it.copy(batteryPct = battery) }

                // ken/motion/feedback — resolve o "SEM SINAL".
                val fb = JSONObject().apply {
                    put("online", true)
                    put("v", round3(motion.currentV))
                    put("w", round3(motion.currentW))
                    put("front_cm", if (frontCm.isNaN()) JSONObject.NULL else round1(frontCm))
                    put("ts", System.currentTimeMillis())
                }
                mqtt.publish(config.topicFeedback, fb.toString(), qos = 0, retained = false)

                // ken/sensors/telemetry — telemetria nativa do SDK (bateria/IMU).
                val tel = chassis.telemetryJson().apply { put("ts", System.currentTimeMillis()) }
                if (tel.length() > 1) {
                    mqtt.publish(config.topicTelemetry, tel.toString())
                }

                updateNotification()
                delay(1000L)
            }
        }
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
        runCatching { mqtt.disconnect() }
        runCatching { chassis.disconnect() }
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
