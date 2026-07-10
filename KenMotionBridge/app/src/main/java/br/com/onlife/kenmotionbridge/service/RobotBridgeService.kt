package br.com.onlife.kenmotionbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.onlife.kenmotionbridge.BridgeConfig
import br.com.onlife.kenmotionbridge.MainActivity
import br.com.onlife.kenmotionbridge.R
import br.com.onlife.kenmotionbridge.StatusBus
import br.com.onlife.kenmotionbridge.ipc.IFeedbackSink
import br.com.onlife.kenmotionbridge.ipc.IRobotControl
import br.com.onlife.kenmotionbridge.motion.MotionController
import br.com.onlife.kenmotionbridge.net.NetworkRouter
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
 * Processo PRINCIPAL da ponte (Solução 1 — dual-homing por processo).
 *
 * Responsável APENAS pelo chassi: amarra ESTE processo à rede do chassi (`192.168.99.x`, sem
 * internet) via [NetworkRouter.bindProcess], conecta o RobotSDK, roda o loop de controle (~20 Hz)
 * e produz feedback/telemetria a 1 Hz. NÃO fala MQTT — isso vive no processo `:mqtt`
 * ([MqttBridgeService]), que tem o próprio bind de rede (internet) e conversa com este serviço por
 * IPC ([IRobotControl]/[IFeedbackSink]).
 */
class RobotBridgeService : Service() {

    companion object {
        private const val TAG = "RobotBridgeService"
        private const val CHANNEL_ID = "ken_motion_bridge"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "br.com.onlife.kenmotionbridge.STOP"
        const val ACTION_RESTART = "br.com.onlife.kenmotionbridge.RESTART"
        /** Dispara o cenário de teste FRENTE 05/07 (botão da UI). */
        const val ACTION_FRONT_TEST = "br.com.onlife.kenmotionbridge.FRONT_TEST"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var feedbackJob: Job? = null

    private lateinit var config: BridgeConfig
    private lateinit var chassis: SlamwareChassis
    private lateinit var motionSdk: KenMotionSdk
    private lateinit var motion: MotionController
    private lateinit var integration: br.com.onlife.kenmotionbridge.control.SlamwareIntegrationService
    private val networkRouter by lazy { NetworkRouter(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var freshlyCreated = false

    /** Diag de redes base (a saúde do chassi é anexada a cada ~3 s). */
    @Volatile private var baseNetDiag: String = ""
    private var healthTickCount = 0
    private var diagTickCount = 0

    /** Canal de volta para o processo :mqtt publicar feedback/telemetria. */
    @Volatile private var feedbackSink: IFeedbackSink? = null

    // ── Servidor IPC exposto ao processo :mqtt ────────────────────────────────
    private val binder = object : IRobotControl.Stub() {
        override fun onCommand(json: String?) {
            json ?: return
            runCatching { motion.onCommand(json) }
                .onFailure { Log.w(TAG, "onCommand falhou: ${it.message}") }
        }
        override fun registerFeedback(sink: IFeedbackSink?) {
            feedbackSink = sink
            Log.i(TAG, "Processo :mqtt registrou canal de feedback")
        }
        override fun unregisterFeedback(sink: IFeedbackSink?) {
            if (feedbackSink == sink) feedbackSink = null
        }
        override fun reportMqttStatus(connected: Boolean, error: String?) {
            StatusBus.update {
                it.copy(brokerConnected = connected, brokerError = if (connected) "" else (error ?: it.brokerError))
            }
        }
        override fun isChassisOnline(): Boolean =
            chassis.connected && chassis.dcConnected()
    }

    override fun onBind(intent: Intent?): IBinder = binder

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
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RESTART -> if (!freshlyCreated) restartConnections()
            ACTION_FRONT_TEST -> runCatching { motion.onCommand("""{"type":"front_test"}""") }
        }
        freshlyCreated = false
        return START_STICKY
    }

    private fun buildPipeline() {
        // Usuário/senha-mascarada exibidos na UI (o broker em si é do processo :mqtt).
        StatusBus.update {
            it.copy(brokerUser = config.mqttUser, brokerPassLen = config.mqttPassword.length)
        }
        chassis = SlamwareChassis(this, config) { connected, error, bnd, classTried ->
            StatusBus.update {
                it.copy(sdkConnected = connected, sdkError = error, sdkBound = bnd, sdkClassTried = classTried)
            }
        }
        motionSdk = KenMotionSdk(chassis)
        motion = MotionController(chassis, config, motionSdk)
        // ACKs dos comandos de mapa e o diag sob demanda (map_status) saem pelo
        // mesmo canal de feedback (ken/motion/feedback) via :mqtt.
        motion.ackSink = { j -> sendFeedback(j.toString()) }
        motion.diagRequester = { publicarDiag() }
        // Serviço de integração/percepção coeso (nav-ready + FRENTE com gate).
        integration = br.com.onlife.kenmotionbridge.control.SlamwareIntegrationService(chassis)
        motion.safeForward = { val r = integration.moveForwardSafe(); r.accepted to r.reason }
        // Resultado do cenário de teste FRENTE: publica no feedback E salva em arquivo
        // (filesDir/front_test_<ts>.json) para comparação futura pelo operador.
        motion.frontTestResultSink = { j ->
            sendFeedback(j.toString())
            runCatching {
                val f = java.io.File(filesDir, "front_test_${System.currentTimeMillis()}.json")
                f.writeText(j.toString(2))
                Log.i(TAG, "Resultado do teste FRENTE salvo em ${f.absolutePath}")
            }.onFailure { Log.w(TAG, "Falha ao salvar resultado do teste: ${it.message}") }
        }
    }

    /** Snapshot de diagnóstico (Rota A) — "type":"diag" em ken/motion/feedback. */
    private fun publicarDiag(tel: SlamwareChassis.ChassisTelemetry? = null) {
        runCatching {
            sendFeedback((tel?.let { chassis.diagJson(it) } ?: chassis.diagJson()).toString())
        }.onFailure { Log.w(TAG, "diag falhou: ${it.message}") }
    }

    private fun connectAll() {
        scope.launch {
            applyChassisRouting()
            chassis.connect()
            // Replica o "despertar" do stack do fabricante (wakeUp + estados idle).
            if (chassis.connected) Log.i(TAG, "Ativação: " + chassis.autoActivate())
            motionSdk.inicializarConexaoRobo()
        }
    }

    /**
     * Amarra ESTE processo à rede do chassi e VERIFICA com dados: em robôs onde o
     * fabricante configura o IP da Ethernet por fora do Android (IP estático direto
     * na interface), a rota ao chassi existe só na tabela principal do kernel — a
     * tabela da Network do Android fica sem ela e o bind gera "Network is
     * unreachable". Nesse caso o TCP fecha DESAMARRADO; então desfazemos o bind.
     */
    private fun applyChassisRouting(quiet: Boolean = false) {
        if (!config.dualHoming) return
        val chassi = networkRouter.findChassisNetwork(config.chassisIp, config.chassisPort)
        var bound = chassi != null && networkRouter.bindProcess(chassi)
        var probe = networkRouter.probeTcp(config.chassisIp, config.chassisPort, if (bound) chassi else null)
        if (bound && probe != "OK") {
            networkRouter.release()
            val plain = networkRouter.probeTcp(config.chassisIp, config.chassisPort, null)
            if (plain == "OK") {
                bound = false
                probe = "OK (rota padrão, SEM bind)"
            } else {
                chassi?.let { networkRouter.bindProcess(it) } // nenhum caminho fechou; mantém o bind
                probe = "$probe | rota padrão=$plain"
            }
        }
        if (quiet) return
        // Sonda candidatos a nucleo Slamware: o que responde TCP em .99.2 pode ser a
        // placa CSJBot (proxy); o modulo Slamware de fabrica usa 192.168.11.1.
        val candidates = linkedSetOf(config.chassisIp, "192.168.99.1", "192.168.11.1")
        val sdpScan = candidates.filter { it != config.chassisIp }.joinToString("") {
            "\n→ TCP $it:${config.chassisPort} = " + networkRouter.probeTcp(it, config.chassisPort, null)
        }
        val csjApps = runCatching {
            packageManager.getInstalledApplications(0)
                .map { it.packageName }.filter { it.startsWith("com.csjbot") }
        }.getOrDefault(emptyList())
        val appsLine = "\n→ apps CSJBot: " +
            (if (csjApps.isEmpty()) "NENHUM instalado neste tablet!" else csjApps.joinToString(", "))
        val diag = networkRouter.describe(config.chassisIp) +
            "\n→ chassi=${chassi ?: "NÃO ACHADA"} bind=${if (bound) "SIM" else "não"}" +
            "\n→ TCP ${config.chassisIp}:${config.chassisPort} = $probe" +
            sdpScan + appsLine +
            "\n→ MQTT: processo :mqtt (rede própria)"
        Log.i(TAG, "Redes:\n$diag")
        baseNetDiag = diag
        StatusBus.update { it.copy(netInfo = diag) }
    }

    private fun rebindChassisQuiet() = applyChassisRouting(quiet = true)

    private fun restartConnections() {
        scope.launch {
            runCatching { chassis.disconnect() }
            config = BridgeConfig.load(this@RobotBridgeService)
            buildPipeline()
            applyChassisRouting()
            chassis.connect()
            motionSdk.inicializarConexaoRobo()
        }
    }

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
                        linear = motion.currentV, angular = motion.currentW,
                        frontCm = motion.frontCm, lastCommand = motion.lastCommandLabel,
                    )
                }
                delay(periodMs)
            }
        }
    }

    private fun startFeedbackLoop() {
        feedbackJob = scope.launch {
            while (isActive) {
                try { publicarTelemetria() } catch (t: Throwable) {
                    Log.e(TAG, "Erro no loop de telemetria (continuando): ${t.message}", t)
                }
                delay(1000L)
            }
        }
    }

    private fun publicarTelemetria() {
        val now = System.currentTimeMillis()

        if (chassis.connected && !chassis.dcConnected()) {
            Log.w(TAG, "Canal Slamware caiu (getDCIsConnected=false) — reconectando…")
            StatusBus.update { it.copy(sdkConnected = false, sdkError = "reconectando ao chassi…") }
            rebindChassisQuiet()
            chassis.connect()
        }

        val tel = chassis.readTelemetry()
        val online = chassis.connected && tel.dcConnected

        StatusBus.update {
            it.copy(
                sdkConnected = online,
                batteryPct = tel.battery, charging = tel.charging,
                poseX = tel.poseX, poseY = tel.poseY, poseYawDeg = tel.poseYawDeg,
                localization = tel.localization,
                frontCm = if (tel.frontCm.isNaN()) motion.frontCm else tel.frontCm,
                telemetryAt = if (online) now else it.telemetryAt,
            )
        }

        // Feedback (ken/motion/feedback) — enviado ao :mqtt por IPC; ele publica e mantém o heartbeat.
        val fb = JSONObject().apply {
            put("online", online)
            put("v", round3(motion.currentV))
            put("w", round3(motion.currentW))
            put("front_cm", if (tel.frontCm.isNaN()) JSONObject.NULL else round1(tel.frontCm))
            put("blind_mode", motion.blindActiveNow())
            put("ts", now)
            // ADITIVO (não remove nada do contrato): diagnóstico do Caminho A quando
            // uma varredura de FRENTE já rodou. Formato: {path, flag_tried, result, …}.
            chassis.forwardProbeSummary()?.let { put("diag", it) }
        }
        sendFeedback(fb.toString())

        // Diagnóstico Rota A: "type":"diag" no mesmo tópico de feedback, a cada 2 s
        // (loop de 1 s, tick alternado). Reusa o `tel` já lido nesta passada.
        if (++diagTickCount % 2 == 0) {
            publicarDiag(tel)
            // Estado de PERCEPÇÃO (SlamwareIntegrationService): lidar/depth/loc/nav_ready.
            // "type":"perception" — para diagnosticar App × Hardware pelos logs.
            runCatching { sendFeedback(integration.perceptionSnapshot().toJson().toString()) }
                .onFailure { Log.w(TAG, "perception feedback falhou: ${it.message}") }
        }

        if (online) {
            val tj = chassis.telemetryJson().apply {
                put("ts", now)
                // No modo cego: sinaliza OA desligado e localização indisponível (web mostra —).
                if (motion.blindActiveNow()) { put("obstacle_avoidance", false); put("localization", JSONObject.NULL) }
            }
            sendTelemetry(tj.toString())
        }

        // Saúde do chassi na VOZ do firmware (E-stop/LIDAR/erros) + status da última
        // ação moveBy (ex.: BLOCKED · reason) — anexadas ao diag da tela a cada ~3 s.
        if (online && baseNetDiag.isNotEmpty() && ++healthTickCount % 3 == 0) {
            val health = chassis.healthSummary()
            val mode = chassis.modeSummary()
            val depth = chassis.frontDepthSummary()
            val lidar = chassis.laserSummary()
            val fwd = chassis.lastForwardStatus()
            val action = chassis.lastActionStatus()
            val extra = "\n→ saúde chassi: " + health +
                (if (mode.isNotEmpty()) "\n→ modo: " + mode else "") +
                (if (lidar.isNotEmpty()) "\n→ " + lidar else "") +
                (if (depth.isNotEmpty()) "\n→ " + depth else "") +
                (if (fwd.isNotEmpty()) "\n→ última FRENTE: " + fwd else "") +
                (if (action.isNotEmpty()) "\n→ última ação: " + action else "")
            StatusBus.update { it.copy(netInfo = baseNetDiag + extra) }
        }
        updateNotification()
    }

    private fun sendFeedback(json: String) {
        val sink = feedbackSink ?: return
        try { sink.onFeedback(json) } catch (e: RemoteException) {
            Log.w(TAG, "Canal :mqtt caiu (feedback): ${e.message}"); feedbackSink = null
        }
    }

    private fun sendTelemetry(json: String) {
        val sink = feedbackSink ?: return
        try { sink.onTelemetry(json) } catch (e: RemoteException) {
            Log.w(TAG, "Canal :mqtt caiu (telemetria): ${e.message}"); feedbackSink = null
        }
    }

    private fun updateNotification() {
        val s = StatusBus.state.value
        val txt = "Broker:${if (s.brokerConnected) "OK" else "—"}  SDK:${if (s.sdkConnected) "OK" else "—"}  " +
            "v=%.2f w=%.2f".format(s.linear, s.angular)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(txt))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KenMotionBridge::robot").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "KEN Motion Bridge", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Ponte de movimento do robô KEN" }
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
        StatusBus.update { it.copy(serviceRunning = false, sdkConnected = false) }
        loopJob?.cancel(); feedbackJob?.cancel()
        runCatching { motion.stop() }
        runCatching { motionSdk.liberar() }
        runCatching { chassis.disconnect() }
        runCatching { networkRouter.release() }
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
