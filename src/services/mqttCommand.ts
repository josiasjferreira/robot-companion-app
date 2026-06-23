import { RobotCommand } from '@/types/Robot';

/** Comandos aceitos pela ponte KenMotionBridge em `ken/motion/cmd`. */
export type JoystickCmd = { type: 'joystick'; x: number; y: number; speed: number; boost: boolean };
export type StopCmd = { type: 'stop' };
export type ChassisCmd = {
  type: 'chassis';
  action: 'frente' | 'tras' | 'esquerda' | 'direita' | 'parar';
  speed?: number;
  angle?: number;
  durationMs?: number;
};
export type MqttCommand = JoystickCmd | StopCmd | ChassisCmd;

const clamp = (v: number, lo: number, hi: number) => (v < lo ? lo : v > hi ? hi : v);

/**
 * Traduz o RobotCommand interno das telas para o schema EXATO da ponte.
 * - move(linear,angular) → joystick { x=angular(esq+/dir−), y=linear(frente+) }
 * - stop                 → { type:'stop' }
 * - demais (dock/save_map/...) → null (a ponte ainda não suporta; o chamador deve avisar)
 */
export function toMqttCommand(cmd: RobotCommand): MqttCommand | null {
  switch (cmd.cmd) {
    case 'move': {
      const x = clamp(cmd.angular ?? 0, -1, 1);
      const y = clamp(cmd.linear ?? 0, -1, 1);
      const speed = clamp(cmd.speed ?? 100, 0, 100);
      return { type: 'joystick', x, y, speed, boost: false };
    }
    case 'stop':
      return { type: 'stop' };
    default:
      return null;
  }
}
