import { ConnectionStatus } from '@/types/Robot';

interface StatusIndicatorProps {
  status: ConnectionStatus;
  size?: 'sm' | 'md' | 'lg';
}

const statusConfig: Record<ConnectionStatus, { label: string; colorClass: string; glowClass: string }> = {
  disconnected: { label: 'Desconectado', colorClass: 'bg-destructive', glowClass: 'glow-destructive' },
  connecting: { label: 'Conectando...', colorClass: 'bg-warning', glowClass: 'glow-accent' },
  connected: { label: 'Conectado', colorClass: 'bg-success', glowClass: 'glow-success' },
  error: { label: 'Erro', colorClass: 'bg-destructive', glowClass: 'glow-destructive' },
};

const sizeMap = { sm: 'w-2.5 h-2.5', md: 'w-3.5 h-3.5', lg: 'w-5 h-5' };
const textSize = { sm: 'text-xs', md: 'text-sm', lg: 'text-base' };

export function StatusIndicator({ status, size = 'md' }: StatusIndicatorProps) {
  const config = statusConfig[status];

  return (
    <div className="flex items-center gap-2">
      <div className={`${sizeMap[size]} rounded-full ${config.colorClass} ${config.glowClass} ${status === 'connecting' ? 'animate-pulse' : 'animate-pulse-dot'}`} />
      <span className={`${textSize[size]} font-mono text-muted-foreground`}>{config.label}</span>
    </div>
  );
}
