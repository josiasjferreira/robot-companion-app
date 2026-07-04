// IAarToSdkApp.aidl — contrato OFICIAL do RobotSDK (CSJBot), extraído do APK
// RobotSDK_release_i18n_2.4.0_43 via javap (ver docs/PROTOCOLO_ROBOTSDK.md).
//
// É a interface que o RobotSdkService devolve no onBind() (MyBinder).
// A ORDEM dos métodos define os códigos de transação Binder e PRECISA bater
// com o app do fabricante: connectToSDK=1, aarMsgToSDKApp=2.
package com.csjbot.sdkhandler;

interface IAarToSdkApp {
    // Handshake: o cliente se apresenta (nome/pacote). Depois disso o SDK
    // binda DE VOLTA no service do cliente com a ação "com.csjbot.sdk.connect".
    void connectToSDK(String appName);

    // Envio de comandos JSON ao SDK (padrão coshandler REQ/NTF/RSP).
    void aarMsgToSDKApp(String msg);
}
