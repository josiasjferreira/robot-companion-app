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
) {
    companion object {
        private const val TAG = "BridgeConfig"
        private const val ASSET = "bridge_config.json"
        private val OVERRIDE = File("/sdcard/kenmotion/config.json")

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

            return BridgeConfig(
                mqttUri = mqtt.optString("uri", "ssl://localhost:8883"),
                mqttUser = mqtt.optString("username", ""),
                mqttPassword = mqtt.optString("password", ""),
                clientId = mqtt.optString("clientId", "ken-motion-bridge"),
                cleanSession = mqtt.optBoolean("cleanSession", true),
                keepAliveSec = mqtt.optInt("keepAliveSec", 30),
                tlsInsecure = mqtt.optBoolean("tlsInsecure", false),
                topicCmd = topics.optString("cmd", "ken/motion/cmd"),
                topicFeedback = topics.optString("feedback", "ken/motion/feedback"),
                topicTelemetry = topics.optString("telemetry", "ken/sensors/telemetry"),
                chassisIp = chassis.optString("ip", "192.168.11.1"),
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
