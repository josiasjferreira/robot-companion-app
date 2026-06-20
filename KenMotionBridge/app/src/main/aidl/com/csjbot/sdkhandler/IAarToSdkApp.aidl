// IAarToSdkApp.aidl — interface EXPOSTA pelo RobotSdkService (o bridge CHAMA nela).
// Assinaturas confirmadas via javap no RobotSDK-client.jar.
package com.csjbot.sdkhandler;

interface IAarToSdkApp {
    // Registra este app (pelo packageName) no RobotSDK; dispara o bind de volta.
    void connectToSDK(String packageName);

    // Envia uma mensagem JSON de comando/consulta ao SDK do robô.
    void aarMsgToSDKApp(String json);
}
