package br.com.onlife.kenmotionbridge.motion

import android.util.Log
import br.com.onlife.kenmotionbridge.BridgeConfig
import br.com.onlife.kenmotionbridge.sdk.KenMotionSdk
import br.com.onlife.kenmotionbridge.sdk.RobotChassis
import org.json.JSONObject
import kotlin.math.abs

/**
 * Traduz comandos do app web (MQTT `ken/motion/cmd`) em comandos DISCRETOS do chassi,
 * enviados como JSON via AIDL ([RobotChassis]). Não há mais velocidade em tempo real
 * (Slamware in-process foi removido); o RobotSdkService é o dono do chassi.
 *
 *  - `joystick` → direção dominante (frente/trás/esq/dir) → RobotChassis.move(dir)
 *  - `stop`     → RobotChassis.sendStop()
 *  - `chassis`  → comandos de alto nível via [KenMotionSdk]
 *
 * Watchdog: se nenhum joystick chegar em [BridgeConfig.watchdogMs], envia stop uma vez.
 */
class MotionController(
    private val chassis: RobotChassis,
    private val config: BridgeConfig,
    private val motionSdk: KenMotionSdk? = null,
) {
    companion object { private const val TAG = "MotionController" }

    // Estado exposto para feedback/telemetria e UI.
    @Volatile var currentV = 0.0; private set
    @Volatile var currentW = 0.0; private set
    @Volatile var frontCm = Double.NaN; private set
    @Volatile var lastCommandLabel = "—"; private set

    @Volatile private var lastCommandAt = 0L
    @Volatile private var moving = false
    @Volatile private var lastDir: RobotChassis.Dir? = null

    /** Processa um payload JSON de `ken/motion/cmd`. Tolerante a payload ruim. */
    fun onCommand(raw: String) {
        val json = try { JSONObject(raw) } catch (t: Throwable) {
            Log.w(TAG, "Payload inválido ignorado: ${t.message}"); return
        }
        when (json.optString("type")) {
            "joystick" -> {
                val x = clamp(json.optDouble("x", 0.0), -1.0, 1.0)
                val y = clamp(json.optDouble("y", 0.0), -1.0, 1.0)
                applyJoystick(x, y)
            }
            "stop" -> stop()
            "chassis" -> handleChassis(json)
            else -> Log.w(TAG, "Tipo de comando desconhecido: $raw")
        }
    }

    /** Converte (x,y) na direção DOMINANTE e envia o comando discreto correspondente. */
    private fun applyJoystick(x: Double, y: Double) {
        val ax = abs(x); val ay = abs(y)
        if (ax < config.deadzone && ay < config.deadzone) { stop(); return }

        val angSign = if (config.invertAngular) -1.0 else 1.0
        val dir = if (ay >= ax) {
            if (y >= 0) RobotChassis.Dir.FORWARD else RobotChassis.Dir.BACKWARD
        } else {
            if (x * angSign >= 0) RobotChassis.Dir.LEFT else RobotChassis.Dir.RIGHT
        }

        lastCommandAt = System.currentTimeMillis()
        // Só reenvia ao chassi quando a direção muda (evita inundar a 1445).
        if (dir != lastDir || !moving) {
            chassis.move(dir)
            lastDir = dir
            moving = true
        }
        // Velocidade nominal só para feedback/UI (não há leitura contínua via AIDL).
        currentV = when (dir) {
            RobotChassis.Dir.FORWARD -> config.vMax
            RobotChassis.Dir.BACKWARD -> -config.vMax
            else -> 0.0
        }
        currentW = when (dir) {
            RobotChassis.Dir.LEFT -> config.wMax
            RobotChassis.Dir.RIGHT -> -config.wMax
            else -> 0.0
        }
        lastCommandLabel = "joystick → ${dir.name}"
    }

    fun stop() {
        if (moving || lastDir != null) chassis.sendStop()
        currentV = 0.0; currentW = 0.0
        moving = false; lastDir = null
        lastCommandLabel = "stop"
        lastCommandAt = System.currentTimeMillis()
    }

    /** Comandos de alto nível, centralizados no [KenMotionSdk]. */
    private fun handleChassis(json: JSONObject) {
        val sdk = motionSdk ?: run { Log.w(TAG, "Comando 'chassis' sem KenMotionSdk"); return }
        val action = json.optString("action")
        val durationMs = if (json.has("durationMs")) json.optLong("durationMs") else null
        lastCommandLabel = "chassis $action"
        lastCommandAt = System.currentTimeMillis()
        when (action) {
            "frente" -> sdk.moverFrente(duracaoMs = durationMs)
            "tras" -> sdk.moverTras(duracaoMs = durationMs)
            "esquerda" -> sdk.virarEsquerda()
            "direita" -> sdk.virarDireita()
            "parar" -> stop()
            else -> Log.w(TAG, "Ação de chassis desconhecida: $action")
        }
    }

    /** Passo do loop de controle: atualiza distância frontal e aplica o watchdog. */
    fun tick(@Suppress("UNUSED_PARAMETER") dtSec: Double) {
        frontCm = chassis.frontCm
        val now = System.currentTimeMillis()
        if (moving && now - lastCommandAt > config.watchdogMs) {
            Log.i(TAG, "Watchdog: sem comando há >${config.watchdogMs}ms — parando")
            stop()
        }
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v
}
