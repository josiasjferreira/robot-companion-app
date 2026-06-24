# NEXT_PHASE — Plano da próxima fase

> Baseline: ver [`STATUS.md`](./STATUS.md). Este plano parte do que já está estável.
> Convenção: cada item vira uma fase de execução; marcar `[x]` ao concluir.

## Prioridades (ordem sugerida)
As fases **2** (rede do chassi) e **3** (web → MQTT) são as que destravam o fluxo ponta a ponta.

## Itens

- [x] **1. Documentar o baseline** — `STATUS.md` + `NEXT_PHASE.md` na raiz.
- [x] **2. Fechar a rede do chassi** — bind opcional do MQTT à rede celular (split de rotas),
  acionado por "Forçar MQTT pelo 4G" nas Configurações. Chassi pelo Wi-Fi do robô, MQTT pelo 4G/USB.
  - Arquivos: `mqtt/MqttManager.kt` (CellularSslSocketFactory + requestNetwork), `BridgeConfig.kt`
    (`mqttForceCellular`), `SettingsActivity.kt`/`activity_settings.xml` (checkbox),
    `AndroidManifest.xml` (CHANGE_NETWORK_STATE). Validar no robô.
- [x] **3. Migrar o web app para MQTT (WSS)** — `RobotConnection.ts` agora usa `mqtt.js`
  (assina feedback/telemetria, publica `ken/motion/cmd`); tradução em `mqttCommand.ts`;
  config por env (`src/config/mqtt.ts` + `.env.example`); teste `mqttCommand.test.ts`.
  Pendente (Lovable/dev): `npm install` (dep `mqtt`), definir `.env` e validar UI.
- [ ] **4. Contrato MQTT versionado** — `docs/MQTT_CONTRACT.md` (tópicos + schemas) como fonte única.
- [ ] **5. Testes de unidade (web)** — parsing de telemetria, joystick→comando, estado de conexão (Vitest).
  - Arquivos: novos `*.test.ts` ao lado de `RobotConnection.ts`/`types/Robot.ts`.
- [ ] **6. Testes de unidade (Android)** — `MotionController` (deadzone/rampa/watchdog), `BridgeConfig` (merge).
  - Arquivos: `KenMotionBridge/app/src/test/`.
- [ ] **7. Confirmar comandos de movimento** — validar `moveBy(MoveDirection)`/`rotate` no robô real.
  - Arquivos: `sdk/SlamwareChassis.kt`, `motion/MotionController.kt`, `sdk/KenMotionSdk.kt`.
- [ ] **8. Tela única de telemetria no web** — consolidar pose/bateria/IMU/localização/"SEM SINAL".
  - Base: `src/components/StatusIndicator.tsx`, `LogPanel.tsx`.
- [ ] **9. Build de release assinado** — job `assembleRelease` + assinatura no workflow.
  - Arquivos: `.github/workflows/android-build.yml`, `KenMotionBridge/app/build.gradle`.
- [ ] **10. Smoke test ponta a ponta** — `docs/SMOKE_TEST.md` (broker → cmd → chassi → feedback).
- [x] **11. Reconexão MQTT robusta a troca de rede** — corrige o "conecta e cai" com
  `Software caused connection abort` no setup dual-path. `MqttManager` deixa de usar o
  auto-reconnect do Paho (que prendia o socket a uma `Network` celular obsoleta) e passa a
  reconectar por conta própria, reconstruindo cliente + `socketFactory` a cada tentativa
  (backoff 2→4→8→16→30s). Um `NetworkCallback` persistente força reconexão imediata quando a
  celular/USB troca de instância.
  - Arquivos: `mqtt/MqttManager.kt`, teste `app/src/test/.../MqttBackoffTest.kt`,
    `app/build.gradle` (JUnit), `.github/workflows/android-build.yml` (passo `testDebugUnitTest`).
- [x] **12. Dual-homing dirigido por dados + diagnóstico de redes** — o tablet tem VÁRIAS redes ao
  mesmo tempo (Wi-Fi do hotspot, USB/tethering do celular, Ethernet do chassi). A 1ª tentativa
  (amarrar por `TRANSPORT_ETHERNET` + `requestNetwork` só por capacidade) **regrediu o MQTT** (DNS
  falhava com o processo amarrado a rede sem internet) e não pegava a interface certa. Correção:
  `NetworkRouter` agora acha a rede do **chassi pela SUB-REDE** (mesma /24 do IP do chassi) e a de
  **internet pela capacidade** (`NET_CAPABILITY_INTERNET`, validada). O serviço captura a internet
  ANTES de amarrar o processo ao chassi e a passa ao `MqttManager` (`connect(internetNet, bindMqtt)`),
  que amarra o socket do MQTT via `ReResolvingSocket` (DNS+saída na rede de internet), mantendo
  hostname/SNI/verificação TLS. A tela mostra um **inventário das redes** (`netInfo`) para diagnóstico.
  - Arquivos: `net/NetworkRouter.kt` (novo), `mqtt/MqttManager.kt`, `service/BridgeService.kt`,
    `BridgeConfig.kt` (flag `dualHoming`), `StatusBus.kt`, `MainActivity.kt`, `activity_main.xml`,
    `assets/bridge_config.json`, `sdk/SlamwareChassis.kt`.
  - Pendente: validar no robô lendo o bloco **"Redes (diagnóstico)"** na tela + `adb logcat`.

## Contrato MQTT atual (referência rápida)
- `ken/motion/cmd` (web→ponte): `{type:"joystick",x,y,speed,boost}` · `{type:"stop"}` · `{type:"chassis",action,speed,angle,durationMs}`
- `ken/motion/feedback` (ponte→web): `{online,v,w,front_cm,ts}`
- `ken/sensors/telemetry` (ponte→web): `{battery,charging,pose{x,y,yaw_deg},imu{yaw,pitch,roll},localization,ts}`
