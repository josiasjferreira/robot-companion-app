package br.com.onlife.kenmotionbridge.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
import com.csjbot.sdkhandler.IAarToSdkApp
import com.csjbot.sdkhandler.ISdkAppToAar
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Camada ÚNICA de contato com o RobotSDK do fabricante.
 *
 * Toda chamada ao SDK proprietário é feita aqui — por reflexão — para que:
 *  - o projeto compile e gere APK mesmo sem o AAR no host de build;
 *  - a ponte se ligue às classes REAIS em runtime
 *    (com.slamtec.slamware.* / com.csjbot.sdkhandler.*);
 *  - uma diferença de assinatura degrade com segurança (loga e continua) em vez
 *    de derrubar o controle do robô.
 *
 * Classes reais utilizadas:
 *  - com.slamtec.slamware.SlamwareCorePlatform  (handle do chassi via TCP 1445)
 *  - com.slamtec.slamware.robot.RealTimeVelocity (setLinearVelocity/setAngularVelocity)
 *  - com.slamtec.slamware.robot.MoveDirection    (FORWARD/BACKWARD/TURN_LEFT/TURN_RIGHT)
 *  - binding AIDL com.csjbot.sdkhandler.ISdkAppToAar / IAarToSdkApp
 */
class SlamwareChassis(
    private val context: Context,
    private val config: BridgeConfig,
    /** Atualiza a tela: (conectado, erro, serviçoBound, classeSdkTentada). */
    private val onStatus: (Boolean, String, Boolean, String) -> Unit = { _, _, _, _ -> },
) {

    companion object {
        private const val TAG = "SlamwareChassis"
        // Serviço do RobotSDK (CSJBot). Bind pela AÇÃO (resolvido em runtime) e, como
        // fallback, pelo COMPONENTE explícito (pacote/serviço conhecidos do app do RobotSDK).
        private const val CSJBOT_BIND_ACTION = "com.csjbot.robotsdkservice.startservice"
        private const val CSJBOT_PKG = "com.csjbot.robotsdk.ten"
        private const val CSJBOT_SERVICE = "com.csjbot.robotsdk.service.RobotSdkService"

        private const val PLATFORM_CLS = "com.slamtec.slamware.SlamwareCorePlatform"
        private const val RTV_CLS = "com.slamtec.slamware.robot.RealTimeVelocity"
        // Pacote correto confirmado via javap: com.slamtec.slamware.action.MoveDirection
        private const val MOVE_DIR_CLS = "com.slamtec.slamware.action.MoveDirection"
        private const val ROTATION_CLS = "com.slamtec.slamware.robot.Rotation"
    }

    @Volatile var connected: Boolean = false
        private set

    /** Serviço do RobotSDK realmente bound? */
    @Volatile private var bound: Boolean = false
    /** Último erro/estado do SDK para exibir na tela. */
    @Volatile private var sdkError: String = ""
    /** Classe do SDK que a reflexão tentou carregar (mostrada na tela). */
    @Volatile private var classTried: String = PLATFORM_CLS

    /** Handle do chassi (SlamwareCorePlatform). Object para não exigir o AAR em compile-time. */
    @Volatile private var platform: Any? = null

    /** Instância reutilizada de RealTimeVelocity. */
    @Volatile private var rtv: Any? = null

    private var sdkBinder: ISdkAppToAar? = null
    private val warnedOnce = HashSet<String>()

    private fun report() = onStatus(connected, sdkError, bound, classTried)

    private val aarCallback = object : IAarToSdkApp.Stub() {
        override fun onSdkReady(info: String?) {
            Log.i(TAG, "SDK host pronto: $info")
        }
        override fun onSdkEvent(what: Int, payload: String?) {
            Log.d(TAG, "Evento SDK what=$what payload=$payload")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sdkBinder = ISdkAppToAar.Stub.asInterface(binder)
            bound = true
            connected = true
            sdkError = ""
            runCatching {
                sdkBinder?.register(aarCallback)
                val info = sdkBinder?.requestChassis()
                Log.i(TAG, "Binding CSJBot OK. requestChassis=$info")
            }.onFailure {
                // Serviço bound, mas a interface AIDL não casou (descriptor diferente do AAR oficial).
                sdkError = "serviço bound, mas handshake AIDL falhou: ${it.message} " +
                    "(ISdkAppToAar pode diferir do AAR oficial)"
                Log.w(TAG, sdkError)
            }
            report()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sdkBinder = null
            bound = false
            connected = platform != null
            sdkError = "RobotSdkService desconectado"
            Log.w(TAG, sdkError)
            report()
        }
    }

    /**
     * Conecta ao chassi. PRIORIZA a Abordagem 1 (conexão DIRETA ao Slamware via TCP 1445);
     * só tenta o bind AIDL (Abordagem 2) como fallback se a conexão direta falhar.
     */
    fun connect(): Boolean {
        classTried = PLATFORM_CLS
        sdkError = ""
        bound = false
        connected = false
        report()

        // Abordagem 1 (recomendada): conexão direta SlamwareCorePlatform.connect(ip, 1445).
        connectSlamware()

        // Abordagem 2 (fallback): bind ao RobotSdkService só se a direta NÃO conectou.
        if (!connected && config.useCsjbotBinding) {
            Log.i(TAG, "Conexão direta falhou; tentando fallback de bind AIDL ao RobotSdkService")
            bindCsjbot()
        }
        report()
        return connected
    }

    private fun bindCsjbot() {
        val intent = Intent(CSJBOT_BIND_ACTION)

        // (1) Tenta resolver o componente real a partir da AÇÃO (bind explícito é exigido no Android 5+).
        val resolved = runCatching { context.packageManager.resolveService(intent, 0) }.getOrNull()
        val component: ComponentName = if (resolved?.serviceInfo != null) {
            val si = resolved.serviceInfo
            Log.i(TAG, "RobotSdkService resolvido pela ação: ${si.packageName}/${si.name} exported=${si.exported}")
            ComponentName(si.packageName, si.name)
        } else {
            // (1b) Fallback: componente EXPLÍCITO conhecido do app do RobotSDK.
            Log.w(TAG, "Ação '$CSJBOT_BIND_ACTION' não resolvida; tentando componente explícito $CSJBOT_PKG/$CSJBOT_SERVICE")
            ComponentName(CSJBOT_PKG, CSJBOT_SERVICE)
        }
        intent.component = component

        // (3) Sobe o serviço ANTES de bindar, para garantir que o RobotSdkService esteja rodando.
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "startService(${component.packageName}) falhou: ${it.message}") }

        // (2) bind com a ação + componente corretos.
        val ok = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (se: SecurityException) {
            // Não sobrescreve o motivo REAL da conexão direta (Abordagem 1), se houver.
            setErrorIfEmpty("bind negado — sem permissão para o serviço ${component.packageName} (${se.message})")
            Log.e(TAG, "bind negado: ${se.message}")
            return
        }
        if (!ok) {
            setErrorIfEmpty(
                "RobotSdkService não encontrado/indisponível — ${component.packageName}/${component.shortClassName} " +
                    "não instalado, não exportado ou sem permissão (confira o app do RobotSDK e o <queries> no manifest)"
            )
            Log.e(TAG, "bindService retornou false para ${component.packageName}/${component.shortClassName}")
        } else {
            if (sdkError.isEmpty()) sdkError = "aguardando handshake do RobotSdkService ($CSJBOT_PKG)…"
            Log.i(TAG, "bindService solicitado (ok=true) em ${component.packageName}/${component.shortClassName}")
        }
    }

    private fun connectSlamware() {
        classTried = PLATFORM_CLS
        try {
            val cls = Class.forName(PLATFORM_CLS)
            // SlamwareCorePlatform.connect(String ip, int port)  (estático)
            val connect: Method = cls.getMethod("connect", String::class.java, Int::class.javaPrimitiveType)
            platform = connect.invoke(null, config.chassisIp, config.chassisPort)
            // Confirma o canal direto (getDCIsConnected), se exposto.
            val dcOk = (invokeReturningDouble(platform, "getDCIsConnected") ?: 1.0) != 0.0
            if (platform != null && dcOk) {
                connected = true
                bound = false
                sdkError = ""
                Log.i(TAG, "Slamware CONECTADO (direto) em ${config.chassisIp}:${config.chassisPort}")
            } else {
                connected = false
                setErrorIfEmpty(
                    "Chassi inacessível — ${config.chassisIp}:${config.chassisPort} (conexão direta não estabelecida)"
                )
            }
        } catch (t: Throwable) {
            connected = false
            setErrorIfEmpty(classifySlamware(t))
            Log.e(TAG, "Falha Slamware (direto): ${t.message}", t)
        }
    }

    /** Só grava o erro se ainda não houver um — preserva o motivo do caminho primário. */
    private fun setErrorIfEmpty(msg: String) {
        if (sdkError.isEmpty()) sdkError = msg
    }

    /** Traduz a falha da reflexão Slamware num motivo específico. */
    private fun classifySlamware(t: Throwable): String {
        val root = unwrap(t)
        val simple = root.javaClass.simpleName
        return when {
            root is ClassNotFoundException ->
                "ClassNotFound: $PLATFORM_CLS — RobotSDK (AAR) ausente no APK"
            root is NoSuchMethodException ->
                "NoSuchMethod: $PLATFORM_CLS.connect(String,int) — versão do AAR diferente da esperada"
            root is UnsatisfiedLinkError ->
                "UnsatisfiedLinkError — biblioteca nativa (.so) do SDK ausente/ABI incompatível"
            // Exceções do próprio Slamware (com.slamtec.slamware.exceptions.*).
            simple.contains("ConnectionFail") || simple.contains("ConnectionTimeOut") ->
                "Chassi inacessível — ${config.chassisIp}:${config.chassisPort} (verifique se o tablet está na rede do robô)"
            simple.contains("Unauthorized") ->
                "Não autorizado pelo chassi — sessão/login necessária (${root.message})"
            root is java.net.ConnectException || root is java.net.SocketTimeoutException ||
                root is java.net.UnknownHostException ->
                "Chassi inacessível — ${config.chassisIp}:${config.chassisPort} (${root.message})"
            else -> "Falha Slamware: $simple: ${root.message}"
        }
    }

    /** Desempacota InvocationTargetException / ExceptionInInitializerError até a causa raiz. */
    private fun unwrap(t: Throwable): Throwable {
        var cur: Throwable = t
        while (true) {
            val c = cur.cause ?: break
            if (cur is java.lang.reflect.InvocationTargetException || cur is ExceptionInInitializerError) {
                cur = c
            } else break
        }
        return cur
    }

    fun disconnect() {
        runCatching { sdkBinder?.unregister(aarCallback) }
        runCatching { context.unbindService(serviceConnection) }
        sdkBinder = null
        bound = false
        platform?.let { invoke(it, "disconnect") }
        platform = null
        connected = false
        report()
    }

    // ── Controle de velocidade ──────────────────────────────────────────────

    /**
     * Aplica velocidade linear (m/s) e angular (rad/s) ao chassi.
     * Caminho principal: RealTimeVelocity.setLinearVelocity/setAngularVelocity +
     * platform.setRealtimeVelocity(rtv). Faz fallback para chassis.setLinearVelocity
     * direto se o método de RealTime não existir.
     */
    fun sendVelocity(linear: Double, angular: Double) {
        val p = platform ?: return
        try {
            val velocity = obtainRtv() ?: run {
                // Fallback: setters direto no platform.
                invokeDouble(p, "setLinearVelocity", linear)
                invokeDouble(p, "setAngularVelocity", angular)
                return
            }
            invokeDouble(velocity, "setLinearVelocity", linear)
            invokeDouble(velocity, "setAngularVelocity", angular)
            // Envia o objeto de velocidade ao chassi (variações conhecidas de nome).
            if (!invoke(p, "setRealtimeVelocity", arrayOf(velocity.javaClass), arrayOf(velocity)) &&
                !invoke(p, "setRealTimeVelocity", arrayOf(velocity.javaClass), arrayOf(velocity)) &&
                !invoke(p, "publishVelocity", arrayOf(velocity.javaClass), arrayOf(velocity))
            ) {
                // Último recurso: setters direto.
                invokeDouble(p, "setLinearVelocity", linear)
                invokeDouble(p, "setAngularVelocity", angular)
            }
        } catch (t: Throwable) {
            warnOnce("sendVelocity", t)
        }
    }

    private fun obtainRtv(): Any? {
        rtv?.let { return it }
        return try {
            rtv = Class.forName(RTV_CLS).getDeclaredConstructor().newInstance()
            rtv
        } catch (t: Throwable) {
            warnOnce("RealTimeVelocity.new", t)
            null
        }
    }

    // ── Movimentos discretos (Abordagem 1: direto via SlamwareCorePlatform) ───

    enum class Dir { FORWARD, BACKWARD, TURN_LEFT, TURN_RIGHT }

    /** Última ação de movimento (IMoveAction) para permitir cancelamento. */
    @Volatile private var lastAction: Any? = null

    /** Move o chassi numa direção discreta: platform.moveBy(MoveDirection). */
    fun moveBy(dir: Dir): Boolean {
        val p = platform ?: return false
        return try {
            val moveDirCls = Class.forName(MOVE_DIR_CLS)
            // MoveDirection.valueOf("FORWARD" | "BACKWARD" | "TURN_LEFT" | "TURN_RIGHT")
            val enumVal = moveDirCls.getMethod("valueOf", String::class.java).invoke(null, dir.name)
            lastAction = p.javaClass.getMethod("moveBy", moveDirCls).invoke(p, enumVal)
            Log.i(TAG, "moveBy(${dir.name}) enviado ao chassi")
            true
        } catch (t: Throwable) {
            warnOnce("moveBy", t); false
        }
    }

    /** Gira o chassi por um ângulo (graus): platform.rotate(Rotation(yawRad)). */
    fun rotate(graus: Float): Boolean {
        val p = platform ?: return false
        return try {
            val rotCls = Class.forName(ROTATION_CLS)
            val yawRad = Math.toRadians(graus.toDouble()).toFloat()
            val rotation = rotCls.getConstructor(Float::class.javaPrimitiveType).newInstance(yawRad)
            lastAction = p.javaClass.getMethod("rotate", rotCls).invoke(p, rotation)
            Log.i(TAG, "rotate($graus°) enviado ao chassi")
            true
        } catch (t: Throwable) {
            warnOnce("rotate", t); false
        }
    }

    /** Cancela o movimento em andamento (IMoveAction.cancel) e tenta cancelAction no platform. */
    fun cancelAction() {
        lastAction?.let { act -> runCatching { act.javaClass.getMethod("cancel").invoke(act) } }
        lastAction = null
        val p = platform ?: return
        if (!invoke(p, "cancelAction")) invoke(p, "CancelAction")
    }

    // ── Sensores / telemetria ────────────────────────────────────────────────

    // ── Telemetria (API real confirmada via javap) ───────────────────────────

    /** Snapshot coeso da telemetria do chassi (fonte única). */
    data class ChassisTelemetry(
        val dcConnected: Boolean,
        val battery: Int,            // %, -1 se indisponível
        val charging: Boolean,
        val poseX: Double,           // m, NaN se indisponível
        val poseY: Double,
        val poseYawDeg: Double,
        val vLinear: Double,         // m/s medido (RealTimeVelocity)
        val vAngular: Double,        // rad/s medido
        val frontCm: Double,         // distância frontal (LaserScan), NaN se indisponível
        val localization: Int,       // 0–100, -1 se indisponível
        val odometry: Double,        // m, NaN se indisponível
    )

    /** Canal direto realmente ativo? (`SlamwareCorePlatform.getDCIsConnected()`) */
    fun dcConnected(): Boolean {
        val p = platform ?: return false
        return invokeReturningDouble(p, "getDCIsConnected")?.let { it != 0.0 } ?: false
    }

    /**
     * Lê TODA a telemetria em uma passada (uma fonte de verdade). Tolerante: cada campo
     * que falhar vira NaN/-1 sem interromper os demais.
     */
    fun readTelemetry(): ChassisTelemetry {
        val p = platform
        if (p == null) {
            return ChassisTelemetry(false, -1, false, Double.NaN, Double.NaN, Double.NaN,
                0.0, 0.0, Double.NaN, -1, Double.NaN)
        }
        // Pose (x, y, yaw em radianos -> graus).
        var px = Double.NaN; var py = Double.NaN; var yawDeg = Double.NaN
        invokeReturningObject(p, "getPose")?.let { pose ->
            invokeReturningDouble(pose, "getX")?.let { px = it }
            invokeReturningDouble(pose, "getY")?.let { py = it }
            invokeReturningDouble(pose, "getYaw")?.let { yawDeg = Math.toDegrees(it) }
        }
        // Velocidade medida (RealTimeVelocity).
        var vLin = 0.0; var vAng = 0.0
        invokeReturningObject(p, "getRealTimeVelocity")?.let { rtv ->
            invokeReturningDouble(rtv, "getLinearVelocity")?.let { vLin = it }
            invokeReturningDouble(rtv, "getAngularVelocity")?.let { vAng = it }
        }
        // Qualidade de localização (0–100).
        var loc = -1
        invokeReturningObject(p, "getLocalizationQuality")?.let { q ->
            (invokeReturningDouble(q, "getLocalizationQuality") ?: invokeReturningDouble(q, "getLevel"))
                ?.let { loc = it.toInt() }
        }
        return ChassisTelemetry(
            dcConnected = invokeReturningDouble(p, "getDCIsConnected")?.let { it != 0.0 } ?: false,
            battery = batteryPercent(),
            charging = (invokeReturningDouble(p, "getBatteryIsCharging") ?: 0.0) != 0.0,
            poseX = px, poseY = py, poseYawDeg = yawDeg,
            vLinear = vLin, vAngular = vAng,
            frontCm = frontDistanceCm(),
            localization = loc,
            odometry = invokeReturningDouble(p, "getOdometry") ?: Double.NaN,
        )
    }

    @Volatile private var frontCache = Double.NaN
    @Volatile private var frontCacheAt = 0L

    /**
     * Distância frontal (cm) a partir do LaserScan: menor distância válida no setor
     * frontal (|ângulo| < ~15°). NaN se o scan não estiver disponível.
     *
     * Com CACHE/THROTTLE (~5 Hz): o loop de controle a 20 Hz chama isto a cada tick, mas
     * o scan só é buscado pela rede no máximo a cada 200 ms — evita inundar o canal 1445.
     */
    fun frontDistanceCm(): Double {
        val p = platform ?: return Double.NaN
        val now = System.currentTimeMillis()
        if (now - frontCacheAt < 200L) return frontCache
        frontCacheAt = now
        @Suppress("UNCHECKED_CAST")
        frontCache = try {
            val scan = p.javaClass.getMethod("getLaserScan").invoke(p)
            val pts = scan?.let { it.javaClass.getMethod("getLaserPoints").invoke(it) } as? List<Any?>
            if (pts == null) {
                Double.NaN
            } else {
                var minM = Double.MAX_VALUE
                val setor = Math.toRadians(15.0)
                for (pt in pts) {
                    pt ?: continue
                    val valido = (invokeReturningDouble(pt, "isValid") ?: 1.0) != 0.0
                    if (!valido) continue
                    val ang = invokeReturningDouble(pt, "getAngle") ?: continue
                    if (kotlin.math.abs(ang) > setor) continue
                    val dist = invokeReturningDouble(pt, "getDistance") ?: continue
                    if (dist > 0.0 && dist < minM) minM = dist
                }
                if (minM == Double.MAX_VALUE) Double.NaN else minM * 100.0
            }
        } catch (t: Throwable) {
            warnOnce("getLaserScan", t); Double.NaN
        }
        return frontCache
    }

    /** Bateria em %, ou -1 se indisponível. */
    fun batteryPercent(): Int {
        val p = platform ?: return -1
        return invokeReturningDouble(p, "getBatteryPercentage")?.toInt() ?: -1
    }

    /** Telemetria nativa do chassi como JSON para republicar em `ken/sensors/telemetry`. */
    fun telemetryJson(): JSONObject {
        val t = readTelemetry()
        return JSONObject().apply {
            put("dc_connected", t.dcConnected)
            if (t.battery >= 0) put("battery", t.battery)
            put("charging", t.charging)
            if (!t.poseX.isNaN() && !t.poseY.isNaN()) {
                put("pose", JSONObject().apply {
                    put("x", round3(t.poseX)); put("y", round3(t.poseY))
                    if (!t.poseYawDeg.isNaN()) put("yaw_deg", round1(t.poseYawDeg))
                })
            }
            put("v_measured", round3(t.vLinear))
            put("w_measured", round3(t.vAngular))
            if (!t.frontCm.isNaN()) put("front_cm", round1(t.frontCm))
            if (t.localization >= 0) put("localization", t.localization)
            if (!t.odometry.isNaN()) put("odometry", round3(t.odometry))
        }
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0


    // ── Helpers de reflexão ───────────────────────────────────────────────────

    private fun invoke(target: Any, name: String): Boolean =
        invoke(target, name, emptyArray(), emptyArray())

    private fun invoke(
        target: Any,
        name: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): Boolean = try {
        target.javaClass.getMethod(name, *paramTypes).invoke(target, *args)
        true
    } catch (e: NoSuchMethodException) {
        false
    } catch (t: Throwable) {
        warnOnce(name, t)
        false
    }

    private fun invokeDouble(target: Any, name: String, value: Double): Boolean {
        // Tenta double e float.
        if (invoke(target, name, arrayOf(Double::class.javaPrimitiveType!!), arrayOf(value))) return true
        return invoke(target, name, arrayOf(Float::class.javaPrimitiveType!!), arrayOf(value.toFloat()))
    }

    /** Invoca um getter sem argumentos e retorna o objeto (ou null em erro/ausência). */
    private fun invokeReturningObject(target: Any?, name: String): Any? {
        val t = target ?: return null
        return try {
            t.javaClass.getMethod(name).invoke(t)
        } catch (e: NoSuchMethodException) {
            null
        } catch (th: Throwable) {
            warnOnce(name, th); null
        }
    }

    private fun invokeReturningDouble(target: Any?, name: String): Double? {
        val t = target ?: return null
        return try {
            val r = t.javaClass.getMethod(name).invoke(t)
            when (r) {
                is Number -> r.toDouble()
                is Boolean -> if (r) 1.0 else 0.0
                else -> null
            }
        } catch (e: NoSuchMethodException) {
            null
        } catch (th: Throwable) {
            warnOnce(name, th); null
        }
    }

    private fun warnOnce(key: String, t: Throwable) {
        if (warnedOnce.add(key)) Log.w(TAG, "Método '$key' indisponível/erro: ${t.message}")
    }
}
