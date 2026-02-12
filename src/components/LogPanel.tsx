import { LogEntry } from '@/types/Robot';
import { useRef, useEffect } from 'react';

interface LogPanelProps {
  logs: LogEntry[];
  maxHeight?: string;
}

const typeIcons: Record<LogEntry['type'], string> = {
  info: 'ℹ️',
  sent: '📤',
  received: '📥',
  voice: '🎤',
  error: '❌',
  success: '✅',
};

const typeColors: Record<LogEntry['type'], string> = {
  info: 'text-muted-foreground',
  sent: 'text-primary',
  received: 'text-success',
  voice: 'text-accent',
  error: 'text-destructive',
  success: 'text-success',
};

export function LogPanel({ logs, maxHeight = '200px' }: LogPanelProps) {
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo(0, scrollRef.current.scrollHeight);
  }, [logs]);

  return (
    <div
      ref={scrollRef}
      className="bg-background/80 rounded-lg border border-border p-3 overflow-y-auto font-mono text-xs space-y-1"
      style={{ maxHeight }}
    >
      {logs.length === 0 && (
        <span className="text-muted-foreground">Aguardando logs...</span>
      )}
      {logs.map((log, i) => (
        <div key={i} className={`flex gap-2 ${typeColors[log.type]}`}>
          <span className="text-muted-foreground shrink-0">
            [{log.timestamp.toLocaleTimeString('pt-BR')}]
          </span>
          <span>{typeIcons[log.type]}</span>
          <span className="break-all">{log.message}</span>
        </div>
      ))}
    </div>
  );
}
