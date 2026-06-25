// Configuração do transporte MQTT entre o app web (UI) e o KenMotionBridge.
//
// O KenMotionBridge (app Android no robô) já assina/publica nestes tópicos
// (ver bridge_config.json). Aqui no app web usamos MQTT sobre WebSocket (wss),
// que é o único transporte MQTT que roda dentro de um WebView/navegador.
//
// Os valores podem ser sobrescritos em build time via variáveis de ambiente Vite
// (prefixo VITE_) — assim as credenciais reais não precisam ficar no código.
//
//   VITE_MQTT_URL=wss://SEU-CLUSTER.s1.eu.hivemq.cloud:8884/mqtt
//   VITE_MQTT_USERNAME=ken-robot
//   VITE_MQTT_PASSWORD=...
//
// Para controle LOCAL (mesma rede) aponte para um broker local; para controle
// REMOTO (4G/5G/VPN) aponte para o HiveMQ Cloud. Mesmo broker do KenMotionBridge.

const env = (import.meta as unknown as { env: Record<string, string | undefined> }).env ?? {};

export interface MqttConfig {
  url: string;
  username?: string;
  password?: string;
  clientId: string;
  topics: {
    cmd: string;
    feedback: string;
    telemetry: string;
  };
}

export const mqttConfig: MqttConfig = {
  // wss://HOST:8884/mqtt (WebSocket/TLS) — porta WebSocket do HiveMQ Cloud.
  url: env.VITE_MQTT_URL ?? "wss://SEU-CLUSTER.s1.eu.hivemq.cloud:8884/mqtt",
  username: env.VITE_MQTT_USERNAME ?? "ken-robot",
  password: env.VITE_MQTT_PASSWORD ?? "TROQUE-ME",
  // ClientId único por instância da UI (evita colisão com o ken-motion-bridge).
  clientId: `ken-ui-${Math.random().toString(16).slice(2, 8)}`,
  topics: {
    cmd: env.VITE_MQTT_TOPIC_CMD ?? "ken/motion/cmd",
    feedback: env.VITE_MQTT_TOPIC_FEEDBACK ?? "ken/motion/feedback",
    telemetry: env.VITE_MQTT_TOPIC_TELEMETRY ?? "ken/sensors/telemetry",
  },
};
