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
class SlamwareChassis(private val context: Context, private val config: BridgeConfig) {

    companion object {
        private const val TAG = "SlamwareChassis"
        // Ação de bind do app-host do RobotSDK (CSJBot). Ajuste se o fabricante usar outra.
        private const val CSJBOT_BIND_ACTION = "com.csjbot.sdkhandler.SdkService"
        private const val CSJBOT_BIND_PKG = "com.csjbot.sdkhandler"

        private const val PLATFORM_CLS = "com.slamtec.slamware.SlamwareCorePlatform"
        private const val RTV_CLS = "com.slamtec.slamware.robot.RealTimeVelocity"
        private const val MOVE_DIR_CLS = "com.slamtec.slamware.robot.MoveDirection"
    }

    @Volatile var connected: Boolean = false
        private set

    /** Handle do chassi (SlamwareCorePlatform). Object para não exigir o AAR em compile-time. */
    @Volatile private var platform: Any? = null

    /** Instância reutilizada de RealTimeVelocity. */
    @Volatile private var rtv: Any? = null

    private var sdkBinder: ISdkAppToAar? = null
    private val warnedOnce = HashSet<String>()

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
            runCatching {
                sdkBinder?.register(aarCallback)
                val info = sdkBinder?.requestChassis()
                Log.i(TAG, "Binding CSJBot OK. requestChassis=$info")
            }.onFailure { Log.w(TAG, "Falha no handshake CSJBot: ${it.message}") }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sdkBinder = null
            Log.w(TAG, "Binding CSJBot desconectado")
        }
    }

    /** Conecta ao chassi. Tenta o binding CSJBot (se configurado) e a conexão Slamware direta. */
    fun connect(): Boolean {
        if (config.useCsjbotBinding) bindCsjbot()
        connectSlamware()
        return connected
    }

    private fun bindCsjbot() {
        runCatching {
            val intent = Intent(CSJBOT_BIND_ACTION).apply { setPackage(CSJBOT_BIND_PKG) }
            val ok = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService CSJBot solicitado (ok=$ok)")
        }.onFailure { Log.w(TAG, "bindService CSJBot indisponível: ${it.message}") }
    }

    private fun connectSlamware() {
        try {
            val cls = Class.forName(PLATFORM_CLS)
            // SlamwareCorePlatform.connect(String ip, int port)
            val connect: Method = cls.getMethod("connect", String::class.java, Int::class.javaPrimitiveType)
            platform = connect.invoke(null, config.chassisIp, config.chassisPort)
            connected = platform != null
            Log.i(TAG, "Slamware conectado em ${config.chassisIp}:${config.chassisPort} -> $connected")
        } catch (t: Throwable) {
            connected = false
            Log.e(TAG, "Falha ao conectar Slamware (AAR presente?): ${t.message}")
        }
    }

    fun disconnect() {
        runCatching { sdkBinder?.unregister(aarCallback) }
        runCatching { context.unbindService(serviceConnection) }
        val p = platform ?: return
        invoke(p, "disconnect")
        platform = null
        connected = false
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

    // ── Movimentos discretos ────────────────────────────────────────────────

    enum class Dir { FORWARD, BACKWARD, TURN_LEFT, TURN_RIGHT }

    fun moveBy(dir: Dir) {
        val p = platform ?: return
        try {
            val moveDirCls = Class.forName(MOVE_DIR_CLS)
            // MoveDirection.valueOf("FORWARD" | "BACKWARD" | "TURN_LEFT" | "TURN_RIGHT")
            val enumVal = moveDirCls.getMethod("valueOf", String::class.java).invoke(null, dir.name)
            invoke(p, "moveBy", arrayOf(moveDirCls), arrayOf(enumVal))
        } catch (t: Throwable) {
            warnOnce("moveBy", t)
        }
    }

    fun cancelAction() {
        val p = platform ?: return
        if (!invoke(p, "cancelAction")) invoke(p, "CancelAction")
    }

    // ── Sensores / telemetria ────────────────────────────────────────────────

    /** Distância frontal em centímetros; NaN se indisponível. */
    fun frontDistanceCm(): Double {
        val p = platform ?: return Double.NaN
        // Tenta leituras conhecidas (metros) e converte.
        for (name in arrayOf("getFrontDistance", "getFrontObstacleDistance", "getMinFrontDistance")) {
            val m = invokeReturningDouble(p, name)
            if (m != null && !m.isNaN()) return m * 100.0
        }
        return Double.NaN
    }

    /** Bateria em %, ou -1 se indisponível. */
    fun batteryPercent(): Int {
        val p = platform ?: return -1
        val v = invokeReturningDouble(p, "getBatteryPercentage")
            ?: invokeReturningDouble(p, "getBatteryPercent")
        return v?.toInt() ?: -1
    }

    /** Telemetria nativa do chassi (bateria/IMU) como JSON para republicar. */
    fun telemetryJson(): JSONObject {
        val json = JSONObject()
        val battery = batteryPercent()
        if (battery >= 0) json.put("battery", battery)

        invokeReturningDouble(platform, "isCharging")?.let { json.put("charging", it != 0.0) }

        // IMU (se exposto). Mantemos tolerante: só inclui o que existir.
        val imu = JSONObject()
        invokeReturningDouble(platform, "getYaw")?.let { imu.put("yaw", it) }
        invokeReturningDouble(platform, "getPitch")?.let { imu.put("pitch", it) }
        invokeReturningDouble(platform, "getRoll")?.let { imu.put("roll", it) }
        if (imu.length() > 0) json.put("imu", imu)

        return json
    }

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
