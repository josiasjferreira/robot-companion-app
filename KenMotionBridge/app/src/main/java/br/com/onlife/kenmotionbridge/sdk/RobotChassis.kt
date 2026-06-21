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

/**
 * Camada de chassi 100% via AIDL com o RobotSdkService (com.csjbot.robotsdk.ten).
 *
 * NÃO usa `com.slamtec.slamware.*` in-process (sem SlamwareCorePlatform, sem
 * System.loadLibrary("rpsdk")) — o RobotSdkService é o dono do chassi e da porta serial;
 * este bridge apenas TROCA JSON com ele:
 *   - saída: IAarToSdkApp.aarMsgToSDKApp(json)  (este objeto chama no serviço)
 *   - entrada: ISdkAppToAar.sdkAppMsgToAar(json) e connectToSDKSucceed()
 *              (o serviço chama no nosso [com.aidlagent.AIDLClientService], que roteia para cá)
 *
 * Só marca CONECTADO quando chega `connectToSDKSucceed()`.
 */
class RobotChassis(
    private val context: Context,
    private val config: BridgeConfig,
    /** (conectado, erro, bound, info). */
    private val onStatus: (Boolean, String, Boolean, String) -> Unit = { _, _, _, _ -> },
) {
    companion object {
        private const val TAG = "RobotChassis"
        private const val SDK_PKG = "com.csjbot.robotsdk.ten"
        private const val SDK_SERVICE = "com.csjbot.robotsdk.service.RobotSdkService"
        private const val SDK_ACTION = "com.csjbot.robotsdkservice.startservice"

        /** Instância ativa para o AIDLClientService rotear os callbacks recebidos do robô. */
        @Volatile var active: RobotChassis? = null
            private set
    }

    enum class Dir { FORWARD, BACKWARD, LEFT, RIGHT }

    @Volatile var connected: Boolean = false   // só após connectToSDKSucceed
        private set
    @Volatile private var bound: Boolean = false
    @Volatile private var sdkError: String = ""
    private var sdk: IAarToSdkApp? = null

    // ── Telemetria parseada (atualizada pelos callbacks JSON) ─────────────────
    @Volatile var batteryPct: Int = -1; private set
    @Volatile var frontCm: Double = Double.NaN; private set
    @Volatile var motionMode: Int = -1; private set
    @Volatile var naviReady: Boolean = false; private set
    @Volatile var reStatus: Int = -1; private set   // status de relocalização
    @Volatile var lastMsgAt: Long = 0L; private set

    /** Último JSON enviado/recebido, para exibir na tela. */
    @Volatile var lastTx: String = "—"; private set
    @Volatile var lastRx: String = "—"; private set

    private fun report() = onStatus(connected, sdkError, bound, "bat=$batteryPct front=$frontCm mode=$motionMode navi=$naviReady")

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sdk = IAarToSdkApp.Stub.asInterface(binder)
            bound = true
            sdkError = "bound; chamando connectToSDK e aguardando connectToSDKSucceed…"
            Log.i(TAG, "onServiceConnected: ${name?.flattenToShortString()}")
            report()
            // Pede a sessão; o robô fará bind de volta no nosso AIDLClientService.
            runCatching { sdk?.connectToSDK(context.packageName) }
                .onSuccess { Log.i(TAG, "connectToSDK(${context.packageName}) enviado") }
                .onFailure {
                    sdkError = "Falha em connectToSDK: ${it.message}"
                    Log.e(TAG, sdkError); report()
                }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sdk = null; bound = false; connected = false
            sdkError = "RobotSdkService desconectado"
            Log.w(TAG, sdkError); report()
        }
    }

    /** Binda o RobotSdkService. Retorna false aqui: CONECTADO só vira true no callback. */
    fun connect(): Boolean {
        active = this
        sdkError = ""; bound = false; connected = false
        report()

        val pm = context.packageManager
        // 1) Descobre o serviço pela AÇÃO (caso o pacote/classe difiram neste tablet).
        val discovered = runCatching {
            pm.queryIntentServices(Intent(SDK_ACTION), 0)
        }.getOrNull().orEmpty().firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

        val component = discovered ?: ComponentName(SDK_PKG, SDK_SERVICE)
        if (discovered != null) {
            Log.i(TAG, "Serviço descoberto pela ação: ${component.flattenToShortString()}")
        }

        val intent = Intent(SDK_ACTION).apply { component?.let { setClassName(it.packageName, it.className) } }
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "startService falhou: ${it.message}") }
        val ok = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (se: SecurityException) {
            sdkError = "bind negado — sem permissão para ${component.packageName} (${se.message})"
            Log.e(TAG, sdkError); report(); return false
        }
        if (!ok) {
            sdkError = "RobotSdkService não encontrado — ${component.flattenToShortString()}. " +
                "Candidatos no aparelho: ${discoverCandidates()}"
            Log.e(TAG, sdkError); report()
        }
        return connected
    }

    /** Diagnóstico (sem adb): lista serviços que respondem à ação e pacotes csjbot/robot/slam. */
    private fun discoverCandidates(): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        // Serviços que respondem à ação de start.
        runCatching {
            pm.queryIntentServices(Intent(SDK_ACTION), 0).forEach {
                sb.append("[svc ${it.serviceInfo.packageName}/${it.serviceInfo.name}] ")
            }
        }
        // Pacotes instalados com palavras-chave do fabricante.
        runCatching {
            pm.getInstalledPackages(0).map { it.packageName }
                .filter { p -> listOf("csjbot", "robotsdk", "slam", "csjrobot", ".ten").any { p.contains(it, true) } }
                .forEach { sb.append("[pkg $it] ") }
        }
        val out = sb.toString().ifBlank { "nenhum (verifique se o app do RobotSDK está instalado / QUERY_ALL_PACKAGES)" }
        Log.i(TAG, "Candidatos: $out")
        return out.take(300)
    }

    fun disconnect() {
        runCatching { context.unbindService(conn) }
        sdk = null; bound = false; connected = false
        if (active === this) active = null
        report()
    }

    // ── Chamados pelo AIDLClientService (callbacks do robô) ───────────────────

    fun onConnectSucceed() {
        connected = true; sdkError = ""; lastMsgAt = System.currentTimeMillis()
        Log.i(TAG, "connectToSDKSucceed — SDK/Chassi CONECTADO")
        report()
    }

    fun onSdkMessage(json: String?) {
        json ?: return
        lastMsgAt = System.currentTimeMillis()
        lastRx = json
        Log.i(TAG, "RX: $json")
        parse(json)
        report()
    }

    // ── Saída (comandos/consultas) ────────────────────────────────────────────

    fun sendJson(json: String): Boolean {
        val s = sdk
        if (s == null) { Log.w(TAG, "sendJson ignorado — sem bind: $json"); return false }
        return try {
            lastTx = json
            Log.i(TAG, "TX: $json")
            s.aarMsgToSDKApp(json)
            true
        } catch (t: Throwable) {
            sdkError = "Falha ao enviar JSON: ${t.message}"
            Log.e(TAG, sdkError); report(); false
        }
    }

    /** Move numa direção lógica; o msg_id e os inteiros do enum vêm do [config] (configuráveis na UI). */
    fun move(dir: Dir): Boolean {
        val d = when (dir) {
            Dir.FORWARD -> config.dirForward
            Dir.BACKWARD -> config.dirBackward
            Dir.LEFT -> config.dirLeft
            Dir.RIGHT -> config.dirRight
        }
        return sendMove(d)
    }

    fun sendMove(direction: Int): Boolean =
        sendJson(JSONObject().put("msg_id", config.moveMsgId).put("direction", direction).toString())

    fun sendStop(): Boolean {
        val o = JSONObject().put("msg_id", config.stopMsgId)
        if (config.dirStop >= 0) o.put("direction", config.dirStop)
        return sendJson(o.toString())
    }

    /** Polling leve de telemetria (sonar + energia). */
    fun requestTelemetry() {
        sendJson(JSONObject().put("msg_id", config.sonarReqId).toString())
        sendJson(JSONObject().put("msg_id", config.powerReqId).toString())
    }

    // ── Parse tolerante das mensagens do robô ─────────────────────────────────

    private fun parse(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("msg_id")) {
            "NAVI_ROBOT_STATES_NTF" -> {
                if (o.has("naviReady")) naviReady = o.optBoolean("naviReady")
                if (o.has("motion_mode")) motionMode = o.optInt("motion_mode")
            }
            "NAVI_ROBOT_RELOCATION_STATES_NTF" -> {
                if (o.has("reStatus")) reStatus = o.optInt("reStatus")
            }
            "ROBOT_GET_POWERTIME_RSP" -> readBattery(o)
            else -> {
                // Respostas de sonar / energia com nomes variados — best-effort.
                readBattery(o)
                readFront(o)
            }
        }
    }

    private fun readBattery(o: JSONObject) {
        for (k in arrayOf("battery_pct", "battery", "power", "electricity", "batteryPercent")) {
            if (o.has(k)) { batteryPct = o.optInt(k, batteryPct); return }
        }
    }

    private fun readFront(o: JSONObject) {
        // Distância em cm; aceita metros se vier em "distance_m".
        for (k in arrayOf("distance_front_cm", "front_cm", "distance_cm", "sonar_front", "distance")) {
            if (o.has(k)) { frontCm = o.optDouble(k, frontCm); return }
        }
        if (o.has("distance_m")) frontCm = o.optDouble("distance_m") * 100.0
    }

    /** Telemetria viva? (recebeu algo há < 5 s) */
    fun telemetryFresh(): Boolean = connected && (System.currentTimeMillis() - lastMsgAt) < 5000L
}
