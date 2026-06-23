# NEXT_PHASE — Plano da próxima fase

> Baseline: ver [`STATUS.md`](./STATUS.md). Este plano parte do que já está estável.
> Convenção: cada item vira uma fase de execução; marcar `[x]` ao concluir.

## Prioridades (ordem sugerida)
As fases **2** (rede do chassi) e **3** (web → MQTT) são as que destravam o fluxo ponta a ponta.

## Itens

- [x] **1. Documentar o baseline** — `STATUS.md` + `NEXT_PHASE.md` na raiz.
- [ ] **2. Fechar a rede do chassi** — validar dual-path (Wi-Fi `RoboKen_Controle` + 4G/USB); se preciso, **bind do MQTT à rede celular** (split de rotas).
  - Arquivos: `KenMotionBridge/.../mqtt/MqttManager.kt`, `service/BridgeService.kt`.
- [ ] **3. Migrar o web app para MQTT (WSS)** — trocar HTTP/WS direto por `mqtt.js`.
  - Arquivos: `src/services/RobotConnection.ts`, `src/types/Robot.ts`, `src/pages/*`.
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

## Contrato MQTT atual (referência rápida)
- `ken/motion/cmd` (web→ponte): `{type:"joystick",x,y,speed,boost}` · `{type:"stop"}` · `{type:"chassis",action,speed,angle,durationMs}`
- `ken/motion/feedback` (ponte→web): `{online,v,w,front_cm,ts}`
- `ken/sensors/telemetry` (ponte→web): `{battery,charging,pose{x,y,yaw_deg},imu{yaw,pitch,roll},localization,ts}`
