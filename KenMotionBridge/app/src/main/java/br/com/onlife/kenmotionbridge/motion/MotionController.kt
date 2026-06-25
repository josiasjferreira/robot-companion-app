package br.com.onlife.kenmotionbridge.motion

import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
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
 */
class MotionController(
    private val chassis: SlamwareChassis,
    private val config: BridgeConfig,
) {
    companion object { private const val TAG = "MotionController" }

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
            else -> Log.w(TAG, "Tipo de comando desconhecido: $raw")
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
        chassis.cancelAction()
        chassis.sendVelocity(0.0, 0.0)
        lastCommandLabel = "stop"
        lastCommandAt = System.currentTimeMillis()
    }

    /**
     * Passo do loop de controle. [dtSec] é o tempo desde o tick anterior.
     * Atualiza a rampa, aplica watchdog + segurança e envia ao chassi.
     */
    fun tick(dtSec: Double) {
        val now = System.currentTimeMillis()

        // Watchdog: sem comando recente -> alvo zero.
        var tv = targetV
        var tw = targetW
        if (now - lastCommandAt > config.watchdogMs) { tv = 0.0; tw = 0.0 }

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

        chassis.sendVelocity(currentV, currentW)
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
