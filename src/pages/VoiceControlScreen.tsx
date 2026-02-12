import { useState, useCallback, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { robotConnection } from '@/services/RobotConnection';
import { LogPanel } from '@/components/LogPanel';
import { StatusIndicator } from '@/components/StatusIndicator';
import { VOICE_COMMANDS, LogEntry } from '@/types/Robot';
import { ArrowLeft, Mic, MicOff } from 'lucide-react';

export default function VoiceControlScreen() {
  const navigate = useNavigate();
  const [listening, setListening] = useState(false);
  const [lastCommand, setLastCommand] = useState<string | null>(null);
  const [transcript, setTranscript] = useState('');
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [supported, setSupported] = useState(true);
  const recognitionRef = useRef<any>(null);

  const addLog = useCallback((entry: LogEntry) => {
    setLogs(prev => [...prev.slice(-50), entry]);
  }, []);

  useEffect(() => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setSupported(false);
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.lang = 'pt-BR';
    recognition.continuous = false;
    recognition.interimResults = true;

    recognition.onresult = (event: any) => {
      const result = event.results[event.results.length - 1];
      const text = result[0].transcript.toLowerCase().trim();
      setTranscript(text);

      if (result.isFinal) {
        addLog({ timestamp: new Date(), type: 'voice', message: `Reconhecido: "${text}"` });
        processCommand(text);
      }
    };

    recognition.onend = () => setListening(false);
    recognition.onerror = (e: any) => {
      addLog({ timestamp: new Date(), type: 'error', message: `Erro de voz: ${e.error}` });
      setListening(false);
    };

    recognitionRef.current = recognition;
  }, [addLog]);

  const processCommand = useCallback(async (text: string) => {
    const match = VOICE_COMMANDS.find(vc => text.includes(vc.phrase));
    if (match) {
      setLastCommand(match.description);
      addLog({ timestamp: new Date(), type: 'sent', message: `Comando: ${match.description}` });
      const result = await robotConnection.sendCommand({ ...match.command, timestamp: Date.now() });
      if (result) {
        addLog({ timestamp: new Date(), type: 'success', message: `✅ ${match.description} enviado` });
      }
    } else {
      addLog({ timestamp: new Date(), type: 'error', message: `Comando não reconhecido: "${text}"` });
    }
  }, [addLog]);

  const toggleListening = () => {
    if (listening) {
      recognitionRef.current?.stop();
    } else {
      setTranscript('');
      recognitionRef.current?.start();
      setListening(true);
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
          <Mic className="w-5 h-5 text-accent" />
          <span className="font-bold text-sm">Controle de Voz</span>
        </div>
        <StatusIndicator status={status} size="sm" />
      </header>

      <main className="flex-1 p-6 max-w-lg mx-auto w-full flex flex-col items-center gap-6">
        {!supported ? (
          <div className="bg-destructive/10 border border-destructive/30 rounded-xl p-6 text-center">
            <p className="text-destructive text-sm">Reconhecimento de voz não suportado neste navegador. Use Chrome ou Edge.</p>
          </div>
        ) : (
          <>
            {/* Mic Button */}
            <button
              onClick={toggleListening}
              className={`w-32 h-32 rounded-full flex items-center justify-center transition-all ${
                listening
                  ? 'bg-destructive/20 border-2 border-destructive glow-destructive'
                  : 'bg-accent/20 border-2 border-accent glow-accent hover:bg-accent/30'
              }`}
            >
              {listening ? (
                <MicOff className="w-12 h-12 text-destructive" />
              ) : (
                <Mic className="w-12 h-12 text-accent" />
              )}
            </button>

            <p className="text-sm text-muted-foreground">
              {listening ? 'Ouvindo...' : 'Pressione para falar'}
            </p>

            {/* Sound wave animation */}
            {listening && (
              <div className="flex items-end gap-1 h-8">
                {[...Array(7)].map((_, i) => (
                  <div
                    key={i}
                    className="w-1 bg-accent rounded-full animate-sound-wave"
                    style={{
                      height: '100%',
                      animationDelay: `${i * 0.1}s`,
                    }}
                  />
                ))}
              </div>
            )}

            {transcript && (
              <div className="bg-card border border-border rounded-xl p-4 w-full text-center">
                <p className="text-xs text-muted-foreground mb-1">Transcrevendo</p>
                <p className="font-mono text-sm text-foreground">"{transcript}"</p>
              </div>
            )}

            {lastCommand && (
              <div className="bg-success/10 border border-success/30 rounded-xl p-4 w-full text-center">
                <p className="text-xs text-muted-foreground mb-1">Último comando</p>
                <p className="font-medium text-success">{lastCommand}</p>
              </div>
            )}
          </>
        )}

        {/* Commands list */}
        <div className="w-full bg-card border border-border rounded-xl p-4 space-y-2">
          <h3 className="text-sm font-medium text-muted-foreground mb-3">Comandos reconhecidos</h3>
          {VOICE_COMMANDS.map((vc) => (
            <button
              key={vc.phrase}
              onClick={() => processCommand(vc.phrase)}
              className="w-full text-left px-3 py-2 rounded-lg bg-secondary/50 hover:bg-secondary text-sm font-mono text-foreground transition-colors"
            >
              • "{vc.description}"
            </button>
          ))}
        </div>

        <div className="w-full space-y-2">
          <h3 className="text-sm font-medium text-muted-foreground">Log</h3>
          <LogPanel logs={logs} maxHeight="150px" />
        </div>
      </main>
    </div>
  );
}
