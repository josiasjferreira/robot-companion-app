#!/usr/bin/env bash
# Build de ponta a ponta (Linux/macOS): UI (Capacitor) + KenMotionBridge (APK).
# Uso:  ./scripts/build-all.sh          # apenas builda
#       INSTALL=1 ./scripts/build-all.sh # builda e instala a ponte via adb
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== 1/4 Verificacoes =="
if [ ! -f .env ]; then
  echo "ERRO: .env nao encontrado. Rode: cp .env.example .env e preencha as credenciais MQTT."
  exit 1
fi
if grep -q "TROQUE-ME\|SEU-CLUSTER" .env; then
  echo "ERRO: .env ainda contem placeholders (TROQUE-ME/SEU-CLUSTER). Preencha antes de buildar."
  exit 1
fi
if ! ls KenMotionBridge/app/libs/*.aar >/dev/null 2>&1; then
  echo "AVISO: nenhum RobotSDK .aar em KenMotionBridge/app/libs/."
  echo "       O APK da ponte compila mesmo assim, mas o robo NAO se move sem ele."
  echo "       Veja KenMotionBridge/app/libs/PLACE_AAR_HERE.md para obter o arquivo."
fi

echo "== 2/4 UI (web -> Capacitor/Android) =="
[ -d node_modules ] || npm install
npm run build
if [ ! -d android ]; then
  npm install @capacitor/android
  npx cap add android
fi
npx cap sync android
echo "UI pronta. Para gerar/instalar o APK da UI: npx cap open android (Android Studio)."

echo "== 3/4 KenMotionBridge (APK da ponte) =="
(
  cd KenMotionBridge
  chmod +x gradlew
  ./gradlew --no-daemon assembleDebug
)

APK="KenMotionBridge/app/build/outputs/apk/debug/app-debug.apk"
echo "== 4/4 Saidas =="
echo "Ponte: $APK"
if [ "${INSTALL:-0}" = "1" ]; then
  echo "Instalando a ponte via adb..."
  adb install -r "$APK"
  adb shell am start -n br.com.onlife.kenmotionbridge/.MainActivity
fi
echo "Concluido."
