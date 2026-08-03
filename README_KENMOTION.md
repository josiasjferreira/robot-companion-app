# KenMotionBridge — como o robô Emy (CSJBot CT300‑H) é controlado

Resumo de engenharia de tudo que foi construído: **o que o sistema faz**, **como
usar o LIDAR**, **como os dados são coletados**, **como os comandos chegam ao
robô** e **como descobrimos qual é a placa/controladora** do chassi.

> Fonte da verdade é o código em `KenMotionBridge/`. Este documento explica a
> arquitetura e o modo de uso; o contrato MQTT detalhado está em
> `docs/KEN_BRIDGE_INSTRUCTIONS.md`.

---

## 1. O que o sistema faz (visão geral)

O robô **Emy / CSJBot CT300‑H** é um carrinho de entrega cujo chassi roda um
**Slamware SLAM core** (navegação por LIDAR). Ele vem com um tablet Android
acoplado. O que construímos é uma **ponte** (o app Android **KenMotionBridge**)
que fica no tablet e faz a tradução:

```
  App web (Lovable)  ──MQTT──►  KenMotionBridge (tablet)  ──TCP 1445──►  Chassi Slamware
   /admin/movimento              (a ponte deste repo)                    (LIDAR, odometria, motores)
        ▲                              │
        └──────────── feedback/telemetria (MQTT) ◄─────────────────────────┘
```

Em uma frase: **você aperta um botão no app web, ele publica um JSON no MQTT, a
ponte recebe e chama a API do chassi (Slamware) para mover o robô — e devolve o
estado (pose, LIDAR, bateria) de volta pelo MQTT.**

### Funções entregues

| Função | Como aciona | O que faz |
|---|---|---|
| **Teleop (joystick)** | `/admin/movimento` ou `teste_mqtt.sh` (`j`) | dirige o robô em tempo real (reemite a ~5 Hz por causa do watchdog) |
| **Frente com sensor** | comando `forward {front_sensor:true}` | `moveBy(FORWARD)` com desvio de obstáculo (OA) — para sozinho na frente de obstáculos. **Precisa do LIDAR vivo.** |
| **Frente sem sensor** | comando `forward {front_sensor:false}` | `trackForward` por odometria (sem OA) — avanço bruto |
| **Ré / giros** | comando `chassis {action:tras\|esquerda\|direita}` | funcionam **mesmo sem LIDAR** |
| **Teste de LIDAR** | comando `lidar_test` | conta pontos do LIDAR + profundidade + localização e devolve o veredito (`LIDAR VIVO` / `LIDAR ainda 0 pts`) |
| **Modo Recepção (greeter)** | comando `greeter {on:true}` | LIDAR detecta pessoa a ≤ 80 cm → toca boas‑vindas da Solar Life Energy no alto‑falante (cooldown 18 s) |
| **Fala (TTS)** | comando `speak` | fala um texto pelo alto‑falante do robô |
| **Diagnóstico contínuo** | automático | publica `diag`/`perception` a cada 2 s com contagem de LIDAR, profundidade, localização e saúde dos sensores |
| **Mapeamento** | comandos `build_mode`/`begin_map`/`end_map` | cria/salva o mapa base do ambiente |

---

## 2. Como usar o LIDAR

O LIDAR do CT300‑H é uma unidade **PACECAT** de rede (Ethernet), ligada ao
**computador interno (IPC)** do robô — **não** ao tablet e **não** a nenhum app
Android. Todos os apps (Emy Delivery, RoboStudio, o nosso KenMotionBridge) são
**clientes** do IPC, que serve os dados pelo protocolo Slamware em
`192.168.99.2:1445`.

### 2.1 Pré‑requisito físico (a causa do problema que tivemos)

O LIDAR só entrega pontos se o **cabo de comunicação dele estiver no roteador
certo** do robô. No nosso caso ele estava no roteador errado — depois de trocar,
o pipeline volta. Sempre confira essa conexão física antes de qualquer teste.

### 2.2 Validando o LIDAR (o teste de aceite)

1. Ligue o robô **fora da doca**, com o cabo do LIDAR no roteador correto.
2. Publique o comando de teste (pelo app, ou pelo `scripts/teste_mqtt.sh` opção `l`):
   ```json
   {"type":"lidar_test"}
   ```
3. Escute `ken/motion/feedback`. A ponte responde com um veredito:
   - `✅ LIDAR VIVO: N pts …` → LIDAR OK, a **frente com sensor** vai funcionar.
   - `⚠️ LIDAR ainda 0 pts …` → ainda sem nuvem de pontos (rever cabo/IPC).
4. Em paralelo, a ponte já publica `type:"perception"` a cada 2 s com
   `lidar_pts`, `depth_pts`, `localization_quality`, `navigation_ready`.

### 2.3 Regra de ouro do movimento

- **Frente com desvio de obstáculo (segura)** → exige `lidar_pts > 0` **e**
  `localization_quality > 0`. Sem isso, o firmware trava em `WAITING_FOR_START`.
- **Ré, giros e frente "sem sensor" (TRACK)** → funcionam sem LIDAR (odometria).

---

## 3. Como os dados são coletados (percepção)

A ponte **não** lê o LIDAR por hardware — ela **pergunta ao chassi** pela API
Slamware, por TCP. O canal é aberto uma vez com
`SlamwareCorePlatform.connect("192.168.99.2", 1445)` e a partir daí a ponte faz
polling contínuo (arquivo `control/SlamwareIntegrationService.kt`,
`startKeepAlive()`), replicando o serviço do app do fabricante
(`RobotStateUpdateService`) descoberto na engenharia reversa:

| Dado | Chamada Slamware | Frequência |
|---|---|---|
| Pose (x, y, ângulo) | `getPose()` | ~5 Hz |
| Nuvem de pontos LIDAR | `getLaserScan()` | ~1 Hz |
| Câmera de profundidade | `getDepthSensorData()` | ~1 Hz |
| Qualidade de localização | `getLocalizationQuality()` | ~11 s |
| Velocidade medida | `getRealTimeVelocity()` (**só leitura**) | ~4 Hz |
| Saúde do robô/sensores | `getRobotHealth()` / `getSensorHealthInfoList()` | no diag |

> **Por que o polling contínuo importa:** descobrimos que o app do fabricante
> puxa o estado sem parar desde a conexão. Sem esse "keep‑alive", o canal de
> percepção do Slamware pode nunca "acordar". Por isso a ponte mantém o mesmo
> ritmo de leitura. Detalhes em `docs/RE_ROBOSTUDIO_MOVIMENTO.md §7`.

Esses dados são reempacotados em JSON e publicados em:
- `ken/sensors/telemetry` — pose, IMU, velocidade, distância frontal, localização.
- `ken/motion/feedback` — heartbeat 1 Hz + `diag`/`perception` a cada 2 s.

O acesso à API proprietária é feito por **reflexão em Java/Kotlin** (a `.aar` do
RobotSDK é carregada em runtime), então a ponte não depende de ter o SDK no
momento da compilação.

---

## 4. Como os comandos chegam ao robô (atuação)

Fluxo de um comando de movimento:

```
1. App web publica  ->  MQTT ken/motion/cmd  ->  2. MqttBridgeService recebe o JSON
2. MqttBridgeService  --IPC-->  3. RobotBridgeService (MotionController.kt) roteia por "type"
3. MotionController  -->  4. SlamwareChassis (sdk/SlamwareChassis.kt) chama a API do chassi
4. Slamware move os motores  -->  5. feedback volta pelo MQTT
```

O tablet roda **dois processos** de propósito (dual‑homing):
- **principal** (`RobotBridgeService`) — amarrado à **Ethernet** do chassi
  (`192.168.99.x`), fala Slamware em `192.168.99.2:1445`.
- **`:mqtt`** (`MqttBridgeService`) — amarrado à **internet** (Wi‑Fi/4G), fala o
  broker HiveMQ. Os dois trocam por IPC interno.

### 4.1 O que cada comando de movimento realmente chama

| Comando MQTT | Chamada real no chassi |
|---|---|
| `forward {front_sensor:true}` | `platform.moveBy(MoveDirection.FORWARD)` — com OA |
| `forward {front_sensor:false}` | `moveTo(Location à frente, MoveOption{MoveTypeTrack}, yaw)` — sem OA |
| `chassis {action:tras}` | `moveBy(MoveDirection.BACKWARD)` |
| `chassis {action:esquerda/direita}` | `rotate(...)` pelo ângulo |
| `joystick {x,y,speed}` | ação de movimento reemitida a cada tick (watchdog 400 ms) |
| `stop` | `cancelAllActions()` / `action.cancel()` |

> **Importante (provado por engenharia reversa):** **não existe** "velocidade
> bruta" (`setRealtimeVelocity`) nesta geração do SDK. O `RealTimeVelocity` é só
> leitura. O próprio app do fabricante move o robô com `moveBy(FORWARD)` —
> exatamente como a nossa ponte. Detalhes em `docs/RE_ROBOSTUDIO_MOVIMENTO.md`.

### 4.2 Segurança embutida

- **Watchdog** (`watchdogMs: 400`) — se a ponte ficar >400 ms sem comando de
  teleop, ela **para** o robô. Por isso o joystick reemite a ~5 Hz.
- **Tetos de velocidade rígidos** (`vMax 0.4`, `vMaxBoost 0.7`, `wMax 0.8` m/s e
  rad/s) — aplicados no código, nunca relaxam o firmware.
- **Frente segura** só libera com nav‑ready (LIDAR + localização).

---

## 5. Como descobrimos qual é a placa/controladora do chassi

Isto foi **engenharia reversa**, não documentação de fábrica. O caminho:

1. **Descompilação dos apps do fabricante.** Baixamos e desmontamos os APKs que
   comprovadamente movem o robô — `Emy Delivery` (`com.csjbot.diningcar`),
   `CsjRobotStudio_V1.0.25.apk` e `robot_studio.apk` — usando **androguard**
   (Python) e `javap` para ler as assinaturas dos `.jar`/`.dex`. Documentado em
   `docs/REVERSE_DELIVERY_APK.md` e `docs/RE_ROBOSTUDIO_MOVIMENTO.md`.

2. **Identificação do SLAM core.** Dentro dos apps achamos a biblioteca
   `com.slamtec…` / `SlamwareCorePlatform` e um agente
   `com.csjbot.robotstation.agent.RPSlamwareSdpAgent` que enfileira "jobs" sobre
   ela. Conclusão: **o chassi é um Slamware (Slamtec)** e todo movimento passa por
   `connect(host, 1445)` + `moveBy/moveTo`. A porta **TCP 1445** e o IP
   **192.168.99.2** aparecem cravados no código (`JobConnect`).

3. **Mapa da topologia interna.** Cruzando o manual do robô com o decompile,
   ficou claro que existem **dois computadores** dentro do robô: um **IPC**
   (serve o Slamware SDP na 1445; LIDAR, profundidade, odometria e IMU ligados
   nele) e o **tablet Android** (só a interface/HMI). **Nenhum app Android gera
   dado de LIDAR** — todos são clientes do IPC. Documentado em
   `docs/ARQUITETURA_CONTROLE_EMY.md`.

4. **Identificação do LIDAR.** A saúde dos sensores (`getSensorHealthInfoList`) e
   as strings dos apps identificaram o LIDAR como uma unidade **PACECAT** de rede
   (Ethernet) — o que explicou por que um cabo no roteador errado zera a nuvem de
   pontos.

5. **Prova do canal de controle.** Confirmamos que o RoboStudio **não** usa AIDL
   secreto para mover: ele conecta igual a nós, por TCP 1445. Ou seja, a nossa
   ponte fala com a **mesma controladora, pelo mesmo caminho** que o app oficial.

Resumo: **a controladora de navegação é um Slamware SLAM core (Slamtec)**, servido
pelo IPC interno do robô na porta TCP 1445, com LIDAR PACECAT em rede — tudo
deduzido de descompilar os apps do fabricante e cruzar com o comportamento
observado.

---

## 6. Como testar agora (sem depender do app web)

Use o console MQTT incluído:

```bash
# no seu PC/tablet, na mesma internet do robô
sudo apt install mosquitto-clients          # (uma vez)
chmod +x scripts/teste_mqtt.sh
HOST=SEU-CLUSTER.s1.eu.hivemq.cloud ./scripts/teste_mqtt.sh
```

Ele abre um menu: `l` testa o LIDAR, `f` anda para frente com sensor, `b` ré,
`e`/`d` giram, `s` para — e mostra o `feedback` do robô ao vivo na mesma tela.
A senha é pedida na hora (nunca fica salva no script).

---

## 7. Onde está cada coisa (mapa do código)

| Arquivo | Papel |
|---|---|
| `KenMotionBridge/…/motion/MotionController.kt` | roteador central de comandos (por `type`) |
| `KenMotionBridge/…/sdk/SlamwareChassis.kt` | fala a API Slamware (moveBy/moveTo/getLaserScan…) |
| `KenMotionBridge/…/control/SlamwareIntegrationService.kt` | keep‑alive de percepção + frente unificada |
| `KenMotionBridge/…/service/RobotBridgeService.kt` | processo principal (rede do chassi) |
| `KenMotionBridge/…/service/MqttBridgeService.kt` | processo `:mqtt` (broker) |
| `KenMotionBridge/…/service/SpeechEngine.kt` | TTS do alto‑falante (Modo Recepção) |
| `KenMotionBridge/…/assets/bridge_config.json` | broker, tópicos, IP do chassi, tetos de segurança |
| `docs/KEN_BRIDGE_INSTRUCTIONS.md` | contrato MQTT completo (todos os comandos) |
| `docs/RE_ROBOSTUDIO_MOVIMENTO.md` | engenharia reversa do movimento |
| `docs/ARQUITETURA_CONTROLE_EMY.md` | topologia IPC × tablet × LIDAR |
| `scripts/teste_mqtt.sh` | console de teste por linha de comando |

---

## 8. Limites conhecidos (honestos)

- **Não existe velocidade bruta** no SDK desta geração — o movimento é por
  ações (`moveBy`/`moveTo`), não por comando de velocidade contínua nativa.
- **Frente com sensor depende do LIDAR** entregar pontos (cabo/IPC corretos).
- O ambiente de desenvolvimento na nuvem (onde o Claude roda) **não** conecta ao
  broker externo nem ao chassi — quem conecta é o **tablet** e o **app web**.
