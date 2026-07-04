package br.com.onlife.kenmotionbridge.sdk

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.csjbot.sdkhandler.ISdkAppToAar

/**
 * Serviço que o RobotSdkService (CSJBot) usa para falar DE VOLTA com a ponte.
 *
 * Contrato oficial extraído do APK do RobotSDK (ver docs/PROTOCOLO_ROBOTSDK.md):
 * após o cliente chamar `IAarToSdkApp.connectToSDK(appName)`, o SDK binda no
 * serviço do cliente que declara a ação `com.csjbot.sdk.connect` e entrega um
 * `ISdkAppToAar` — `connectToSDKSucceed()` confirma o handshake e
 * `sdkAppMsgToAar(json)` traz eventos/respostas (padrão coshandler REQ/NTF/RSP).
 *
 * Os eventos são repassados ao [SdkLink] (objeto-ponte, processo principal).
 */
class SdkCallbackService : Service() {

    companion object { private const val TAG = "SdkCallbackService" }

    private val binder = object : ISdkAppToAar.Stub() {
        override fun connectToSDKSucceed() {
            Log.i(TAG, "RobotSDK confirmou o handshake (connectToSDKSucceed)")
            SdkLink.onHandshake()
        }

        override fun sdkAppMsgToAar(msg: String?) {
            msg ?: return
            Log.d(TAG, "Mensagem do RobotSDK: $msg")
            SdkLink.onMessage(msg)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "RobotSDK bindou de volta (action=${intent?.action})")
        return binder
    }
}

/**
 * Ponte estática entre o [SdkCallbackService] (chamado pelo binder do SDK) e o
 * [SlamwareChassis]/telemetria. Mantém o último estado para diagnóstico na tela.
 */
object SdkLink {
    @Volatile var handshakeOk: Boolean = false
        private set

    @Volatile var lastMessage: String? = null
        private set

    @Volatile private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) { listener = l }

    fun onHandshake() { handshakeOk = true }

    fun onMessage(msg: String) {
        lastMessage = msg
        listener?.invoke(msg)
    }

    fun reset() { handshakeOk = false; lastMessage = null }
}
