import { useState, useCallback, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { robotConnection } from '@/services/RobotConnection';
import { VirtualJoystick } from '@/components/VirtualJoystick';
import { LogPanel } from '@/components/LogPanel';
import { StatusIndicator } from '@/components/StatusIndicator';
import { LogEntry } from '@/types/Robot';
import { ArrowLeft, Gamepad2, ChevronUp, ChevronDown, ChevronLeft, ChevronRight, OctagonX } from 'lucide-react';

export default function JoystickScreen() {
  const navigate = useNavigate();
  const [speed, setSpeed] = useState(80);
  const [linear, setLinear] = useState(0);
  const [angular, setAngular] = useState(0);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);
  const lastCmd = useRef({ linear: 0, angular: 0 });

  const addLog = useCallback((entry: LogEntry) => {
    setLogs(prev => [...prev.slice(-50), entry]);
  }, []);

  const speedMultiplier = speed / 100;

  const sendMovement = useCallback((lin: number, ang: number) => {
    lastCmd.current = { linear: lin, angular: ang };
    setLinear(lin);
    setAngular(ang);
  }, []);

  // Continuous send loop
  useEffect(() => {
    intervalRef.current = setInterval(() => {
      const { linear: l, angular: a } = lastCmd.current;
      if (l !== 0 || a !== 0) {
        robotConnection.sendCommand({ cmd: 'move', linear: l, angular: a, timestamp: Date.now() });
      }
    }, 100);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, []);

  const handleStop = useCallback(() => {
    lastCmd.current = { linear: 0, angular: 0 };
    setLinear(0);
    setAngular(0);
    robotConnection.sendCommand({ cmd: 'stop', timestamp: Date.now() });
  }, []);

  const handleEmergencyStop = () => {
    handleStop();
    robotConnection.sendCommand({ cmd: 'stop', emergency: true, timestamp: Date.now() });
    addLog({ timestamp: new Date(), type: 'error', message: '🛑 PARADA DE EMERGÊNCIA' });
  };

  const handleDPad = (direction: string) => {
    const s = speedMultiplier * 0.5;
    const cmds: Record<string, { linear: number; angular: number }> = {
      up: { linear: s, angular: 0 },
      down: { linear: -s, angular: 0 },
      left: { linear: 0, angular: s },
      right: { linear: 0, angular: -s },
    };
    const cmd = cmds[direction];
    if (cmd) {
      robotConnection.sendCommand({ cmd: 'move', ...cmd, timestamp: Date.now() });
      addLog({ timestamp: new Date(), type: 'sent', message: `D-Pad: ${direction}` });
    }
  };

  const status = robotConnection.getStatus();

  return (
    <div className="min-h-screen bg-background flex flex-col">
      <header className="border-b border-border px-6 py-4 flex items-center justify-between">
        <button onClick={() => navigate('/')} className="flex items-center gap-2 text-muted-foreground hover:text-foreground transition-colors">
          <ArrowLeft className="w-5 h-5" />
          <span className="text-sm">Voltar</span>
        </button>
        <div className="flex items-center gap-2">
          <Gamepad2 className="w-5 h-5 text-primary" />
          <span className="font-bold text-sm">Joystick Manual</span>
        </div>
        <StatusIndicator status={status} size="sm" />
      </header>

      <main className="flex-1 p-6 max-w-lg mx-auto w-full flex flex-col items-center gap-6">
        {/* D-Pad */}
        <div className="grid grid-cols-3 gap-2 w-48">
          <div />
          <button onMouseDown={() => handleDPad('up')} onMouseUp={handleStop} onTouchStart={() => handleDPad('up')} onTouchEnd={handleStop}
            className="bg-secondary border border-border rounded-xl p-4 flex items-center justify-center hover:bg-primary/20 active:bg-primary/30 transition-colors">
            <ChevronUp className="w-6 h-6 text-primary" />
          </button>
          <div />
          <button onMouseDown={() => handleDPad('left')} onMouseUp={handleStop} onTouchStart={() => handleDPad('left')} onTouchEnd={handleStop}
            className="bg-secondary border border-border rounded-xl p-4 flex items-center justify-center hover:bg-primary/20 active:bg-primary/30 transition-colors">
            <ChevronLeft className="w-6 h-6 text-primary" />
          </button>
          <div className="bg-secondary/50 border border-border rounded-xl flex items-center justify-center">
            <div className="w-3 h-3 rounded-full bg-primary/40" />
          </div>
          <button onMouseDown={() => handleDPad('right')} onMouseUp={handleStop} onTouchStart={() => handleDPad('right')} onTouchEnd={handleStop}
            className="bg-secondary border border-border rounded-xl p-4 flex items-center justify-center hover:bg-primary/20 active:bg-primary/30 transition-colors">
            <ChevronRight className="w-6 h-6 text-primary" />
          </button>
          <div />
          <button onMouseDown={() => handleDPad('down')} onMouseUp={handleStop} onTouchStart={() => handleDPad('down')} onTouchEnd={handleStop}
            className="bg-secondary border border-border rounded-xl p-4 flex items-center justify-center hover:bg-primary/20 active:bg-primary/30 transition-colors">
            <ChevronDown className="w-6 h-6 text-primary" />
          </button>
          <div />
        </div>

        {/* Joystick */}
        <VirtualJoystick
          size={220}
          onMove={(output) => sendMovement(output.linear, output.angular)}
          onStop={handleStop}
          speedMultiplier={speedMultiplier}
        />

        {/* Speed Slider */}
        <div className="w-full space-y-2">
          <div className="flex justify-between text-sm">
            <span className="text-muted-foreground">Velocidade</span>
            <span className="font-mono text-accent">{speed}%</span>
          </div>
          <input
            type="range"
            min={10}
            max={100}
            value={speed}
            onChange={(e) => setSpeed(Number(e.target.value))}
            className="w-full accent-accent h-2 bg-secondary rounded-full appearance-none cursor-pointer"
          />
        </div>

        {/* Telemetry */}
        <div className="grid grid-cols-2 gap-4 w-full">
          <div className="bg-card border border-border rounded-xl p-4 text-center">
            <p className="text-xs text-muted-foreground">Linear</p>
            <p className="font-mono text-lg text-foreground">{linear.toFixed(2)} <span className="text-xs text-muted-foreground">m/s</span></p>
          </div>
          <div className="bg-card border border-border rounded-xl p-4 text-center">
            <p className="text-xs text-muted-foreground">Angular</p>
            <p className="font-mono text-lg text-foreground">{angular.toFixed(2)} <span className="text-xs text-muted-foreground">rad/s</span></p>
          </div>
        </div>

        {/* Emergency Stop */}
        <button
          onClick={handleEmergencyStop}
          className="w-full py-5 rounded-xl bg-destructive/20 border-2 border-destructive text-destructive font-bold text-lg flex items-center justify-center gap-3 hover:bg-destructive/30 active:bg-destructive/40 transition-colors glow-destructive"
        >
          <OctagonX className="w-7 h-7" />
          PARADA DE EMERGÊNCIA
        </button>

        <div className="w-full space-y-2">
          <h3 className="text-sm font-medium text-muted-foreground">Log</h3>
          <LogPanel logs={logs} maxHeight="120px" />
        </div>
      </main>
    </div>
  );
}
