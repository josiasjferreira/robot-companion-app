// ISdkAppToAar.aidl — contrato OFICIAL do RobotSDK (CSJBot), extraído do APK
// RobotSDK_release_i18n_2.4.0_43 via javap (ver docs/PROTOCOLO_ROBOTSDK.md).
//
// É a interface que o app CLIENTE expõe (SdkCallbackService, ação
// "com.csjbot.sdk.connect"); o SDK binda nela para entregar eventos.
// A ORDEM dos métodos define os códigos de transação Binder e PRECISA bater
// com o app do fabricante: sdkAppMsgToAar=1, connectToSDKSucceed=2.
package com.csjbot.sdkhandler;

interface ISdkAppToAar {
    // Eventos/respostas JSON vindos do SDK (padrão coshandler REQ/NTF/RSP).
    void sdkAppMsgToAar(String msg);

    // Confirmação do handshake iniciado por IAarToSdkApp.connectToSDK().
    void connectToSDKSucceed();
}
