import { describe, it, expect } from 'vitest';
import { toMqttCommand } from '@/services/mqttCommand';

describe('toMqttCommand', () => {
  it('traduz move → joystick com x=angular, y=linear', () => {
    const out = toMqttCommand({ cmd: 'move', linear: 0.5, angular: -0.3, timestamp: 0 });
    expect(out).toEqual({ type: 'joystick', x: -0.3, y: 0.5, speed: 100, boost: false });
  });

  it('limita x/y ao intervalo [-1,1] e speed a [0,100]', () => {
    const out = toMqttCommand({ cmd: 'move', linear: 5, angular: -9, speed: 250, timestamp: 0 });
    expect(out).toEqual({ type: 'joystick', x: -1, y: 1, speed: 100, boost: false });
  });

  it('traduz stop → {type:stop}', () => {
    expect(toMqttCommand({ cmd: 'stop', timestamp: 0 })).toEqual({ type: 'stop' });
  });

  it('retorna null para comandos não suportados pela ponte', () => {
    expect(toMqttCommand({ cmd: 'dock', timestamp: 0 })).toBeNull();
    expect(toMqttCommand({ cmd: 'save_map', name: 'x', timestamp: 0 })).toBeNull();
  });
});
