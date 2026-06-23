import mqtt, { MqttClient } from 'mqtt';
import {
  ConnectionStatus, ConnectionConfig, RobotCommand, RobotResponse, LogEntry,
  MotionFeedback, SensorTelemetry, TelemetryState,
} from '@/types/Robot';
import { mqttConfig, isMqttConfigured } from '@/config/mqtt';
import { toMqttCommand } from '@/services/mqttCommand';

type LogCallback = (entry: LogEntry) => void;
type StatusCallback = (status: ConnectionStatus) => void;
type TelemetryCallback = (state: TelemetryState) => void;

/**
 * Serviço de conexão com o robô via MQTT (HiveMQ Cloud, WSS).
 *
 * O navegador NÃO fala com o robô diretamente: publica comandos em `ken/motion/cmd`
 * e assina `ken/motion/feedback` + `ken/sensors/telemetry`. A tradução para o schema
 * da ponte (joystick/stop) é feita em [toMqttCommand].
 *
 * A interface pública (setCallbacks/connect/disconnect/sendCommand/getStatus/getLatency)
 * é mantida para compatibilidade com as telas existentes.
 */
class RobotConnectionService {
  private config: ConnectionConfig = { ip: '', port: '8884' };
  private status: ConnectionStatus = 'disconnected';
  private client: MqttClient | null = null;
  private onLog: LogCallback | null = null;
  private onStatus: StatusCallback | null = null;
  private onTelemetry: TelemetryCallback | null = null;
  private latency: number | null = null;
  private lastAttempt: Date | null = null;
  private telemetry: TelemetryState = { feedback: null, sensors: null, lastTs: 0 };

  setCallbacks(onLog: LogCallback, onStatus: StatusCallback, onTelemetry?: TelemetryCallback) {
    this.onLog = onLog;
    this.onStatus = onStatus;
    this.onTelemetry = onTelemetry ?? null;
  }

  getConfig() { return this.config; }
  getLatency() { return this.latency; }
  getLastAttempt() { return this.lastAttempt; }
  getStatus() { return this.status; }
  getTelemetry() { return this.telemetry; }

  setConfig(config: ConnectionConfig) {
    // Mantido por compatibilidade com a tela de conexão; o broker vem das env VITE_MQTT_*.
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

    if (!isMqttConfigured()) {
      this.setStatus('error');
      this.log('error', 'Credenciais do broker ausentes (defina VITE_MQTT_URL, VITE_MQTT_USER, VITE_MQTT_PASS).');
      return false;
    }

    this.setStatus('connecting');
    this.log('info', `Conectando ao broker MQTT ${mqttConfig.url}...`);

    return new Promise<boolean>((resolve) => {
      let settled = false;
      try {
        const client = mqtt.connect(mqttConfig.url, {
          username: mqttConfig.username,
          password: mqttConfig.password,
          protocolVersion: 4,
          clean: true,
          reconnectPeriod: 2000,
          connectTimeout: 10000,
          clientId: `web-${Math.random().toString(16).slice(2, 10)}`,
        });
        this.client = client;

        client.on('connect', () => {
          this.setStatus('connected');
          this.log('success', 'Conectado ao broker MQTT');
          client.subscribe([mqttConfig.topicFeedback, mqttConfig.topicTelemetry], (err) => {
            if (err) this.log('error', `Falha ao assinar tópicos: ${err.message}`);
            else this.log('info', `Assinado: ${mqttConfig.topicFeedback}, ${mqttConfig.topicTelemetry}`);
          });
          if (!settled) { settled = true; resolve(true); }
        });

        client.on('message', (topic, payload) => this.handleMessage(topic, payload.toString()));

        client.on('error', (err) => {
          this.log('error', `Erro MQTT: ${err.message}`);
          this.setStatus('error');
          if (!settled) { settled = true; resolve(false); }
        });

        client.on('close', () => {
          if (this.status === 'connected') {
            this.setStatus('disconnected');
            this.log('info', 'Conexão MQTT encerrada');
          }
        });

        client.on('offline', () => {
          this.setStatus('disconnected');
          this.log('info', 'Broker offline (sem rede?)');
        });
      } catch (error) {
        this.setStatus('error');
        this.log('error', `Falha ao iniciar MQTT: ${error}`);
        if (!settled) { settled = true; resolve(false); }
      }
    });
  }

  private handleMessage(topic: string, body: string) {
    let data: unknown;
    try { data = JSON.parse(body); } catch { this.log('error', `JSON inválido em ${topic}`); return; }

    if (topic === mqttConfig.topicFeedback) {
      const fb = data as MotionFeedback;
      this.telemetry = { ...this.telemetry, feedback: fb, lastTs: fb.ts ?? Date.now() };
      if (typeof fb.ts === 'number') this.latency = Math.max(0, Date.now() - fb.ts);
      this.onTelemetry?.(this.telemetry);
    } else if (topic === mqttConfig.topicTelemetry) {
      const t = data as SensorTelemetry;
      this.telemetry = { ...this.telemetry, sensors: t, lastTs: Math.max(this.telemetry.lastTs, t.ts ?? Date.now()) };
      this.onTelemetry?.(this.telemetry);
    }
  }

  async sendCommand(command: RobotCommand): Promise<RobotResponse | null> {
    const client = this.client;
    if (!client || !client.connected) {
      this.log('error', 'Comando ignorado — broker MQTT desconectado');
      return null;
    }
    const mqttCmd = toMqttCommand(command);
    if (!mqttCmd) {
      this.log('error', `Comando '${command.cmd}' não suportado pela ponte`);
      return { status: 'error', message: 'unsupported' };
    }
    const payload = JSON.stringify(mqttCmd);
    return new Promise<RobotResponse | null>((resolve) => {
      client.publish(mqttConfig.topicCmd, payload, { qos: 0 }, (err) => {
        if (err) {
          this.log('error', `Falha ao publicar: ${err.message}`);
          resolve(null);
        } else {
          this.log('sent', payload);
          resolve({ status: 'ok' });
        }
      });
    });
  }

  disconnect() {
    this.client?.end(true);
    this.client = null;
    this.setStatus('disconnected');
    this.log('info', 'Desconectado do broker');
  }
}

export const robotConnection = new RobotConnectionService();
