package br.com.onlife.kenmotionbridge.control

import android.util.Log
import br.com.onlife.kenmotionbridge.sdk.SlamwareChassis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * SlamwareIntegrationService — camada COESA de integração com o núcleo Slamware
 * (SLAM/percepção/movimento seguro), preparada para hardware SAUDÁVEL.
 *
 * Não reimplementa a reflexão do SDK: DELEGA ao [SlamwareChassis] existente
 * (conexão TCP 1445, leituras tolerantes, primitivas de movimento). Adiciona por
 * cima: (1) loop de percepção, (2) regra de nav-ready, (3) movimento com gate de
 * segurança na FRENTE, (4) feedback de percepção para MQTT/UI.
 *
 * Estado atual do robô: LIDAR/depth/odometria em zero ("sensores mortos"). Este
 * módulo é justamente o que separa "app pronto" de "hardware pronto": com sensor
 * vivo, [isNavigationReady] vira true e a FRENTE é liberada; com sensor morto,
 * ele RECUSA a frente com motivo — nunca comanda às cegas.
 *
 * Métodos do SDK polados (assinaturas confirmadas por javap no RobotSDK-client.jar):
 *  - getPose(): Pose
 *  - getLaserScan(): LaserScan (getLaserPoints())
 *  - getDepthSensorData(): List        // disponível — sem necessidade de fallback
 *  - getLocalizationQuality(): LocalizationQuality
 * Nota da tarefa: não há API de Bumper/impacto nesta plataforma (verificado);
 * a profundidade (RGBD) é o getDepthSensorData acima.
 */
class SlamwareIntegrationService(
    private val chassis: SlamwareChassis,
) {
    companion object {
        private const val TAG = "SlamwareIntegration"
        const val CHASSIS_IP = "192.168.99.2"
        const val CHASSIS_PORT = 1445
        private const val DEFAULT_POLL_MS = 1000L
    }

    /** Snapshot coeso da percepção num instante. */
    data class PerceptionSnapshot(
        val ts: Long,
        val connected: Boolean,
        val lidarPts: Int,
        val depthPts: Int,
        val poseX: Double,
        val poseY: Double,
        val poseYawDeg: Double,
        val localizationQuality: Double,   // 0–1, -1 se indisponível
        val navigationReady: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", "perception")
            put("ts", ts)
            put("connected", connected)
            put("lidar_pts", lidarPts)
            put("depth_pts", depthPts)
            put("pose", JSONObject().put("x", poseX).put("y", poseY).put("yaw_deg", poseYawDeg))
            put("localization_quality", localizationQuality)
            put("navigation_ready", navigationReady)
        }
    }

    /** Resultado de uma tentativa de movimento (aceito pela lógica de segurança?). */
    data class MoveResult(val accepted: Boolean, val reason: String)

    // ── Conexão ───────────────────────────────────────────────────────────────

    /** (conectado) → notificado a cada mudança relevante de estado de conexão. */
    @Volatile var connectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var lastConnected = false

    fun isConnected(): Boolean = chassis.connected && chassis.dcConnected()

    /**
     * Conecta ao núcleo (SlamwareCorePlatform.connect(192.168.99.2, 1445), via
     * [SlamwareChassis.connect]) e dispara o listener de status.
     */
    fun connect(): Boolean {
        Log.i(TAG, "connect($CHASSIS_IP:$CHASSIS_PORT)")
        chassis.connect()
        val ok = isConnected()
        notifyConnection(ok)
        return ok
    }

    private fun notifyConnection(connected: Boolean) {
        if (connected != lastConnected) {
            lastConnected = connected
            runCatching { connectionListener?.invoke(connected) }
                .onFailure { Log.w(TAG, "connectionListener falhou: ${it.message}") }
        }
    }

    // ── Percepção ───────────────────────────────────────────────────────────────

    /** Lê os 4 sensores numa passada e compõe o snapshot + nav-ready. */
    fun perceptionSnapshot(): PerceptionSnapshot {
        val tel = chassis.readTelemetry()               // getPose + demais
        val lidar = chassis.laserPointCount()           // getLaserScan
        val depth = chassis.depthPointCount()           // getDepthSensorData
        val locQ = chassis.localizationQuality01()      // getLocalizationQuality
        val connected = isConnected()
        notifyConnection(connected)
        return PerceptionSnapshot(
            ts = System.currentTimeMillis(),
            connected = connected,
            lidarPts = lidar,
            depthPts = depth,
            poseX = if (tel.poseX.isNaN()) 0.0 else tel.poseX,
            poseY = if (tel.poseY.isNaN()) 0.0 else tel.poseY,
            poseYawDeg = if (tel.poseYawDeg.isNaN()) 0.0 else tel.poseYawDeg,
            localizationQuality = locQ,
            navigationReady = navReady(locQ, lidar),
        )
    }

    /**
     * REGRA DE SEGURANÇA (exata da tarefa): navegação pronta apenas se
     * LocalizationQuality > 0 E LaserScan.size > 0. É o portão da FRENTE.
     */
    fun isNavigationReady(): Boolean =
        navReady(chassis.localizationQuality01(), chassis.laserPointCount())

    private fun navReady(locQuality: Double, lidarPts: Int): Boolean =
        locQuality > 0.0 && lidarPts > 0

    // ── Loop de percepção ─────────────────────────────────────────────────────

    private var pollJob: Job? = null

    /**
     * Inicia o polling de percepção. Cada snapshot vai para [onSnapshot] (ex.: o
     * serviço publica em `ken/motion/feedback` como `type:"perception"`).
     */
    fun startPerceptionLoop(
        scope: CoroutineScope,
        intervalMs: Long = DEFAULT_POLL_MS,
        onSnapshot: (PerceptionSnapshot) -> Unit,
    ) {
        stopPerceptionLoop()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val snap = perceptionSnapshot()
                    onSnapshot(snap)
                    if (!snap.navigationReady) {
                        Log.d(TAG, "nav NOT ready: lidar=${snap.lidarPts} loc=${snap.localizationQuality}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "perception poll erro (continua): ${t.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPerceptionLoop() { pollJob?.cancel(); pollJob = null }

    // ── Movimento com gate de segurança ───────────────────────────────────────

    /**
     * FRENTE com gate OBRIGATÓRIO: só executa se [isNavigationReady]. Caso
     * contrário RECUSA com motivo (para o feedback), sem comandar o chassi.
     */
    fun moveForwardSafe(): MoveResult {
        if (!isNavigationReady()) {
            val r = "FRENTE bloqueada: navegação não pronta " +
                "(loc=${chassis.localizationQuality01()}, lidar=${chassis.laserPointCount()}pts)"
            Log.w(TAG, r)
            return MoveResult(false, r)
        }
        val ok = chassis.moveBy(SlamwareChassis.Dir.FORWARD)
        return MoveResult(ok, if (ok) "moveBy(FORWARD) enviado" else "moveBy(FORWARD) falhou no envio")
    }

    /** Ré/giros: passam sem gate (o firmware os trata como manobra segura curta). */
    fun moveBackward(): MoveResult = passthrough(SlamwareChassis.Dir.BACKWARD)
    fun turnLeft(): MoveResult = passthrough(SlamwareChassis.Dir.TURN_LEFT)
    fun turnRight(): MoveResult = passthrough(SlamwareChassis.Dir.TURN_RIGHT)

    private fun passthrough(dir: SlamwareChassis.Dir): MoveResult {
        val ok = chassis.moveBy(dir)
        return MoveResult(ok, "moveBy($dir) ${if (ok) "enviado" else "falhou"}")
    }

    /** Avanço por odometria (moveTo + MoveTypeTrack) — também gated pela FRENTE. */
    fun moveToTrackSafe(distM: Float): MoveResult {
        if (!isNavigationReady()) {
            return MoveResult(false, "moveTo(TRACK) bloqueado: navegação não pronta")
        }
        val res = chassis.trackForward(distM)
        return MoveResult(res.contains("enviado"), res)
    }

    /** Parada segura. */
    fun stop() = chassis.cancelAction()
}
