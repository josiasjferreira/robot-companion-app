export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

export type ConnectionProtocol = 'http' | 'websocket' | 'tcp';

export interface RobotCommand {
  cmd: string;
  linear?: number;
  angular?: number;
  action?: string;
  speed?: number;
  name?: string;
  emergency?: boolean;
  timestamp: number;
}

export interface RobotResponse {
  status: 'ok' | 'error';
  message?: string;
  position?: string;
}

export interface ConnectionConfig {
  ip: string;
  port: string;
}

export interface LogEntry {
  timestamp: Date;
  type: 'info' | 'sent' | 'received' | 'voice' | 'error' | 'success';
  message: string;
}

export interface VoiceCommand {
  phrase: string;
  command: RobotCommand;
  description: string;
}

export const VOICE_COMMANDS: VoiceCommand[] = [
  { phrase: 'andar para frente', description: 'Andar para frente', command: { cmd: 'move', linear: 0.5, angular: 0, timestamp: 0 } },
  { phrase: 'andar para trás', description: 'Andar para trás', command: { cmd: 'move', linear: -0.5, angular: 0, timestamp: 0 } },
  { phrase: 'virar à esquerda', description: 'Virar à esquerda', command: { cmd: 'move', linear: 0, angular: 0.5, timestamp: 0 } },
  { phrase: 'virar à direita', description: 'Virar à direita', command: { cmd: 'move', linear: 0, angular: -0.5, timestamp: 0 } },
  { phrase: 'parar', description: 'Parar', command: { cmd: 'stop', timestamp: 0 } },
  { phrase: 'salvar mapa', description: 'Salvar mapa', command: { cmd: 'save_map', name: 'manual_save', timestamp: 0 } },
  { phrase: 'voltar para base', description: 'Voltar para base', command: { cmd: 'dock', timestamp: 0 } },
];
