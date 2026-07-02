// IPC entre os processos da ponte (principal ⇄ :mqtt). Implementada pelo MqttBridgeService
// (processo :mqtt) e chamada pelo RobotBridgeService (processo principal) para publicar.
package br.com.onlife.kenmotionbridge.ipc;

interface IFeedbackSink {
    /** JSON pronto para ken/motion/feedback. */
    oneway void onFeedback(String json);
    /** JSON pronto para ken/sensors/telemetry. */
    oneway void onTelemetry(String json);
}
