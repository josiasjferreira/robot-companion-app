# STATUS — Baseline estável (snapshot)

> Snapshot do que está **confiável** para iniciar a próxima fase. Atualizado a partir da
> branch `claude/confident-ritchie-0n0dkc` (PR #1). Última CI verde: **run #24** (`d29a311`).

## Visão geral
Monorepo com dois subprojetos que conversam por **MQTT (HiveMQ Cloud)**:
- **App web** (Vite + React + shadcn/ui + TypeScript, empacotável via Capacitor) — controle/telemetria.
- **KenMotionBridge** (Android/Kotlin) — roda no tablet do robô KEN (CSJBot/Pangolin Amy, chassi Slamtec Slamware) e faz a ponte **MQTT ⇄ chassi**.

## ✅ Estável (baseline)
| Área | Arquivos principais | Estado |
|---|---|---|
| CI build do APK | `.github/workflows/android-build.yml` | Verde; gera `KenMotionBridge-debug-apk` com SDK |
| MQTT nativo (Paho) + diagnóstico | `KenMotionBridge/.../mqtt/MqttManager.kt`, `BridgeConfig.kt`, `StatusBus.kt` | Broker CONECTADO confirmado no device |
| Serviço foreground + UI + Settings | `service/BridgeService.kt`, `MainActivity.kt`, `SettingsActivity.kt`, `res/layout/*` | Estável |
| Carregamento do SDK Slamware | `app/build.gradle` (java-websocket/gson/commons-io), `app/libs/RobotSDK-client.jar`, `sdk/SlamwareChassis.kt` | `NoClassDefFoundError` resolvido |
| Pipeline telemetria/feedback | `service/BridgeService.kt`, `motion/MotionController.kt`, `sdk/SlamwareChassis.kt` | Estrutura estável |
| Web — esqueleto UI + rotas + Capacitor | `src/App.tsx`, `src/pages/*`, `src/components/*`, `capacitor.config.ts` | Build ok |

## Testes / CI
- **Vitest** (`vitest.config.ts`, `src/test/setup.ts`) + Testing Library + jsdom. Script `npm test`.
  - Único teste: `src/test/example.test.ts` (trivial, passa). Cobertura real ≈ 0.
- **CI Android**: valida compilação + dexing + APK a cada push (verde).
- **Lint**: `eslint.config.js` (`npm run lint`).

## Dependências externas em uso
- HiveMQ Cloud (broker MQTT) · Eclipse Paho (cliente Android) · RobotSDK CSJBot (`RobotSDK-client.jar`)
- Java-WebSocket + Gson + commons-io (transitivas do Slamware) · GitHub Actions · Capacitor 8
- Slamtec Slamware (chassi TCP `192.168.99.2:1445`) — código pronto.

## ⏳ Pendente (fora do baseline)
- **Conexão real ao chassi**: bloqueio de **rede** (tablet precisa alcançar `192.168.99.2:1445` — solução dual-path Wi-Fi `RoboKen_Controle` + 4G/USB). Não é defeito de código.
- **Web app no repo ainda usa HTTP/WS direto** (`src/services/RobotConnection.ts`) — falta migrar para MQTT WSS.
- **Valores de comando de movimento** do chassi a confirmar no robô real.
