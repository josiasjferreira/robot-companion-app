// IAarToSdkApp.aidl
//
// Callback que o app-host (RobotSDK / launcher CSJBot) usa para devolver dados
// ao KenMotionBridge depois do binding.
//
// IMPORTANTE: estas assinaturas são um scaffold compatível com o padrão
// com.csjbot.sdkhandler. Se o RobotSDK_release_i18n_2_4_0_43 trouxer um .aidl
// oficial, substitua ESTE arquivo pela versão do fabricante para garantir o
// mesmo descriptor de Binder (caso contrário o bind falha por transação).
package com.csjbot.sdkhandler;

interface IAarToSdkApp {
    // Resultado/handshake do binding (ex.: chassi pronto, parâmetros de conexão)
    void onSdkReady(String info);

    // Eventos genéricos vindos do SDK do fabricante (telemetria nativa, etc.)
    void onSdkEvent(int what, String payload);
}
