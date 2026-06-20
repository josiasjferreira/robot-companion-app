// ISdkAppToAar.aidl — callback IMPLEMENTADO pelo bridge (o RobotSdkService CHAMA nele).
// Exposto pelo nosso serviço com.aidlagent.AIDLClientService (bind de volta do robô).
// Assinaturas confirmadas via javap no RobotSDK-client.jar.
package com.csjbot.sdkhandler;

interface ISdkAppToAar {
    // O SDK do robô confirma que a sessão foi estabelecida.
    void connectToSDKSucceed();

    // Mensagens JSON vindas do SDK do robô (telemetria/estados/respostas).
    void sdkAppMsgToAar(String json);
}
