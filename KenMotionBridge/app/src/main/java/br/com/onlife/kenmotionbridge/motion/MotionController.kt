package br.com.onlife.kenmotionbridge.motion

import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
import br.com.onlife.kenmotionbridge.sdk.KenMotionSdk
import br.com.onlife.kenmotionbridge.sdk.SlamwareChassis
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sign

/**
 * Traduz comandos do app web em velocidade do chassi, com:
 *  - deadzone, rampa suave e tetos RÍGIDOS de velocidade;
 *  - watchdog (zera se nenhum comando chegar em watchdogMs);
 *  - segurança de obstáculo frontal (< safeFrontCm e v>0 -> v=0).
 *
 * O loop de controle (~controlHz) chama [tick]; o MQTT chama [onCommand].
 *
 * Caminhos de movimento:
 *  - `joystick`/`stop`: velocidade em tempo real via [SlamwareChassis] (teleop contínuo).
 *  - `chassis`: comandos de ALTO NÍVEL (frente/trás/girar/ângulo) centralizados no
 *    [KenMotionSdk] (RobotSDK CSJBot). Mantém o joystick intacto.
 */
class MotionController(
    private val chassis: SlamwareChassis,
    private val config: BridgeConfig,
    /** Camada central de movimento do chassi (RobotSDK). Opcional p/ não quebrar testes. */
    private val motionSdk: KenMotionSdk? = null,
) {
    companion object {
        private const val TAG = "MotionController"
        /** Teto de segurança do modo cego (m/s), configurável. */
        private const val V_MAX_BLIND = 0.35
    }

    /** Canal de ACK dos comandos de mapa — o serviço publica em ken/motion/feedback. */
    @Volatile var ackSink: ((JSONObject) -> Unit)? = null
    /** Pedido de publicação IMEDIATA do diag (comando map_status). */
    @Volatile var diagRequester: (() -> Unit)? = null

    private fun ack(type: String, ok: Boolean, detail: String) {
        val j = JSONObject().put("type", type).put("ok", ok).put("detail", detail)
            .put("ts", System.currentTimeMillis())
        runCatching { ackSink?.invoke(j) }
            .onFailure { Log.w(TAG, "ack($type) falhou: ${it.message}") }
    }

    /** Fim da janela do watchdog do modo cego (300 ms). 0 = modo cego inativo. */
    @Volatile private var blindUntil = 0L
    /** Modo cego ativo agora? (para o feedback blind_mode). */
    fun blindActiveNow() = System.currentTimeMillis() < blindUntil

    // Alvos vindos do joystick (já normalizados, antes da rampa).
    @Volatile private var targetV = 0.0
    @Volatile private var targetW = 0.0

    // Velocidade corrente (saída da rampa) — exposta para o feedback.
    @Volatile var currentV = 0.0; private set
    @Volatile var currentW = 0.0; private set

    @Volatile var frontCm = Double.NaN; private set
    @Volatile var lastCommandLabel = "—"; private set
    @Volatile private var lastCommandAt = 0L

    /** Processa um payload JSON de `ken/motion/cmd`. Tolerante a payload ruim. */
    fun onCommand(raw: String) {
        val json = try { JSONObject(raw) } catch (t: Throwable) {
            Log.w(TAG, "Payload inválido ignorado: ${t.message}"); return
        }
        when (json.optString("type")) {
            "joystick" -> {
                val x = clamp(json.optDouble("x", 0.0), -1.0, 1.0)
                val y = clamp(json.optDouble("y", 0.0), -1.0, 1.0)
                val speed = clamp(json.optDouble("speed", 0.0), 0.0, 100.0) / 100.0
                val boost = json.optBoolean("boost", false)
                applyJoystick(x, y, speed, boost)
                lastCommandLabel = "joystick x=%.2f y=%.2f s=%d%s"
                    .format(x, y, (speed * 100).toInt(), if (boost) " boost" else "")
                lastCommandAt = System.currentTimeMillis()
            }
            "stop" -> stop()
            // MODO CEGO (web /admin/movimento): {"type":"blind_move","dx":0,"dy":0.6,"speed":40}
            // dx forçado a 0 (sem giro), dy só para frente (ignora negativo). Roteia pelo
            // caminho de FRENTE em modo TRACK (odometria, sem OA). ATENÇÃO: não existe API
            // de "velocidade bruta" neste SDK (provado em 5 binários — ver
            // docs/REVERSE_DELIVERY_APK.md); o avanço ainda depende do firmware liberar.
            "blind_move" -> {
                val dy = clamp(json.optDouble("dy", 0.0), -1.0, 1.0)
                if (dy <= 0.0) { stop(); return }         // só frente; ré/parado -> para
                val speed = clamp(json.optDouble("speed", 0.0), 0.0, 100.0) / 100.0
                forwardMode = ForwardMode.TRACK           // odometria, sem OA
                targetV = dy * V_MAX_BLIND * speed        // dx ignorado (sem rotação)
                targetW = 0.0
                val now = System.currentTimeMillis()
                lastCommandAt = now
                blindUntil = now + 300L                   // watchdog dedicado de 300 ms
                lastCommandLabel = "blind_move dy=%.2f s=%d → v=%.2f".format(dy, (speed*100).toInt(), targetV)
            }
            // SCANNER de direção: {"type":"moveby_raw","direction":N} envia o código
            // cru ao firmware (descobre a tabela real de direções do chassi).
            "moveby_raw" -> {
                val d = json.optInt("direction", -1)
                val res = chassis.moveByRaw(d)
                lastCommandLabel = "moveby_raw d=$d → $res"
                lastCommandAt = System.currentTimeMillis()
                Log.i(TAG, lastCommandLabel)
            }
            // TESTE isolado do avanço cego: {"type":"track_forward","dist":0.6}
            "track_forward" -> {
                val dist = json.optDouble("dist", 0.6).toFloat()
                val res = chassis.trackForward(dist)
                lastCommandLabel = "track_forward ${dist}m → $res"
                lastCommandAt = System.currentTimeMillis()
                Log.i(TAG, lastCommandLabel)
            }
            // Alterna a estratégia da frente do joystick: {"type":"forward_mode","mode":"track|oa"}
            "forward_mode" -> {
                forwardMode = if (json.optString("mode") == "oa") ForwardMode.OA else ForwardMode.TRACK
                lastCommandLabel = "forward_mode = $forwardMode"
                lastCommandAt = System.currentTimeMillis()
            }
            // Parametro de sistema do chassi (evidencia RoboStudio JobSpeed):
            // {"type":"sys_param","key":"max_linear_vel","value":"0.6"}  (get se sem value)
            "sys_param" -> {
                val key = json.optString("key")
                val res = if (json.has("value")) chassis.setSystemParam(key, json.optString("value"))
                          else "$key = " + chassis.getSystemParam(key)
                lastCommandLabel = "sys_param $res"
                lastCommandAt = System.currentTimeMillis()
                Log.i(TAG, lastCommandLabel)
            }
            // ROTA A (mapa): map_status força o diag imediato; os demais acionam o
            // mapCtl do chassi. Cada um responde {"type":…,"ok":…,"detail":…} no
            // ken/motion/feedback (aceite: operador mapeia sem tocar no RoboStudio).
            "map_status" -> {
                diagRequester?.invoke()
                ack("map_status", true, "diag publicado")
                lastCommandLabel = "map_status → diag"
                lastCommandAt = System.currentTimeMillis()
            }
            "build_mode", "begin_map", "end_map", "clear_map", "recover_localization" -> {
                val tipo = json.optString("type")
                val (ok, detail) = chassis.mapCtl(tipo)
                ack(tipo, ok, detail)
                lastCommandLabel = "$tipo → $detail"
                lastCommandAt = System.currentTimeMillis()
                Log.i(TAG, lastCommandLabel)
            }
            "chassis" -> handleChassis(json)
            // ATIVADOR do chassi: alavancas do stack do fabricante (wakeup, modos, mapa).
            // {"type":"chassis_ctl","action":"wakeup|idle|navi_mode|build_mode|begin_map|loc_on|loc_off|upd_on|upd_off|maps"}
            "chassis_ctl" -> {
                val action = json.optString("action")
                val res = chassis.chassisCtl(action)
                lastCommandLabel = "ctl $action → $res"
                lastCommandAt = System.currentTimeMillis()
                Log.i(TAG, lastCommandLabel)
            }
            else -> Log.w(TAG, "Tipo de comando desconhecido: $raw")
        }
    }

    /**
     * Comandos de ALTO NÍVEL do chassi, centralizados no [KenMotionSdk].
     * Formato: { "type":"chassis", "action":"frente|tras|esquerda|direita|parar",
     *            "speed":<m/s opcional>, "angle":<graus opcional>, "durationMs":<opcional> }
     */
    private fun handleChassis(json: JSONObject) {
        val sdk = motionSdk
        if (sdk == null) {
            Log.w(TAG, "Comando 'chassis' ignorado: KenMotionSdk não disponível")
            return
        }
        val action = json.optString("action")
        val speed = json.optDouble("speed", KenMotionSdk.VELOCIDADE_PADRAO.toDouble()).toFloat()
        val angle = if (json.has("angle")) json.optInt("angle") else null
        val durationMs = if (json.has("durationMs")) json.optLong("durationMs") else null
        lastCommandLabel = "chassis $action${angle?.let { " ${it}°" } ?: ""}"
        lastCommandAt = System.currentTimeMillis()
        when (action) {
            "frente" -> sdk.moverFrente(speed, durationMs)
            "tras" -> sdk.moverTras(speed, durationMs)
            "esquerda" -> sdk.virarEsquerda(angle, speed)
            "direita" -> sdk.virarDireita(angle, speed)
            "parar" -> { sdk.pararMovimento(); stop() }
            else -> Log.w(TAG, "Ação de chassis desconhecida: $action")
        }
    }

    private fun applyJoystick(x: Double, y: Double, speed: Double, boost: Boolean) {
        val dx = deadzone(x)
        val dy = deadzone(y)
        val vMax = if (boost) config.vMaxBoost else config.vMax
        // y -> linear (frente +/trás −); x -> angular (girar esq +/dir −)
        val angSign = if (config.invertAngular) -1.0 else 1.0
        targetV = dy * vMax * speed
        targetW = angSign * dx * config.wMax * speed
        lastCommandAt = System.currentTimeMillis()
    }

    fun stop() {
        targetV = 0.0; targetW = 0.0
        currentV = 0.0; currentW = 0.0
        blindUntil = 0L                    // encerra o modo cego
        chassis.cancelAction()
        chassis.sendVelocity(0.0, 0.0)
        stopDrive()
        lastCommandLabel = "stop"
        lastCommandAt = System.currentTimeMillis()
    }

    /**
     * Passo do loop de controle. [dtSec] é o tempo desde o tick anterior.
     * Atualiza a rampa, aplica watchdog + segurança e envia ao chassi.
     */
    fun tick(dtSec: Double) {
        val now = System.currentTimeMillis()

        // Watchdog: sem comando recente -> alvo zero. No modo cego a janela é 300 ms
        // (dead-man mais curto exigido pelo teleop cego); senão o watchdog do config.
        val wd = if (blindUntil != 0L) 300L else config.watchdogMs
        var tv = targetV
        var tw = targetW
        if (now - lastCommandAt > wd) { tv = 0.0; tw = 0.0; if (blindUntil != 0L && now > blindUntil) blindUntil = 0L }

        // Segurança de obstáculo frontal.
        frontCm = chassis.frontDistanceCm()
        if (!frontCm.isNaN() && frontCm < config.safeFrontCm && tv > 0.0) {
            tv = 0.0
        }

        // Rampa suave (limita a variação por tick).
        currentV = ramp(currentV, tv, config.accelLinear * dtSec)
        currentW = ramp(currentW, tw, config.accelAngular * dtSec)

        // Tetos RÍGIDOS — nunca exceder, independente de payload.
        currentV = clamp(currentV, -config.vMaxBoost, config.vMaxBoost)
        currentW = clamp(currentW, -config.wMax, config.wMax)

        dispatchDrive(currentV, currentW)
    }

    // ── Despacho DISCRETO (o que este RobotSDK realmente executa) ────────────
    // Verificado via javap no RobotSDK-client.jar: NÃO existe setter de velocidade
    // contínua (sendVelocity degrada em no-op). O movimento real é
    // moveBy(FORWARD/BACKWARD/TURN_LEFT/TURN_RIGHT) reemitido enquanto o joystick
    // segura a direção, com setSpeed/setAngularVelocity regulando a intensidade.

    @Volatile private var lastDir: SlamwareChassis.Dir? = null
    private var lastMoveByAt = 0L
    private var lastSpeedSent = -1.0
    private var lastAngSent = -1.0

    private fun dispatchDrive(v: Double, w: Double) {
        // Mantido: inofensivo neste SDK e útil se outra variante expuser velocidade real.
        chassis.sendVelocity(v, w)

        val vAbs = abs(v)
        val wAbs = abs(w)
        val dir = when {
            vAbs < 0.03 && wAbs < 0.08 -> null
            vAbs >= wAbs * 0.5 -> if (v > 0) SlamwareChassis.Dir.FORWARD else SlamwareChassis.Dir.BACKWARD
            else -> if (w > 0) SlamwareChassis.Dir.TURN_LEFT else SlamwareChassis.Dir.TURN_RIGHT
        }
        if (dir == null) { stopDrive(); return }

        // Intensidade no RobotSDK (linear m/s; angular rad/s). Só reenvia quando muda.
        if (abs(vAbs - lastSpeedSent) > 0.02) {
            motionSdk?.definirVelocidades(vAbs.toFloat(), null)
            lastSpeedSent = vAbs
        }
        if (abs(wAbs - lastAngSent) > 0.05) {
            motionSdk?.definirVelocidades(null, wAbs.toFloat())
            lastAngSent = wAbs
        }

        // Reemite o passo enquanto a direção segue ativa (ações moveBy são curtas).
        val now = System.currentTimeMillis()
        if (dir != lastDir || now - lastMoveByAt > 300L) {
            drive(dir)
            lastDir = dir
            lastMoveByAt = now
        }
    }

    /**
     * Emite um passo de movimento na direção [dir].
     *
     * Verificado no bytecode do fabricante (SlamAction): o teleop oficial usa
     * exatamente `platform.moveBy(direção)` — inclusive para FRENTE. Então a
     * recusa do avanço vem do FIRMWARE (segurança/sensor), não do comando.
     * Antes de reemitir a FRENTE, lemos o status/motivo da ação anterior
     * (ActionStatus.BLOCKED + reason) para o firmware nos dizer o porquê —
     * mostrado em "Último comando" na tela.
     */
    private fun drive(dir: SlamwareChassis.Dir) {
        if (dir == SlamwareChassis.Dir.FORWARD) {
            // A FRENTE via moveBy(FORWARD) trava em WAITING_FOR_START (desvio de
            // obstáculo esperando a câmera, que está sem leitura). Usamos o AVANÇO
            // CEGO por odometria (moveTo + MoveTypeTrack), que NÃO espera sensor.
            // Reemite um alvo curto à frente a cada ~0,5 s enquanto o joystick segura.
            if (forwardMode == ForwardMode.TRACK) {
                val res = chassis.trackForward(0.5f)
                lastCommandLabel = "frente(track) → $res"
                return
            }
            chassis.lastActionStatus().takeIf { it.isNotEmpty() }?.let { st ->
                lastCommandLabel = "frente → $st"
            }
            chassis.moveBy(dir)
            motionSdk?.moverNativo(dir)
            return
        }
        chassis.moveBy(dir)
    }

    /** Estratégia de avanço: TRACK (odometria cega, contorna o desvio) ou OA (moveBy padrão). */
    enum class ForwardMode { TRACK, OA }
    @Volatile var forwardMode: ForwardMode = ForwardMode.TRACK

    private fun stopDrive() {
        if (lastDir == null) return
        lastDir = null
        lastSpeedSent = -1.0
        lastAngSent = -1.0
        chassis.cancelAction()
        motionSdk?.pararNativo()          // para o move(0) nativo (frente)
        motionSdk?.definirVelocidades(0f, 0f)
    }

    /** Aplica deadzone e re-escala o restante para [0,1] preservando o sinal. */
    private fun deadzone(value: Double): Double {
        val a = abs(value)
        if (a < config.deadzone) return 0.0
        val scaled = (a - config.deadzone) / (1.0 - config.deadzone)
        return sign(value) * scaled
    }

    private fun ramp(current: Double, target: Double, maxDelta: Double): Double {
        val delta = target - current
        if (abs(delta) <= maxDelta) return target
        return current + sign(delta) * maxDelta
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v
}
