/**
 * Configuração do broker MQTT (HiveMQ Cloud) lida das variáveis de ambiente Vite.
 *
 * No NAVEGADOR é obrigatório usar WebSocket Secure (wss://HOST:8884/mqtt) — não use
 * ssl://...:8883 (essa porta é do cliente nativo da ponte KenMotionBridge).
 *
 * Defina em um arquivo .env (não versionar credenciais):
 *   VITE_MQTT_URL=wss://SEU-CLUSTER.s1.eu.hivemq.cloud:8884/mqtt
 *   VITE_MQTT_USER=...
 *   VITE_MQTT_PASS=...
 */
export interface MqttConfig {
  url: string;
  username: string;
  password: string;
  topicCmd: string;
  topicFeedback: string;
  topicTelemetry: string;
}

const env = import.meta.env as unknown as Record<string, string | undefined>;

export const mqttConfig: MqttConfig = {
  url: env.VITE_MQTT_URL ?? '',
  username: env.VITE_MQTT_USER ?? '',
  password: env.VITE_MQTT_PASS ?? '',
  topicCmd: env.VITE_MQTT_TOPIC_CMD ?? 'ken/motion/cmd',
  topicFeedback: env.VITE_MQTT_TOPIC_FEEDBACK ?? 'ken/motion/feedback',
  topicTelemetry: env.VITE_MQTT_TOPIC_TELEMETRY ?? 'ken/sensors/telemetry',
};

/** As credenciais essenciais (URL/usuário/senha) estão configuradas? */
export function isMqttConfigured(): boolean {
  return !!mqttConfig.url && !!mqttConfig.username && !!mqttConfig.password;
}
