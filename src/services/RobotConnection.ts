import { ConnectionStatus, ConnectionConfig, RobotCommand, RobotResponse, LogEntry } from '@/types/Robot';

type LogCallback = (entry: LogEntry) => void;
type StatusCallback = (status: ConnectionStatus) => void;

class RobotConnectionService {
  private config: ConnectionConfig = { ip: '192.168.99.2', port: '8080' };
  private status: ConnectionStatus = 'disconnected';
  private ws: WebSocket | null = null;
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

  private getBaseUrl() {
    return `http://${this.config.ip}:${this.config.port}`;
  }

  async connect(): Promise<boolean> {
    this.lastAttempt = new Date();
    this.setStatus('connecting');
    this.log('info', `Tentando conectar a ${this.config.ip}:${this.config.port}...`);

    // Try HTTP first
    try {
      const start = Date.now();
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5000);

      const response = await fetch(`${this.getBaseUrl()}/api/status`, {
        method: 'GET',
        signal: controller.signal,
      });
      clearTimeout(timeout);

      this.latency = Date.now() - start;

      if (response.ok) {
        this.setStatus('connected');
        this.log('success', `Conectado via HTTP (${this.latency}ms)`);
        return true;
      }
    } catch {
      this.log('info', 'HTTP falhou, tentando WebSocket...');
    }

    // Try WebSocket
    try {
      return await this.connectWebSocket();
    } catch {
      this.log('error', 'WebSocket falhou');
    }

    this.setStatus('error');
    this.log('error', 'Todas as tentativas falharam');
    return false;
  }

  private connectWebSocket(): Promise<boolean> {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error('WebSocket timeout'));
      }, 5000);

      try {
        this.ws = new WebSocket(`ws://${this.config.ip}:${this.config.port}`);

        this.ws.onopen = () => {
          clearTimeout(timeout);
          this.setStatus('connected');
          this.log('success', 'Conectado via WebSocket');
          resolve(true);
        };

        this.ws.onerror = () => {
          clearTimeout(timeout);
          reject(new Error('WebSocket error'));
        };

        this.ws.onclose = () => {
          if (this.status === 'connected') {
            this.setStatus('disconnected');
            this.log('info', 'WebSocket desconectado');
          }
        };

        this.ws.onmessage = (event) => {
          this.log('received', `Resposta: ${event.data}`);
        };
      } catch {
        clearTimeout(timeout);
        reject(new Error('WebSocket init failed'));
      }
    });
  }

  async sendCommand(command: RobotCommand): Promise<RobotResponse | null> {
    const cmd = { ...command, timestamp: Date.now() };
    this.log('sent', `Comando: ${JSON.stringify(cmd)}`);

    // Try WebSocket first if connected
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(cmd));
      return { status: 'ok' };
    }

    // Fallback to HTTP
    try {
      const response = await fetch(`${this.getBaseUrl()}/api/command`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(cmd),
      });
      const data = await response.json();
      this.log('received', `Resposta: ${JSON.stringify(data)}`);
      return data;
    } catch (error) {
      this.log('error', `Erro ao enviar: ${error}`);
      return null;
    }
  }

  disconnect() {
    this.ws?.close();
    this.ws = null;
    this.setStatus('disconnected');
    this.log('info', 'Desconectado');
  }
}

export const robotConnection = new RobotConnectionService();
