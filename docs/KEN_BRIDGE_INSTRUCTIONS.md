# KenMotionBridge — instruções e contrato

Documento de referência da ponte (MQTT ⇄ RobotSDK/Slamware) do Emy CT300‑H.
As seções 2 (contrato MQTT) e 4 (AIDL) são referenciadas por prompts de teste.
**Fonte da verdade: o código** — este doc descreve o que a ponte realmente
publica/consome (não um contrato aspiracional).

## 1. Topologia

- Tablet Android acoplado ao robô. Dois processos:
  - **principal** (`RobotBridgeService`) — rede do chassi (`192.168.99.x`), fala
    `SlamwareCorePlatform` por TCP **1445** em `192.168.99.2`.
  - **`:mqtt`** (`MqttBridgeService`) — rede com internet, fala o broker; troca
    com o principal por IPC (`IRobotControl`/`IFeedbackSink`).

## 2. Contrato MQTT (conforme implementado)

Tópicos (de `assets/bridge_config.json`):

| Papel | Tópico |
|---|---|
| Comandos (web → robô) | `ken/motion/cmd` |
| Feedback/heartbeat (robô → web) | `ken/motion/feedback` |
| Telemetria de sensores | `ken/sensors/telemetry` |

### 2.1 Heartbeat `ken/motion/feedback` (1 Hz — NÃO parar)

Campos **contratados** (nunca remover — o web marca OFFLINE se faltar >5 s):

```json
{ "online": true, "v": 0.0, "w": 0.0, "front_cm": null, "blind_mode": false, "ts": 0 }
```

Campos **aditivos** (podem faltar; o web ignora se não conhecer):

- `diag` — diagnóstico do Caminho A quando uma varredura de FRENTE já rodou:
  `{ "path":"A", "flag_tried":"…", "result":"ok|waiting_for_start|blocked|exception", "exception_class":"…", "exception_msg":"…" }`

Respostas de comando (ACK) e o snapshot `type:"diag"` (2 s) também saem neste
tópico com um campo `type` — ver §3.

### 2.2 Comandos `ken/motion/cmd`

Todo payload é JSON com `type`. Unidade de `speed`: **0–100** (%), convertida
internamente para m/s pelos tetos de `bridge_config.json`.

| type | payload | efeito |
|---|---|---|
| `joystick` | `{x,y,speed,boost}` | teleop contínuo (reemite ação + watchdog) |
| `stop` | — | parada segura |
| `blind_move` | `{dx,dy,speed}` | avanço cego (odometria) |
| `chassis` | `{action:frente\|tras\|esquerda\|direita\|parar, speed, dist?, angle?, durationMs?}` | alto nível |
| `chassis_ctl` | `{action}` | alavancas de fábrica — ver §4.2 |
| `map_status`/`build_mode`/`begin_map`/`end_map`/`clear_map`/`recover_localization` | — | Rota A (mapa) |
| `forward_probe` | `{dist?}` | **Caminho A** — varredura de FRENTE (§4.1) |
| `front_test` | `{note?}` | cenário "FRENTE 05/07 revisitado" |
| `sys_param` | `{key,value?}` | get/set de parâmetro de sistema |

## 3. Telemetria/diag

- `ken/sensors/telemetry`: pose, IMU, `v_measured`, `front_cm`, `localization`,
  `odometry`.
- `type:"diag"` em `ken/motion/feedback` (2 s): `lidar_pts`, `depth_pts`,
  `work_mode`, `navi_ready`, `move_states`, `has_map`, `map_cells`,
  `robot_health` (flags + `errors[]`), `sensors[]` (LIDAR/DEPTH/ODOM_HEALTH).

## 4. Caminhos para destravar a FRENTE

Contexto: `moveBy(FORWARD)` fica em `WAITING_FOR_START`; ré/giros passam. Diag
atual: `lidar=0`, `depth=0`, odometria congelada, `naviReady=false`. Ver
`docs/RELATORIO_SENSORES_MORTOS.md`.

### 4.1 Caminho A — varredura de MoveOption (implementado)

Comando `{"type":"forward_probe","dist":0.3}`. **Correção ao spec original:** as
flags citadas (`FLAG_MOVE_WITHOUT_STOP_AT_OBSTACLE`, `isWithYielding`,
`moveBy(dir,opt)`) **não existem** nesta geração do SDK (confirmado por javap). A
implementação usa as alavancas REAIS do `MoveOption`:

- `setMoveType(MoveTypeOA | MoveTypeTrack | MoveTypeTrackWitOA)`
- `setTrackWithOA(false)` ← equivalente real de "sem gate de obstáculo"
- `setNoSmooth`, `setPrecise`, `setKeyPoints`, `setMilestone`,
  `setReturnUnreachableDirectly`
- execução por `moveTo(Location, MoveOption, yaw)` e `moveBy(float, MoveOption)`

Testa 5 configurações, lê `ActionStatus`/reason de cada uma, classifica e publica
o resultado completo (ack) + resumo no `diag` do heartbeat. Se alguma der
`result:"ok"`, a FRENTE liberou por flag; senão, cai para B/C.

### 4.2 Caminho B — AIDL do RobotSdkService

Estado real neste tablet: o pacote `com.csjbot.robotsdk.ten` **não está
instalado** (só `com.csjbot.diningcar`). O `chassis_ctl:rebind` já implementa
bind + **varredura** de `com.csjbot.*`/`com.slamtec.*` por um `*RobotSdkService*`
e reporta `alvo=<pacote/serviço>` (ou `NENHUM`). Descoberta por RE: o app oficial
RoboStudio **não binda AIDL para mover** — conecta igual a nós por TCP 1445. Logo
o bind não é o gargalo da FRENTE (ver `docs/ANALISE_ROBOSTUDIO_E_MAPA.md`).

### 4.3 Caminho C — mapa base permanente

`build_mode` → `begin_map` → empurrar → `end_map` (salva) → boot recarrega. **Só
funciona com `lidar_pts > 0`** — sem nuvem de pontos não há occupancy grid.

## 5. Não fazer

- Não mudar tópicos, nomes de campos ou unidade de `speed` do §2.
- Não escrever pacotes SDP crus sobre o TCP 1445.
- Não parar o heartbeat durante testes.
