package br.com.onlife.kenmotionbridge.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
import com.csjbot.sdkhandler.IAarToSdkApp
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

    /** Binder do RobotSdkService (contrato OFICIAL: MyBinder extends IAarToSdkApp.Stub). */
    private var sdkBinder: IAarToSdkApp? = null
    /** Informação/eventos do SDK (chegam via SdkCallbackService -> SdkLink). */
    @Volatile private var chassisInfo: String? = null
    /** Verdadeiro se estamos usando o caminho do SDK (AIDL) em vez de reflexão direta. */
    @Volatile private var usingAarPath: Boolean = false
    private val warnedOnce = HashSet<String>()

    private fun report() = onStatus(connected, sdkError, bound, classTried)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sdkBinder = IAarToSdkApp.Stub.asInterface(binder)
            bound = true
            sdkError = ""
            Log.i(TAG, "RobotSdkService bound (${name?.shortClassName})")
            // Handshake oficial (docs/PROTOCOLO_ROBOTSDK.md): connectToSDK(appName).
            // O SDK binda DE VOLTA no SdkCallbackService (ação com.csjbot.sdk.connect),
            // confirma com connectToSDKSucceed() e entrega eventos por sdkAppMsgToAar(json).
            SdkLink.reset()
            SdkLink.setListener { msg ->
                chassisInfo = msg
                usingAarPath = true
            }
            runCatching {
                sdkBinder?.connectToSDK(context.packageName)
                Log.i(TAG, "connectToSDK('${context.packageName}') enviado; aguardando bind-back do SDK")
            }.onFailure {
                sdkError = "handshake AIDL falhou: ${it.message}"
                Log.w(TAG, sdkError, it)
            }
            report()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sdkBinder = null
            usingAarPath = false
            bound = false
            connected = platform != null  // Fallback para reflexão direta, se disponível.
            sdkError = "RobotSdkService desconectado"
            Log.w(TAG, sdkError)
            report()
        }
    }

    /**
     * Conecta ao chassi. PRIORIZA a Abordagem 1 (conexão DIRETA ao Slamware via TCP 1445);
     * só tenta o bind AIDL (Abordagem 2) como fallback se a conexão direta falhar.
     *
     * Solução 2 (AIDL): se a conexão direta falha, tenta bindar ao RobotSdkService do
     * fabricante. Se o handshake AIDL suceder, tenta conectar usando a informação
     * entregue pelo SDK via sdkAppMsgToAar (pode ser um endereço remoto ou um proxy).
     */
    fun connect(): Boolean {
        classTried = PLATFORM_CLS
        sdkError = ""
        bound = false
        connected = false
        usingAarPath = false
        report()

        // Abordagem 1 (recomendada): conexão direta SlamwareCorePlatform.connect(ip, 1445).
        connectSlamware()

        // Abordagem 2 (fallback): bind ao RobotSdkService só se a direta NÃO conectou.
        if (!connected && config.useCsjbotBinding) {
            Log.i(TAG, "Conexão direta falhou; tentando fallback de bind AIDL ao RobotSdkService")
            bindCsjbot()
            // Aguarda um pouco para o handshake AIDL completar (connectToSDKSucceed/sdkAppMsgToAar).
            Thread.sleep(500L)
            // Se o AIDL forneceu informações do chassi, tenta conectar via Solução 2.
            if (bound && !connected && !chassisInfo.isNullOrEmpty()) {
                Log.i(TAG, "Tentando conectar via informação do SDK: $chassisInfo")
                connectViaSdkInfo()
            }
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
            appendError("bind negado — sem permissão (${se.message})")
            Log.e(TAG, "bind negado: ${se.message}")
            return
        }
        if (!ok) {
            appendError("bind=false em ${component.packageName} (app do RobotSDK instalado? exportado?)")
            Log.e(TAG, "bindService retornou false para ${component.packageName}/${component.shortClassName}")
        } else {
            if (sdkError.isEmpty()) sdkError = "aguardando handshake do RobotSdkService ($CSJBOT_PKG)…"
            Log.i(TAG, "bindService solicitado (ok=true) em ${component.packageName}/${component.shortClassName}")
        }
    }

    private fun connectSlamware() {
        classTried = PLATFORM_CLS
        // O chassi pode recusar a sessão SDP transitoriamente (ex.: logo após boot ou
        // enquanto o app do fabricante renegocia). 3 tentativas espaçadas.
        val attempts = 3
        for (attempt in 1..attempts) {
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
                    Log.i(TAG, "Slamware CONECTADO (direto) em ${config.chassisIp}:${config.chassisPort} (tentativa $attempt)")
                    return
                } else {
                    connected = false
                    setErrorIfEmpty(
                        "Chassi inacessível — ${config.chassisIp}:${config.chassisPort} (conexão direta não estabelecida)"
                    )
                }
            } catch (t: Throwable) {
                connected = false
                sdkError = "${classifySlamware(t)} [tentativa $attempt/$attempts]"
                Log.e(TAG, "Falha Slamware (direto, tentativa $attempt/$attempts): ${t.message}", t)
            }
            if (attempt < attempts) Thread.sleep(1500L)
        }
    }

    /** Tenta conectar usando as informações retornadas pelo SDK (Solução 2). */
    private fun connectViaSdkInfo() {
        classTried = "$PLATFORM_CLS (via SDK)"
        try {
            val info = chassisInfo ?: return
            // Esperamos "ip:porta" ou JSON com parâmetros. Tenta parse simples primeiro.
            val parts = info.split(":")
            if (parts.size < 2) {
                Log.w(TAG, "Formato inválido de chassisInfo: $info (esperava ip:porta)")
                return
            }
            val sdkIp = parts[0]
            val sdkPort = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: 1445
            Log.i(TAG, "Conectando via SDK ao $sdkIp:$sdkPort")

            val cls = Class.forName(PLATFORM_CLS)
            val connect: Method = cls.getMethod("connect", String::class.java, Int::class.javaPrimitiveType)
            platform = connect.invoke(null, sdkIp, sdkPort)
            val dcOk = (invokeReturningDouble(platform, "getDCIsConnected") ?: 1.0) != 0.0
            if (platform != null && dcOk) {
                connected = true
                usingAarPath = true
                sdkError = ""
                Log.i(TAG, "Slamware CONECTADO (via SDK) em $sdkIp:$sdkPort")
            } else {
                connected = false
                setErrorIfEmpty("Slamware via SDK não estabeleceu conexão em $sdkIp:$sdkPort")
            }
        } catch (t: Throwable) {
            connected = false
            setErrorIfEmpty("Falha ao conectar via SDK: ${classifySlamware(t)}")
            Log.e(TAG, "Falha Slamware (via SDK): ${t.message}", t)
        }
    }

    /** Só grava o erro se ainda não houver um — preserva o motivo do caminho primário. */
    private fun setErrorIfEmpty(msg: String) {
        if (sdkError.isEmpty()) sdkError = msg
    }

    /** Anexa um motivo ao erro atual sem apagar o principal (aparece na tela). */
    private fun appendError(msg: String) {
        sdkError = if (sdkError.isEmpty()) msg else "$sdkError | $msg"
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
            simple.contains("Unauthorized") ->
                "Não autorizado pelo chassi — sessão/login necessária (${root.message})"
            // Para QUALQUER outra falha (inclui ConnectionFail/Timeout do Slamware) mostramos a
            // cadeia CRUA de exceções: o errno nativo (ECONNREFUSED/ETIMEDOUT/ENETUNREACH) revela
            // se é rede ou handshake. A porta 1445 abre no TCP (sonda), então isto é decisivo.
            else -> "SDK: ${causeChain(t)}"
        }
    }

    /** Monta a cadeia completa de causas (classe: mensagem ← classe: mensagem ← …). */
    private fun causeChain(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (sb.isNotEmpty()) sb.append(" ← ")
            sb.append(cur.javaClass.simpleName)
            cur.message?.let { sb.append(": ").append(it.take(120)) }
            cur = cur.cause
        }
        return sb.toString()
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
        SdkLink.setListener(null)
        runCatching { context.unbindService(serviceConnection) }
        sdkBinder = null
        bound = false
        usingAarPath = false
        platform?.let { invoke(it, "disconnect") }
        platform = null
        connected = false
        chassisInfo = null
        report()
    }

    /** Envia um comando JSON ao RobotSDK pelo canal AIDL oficial (aarMsgToSDKApp). */
    fun sendSdkMessage(json: String): Boolean {
        val b = sdkBinder ?: return false
        return runCatching { b.aarMsgToSDKApp(json); true }
            .onFailure { warnOnce("aarMsgToSDKApp", it) }
            .getOrDefault(false)
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

    /** Última ação de FRENTE (status persiste na tela mesmo após stop/outras direções). */
    @Volatile private var lastForwardAction: Any? = null

    /** Status/motivo da última tentativa de FRENTE — não é apagado por stop/ré/giros. */
    fun lastForwardStatus(): String {
        val a = lastForwardAction ?: return ""
        val st = runCatching { a.javaClass.getMethod("getStatus").invoke(a)?.toString() }.getOrNull().orEmpty()
        val rs = runCatching { a.javaClass.getMethod("getReason").invoke(a)?.toString() }.getOrNull().orEmpty()
        return listOf(st, rs).filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
    }

    /**
     * SCANNER de direção: envia move_by com o CÓDIGO CRU [direction] (0..N) direto ao
     * firmware, contornando o enum. Descobre empiricamente a tabela de direções do
     * chassi (a ordem do enum BACKWARD=0/FORWARD=1 pode divergir da tabela do firmware —
     * no comando NAVI nativo, 0=frente). Constrói MoveByReqBean e invoca o
     * CsjSlamCore.buildAndSendMsg privado por reflexão.
     */
    fun moveByRaw(direction: Int): String {
        val p = platform ?: return "sem plataforma"
        return try {
            val beanCls = Class.forName("com.slamtec.slamware.core.entity.request.MoveByReqBean")
            val bean = beanCls.getDeclaredConstructor().newInstance()
            beanCls.getMethod("setDirection", Int::class.javaPrimitiveType).invoke(bean, direction)
            val sdpCls = Class.forName("com.slamtec.slamware.sdp.SlamwareSdpPlatform")
            val coreField = sdpCls.getDeclaredField("core").apply { isAccessible = true }
            val core = coreField.get(null) ?: return "core nulo"
            val send = core.javaClass.getDeclaredMethod(
                "buildAndSendMsg", Class.forName("com.slamtec.slamware.core.entity.BaseReqBean")
            ).apply { isAccessible = true }
            send.invoke(core, bean)
            "enviado d=$direction"
        } catch (t: Throwable) {
            "falhou: ${t.cause?.message ?: t.message}"
        }
    }

    /** Status/motivo da última ação moveBy — na voz do firmware (ex.: BLOCKED · reason). */
    fun lastActionStatus(): String {
        val a = lastAction ?: return ""
        val st = runCatching { a.javaClass.getMethod("getStatus").invoke(a)?.toString() }.getOrNull().orEmpty()
        val rs = runCatching { a.javaClass.getMethod("getReason").invoke(a)?.toString() }.getOrNull().orEmpty()
        return listOf(st, rs).filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
    }

    /** Saúde do chassi na voz do firmware (E-stop, LIDAR, câmera de profundidade, erros). */
    fun healthSummary(): String {
        val p = platform ?: return "sem plataforma"
        return try {
            val h = p.javaClass.getMethod("getRobotHealth").invoke(p) ?: return "indisponível"
            fun flag(name: String): Boolean? =
                runCatching { h.javaClass.getMethod(name).invoke(h) as? Boolean }.getOrNull()
            val parts = mutableListOf<String>()
            if (flag("getHasSystemEmergencyStop") == true) parts.add("E-STOP ATIVO!")
            if (flag("getHasLidarDisconnected") == true) parts.add("LIDAR desconectado")
            if (flag("getHasDepthCameraDisconnected") == true) parts.add("câmera profundidade OFF")
            val errs = runCatching { h.javaClass.getMethod("getErrors").invoke(h) as? java.util.ArrayList<*> }
                .getOrNull()
            errs?.take(3)?.forEach { e ->
                val msg = runCatching { e.javaClass.getMethod("getErrorMessage").invoke(e)?.toString() }.getOrNull()
                val code = runCatching { e.javaClass.getMethod("getErrorCode").invoke(e)?.toString() }.getOrNull()
                parts.add(("[" + (code ?: "?") + "] " + (msg ?: "")).trim())
            }
            if (parts.isEmpty()) "OK (sem erros reportados)" else parts.joinToString("; ")
        } catch (t: Throwable) {
            "erro ao ler: ${t.message}"
        }
    }

    /** Move o chassi numa direção discreta: platform.moveBy(MoveDirection). */
    fun moveBy(dir: Dir): Boolean {
        val p = platform ?: return false
        return try {
            val moveDirCls = Class.forName(MOVE_DIR_CLS)
            // MoveDirection.valueOf("FORWARD" | "BACKWARD" | "TURN_LEFT" | "TURN_RIGHT")
            val enumVal = moveDirCls.getMethod("valueOf", String::class.java).invoke(null, dir.name)
            lastAction = p.javaClass.getMethod("moveBy", moveDirCls).invoke(p, enumVal)
            if (dir == Dir.FORWARD) lastForwardAction = lastAction
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
        val pitchDeg: Double,        // IMU (graus), NaN se indisponível
        val rollDeg: Double,
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
                Double.NaN, Double.NaN, 0.0, 0.0, Double.NaN, -1, Double.NaN)
        }
        // Pose (x, y) + atitude IMU (yaw/pitch/roll, radianos -> graus) via Pose.getRotation().
        var px = Double.NaN; var py = Double.NaN
        var yawDeg = Double.NaN; var pitchDeg = Double.NaN; var rollDeg = Double.NaN
        invokeReturningObject(p, "getPose")?.let { pose ->
            invokeReturningDouble(pose, "getX")?.let { px = it }
            invokeReturningDouble(pose, "getY")?.let { py = it }
            invokeReturningDouble(pose, "getYaw")?.let { yawDeg = Math.toDegrees(it) }
            invokeReturningObject(pose, "getRotation")?.let { rot ->
                invokeReturningDouble(rot, "getYaw")?.let { yawDeg = Math.toDegrees(it) }
                invokeReturningDouble(rot, "getPitch")?.let { pitchDeg = Math.toDegrees(it) }
                invokeReturningDouble(rot, "getRoll")?.let { rollDeg = Math.toDegrees(it) }
            }
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
            pitchDeg = pitchDeg, rollDeg = rollDeg,
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
            // IMU (graus) — consumido pelo card de Telemetria do app web (/admin/movimento).
            if (!t.poseYawDeg.isNaN() || !t.pitchDeg.isNaN() || !t.rollDeg.isNaN()) {
                put("imu", JSONObject().apply {
                    if (!t.poseYawDeg.isNaN()) put("yaw", round1(t.poseYawDeg))
                    if (!t.pitchDeg.isNaN()) put("pitch", round1(t.pitchDeg))
                    if (!t.rollDeg.isNaN()) put("roll", round1(t.rollDeg))
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
