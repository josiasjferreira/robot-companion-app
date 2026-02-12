import { useRef, useState, useCallback, useEffect } from 'react';

interface JoystickOutput {
  linear: number;
  angular: number;
}

interface VirtualJoystickProps {
  size?: number;
  onMove: (output: JoystickOutput) => void;
  onStop: () => void;
  speedMultiplier: number;
}

export function VirtualJoystick({ size = 200, onMove, onStop, speedMultiplier }: VirtualJoystickProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const maxRadius = size / 2 - 30;

  const getRelativePosition = useCallback((clientX: number, clientY: number) => {
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;
    let dx = clientX - centerX;
    let dy = clientY - centerY;
    const dist = Math.sqrt(dx * dx + dy * dy);
    if (dist > maxRadius) {
      dx = (dx / dist) * maxRadius;
      dy = (dy / dist) * maxRadius;
    }
    return { x: dx, y: dy };
  }, [maxRadius]);

  const handleMove = useCallback((clientX: number, clientY: number) => {
    const pos = getRelativePosition(clientX, clientY);
    setPosition(pos);
    const linear = -(pos.y / maxRadius) * speedMultiplier;
    const angular = -(pos.x / maxRadius) * speedMultiplier;
    onMove({ linear: Math.round(linear * 100) / 100, angular: Math.round(angular * 100) / 100 });
  }, [getRelativePosition, maxRadius, onMove, speedMultiplier]);

  const handleStart = useCallback((clientX: number, clientY: number) => {
    setDragging(true);
    handleMove(clientX, clientY);
  }, [handleMove]);

  const handleEnd = useCallback(() => {
    setDragging(false);
    setPosition({ x: 0, y: 0 });
    onStop();
  }, [onStop]);

  useEffect(() => {
    if (!dragging) return;

    const onMouseMove = (e: MouseEvent) => handleMove(e.clientX, e.clientY);
    const onTouchMove = (e: TouchEvent) => {
      e.preventDefault();
      handleMove(e.touches[0].clientX, e.touches[0].clientY);
    };
    const onUp = () => handleEnd();

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onUp);
    window.addEventListener('touchmove', onTouchMove, { passive: false });
    window.addEventListener('touchend', onUp);

    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('touchmove', onTouchMove);
      window.removeEventListener('touchend', onUp);
    };
  }, [dragging, handleMove, handleEnd]);

  return (
    <div
      ref={containerRef}
      className="relative rounded-full border-2 border-border bg-secondary/50 select-none touch-none"
      style={{ width: size, height: size }}
      onMouseDown={(e) => handleStart(e.clientX, e.clientY)}
      onTouchStart={(e) => {
        e.preventDefault();
        handleStart(e.touches[0].clientX, e.touches[0].clientY);
      }}
    >
      {/* Crosshair lines */}
      <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
        <div className="absolute w-full h-px bg-border/40" />
        <div className="absolute h-full w-px bg-border/40" />
      </div>

      {/* Thumb */}
      <div
        className={`absolute w-16 h-16 rounded-full border-2 transition-shadow ${dragging ? 'border-accent bg-accent/30 glow-accent' : 'border-primary bg-primary/20 glow-primary'}`}
        style={{
          left: `calc(50% + ${position.x}px - 2rem)`,
          top: `calc(50% + ${position.y}px - 2rem)`,
          transition: dragging ? 'none' : 'all 0.2s ease-out',
        }}
      >
        <div className="w-full h-full flex items-center justify-center">
          <div className={`w-4 h-4 rounded-full ${dragging ? 'bg-accent' : 'bg-primary'}`} />
        </div>
      </div>
    </div>
  );
}
