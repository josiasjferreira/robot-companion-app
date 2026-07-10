#!/usr/bin/env bash
# Teste de movimento do KEN via MQTT — reproduz o teste de 05/07/2026, mas com
# movimento SUSTENTADO: publica {"type":"joystick",...} em ken/motion/cmd a
# ~5 Hz para vencer o watchdog de 400 ms do KenMotionBridge (um publish único
# faz o robô apenas "tentar" andar por ~0,4 s) e envia {"type":"stop"} ao final
# (inclusive em Ctrl+C).
#
# Uso:
#   ./scripts/drive-test.sh frente [DURACAO_S] [VELOCIDADE_0..100]
#   ./scripts/drive-test.sh tras|esquerda|direita [DURACAO_S] [VELOCIDADE]
#   ./scripts/drive-test.sh stop        # só envia o stop
#   ./scripts/drive-test.sh feedback    # assina ken/motion/feedback + telemetria
#
# Exemplos:
#   ./scripts/drive-test.sh frente          # frente por 2 s a 50%
#   ./scripts/drive-test.sh frente 5 30     # frente por 5 s a 30%
#
# Credenciais: lidas do .env da raiz (VITE_MQTT_URL/USERNAME/PASSWORD — as
# mesmas do app web). Podem ser sobrescritas por variáveis de ambiente:
#   MQTT_HOST, MQTT_PORT (default 8883), MQTT_USER, MQTT_PASSWORD, MQTT_CAPATH.
#
# Requer mosquitto-clients (mosquitto_pub/mosquitto_sub).
#
# Lembrete: o bridge força v=0 se houver obstáculo a < 40 cm à frente
# (safeFrontCm). Se o robô "travar" indo para frente, confira o front_cm no
# feedback antes de suspeitar do comando.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

host="${MQTT_HOST:-}"
port="${MQTT_PORT:-8883}"
user="${MQTT_USER:-}"
pass="${MQTT_PASSWORD:-}"
capath="${MQTT_CAPATH:-/etc/ssl/certs}"
topic_cmd="${MQTT_TOPIC_CMD:-ken/motion/cmd}"
topic_feedback="${MQTT_TOPIC_FEEDBACK:-ken/motion/feedback}"
topic_telemetry="${MQTT_TOPIC_TELEMETRY:-ken/sensors/telemetry}"

# Tolerante a chave ausente (grep sem match retornaria 1 e derrubaria o set -e).
env_get() { { grep -E "^$1=" "$ROOT/.env" 2>/dev/null || true; } | tail -1 | cut -d= -f2- | tr -d '\r'; }

if [ -f "$ROOT/.env" ]; then
  if [ -z "$host" ]; then
    url="$(env_get VITE_MQTT_URL)"
    # wss://HOST:8884/mqtt -> HOST (mosquitto usa TLS nativo na 8883)
    [ -n "$url" ] && host="$(printf '%s' "$url" | sed -E 's#^[a-z]+://##; s#[:/].*$##')"
  fi
  [ -z "$user" ] && user="$(env_get VITE_MQTT_USERNAME)"
  [ -z "$pass" ] && pass="$(env_get VITE_MQTT_PASSWORD)"
  t="$(env_get VITE_MQTT_TOPIC_CMD)";       [ -n "$t" ] && topic_cmd="$t"
  t="$(env_get VITE_MQTT_TOPIC_FEEDBACK)";  [ -n "$t" ] && topic_feedback="$t"
  t="$(env_get VITE_MQTT_TOPIC_TELEMETRY)"; [ -n "$t" ] && topic_telemetry="$t"
fi

placeholder=false
case "$host$pass" in *SEU-CLUSTER*|*TROQUE-ME*) placeholder=true ;; esac
if [ -z "$host" ] || [ -z "$user" ] || [ -z "$pass" ] || [ "$placeholder" = true ]; then
  echo "ERRO: credenciais MQTT ausentes ou com placeholder." >&2
  echo "Preencha o .env da raiz (cp .env.example .env) ou exporte MQTT_HOST/MQTT_USER/MQTT_PASSWORD." >&2
  exit 1
fi

command -v mosquitto_pub >/dev/null || {
  echo "ERRO: mosquitto_pub nao encontrado. Instale mosquitto-clients (apt/brew install mosquitto-clients)." >&2
  exit 1
}

pub() {
  mosquitto_pub -h "$host" -p "$port" -u "$user" -P "$pass" \
    --capath "$capath" -t "$topic_cmd" -m "$1"
}

send_stop() { pub '{"type":"stop"}'; echo "stop enviado."; }

cmd="${1:-}"
dur="${2:-2}"
speed="${3:-50}"

case "$cmd" in
  frente)   x=0;  y=1  ;;
  tras)     x=0;  y=-1 ;;
  esquerda) x=1;  y=0  ;;
  direita)  x=-1; y=0  ;;
  stop)     send_stop; exit 0 ;;
  feedback)
    command -v mosquitto_sub >/dev/null || { echo "ERRO: mosquitto_sub nao encontrado." >&2; exit 1; }
    echo "Assinando $topic_feedback e $topic_telemetry em $host (Ctrl+C para sair)..."
    exec mosquitto_sub -h "$host" -p "$port" -u "$user" -P "$pass" \
      --capath "$capath" -t "$topic_feedback" -t "$topic_telemetry" -v
    ;;
  *)
    echo "Uso: $0 frente|tras|esquerda|direita [DURACAO_S] [VELOCIDADE_0..100] | stop | feedback" >&2
    exit 2
    ;;
esac

case "$dur" in ''|*[!0-9]*) echo "ERRO: duracao deve ser inteiro em segundos." >&2; exit 2;; esac
case "$speed" in ''|*[!0-9]*) echo "ERRO: velocidade deve ser inteiro 0..100." >&2; exit 2;; esac
[ "$speed" -gt 100 ] && speed=100

payload="{\"type\":\"joystick\",\"x\":$x,\"y\":$y,\"speed\":$speed}"
echo "Enviando '$cmd' por ${dur}s a ${speed}% -> $topic_cmd @ $host"
echo "Payload: $payload  (republicado a cada 200 ms; Ctrl+C para abortar com stop)"

trap 'send_stop; exit 130' INT TERM

i=0
n=$((dur * 5))
while [ "$i" -lt "$n" ]; do
  pub "$payload"
  sleep 0.2
  i=$((i + 1))
done

trap - INT TERM
send_stop
