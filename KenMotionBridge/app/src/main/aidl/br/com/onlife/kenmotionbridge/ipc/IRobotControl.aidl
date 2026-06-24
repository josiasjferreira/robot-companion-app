// IPC entre os processos da ponte (principal ⇄ :mqtt). Implementada pelo RobotBridgeService
// (processo principal) e chamada pelo MqttBridgeService (processo :mqtt).
package br.com.onlife.kenmotionbridge.ipc;

import br.com.onlife.kenmotionbridge.ipc.IFeedbackSink;

interface IRobotControl {
    /** Comando cru recebido em ken/motion/cmd (JSON), repassado ao MotionController. */
    oneway void onCommand(String json);
    /** Registra o canal de volta para publicar feedback/telemetria. */
    void registerFeedback(IFeedbackSink sink);
    void unregisterFeedback(IFeedbackSink sink);
    /** O processo :mqtt informa o estado do broker (para a UI no processo principal). */
    oneway void reportMqttStatus(boolean connected, String error);
    /** Chassi com canal direto ativo agora? */
    boolean isChassisOnline();
}
