import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { robotConnection } from '@/services/RobotConnection';
import { StatusIndicator } from '@/components/StatusIndicator';
import { LogPanel } from '@/components/LogPanel';
import { ConnectionStatus, LogEntry } from '@/types/Robot';
import { Bot, Wifi, Mic, Gamepad2 } from 'lucide-react';

export default function ConnectionScreen() {
  const navigate = useNavigate();
  const [ip, setIp] = useState('192.168.99.2');
  const [port, setPort] = useState('8080');
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [latency, setLatency] = useState<number | null>(null);
  const [lastAttempt, setLastAttempt] = useState<string>('--');
  const [connecting, setConnecting] = useState(false);

  const addLog = useCallback((entry: LogEntry) => {
    setLogs(prev => [...prev.slice(-50), entry]);
  }, []);

  useEffect(() => {
    robotConnection.setCallbacks(addLog, setStatus);
  }, [addLog]);

  const handleConnect = async () => {
    robotConnection.setConfig({ ip, port });
    setConnecting(true);
    await robotConnection.connect();
    setLatency(robotConnection.getLatency());
    setLastAttempt(new Date().toLocaleTimeString('pt-BR'));
    setConnecting(false);
  };

  const handleDisconnect = () => {
    robotConnection.disconnect();
    setLatency(null);
  };

  const isConnected = status === 'connected';

  return (
    <div className="min-h-screen bg-background flex flex-col">
      {/* Header */}
      <header className="border-b border-border px-6 py-4">
        <div className="flex items-center gap-3">
          <Bot className="w-8 h-8 text-accent" />
          <div>
            <h1 className="text-xl font-bold text-gradient-brand">AlphaBot MVP</h1>
            <p className="text-xs font-mono text-muted-foreground">Solar Life Energy · v1.0</p>
          </div>
        </div>
      </header>

      <main className="flex-1 p-6 max-w-lg mx-auto w-full space-y-6">
        {/* Status Card */}
        <div className="rounded-xl border border-border bg-card p-6 space-y-4">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-muted-foreground">Status</span>
            <StatusIndicator status={status} size="md" />
          </div>

          {/* IP Input */}
          <div className="space-y-2">
            <label className="text-sm font-medium text-muted-foreground">IP Robô</label>
            <div className="flex gap-2">
              <input
                type="text"
                value={ip}
                onChange={(e) => setIp(e.target.value)}
                disabled={isConnected}
                className="flex-1 bg-secondary rounded-lg px-4 py-3 font-mono text-sm text-foreground border border-border focus:border-primary focus:ring-1 focus:ring-primary outline-none disabled:opacity-50"
                placeholder="192.168.99.2"
              />
            </div>
          </div>

          {/* Port Input */}
          <div className="space-y-2">
            <label className="text-sm font-medium text-muted-foreground">Porta</label>
            <input
              type="text"
              value={port}
              onChange={(e) => setPort(e.target.value)}
              disabled={isConnected}
              className="w-full bg-secondary rounded-lg px-4 py-3 font-mono text-sm text-foreground border border-border focus:border-primary focus:ring-1 focus:ring-primary outline-none disabled:opacity-50"
              placeholder="8080"
            />
          </div>

          {/* Connect Button */}
          <button
            onClick={isConnected ? handleDisconnect : handleConnect}
            disabled={connecting}
            className={`w-full py-4 rounded-xl font-bold text-sm flex items-center justify-center gap-2 transition-all ${
              isConnected
                ? 'bg-destructive/20 text-destructive border border-destructive/30 hover:bg-destructive/30'
                : 'bg-primary text-primary-foreground glow-primary hover:brightness-110'
            } disabled:opacity-50`}
          >
            <Wifi className="w-5 h-5" />
            {connecting ? 'Conectando...' : isConnected ? 'Desconectar' : 'Conectar Robô'}
          </button>

          {/* Stats */}
          <div className="grid grid-cols-2 gap-4 text-center">
            <div className="bg-secondary/50 rounded-lg p-3">
              <p className="text-xs text-muted-foreground">Última tentativa</p>
              <p className="font-mono text-sm text-foreground">{lastAttempt}</p>
            </div>
            <div className="bg-secondary/50 rounded-lg p-3">
              <p className="text-xs text-muted-foreground">Latência</p>
              <p className="font-mono text-sm text-foreground">{latency !== null ? `${latency} ms` : '-- ms'}</p>
            </div>
          </div>
        </div>

        {/* Navigation */}
        {isConnected && (
          <div className="grid grid-cols-2 gap-4">
            <button
              onClick={() => navigate('/voice')}
              className="bg-card border border-border rounded-xl p-6 flex flex-col items-center gap-3 hover:border-accent transition-colors"
            >
              <Mic className="w-8 h-8 text-accent" />
              <span className="text-sm font-medium">Controle de Voz</span>
            </button>
            <button
              onClick={() => navigate('/joystick')}
              className="bg-card border border-border rounded-xl p-6 flex flex-col items-center gap-3 hover:border-primary transition-colors"
            >
              <Gamepad2 className="w-8 h-8 text-primary" />
              <span className="text-sm font-medium">Joystick Manual</span>
            </button>
          </div>
        )}

        {/* Logs */}
        <div className="space-y-2">
          <h3 className="text-sm font-medium text-muted-foreground">Debug Log</h3>
          <LogPanel logs={logs} maxHeight="180px" />
        </div>
      </main>
    </div>
  );
}
