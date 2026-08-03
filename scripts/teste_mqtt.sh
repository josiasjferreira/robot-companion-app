#!/usr/bin/env bash
#
# teste_mqtt.sh — Console de teste do KenMotionBridge por MQTT
# ------------------------------------------------------------
# Publica comandos de movimento em ken/motion/cmd e escuta o heartbeat/diag
# em ken/motion/feedback, para você validar num só lugar se o robô respondeu
# e se o LIDAR voltou a vida (comando "l" -> lidar_test).
#
# Roda no SEU PC/tablet (na mesma internet do robô). Do sandbox do Claude NÃO
# dá para conectar no broker — quem conecta é esta máquina.
#
# Requisitos: mosquitto-clients  (mosquitto_pub / mosquitto_sub)
#   Ubuntu/Debian : sudo apt install mosquitto-clients
#   macOS (brew)  : brew install mosquitto
#   Windows       : use o WSL, ou instale o Mosquitto e rode este .sh no Git Bash
#
# Uso:
#   ./teste_mqtt.sh                      # pergunta host/senha interativamente
#   HOST=xxx.hivemq.cloud ./teste_mqtt.sh
#   HOST=... USER=ken-robot PASS=... ./teste_mqtt.sh
#
# Segurança: a senha nunca fica no script. Passe por variável de ambiente ou
# digite quando pedido (entrada oculta).
#
set -euo pipefail

# ---------- configuração (broker HiveMQ Cloud do projeto) ----------
HOST="${HOST:-}"
PORT="${PORT:-8883}"          # 8883 = MQTT sobre TLS (HiveMQ Cloud)
USER="${USER:-ken-robot}"     # mesmo usuário do bridge_config.json
PASS="${PASS:-}"
CMD_TOPIC="${CMD_TOPIC:-ken/motion/cmd}"
FB_TOPIC="${FB_TOPIC:-ken/motion/feedback}"

# ---------- checagem de dependência ----------
if ! command -v mosquitto_pub >/dev/null 2>&1; then
  echo "ERRO: mosquitto_pub não encontrado. Instale o pacote 'mosquitto-clients'." >&2
  echo "  Ubuntu/Debian: sudo apt install mosquitto-clients" >&2
  echo "  macOS        : brew install mosquitto" >&2
  exit 1
fi

# ---------- coleta de credenciais ----------
if [ -z "$HOST" ]; then
  read -r -p "Host do broker HiveMQ (ex.: abc123.s1.eu.hivemq.cloud): " HOST
fi
if [ -z "$PASS" ]; then
  read -r -s -p "Senha do usuário '$USER': " PASS; echo
fi

# HiveMQ Cloud exige TLS. --capath usa a store de CAs do sistema.
TLS_ARGS=(-p "$PORT" --capath /etc/ssl/certs)
AUTH_ARGS=(-h "$HOST" -u "$USER" -P "$PASS" "${TLS_ARGS[@]}")

pub() {
  # pub '<json>'  -> publica em ken/motion/cmd
  local payload="$1"
  echo ">> $CMD_TOPIC  $payload"
  mosquitto_pub "${AUTH_ARGS[@]}" -t "$CMD_TOPIC" -m "$payload"
}

# ---------- teleop contínuo (joystick) ----------
# O watchdog para o robô se ficar >400 ms sem comando, então reemitimos a ~5 Hz.
joystick_hold() {
  local x="$1" y="$2" speed="$3" secs="${4:-2}"
  local payload="{\"type\":\"joystick\",\"x\":$x,\"y\":$y,\"speed\":$speed}"
  echo ">> teleop ${secs}s  $payload   (reemitindo a 5 Hz)"
  local end=$(( $(date +%s) + secs ))
  while [ "$(date +%s)" -lt "$end" ]; do
    mosquitto_pub "${AUTH_ARGS[@]}" -t "$CMD_TOPIC" -m "$payload"
    sleep 0.2
  done
  pub '{"type":"stop"}'
}

# ---------- listener de feedback em background ----------
start_listener() {
  echo "== escutando $FB_TOPIC (heartbeat 1 Hz + diag) =="
  mosquitto_sub "${AUTH_ARGS[@]}" -t "$FB_TOPIC" -v &
  LISTENER_PID=$!
}
stop_listener() { [ -n "${LISTENER_PID:-}" ] && kill "$LISTENER_PID" 2>/dev/null || true; }
trap 'stop_listener' EXIT

menu() {
  cat <<'EOF'

============ KenMotionBridge — console MQTT ============
  MOVIMENTO
    f  frente 0,5 m COM sensor  (OA, para em obstáculo)   -> precisa do LIDAR vivo
    t  frente 0,5 m SEM sensor  (TRACK bruto, odometria)
    b  ré 0,4 m                 (funciona sem LIDAR)
    e  girar esquerda 45°       (funciona sem LIDAR)
    d  girar direita 45°        (funciona sem LIDAR)
    j  teleop frente 2 s        (joystick contínuo, 5 Hz)
    s  PARAR (stop)

  SENSOR / DIAGNÓSTICO
    l  testar LIDAR (lidar_test) -> veredito no feedback
    p  frente unificada (escada automática nav->OA / LIDAR->TRACK)
    g  ligar Modo Recepção (greeter 80 cm)
    v  falar boas-vindas no alto-falante (speak)

  OUTROS
    r  publicar JSON cru (você digita o payload)
    q  sair
=======================================================
EOF
}

start_listener
menu
while true; do
  read -r -p "cmd> " choice || break
  case "$choice" in
    f) pub '{"type":"forward","front_sensor":true,"dist":0.5}' ;;
    t) pub '{"type":"forward","front_sensor":false,"dist":0.5}' ;;
    b) pub '{"type":"chassis","action":"tras","dist":0.4}' ;;
    e) pub '{"type":"chassis","action":"esquerda","angle":45}' ;;
    d) pub '{"type":"chassis","action":"direita","angle":45}' ;;
    j) joystick_hold 0 1 40 2 ;;
    s) pub '{"type":"stop"}' ;;
    l) pub '{"type":"lidar_test"}' ;;
    p) pub '{"type":"forward_unified","dist":0.5}' ;;
    g) pub '{"type":"greeter","on":true,"threshold_cm":80}' ;;
    v) pub '{"type":"speak"}' ;;
    r) read -r -p "JSON> " raw; pub "$raw" ;;
    q) break ;;
    "") menu ;;
    *) echo "opção inválida"; menu ;;
  esac
done
