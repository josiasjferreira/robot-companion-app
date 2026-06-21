// ISdkAppToAar.aidl
//
// Interface exposta pelo app-host do RobotSDK. O KenMotionBridge faz bindService()
// nela para registrar seu callback e obter o handle do chassi.
//
// IMPORTANTE: scaffold compatível com o padrão com.csjbot.sdkhandler. Se houver
// um .aidl oficial no AAR do fabricante, use-o no lugar deste (ver IAarToSdkApp.aidl).
package com.csjbot.sdkhandler;

import com.csjbot.sdkhandler.IAarToSdkApp;

interface ISdkAppToAar {
    // Registra o callback do KenMotionBridge no SDK do fabricante.
    void register(IAarToSdkApp callback);

    void unregister(IAarToSdkApp callback);

    // Solicita ao SDK a inicialização/handle do chassi Slamware (TCP 1445).
    // Retorna info de conexão (ex.: ip:porta) ou null se o SDK gerencia internamente.
    String requestChassis();
}
