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
    /** Amarra o socket do MQTT à rede celular (4G/USB) — split de rotas com o Wi-Fi do robô. */
    val mqttForceCellular: Boolean,
    /**
     * Dual-homing do tablet do robô: o CHASSI fica numa LAN Ethernet sem internet
     * (ex.: 192.168.99.x) e a INTERNET vem por outra rede (Wi-Fi/4G/USB). Quando ligado:
     *  - o PROCESSO é amarrado à Ethernet (para o RobotSDK alcançar o chassi);
     *  - o socket do MQTT é amarrado à rede de internet (com DNS escopado nela).
     */
    val dualHoming: Boolean,
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

        // SharedPreferences usado pela tela de Configurações.
        const val PREFS = "ken_bridge_settings"
        const val KEY_HOST = "mqtt_host"
        const val KEY_PORT = "mqtt_port"
        const val KEY_USER = "mqtt_user"
        const val KEY_PASS = "mqtt_pass"
        const val KEY_FORCE_CELL = "mqtt_force_cellular"

        /** Host (sem esquema/porta) extraído de uma URI ssl://host:porta ou wss://host:porta/path. */
        fun hostFromUri(uri: String): String =
            uri.substringAfter("://").substringBefore(":").substringBefore("/")

        /** Porta extraída da URI; default 8883 (MQTT/TLS nativo). */
        fun portFromUri(uri: String): Int =
            uri.substringAfter("://").substringAfter(":", "").substringBefore("/")
                .toIntOrNull() ?: 8883

        /**
         * Arquivo de settings compartilhado entre processos. SharedPreferences NÃO é
         * confiável entre processos (o :mqtt lia valores velhos/vazios e caía no
         * placeholder do asset — na tela: ora "SEU-CLUSTER", ora o host real).
         * Um arquivo com escrita atômica (tmp+rename) é lido sempre fresco do disco.
         */
        private fun settingsFile(context: Context) = File(context.filesDir, "bridge_settings.json")

        /** Persiste os valores da tela de Configurações (arquivo multi-processo + prefs legadas). */
        fun saveSettings(
            context: Context, host: String, port: Int, user: String, pass: String,
            forceCellular: Boolean,
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HOST, host.trim())
                .putInt(KEY_PORT, port)
                .putString(KEY_USER, user.trim())
                .putString(KEY_PASS, pass.trim())
                .putBoolean(KEY_FORCE_CELL, forceCellular)
                .apply()

            val json = JSONObject()
                .put("host", host.trim())
                .put("port", port)
                .put("user", user.trim())
                .put("pass", pass.trim())
                .put("forceCellular", forceCellular)
            runCatching {
                val f = settingsFile(context)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(f)) { f.writeText(json.toString()); tmp.delete() }
            }.onFailure { Log.e(TAG, "Falha ao gravar settings em arquivo: ${it.message}") }
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

            // Override da TELA de configurações — prioridade máxima. Fonte primária: o
            // ARQUIVO multi-processo; prefs ficam como fallback legado (instalações antigas).
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fileJson = runCatching { JSONObject(settingsFile(context).readText()) }.getOrNull()
            val spHost = (fileJson?.optString("host")?.ifEmpty { null }
                ?: sp.getString(KEY_HOST, null))?.trim().orEmpty()
            val spPort = fileJson?.optInt("port", 8883) ?: sp.getInt(KEY_PORT, 8883)
            // Host de placeholder (ex.: SEU-CLUSTER…) gravado por engano é IGNORADO → cai no asset.
            val spHostValid = spHost.isNotEmpty() && !spHost.contains("SEU-CLUSTER", ignoreCase = true)
            val uriFromAsset = mqtt.optString("uri", "ssl://localhost:8883")
            val mqttUri = if (spHostValid) "ssl://$spHost:$spPort" else uriFromAsset
            // Usuário/senha do override (se preenchidos) — INDEPENDENTES do host, para não perder a
            // senha real quando o host salvo for um placeholder. TRIM remove espaços/quebras coladas.
            val spUser = (fileJson?.optString("user")?.ifEmpty { null }
                ?: sp.getString(KEY_USER, null))?.trim().orEmpty()
            val spPass = (fileJson?.optString("pass")?.ifEmpty { null }
                ?: sp.getString(KEY_PASS, null))?.trim().orEmpty()
            val mqttUser = (if (spUser.isNotEmpty()) spUser else mqtt.optString("username", "")).trim()
            val mqttPassword = (if (spPass.isNotEmpty()) spPass else mqtt.optString("password", "")).trim()

            return BridgeConfig(
                mqttUri = mqttUri,
                mqttUser = mqttUser,
                mqttPassword = mqttPassword,
                clientId = mqtt.optString("clientId", "ken-motion-bridge"),
                cleanSession = mqtt.optBoolean("cleanSession", true),
                keepAliveSec = mqtt.optInt("keepAliveSec", 30),
                tlsInsecure = mqtt.optBoolean("tlsInsecure", false),
                mqttForceCellular = fileJson?.optBoolean("forceCellular", false)
                    ?: sp.getBoolean(KEY_FORCE_CELL, mqtt.optBoolean("forceCellular", false)),
                dualHoming = mqtt.optBoolean("dualHoming", true),
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
