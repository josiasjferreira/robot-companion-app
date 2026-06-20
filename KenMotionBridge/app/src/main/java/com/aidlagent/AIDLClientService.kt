package com.aidlagent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import br.com.onlife.kenmotionbridge.sdk.RobotChassis
import com.csjbot.sdkhandler.ISdkAppToAar

/**
 * Serviço EXPORTADO que o RobotSdkService (com.csjbot.robotsdk.ten) faz bind de volta,
 * usando o packageName que passamos em IAarToSdkApp.connectToSDK(packageName).
 *
 * O nome de classe `com.aidlagent.AIDLClientService` é o componente esperado pelo SDK do
 * fabricante para o canal de callbacks. Implementa [ISdkAppToAar] e roteia tudo para a
 * instância ativa de [RobotChassis].
 */
class AIDLClientService : Service() {

    companion object { private const val TAG = "AIDLClientService" }

    private val binder = object : ISdkAppToAar.Stub() {
        override fun connectToSDKSucceed() {
            Log.i(TAG, "connectToSDKSucceed (callback do robô)")
            RobotChassis.active?.onConnectSucceed()
        }

        override fun sdkAppMsgToAar(json: String?) {
            RobotChassis.active?.onSdkMessage(json)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind do RobotSdkService: ${intent?.action ?: intent?.component}")
        return binder
    }
}
