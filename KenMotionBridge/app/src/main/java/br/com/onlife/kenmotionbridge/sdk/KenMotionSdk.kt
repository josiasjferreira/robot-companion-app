package br.com.onlife.kenmotionbridge.sdk

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * KenMotionSdk — camada ÚNICA e central de **controle de movimento do chassi** do robô KEN.
 *
 * Esta classe é a única porta de entrada para comandos de movimento. Ela encapsula os
 * detalhes do RobotSDK (CSJBot `coshandler`) e expõe métodos de alto nível
 * (moverFrente/moverTras/virarEsquerda/virarDireita/pararMovimento).
 *
 * Integração (confirmada por `javap` no RobotSDK-client.jar):
 *  - `com.csjbot.coshandler.core.Robot` (singleton): `getInstance()`, `getConnectState()`,
 *    e os comandos discretos `moveForward/moveBack/moveLeft/moveRight/turnLeft/turnRight`
 *    (interface `com.csjbot.coshandler.core.interfaces.IChassis`).
 *  - `com.csjbot.coshandler.core.ClientReqProxy` / `...client_req.chassis.ChassisReqImpl`
 *    (interface `IChassisReq`): `move(int)`, `moveAngle(int)`, `goAngle(int)`, `navi(String)`,
 *    `cancelNavi()`, `setSpeed(float)`, `setAngularVelocity(float)`, `goHome()`.
 *
 * As chamadas ao SDK proprietário são feitas por REFLEXÃO — mesmo critério do
 * [SlamwareChassis] — para que o projeto compile/gere APK mesmo sem o AAR e degrade com
 * segurança (loga e ignora) caso uma assinatura mude entre versões do RobotSDK.
 *
 * @param chassis (opcional) ponte Slamware de velocidade em tempo real; usada por
 *        [pararMovimento] para também zerar a velocidade do caminho de joystick.
 */
class KenMotionSdk(private val chassis: SlamwareChassis? = null) {

    companion object {
        private const val TAG = "KenMotionSdk"

        private const val ROBOT_CLS = "com.csjbot.coshandler.core.Robot"
        private const val PROXY_CLS = "com.csjbot.coshandler.core.ClientReqProxy"

        /** Velocidade linear padrão (m/s) — limitada de forma defensiva. */
        const val VELOCIDADE_PADRAO = 0.3f
        const val VELOCIDADE_MAX = 0.7f
        const val VELOCIDADE_ANGULAR_MAX = 0.8f
    }

    /** Instância singleton de `Robot` (Object para não exigir o AAR em compile-time). */
    @Volatile private var robot: Any? = null
    /** Proxy de requisições de chassi (`ClientReqProxy`). */
    @Volatile private var proxy: Any? = null
    @Volatile private var inicializado = false

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val warnedOnce = HashSet<String>()

    // ── Ciclo de vida / estado ────────────────────────────────────────────────

    /**
     * Inicializa o acesso ao RobotSDK. Obtém `Robot.getInstance()` e cria o `ClientReqProxy`.
     * Se [mqttUrl] for informado, também tenta `Robot.connectMqttServer(url,user,senha)`.
     * @return true se o handle do robô foi obtido (não garante sessão ativa — ver [estaConectado]).
     */
    fun inicializarConexaoRobo(
        mqttUrl: String? = null,
        usuario: String? = null,
        senha: String? = null,
    ): Boolean {
        Log.i(TAG, "inicializarConexaoRobo(url=$mqttUrl, user=$usuario)")
        robot = runCatching {
            Class.forName(ROBOT_CLS).getMethod("getInstance").invoke(null)
        }.onFailure { Log.e(TAG, "Falha ao obter Robot.getInstance(): ${it.message}") }.getOrNull()

        proxy = runCatching {
            Class.forName(PROXY_CLS).getDeclaredConstructor().newInstance()
        }.onFailure { Log.w(TAG, "Falha ao criar ClientReqProxy: ${it.message}") }.getOrNull()

        if (robot != null && mqttUrl != null) {
            invoke(
                robot!!, "connectMqttServer",
                arrayOf(String::class.java, String::class.java, String::class.java),
                arrayOf(mqttUrl, usuario ?: "", senha ?: ""),
            )
        }

        inicializado = robot != null
        Log.i(TAG, "inicializado=$inicializado conectado=${estaConectado()}")
        return inicializado
    }

    /** Estado de conexão/sessão do robô (`Robot.getConnectState()`), default false. */
    fun estaConectado(): Boolean = runCatching {
        Class.forName(ROBOT_CLS).getMethod("getConnectState").invoke(null) as? Boolean ?: false
    }.getOrDefault(false)

    // ── Comandos de movimento (porta de entrada única) ────────────────────────

    /** Chassi apto a receber comando de movimento agora? (canal direto OU sessão CSJBot). */
    fun isMotionAvailable(): Boolean =
        chassis?.connected == true || (inicializado && estaConectado())

    /**
     * FRENTE com distância/velocidade configuráveis (interface de alto nível do
     * RobotSdkMotionBridge — ver docs/STACK_FRENTE_ROBOTSDK.md). O firmware CT300
     * não expõe "andar X metros" (moveBy(float) é ignorado; provado por teste no
     * robô), então a distância vira DURAÇÃO (t = d/v) sobre o passo contínuo.
     */
    fun moveForward(distanceMeters: Float? = null, speed: Float? = null) {
        val v = (speed ?: VELOCIDADE_PADRAO).coerceIn(0.05f, VELOCIDADE_MAX)
        val durMs = distanceMeters?.let { d -> ((d.coerceIn(0.1f, 5f) / v) * 1000f).toLong() }
        moverFrente(v, durMs)
    }

    /** Parada segura — cancela navegação + zera velocidades nos DOIS caminhos. */
    fun stop() = pararMovimento()

    /** Move o chassi para frente. [velocidade] em m/s; se [duracaoMs] != null, para sozinho depois. */
    fun moverFrente(velocidade: Float = VELOCIDADE_PADRAO, duracaoMs: Long? = null) =
        comandoDirecional("moverFrente", "moveForward", velocidade, duracaoMs)

    /** Move o chassi para trás. */
    fun moverTras(velocidade: Float = VELOCIDADE_PADRAO, duracaoMs: Long? = null) =
        comandoDirecional("moverTras", "moveBack", velocidade, duracaoMs)

    /**
     * Gira à esquerda. Se [angulo] for informado, usa rotação por ângulo (`goAngle`);
     * caso contrário, giro contínuo (`turnLeft`). Convenção: ângulo positivo = esquerda.
     */
    fun virarEsquerda(angulo: Int? = null, velocidade: Float? = null) =
        comandoRotacao("virarEsquerda", contInuo = "turnLeft", angulo = angulo, sinalAngulo = +1, velocidade = velocidade)

    /** Gira à direita. Convenção: ângulo negativo = direita. */
    fun virarDireita(angulo: Int? = null, velocidade: Float? = null) =
        comandoRotacao("virarDireita", contInuo = "turnRight", angulo = angulo, sinalAngulo = -1, velocidade = velocidade)

    /**
     * Define as velocidades do chassi no RobotSDK (linear em m/s e/ou angular em rad/s).
     * Usado pelo despacho discreto do joystick (setSpeed/setAngularVelocity do IChassisReq).
     */
    fun definirVelocidades(linear: Float?, angular: Float?) {
        val alvo = proxy ?: robot ?: return
        linear?.let { invokeFloat(alvo, "setSpeed", it.coerceIn(0f, VELOCIDADE_MAX)) }
        angular?.let { invokeFloat(alvo, "setAngularVelocity", it.coerceIn(0f, VELOCIDADE_ANGULAR_MAX)) }
    }

    /**
     * Movimento pelo primitivo NATIVO CSJBot (`Robot.moveForward/moveBack/moveLeft/moveRight`
     * → `ClientReqProxy.move(0..3)`), que NÃO passa pelo desvio de obstáculo do Slamware.
     * Usado para a FRENTE, que o `moveBy(FORWARD)` do Slamware recusa. @return true se enviou.
     */
    fun moverNativo(dir: SlamwareChassis.Dir): Boolean {
        val r = robot ?: return false
        val metodo = when (dir) {
            SlamwareChassis.Dir.FORWARD -> "moveForward"
            SlamwareChassis.Dir.BACKWARD -> "moveBack"
            SlamwareChassis.Dir.TURN_LEFT -> "moveLeft"
            SlamwareChassis.Dir.TURN_RIGHT -> "moveRight"
        }
        val ok = invoke(r, metodo, emptyArray(), emptyArray())
        if (ok) Log.i(TAG, "moverNativo($metodo) → move() CSJBot")
        return ok
    }

    /** Para o movimento nativo CSJBot (setSpeed/setAngularVelocity = 0). */
    fun pararNativo() {
        val p = proxy ?: robot ?: return
        invokeFloat(p, "setSpeed", 0f)
        invokeFloat(p, "setAngularVelocity", 0f)
    }

    /** Interrompe QUALQUER movimento em andamento (navegação + velocidade em tempo real). */
    fun pararMovimento() {
        Log.i(TAG, "pararMovimento — enviando comando de parada ao robô…")
        val p = proxy ?: robot
        if (p != null) {
            invoke(p, "cancelNavi", emptyArray(), emptyArray())
            invokeFloat(p, "setSpeed", 0f)
            invokeFloat(p, "setAngularVelocity", 0f)
        } else {
            Log.w(TAG, "pararMovimento: proxy/robot indisponível (SDK não inicializado)")
        }
        // Cobre também o caminho de velocidade em tempo real (joystick/Slamware).
        runCatching {
            chassis?.cancelAction()
            chassis?.sendVelocity(0.0, 0.0)
        }
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private fun comandoDirecional(api: String, metodoSdk: String, velocidade: Float, duracaoMs: Long?) {
        val v = velocidade.coerceIn(0f, VELOCIDADE_MAX)
        Log.i(TAG, "Chamado $api com velocidade=$v duracaoMs=$duracaoMs")
        if (!garantirConectado(api)) return

        // Abordagem 1 (preferida): movimento DIRETO via SlamwareCorePlatform.moveBy(MoveDirection).
        if (chassis?.connected == true) {
            val dir = if (metodoSdk == "moveForward") SlamwareChassis.Dir.FORWARD else SlamwareChassis.Dir.BACKWARD
            Log.i(TAG, "Enviando comando de movimento ao robô (direto Slamware): moveBy($dir)")
            if (chassis.moveBy(dir)) { agendarParadaSeNecessario(duracaoMs); return }
        }

        // Fallback: RobotSDK CSJBot (Robot/ClientReqProxy).
        val alvo = proxy ?: robot ?: return
        invokeFloat(alvo, "setSpeed", v)
        Log.i(TAG, "Enviando comando de movimento ao robô (CSJBot): $metodoSdk (v=$v)")
        if (!invoke(robot ?: alvo, metodoSdk, emptyArray(), emptyArray())) {
            Log.e(TAG, "Erro ao enviar comando: método '$metodoSdk' indisponível no Robot")
        }
        agendarParadaSeNecessario(duracaoMs)
    }

    private fun comandoRotacao(api: String, contInuo: String, angulo: Int?, sinalAngulo: Int, velocidade: Float?) {
        Log.i(TAG, "Chamado $api com angulo=$angulo velocidade=$velocidade")
        if (!garantirConectado(api)) return

        // Abordagem 1 (preferida): rotação DIRETA via SlamwareCorePlatform.
        if (chassis?.connected == true) {
            if (angulo != null) {
                val graus = (sinalAngulo * kotlin.math.abs(angulo)).toFloat()
                Log.i(TAG, "Enviando comando de movimento ao robô (direto Slamware): rotate($graus°)")
                if (chassis.rotate(graus)) return
            } else {
                val dir = if (sinalAngulo > 0) SlamwareChassis.Dir.TURN_LEFT else SlamwareChassis.Dir.TURN_RIGHT
                Log.i(TAG, "Enviando comando de movimento ao robô (direto Slamware): moveBy($dir)")
                if (chassis.moveBy(dir)) return
            }
        }

        // Fallback: RobotSDK CSJBot.
        val alvo = proxy ?: robot ?: return
        if (velocidade != null) invokeFloat(alvo, "setAngularVelocity", velocidade.coerceIn(0f, VELOCIDADE_ANGULAR_MAX))
        if (angulo != null) {
            val graus = sinalAngulo * kotlin.math.abs(angulo)
            Log.i(TAG, "Enviando comando de movimento ao robô (CSJBot): goAngle($graus)")
            if (!invokeInt(alvo, "goAngle", graus) && !invokeInt(alvo, "moveAngle", graus)) {
                Log.e(TAG, "Erro ao enviar comando: goAngle/moveAngle indisponíveis")
            }
        } else {
            Log.i(TAG, "Enviando comando de movimento ao robô (CSJBot): $contInuo (contínuo)")
            if (!invoke(robot ?: alvo, contInuo, emptyArray(), emptyArray())) {
                Log.e(TAG, "Erro ao enviar comando: método '$contInuo' indisponível")
            }
        }
    }

    /** Verifica sessão ativa antes de aceitar comandos (requisito de segurança). */
    private fun garantirConectado(api: String): Boolean {
        // Aceita se o canal DIRETO Slamware está conectado (Abordagem 1)…
        if (chassis?.connected == true) return true
        // …ou se a sessão do RobotSDK CSJBot está ativa (Abordagem 2).
        if (!inicializado) {
            Log.e(TAG, "Erro ao enviar comando: $api ignorado — sem conexão (chassi direto OFF e SDK não inicializado)")
            return false
        }
        if (!estaConectado()) {
            Log.e(TAG, "Erro ao enviar comando: $api ignorado — robô não conectado/autenticado (getConnectState=false)")
            return false
        }
        return true
    }

    private fun agendarParadaSeNecessario(duracaoMs: Long?) {
        if (duracaoMs == null || duracaoMs <= 0) return
        scheduler.schedule({
            Log.i(TAG, "Duração de ${duracaoMs}ms expirou — parando movimento automaticamente")
            pararMovimento()
        }, duracaoMs, TimeUnit.MILLISECONDS)
    }

    fun liberar() {
        runCatching { scheduler.shutdownNow() }
    }

    // ── Helpers de reflexão (tolerantes) ──────────────────────────────────────

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, args: Array<Any?>): Boolean = try {
        target.javaClass.getMethod(name, *types).invoke(target, *args)
        true
    } catch (e: NoSuchMethodException) {
        false
    } catch (t: Throwable) {
        if (warnedOnce.add(name)) Log.w(TAG, "Método '$name' falhou: ${t.message}")
        false
    }

    private fun invokeFloat(target: Any, name: String, value: Float): Boolean =
        invoke(target, name, arrayOf(Float::class.javaPrimitiveType!!), arrayOf(value))

    private fun invokeInt(target: Any, name: String, value: Int): Boolean =
        invoke(target, name, arrayOf(Int::class.javaPrimitiveType!!), arrayOf(value))
}
