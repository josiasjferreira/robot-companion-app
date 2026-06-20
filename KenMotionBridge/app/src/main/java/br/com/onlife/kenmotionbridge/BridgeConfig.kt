package br.com.onlife.kenmotionbridge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Configuração da ponte. Carregada de `assets/bridge_config.json` e, se existir,
 * sobreposta por `/sdcard/kenmotion/config.json` (para ajustar broker/credenciais
 * no tablet sem recompilar).
 */
data class BridgeConfig(
    // MQTT
    val mqttUri: String,
    val mqttUser: String,
    val mqttPassword: String,
    val clientId: String,
    val cleanSession: Boolean,
    val keepAliveSec: Int,
    val tlsInsecure: Boolean,
    // Tópicos
    val topicCmd: String,
    val topicFeedback: String,
    val topicTelemetry: String,
    // Chassi
    val chassisIp: String,
    val chassisPort: Int,
    val useCsjbotBinding: Boolean,
    // Motion (limites de segurança)
    val vMax: Double,
    val vMaxBoost: Double,
    val wMax: Double,
    val deadzone: Double,
    val accelLinear: Double,
    val accelAngular: Double,
    val watchdogMs: Long,
    val controlHz: Int,
    val safeFrontCm: Double,
    val invertAngular: Boolean,
    // Comandos AIDL do chassi (configuráveis na UI — valores exatos confirmados em runtime)
    val moveMsgId: String,
    val stopMsgId: String,
    val dirForward: Int,
    val dirBackward: Int,
    val dirLeft: Int,
    val dirRight: Int,
    val dirStop: Int,
    val sonarReqId: String,
    val powerReqId: String,
    val telemetryPollMs: Long,
) {
    companion object {
        private const val TAG = "BridgeConfig"
        private const val ASSET = "bridge_config.json"
        private val OVERRIDE = File("/sdcard/kenmotion/config.json")

        // SharedPreferences usado pela tela de Configurações.
        const val PREFS = "ken_bridge_settings"
        const val KEY_HOST = "mqtt_host"
        const val KEY_PORT = "mqtt_port"
        const val KEY_USER = "mqtt_user"
        const val KEY_PASS = "mqtt_pass"
        // Comandos AIDL do chassi (configuráveis na UI)
        const val KEY_MOVE_MSG = "move_msg_id"
        const val KEY_DIR_FWD = "dir_forward"
        const val KEY_DIR_BACK = "dir_backward"
        const val KEY_DIR_LEFT = "dir_left"
        const val KEY_DIR_RIGHT = "dir_right"

        /** Host (sem esquema/porta) extraído de uma URI ssl://host:porta ou wss://host:porta/path. */
        fun hostFromUri(uri: String): String =
            uri.substringAfter("://").substringBefore(":").substringBefore("/")

        /** Porta extraída da URI; default 8883 (MQTT/TLS nativo). */
        fun portFromUri(uri: String): Int =
            uri.substringAfter("://").substringAfter(":", "").substringBefore("/")
                .toIntOrNull() ?: 8883

        /** Persiste os valores da tela de Configurações (MQTT). */
        fun saveSettings(context: Context, host: String, port: Int, user: String, pass: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HOST, host.trim())
                .putInt(KEY_PORT, port)
                .putString(KEY_USER, user.trim())
                .putString(KEY_PASS, pass.trim())
                .apply()
        }

        /** Persiste os comandos AIDL de movimento (msg_id + inteiros das direções). */
        fun saveMotionCmd(context: Context, moveMsgId: String, fwd: Int, back: Int, left: Int, right: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MOVE_MSG, moveMsgId.trim())
                .putInt(KEY_DIR_FWD, fwd).putInt(KEY_DIR_BACK, back)
                .putInt(KEY_DIR_LEFT, left).putInt(KEY_DIR_RIGHT, right)
                .apply()
        }

        fun load(context: Context): BridgeConfig {
            val base = readAsset(context)
            val merged = if (OVERRIDE.exists()) {
                runCatching { deepMerge(base, JSONObject(OVERRIDE.readText())) }
                    .onFailure { Log.w(TAG, "Falha ao ler override: ${it.message}") }
                    .getOrDefault(base)
            } else base

            val mqtt = merged.optJSONObject("mqtt") ?: JSONObject()
            val topics = merged.optJSONObject("topics") ?: JSONObject()
            val chassis = merged.optJSONObject("chassis") ?: JSONObject()
            val motion = merged.optJSONObject("motion") ?: JSONObject()

            // Override da TELA de configurações (SharedPreferences) — prioridade máxima.
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val spHost = sp.getString(KEY_HOST, null)?.trim().orEmpty()
            val uriFromAsset = mqtt.optString("uri", "ssl://localhost:8883")
            val mqttUri = if (spHost.isNotEmpty()) {
                val port = sp.getInt(KEY_PORT, 8883)
                "ssl://$spHost:$port"
            } else uriFromAsset
            // TRIM remove espaços/quebras acidentais coladas no campo (causa comum de "auth falhou").
            val mqttUser = (sp.getString(KEY_USER, null)?.takeIf { spHost.isNotEmpty() }
                ?: mqtt.optString("username", "")).trim()
            val mqttPassword = (sp.getString(KEY_PASS, null)?.takeIf { spHost.isNotEmpty() }
                ?: mqtt.optString("password", "")).trim()

            return BridgeConfig(
                mqttUri = mqttUri,
                mqttUser = mqttUser,
                mqttPassword = mqttPassword,
                clientId = mqtt.optString("clientId", "ken-motion-bridge"),
                cleanSession = mqtt.optBoolean("cleanSession", true),
                keepAliveSec = mqtt.optInt("keepAliveSec", 30),
                tlsInsecure = mqtt.optBoolean("tlsInsecure", false),
                topicCmd = topics.optString("cmd", "ken/motion/cmd"),
                topicFeedback = topics.optString("feedback", "ken/motion/feedback"),
                topicTelemetry = topics.optString("telemetry", "ken/sensors/telemetry"),
                chassisIp = chassis.optString("ip", "192.168.99.2"),
                chassisPort = chassis.optInt("port", 1445),
                useCsjbotBinding = chassis.optBoolean("useCsjbotBinding", true),
                vMax = motion.optDouble("vMax", 0.4),
                vMaxBoost = motion.optDouble("vMaxBoost", 0.7),
                wMax = motion.optDouble("wMax", 0.8),
                deadzone = motion.optDouble("deadzone", 0.08),
                accelLinear = motion.optDouble("accelLinear", 0.8),
                accelAngular = motion.optDouble("accelAngular", 2.5),
                watchdogMs = motion.optLong("watchdogMs", 400L),
                controlHz = motion.optInt("controlHz", 20),
                safeFrontCm = motion.optDouble("safeFrontCm", 40.0),
                invertAngular = motion.optBoolean("invertAngular", false),
                // Comandos AIDL do chassi: UI (SharedPreferences) tem prioridade sobre o asset.
                moveMsgId = sp.getString(KEY_MOVE_MSG, null)?.takeIf { it.isNotBlank() }
                    ?: chassis.optString("moveMsgId", "NAVI_ROBOT_MOVE_REQ"),
                stopMsgId = chassis.optString("stopMsgId", "NAVI_ROBOT_MOVE_REQ"),
                dirForward = sp.getInt(KEY_DIR_FWD, chassis.optInt("dirForward", 0)),
                dirBackward = sp.getInt(KEY_DIR_BACK, chassis.optInt("dirBackward", 1)),
                dirLeft = sp.getInt(KEY_DIR_LEFT, chassis.optInt("dirLeft", 2)),
                dirRight = sp.getInt(KEY_DIR_RIGHT, chassis.optInt("dirRight", 3)),
                dirStop = chassis.optInt("dirStop", -1),
                sonarReqId = chassis.optString("sonarReqId", "GET_SONAR_DISTANCE_REQ"),
                powerReqId = chassis.optString("powerReqId", "ROBOT_GET_POWERTIME_REQ"),
                telemetryPollMs = chassis.optLong("telemetryPollMs", 2500L),
            )
        }

        private fun readAsset(context: Context): JSONObject =
            runCatching {
                context.assets.open(ASSET).bufferedReader().use { JSONObject(it.readText()) }
            }.getOrElse {
                Log.e(TAG, "Não foi possível ler $ASSET; usando defaults", it)
                JSONObject()
            }

        /** Sobrepõe chaves do override sobre o base, recursivamente. */
        private fun deepMerge(base: JSONObject, over: JSONObject): JSONObject {
            val out = JSONObject(base.toString())
            for (key in over.keys()) {
                val ov = over.get(key)
                val bv = out.opt(key)
                if (ov is JSONObject && bv is JSONObject) {
                    out.put(key, deepMerge(bv, ov))
                } else {
                    out.put(key, ov)
                }
            }
            return out
        }
    }
}
