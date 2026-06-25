import mqtt, { MqttClient } from 'mqtt';
import { ConnectionStatus, ConnectionConfig, RobotCommand, RobotResponse, LogEntry } from '@/types/Robot';
import { mqttConfig } from '@/services/mqttConfig';

type LogCallback = (entry: LogEntry) => void;
type StatusCallback = (status: ConnectionStatus) => void;

/**
 * Transporte UI <-> robô via MQTT (sobre WebSocket).
 *
 * Fala os MESMOS tópicos/payloads que o KenMotionBridge já implementa:
 *   - publica comandos em  ken/motion/cmd     ({"type":"joystick"|"stop", ...})
 *   - assina feedback em    ken/motion/feedback
 *   - assina telemetria em  ken/sensors/telemetry
 *
 * A API pública (connect/sendCommand/disconnect/...) é mantida igual à versão
 * HTTP/WebSocket anterior, então NENHUM componente da UI precisa mudar — a
 * tradução de {cmd:'move', linear, angular} para o payload do bridge acontece
 * aqui dentro, em [toBridgePayload].
 */
class RobotConnectionService {
  private config: ConnectionConfig = { ip: '192.168.99.2', port: '8080' };
  private status: ConnectionStatus = 'disconnected';
  private client: MqttClient | null = null;
  private onLog: LogCallback | null = null;
  private onStatus: StatusCallback | null = null;
  private latency: number | null = null;
  private lastAttempt: Date | null = null;

  setCallbacks(onLog: LogCallback, onStatus: StatusCallback) {
    this.onLog = onLog;
    this.onStatus = onStatus;
  }

  getConfig() { return this.config; }
  getLatency() { return this.latency; }
  getLastAttempt() { return this.lastAttempt; }
  getStatus() { return this.status; }

  setConfig(config: ConnectionConfig) {
    this.config = config;
  }

  private log(type: LogEntry['type'], message: string) {
    this.onLog?.({ timestamp: new Date(), type, message });
  }

  private setStatus(status: ConnectionStatus) {
    this.status = status;
    this.onStatus?.(status);
  }

  async connect(): Promise<boolean> {
    this.lastAttempt = new Date();
    this.setStatus('connecting');
    this.log('info', `Conectando ao broker MQTT ${mqttConfig.url}...`);

    // Já conectado? Reaproveita.
    if (this.client?.connected) {
      this.setStatus('connected');
      return true;
    }

    return new Promise<boolean>((resolve) => {
      const start = Date.now();
      let settled = false;

      try {
        this.client = mqtt.connect(mqttConfig.url, {
          username: mqttConfig.username,
          password: mqttConfig.password,
          clientId: mqttConfig.clientId,
          clean: true,
          keepalive: 30,
          reconnectPeriod: 3000,
          connectTimeout: 8000,
        });
      } catch (err) {
        this.setStatus('error');
        this.log('error', `Falha ao iniciar MQTT: ${err}`);
        resolve(false);
        return;
      }

      this.client.on('connect', () => {
        this.latency = Date.now() - start;
        this.setStatus('connected');
        this.log('success', `Conectado via MQTT (${this.latency}ms)`);

        // Assina os tópicos de retorno do bridge.
        this.client?.subscribe([mqttConfig.topics.feedback, mqttConfig.topics.telemetry], (err) => {
          if (err) this.log('error', `Falha ao assinar tópicos: ${err.message}`);
        });

        if (!settled) { settled = true; resolve(true); }
      });

      this.client.on('message', (topic, payload) => {
        const text = payload.toString();
        if (topic === mqttConfig.topics.telemetry) {
          this.log('received', `Telemetria: ${text}`);
        } else {
          this.log('received', `Feedback: ${text}`);
        }
      });

      this.client.on('error', (err) => {
        this.log('error', `Erro MQTT: ${err.message}`);
        if (!settled) { settled = true; this.setStatus('error'); resolve(false); }
      });

      this.client.on('close', () => {
        if (this.status === 'connected') {
          this.setStatus('disconnected');
          this.log('info', 'MQTT desconectado');
        }
      });

      this.client.on('reconnect', () => this.log('info', 'Reconectando MQTT...'));
    });
  }

  /**
   * Converte o RobotCommand da UI no payload que o MotionController do bridge
   * entende. No bridge: x -> angular, y -> linear, speed em 0..100.
   * Os valores linear/angular da UI já vêm normalizados (-1..1, com o
   * multiplicador de velocidade aplicado), então enviamos speed=100 para não
   * escalar duas vezes — os tetos rígidos do bridge continuam valendo.
   */
  private toBridgePayload(command: RobotCommand): Record<string, unknown> {
    switch (command.cmd) {
      case 'move':
        return {
          type: 'joystick',
          x: command.angular ?? 0,
          y: command.linear ?? 0,
          speed: 100,
          boost: false,
        };
      case 'stop':
        return { type: 'stop', emergency: command.emergency ?? false };
      default:
        // dock, save_map, etc. — repassados para tratamento futuro no bridge.
        return { type: command.cmd, ...command };
    }
  }

  async sendCommand(command: RobotCommand): Promise<RobotResponse | null> {
    const cmd = { ...command, timestamp: Date.now() };

    if (!this.client?.connected) {
      this.log('error', 'Comando ignorado: MQTT não conectado');
      return { status: 'error', message: 'desconectado' };
    }

    const payload = JSON.stringify(this.toBridgePayload(cmd));
    this.log('sent', `Comando: ${payload}`);

    return new Promise<RobotResponse | null>((resolve) => {
      this.client?.publish(mqttConfig.topics.cmd, payload, { qos: 0 }, (err) => {
        if (err) {
          this.log('error', `Erro ao publicar: ${err.message}`);
          resolve({ status: 'error', message: err.message });
        } else {
          resolve({ status: 'ok' });
        }
      });
    });
  }

  disconnect() {
    this.client?.end(true);
    this.client = null;
    this.setStatus('disconnected');
    this.log('info', 'Desconectado');
  }
}

export const robotConnection = new RobotConnectionService();
