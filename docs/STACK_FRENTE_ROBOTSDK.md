# Stack completo do comando FRENTE — CT300‑H + RobotSDK + KenMotionBridge

Documento de consolidação: APIs REAIS do RobotSDK (extraídas por `javap` do
`RobotSDK-client.jar` embarcado neste projeto), arquitetura em camadas, design do
módulo de movimento e checklist de validação no tablet. Nenhuma API aqui foi
inventada; tudo que não pôde ser confirmado está marcado como **[SUPOSIÇÃO]**
com o teste de validação correspondente.

Fontes consultadas:

| Fonte | Status | O que rendeu |
|---|---|---|
| `RobotSDK-client.jar` (este repo, `KenMotionBridge/app/libs/`) | ✅ desmontado (`javap`) | Todas as assinaturas abaixo |
| APK Delivery/RoboStudio do fabricante | ✅ desmontado (sessões anteriores) | Fluxo real do teleop: `SlamAction → platform.moveBy(direção)` |
| `gitlab.csjbot.com/open/RobotSDKForYingbinV10-Android-Sample-app` | ❌ HTTP 403 (host interno CSJBot) | inacessível — nada usado de lá |
| `github.com/Cobot-Maker-Space/csjbot-alice-ros-control` | ⚠️ só README acessível | controle por ROS topics; sem detalhes de protocolo |
| SDK Slamware oficial 2.0.4 | ✅ analisado (sessão anterior) | confirma `MoveOption`/flags; `moveBy(float,…)` não suportado no firmware CT300 |

---

## 1. APIs reais de chassi do RobotSDK (assinaturas verificadas)

### 1.1 Caminho A — Slamware direto (TCP 1445) · `com.slamtec.slamware.SlamwareCorePlatform`

```java
// Ciclo de vida
public static SlamwareCorePlatform connect(String ip, int port);   // estático; 192.168.99.2:1445 neste robô
public void disconnect();
public void wakeUp();

// Movimento — TODOS retornam IMoveAction (ação assíncrona cancelável)
public IMoveAction moveBy(MoveDirection direction);                 // passo discreto FRENTE/TRÁS/GIRO
public IMoveAction moveBy(float dist, MoveOption opt);              // [NÃO SUPORTADO no firmware CT300 — testado: sem efeito]
public IMoveAction moveTo(Location target);                         // navegação a um ponto (exige mapa)
public IMoveAction moveTo(Location target, MoveOption opt, float yaw);
public IMoveAction moveTo(List<Location> path, MoveOption opt, float yaw);
public IMoveAction rotate(Rotation delta);                          // giro relativo (rad)
public IMoveAction rotateTo(Rotation absolute);
public IMoveAction goHome();

// Velocidade — SOMENTE LEITURA. Não existe setVelocity/setRealtimeVelocity
// público em nenhum dos 5 binários analisados (provado; ver REVERSE_DELIVERY_APK.md).
public RealTimeVelocity getRealTimeVelocity();
```

Enums/objetos de apoio:

```java
com.slamtec.slamware.action.MoveDirection { FORWARD, BACKWARD, TURN_LEFT, TURN_RIGHT }

com.slamtec.slamware.action.IMoveAction extends IAction {
    Path getRemainingMilestones();
    Path getRemainingPath();
}
com.slamtec.slamware.action.IAction {
    void cancel();                       // parada da ação em andamento
    ActionStatus getStatus();            // ver abaixo
    String getReason();                  // motivo (ex.: causa do BLOCKED)
    double getProgress();
    ActionStatus waitUntilDone();        // bloqueante — NÃO usar na main thread
    int getActionId(); String getActionName(); boolean isEmpty();
}
com.slamtec.slamware.action.ActionStatus {
    WAITING_FOR_START,   // aceita mas NÃO iniciou — é aqui que a FRENTE trava hoje
    RUNNING, PAUSED, FINISHED, ABORT, FAILED, BLOCKED,
    NEAR_TARGET_BUT_FAIL, WAITSHORT, WAITLONG
}
com.slamtec.slamware.robot.MoveOption {
    MoveOption(); MoveOption(Double speedRatio);
    void setMoveType(MoveType t);        // MoveTypeOA | MoveTypeTrack | MoveTypeTrackWitOA
    // flags: setPrecise, setMilestone, setNoSmooth, setWithYaw(implícito no moveTo),
    //        setKeyPoints, setAppending, setReturnUnreachableDirectly
    Double getSpeedRatio();
}
```

Semântica dos parâmetros (confirmada por uso no APK do fabricante + SDK 2.0.4):

- `moveBy(MoveDirection)`: passo curto na direção; o firmware decide distância/rampa.
  **É o que o teleop oficial da Delivery usa — inclusive para FRENTE.**
- `moveTo(Location, MoveOption, yaw)`: `Location(x, y, z)` em metros no frame do
  mapa; `yaw` em rad; `MoveTypeTrack` = rastreio por odometria **sem** desvio de
  obstáculo (não espera câmera), `MoveTypeOA` = com desvio (espera percepção).
- `IMoveAction.getStatus()/getReason()`: polling do resultado; não há callback no
  caminho direto — o padrão do fabricante é reemitir `moveBy` a cada ~300 ms
  enquanto o botão está pressionado.

### 1.2 Caminho B — CSJBot coshandler (AIDL/serviço do fabricante)

```java
// Singleton de entrada
com.csjbot.coshandler.core.Robot {
    public static Robot getInstance();
    public static boolean getConnectState();
    // IChassis — passos discretos (mesma semântica do moveBy direcional)
    public void moveForward(); public void moveBack();
    public void moveLeft();    public void moveRight();
    public void turnLeft();    public void turnRight();
    // Eventos (registrar ANTES de mover)
    public void setonRobotNaviStatesListener(OnRobotNaviStatesListener l); // onNavi(boolean ready, int state)
    public void setSpeedSetListener(OnSpeedSetListener l);
    // push* = internos do SDK (respostas do firmware); não chamar
}

// Requisições de chassi (proxy REQ/NTF/RSP sobre o transporte do fabricante)
com.csjbot.coshandler.client_req.chassis.IChassisReq /  ChassisReqImpl {
    void move(int direction);        // código CRU de direção (tabela do firmware; 0=frente no NAVI nativo)
    void moveAngle(int deg);         // giro por ângulo (graus, sinal = lado)
    void goAngle(int deg);
    void setSpeed(float mps);        // velocidade LINEAR dos passos (m/s)
    void setAngularVelocity(float);  // velocidade ANGULAR (rad/s)
    void navi(String pointName);     // navegação a ponto nomeado do mapa
    void cancelNavi();               // cancela navegação em curso
    void goHome();                   // volta à doca
    void loadMap(); void saveMap();
    void setPose(com.slamtec.slamware.robot.Pose p);
    void retargeting();              // relocalização
    void setNaviMode(int); void getPosition();
    // limiares de localização: setLowLQThreshold(int), setLowHoldTime(int)
}
```

**Como as camadas se ligam** (verificado no bytecode): `Robot.moveForward()` →
`ClientReqProxy/ChassisReqImpl.move(0)` → mensagem JSON (padrão coshandler
REQ/NTF/RSP) → transporte do fabricante → firmware → no fim da cadeia o próprio
núcleo Slamware executa o equivalente de `moveBy(FORWARD)`. Ou seja: **os dois
caminhos convergem no mesmo ponto do firmware** — não existe uma “porta
traseira” de movimento no caminho B.

### 1.3 O que NÃO existe (provado, não suposição)

- `setVelocity(vx, vy, vtheta)` / `setRealtimeVelocity(...)` público — ausente
  nos 5 binários. `RealTimeVelocity` é só leitura (`getRealTimeVelocity()`).
- `moveBy(float dist, MoveOption)` — existe na classe, mas o firmware CT300
  ignora (testado no robô; sem efeito).
- Toggle de desvio de obstáculo por SDK — só existe na camada UART/serial
  (`ReqSetObstacleAvoidancePacket`), fora do alcance do TCP 1445 e do AIDL.

---

## 2. Arquitetura em camadas + fluxo do comando FRENTE

```
Camada 4 — INTERFACES DE COMANDO
  • App web kenjun26 (joystick, botões, voz) → MQTT ken/motion/cmd (JSON)
  • Tela local do KenMotionBridge (diagnóstico/StatusBus)
        │  {"type":"chassis","action":"frente","speed":0.3}
        │  {"type":"joystick","x":…,"y":…,"speed":…}
        ▼
Camada 3 — KENMOTIONBRIDGE (este app)
  • MqttBridgeService (processo :mqtt, rede internet) — assina ken/motion/cmd,
    publica ken/motion/feedback (+ diag 2 s) e ken/sensors/telemetry
        │  IPC AIDL interno (IRobotControl / IFeedbackSink)
        ▼
  • RobotBridgeService (processo principal, rede do chassi 192.168.99.x)
      └─ MotionController  ← interpreta o comando, watchdog, rampa, teto de vel.
           └─ KenMotionSdk  ←←← “RobotSdkMotionBridge”: camada ÚNICA de movimento
                ├─ (preferido)  SlamwareChassis.moveBy(FORWARD)         [Caminho A]
                ├─ (blind/track) SlamwareChassis.trackForward(dist)      [Caminho A]
                └─ (fallback)   Robot.moveForward() + setSpeed()         [Caminho B]
        ▼
Camada 2 — ROBOTSDK (AAR/JAR do fabricante, carregado por reflexão)
  • com.slamtec.slamware.SlamwareCorePlatform  (sessão TCP 1445 → 192.168.99.2)
  • com.csjbot.coshandler.core.Robot / ChassisReqImpl (REQ/NTF/RSP JSON)
        ▼
Camada 1 — FIRMWARE / SERVIÇOS DO ROBÔ
  • Núcleo Slamware (SLAM, desvio de obstáculo, gerador de trajetória)
  • Placa CSJBot (CosClientAgentNG ⇆ UART serial — motores, sensores, E‑stop)
        ▼
Camada 0 — HARDWARE
  • Motores de tração, RPLIDAR, câmera RGBD frontal, IMU, odometria, E‑stop
```

Fluxo FRENTE, passo a passo (caminho vigente no código):

1. Operador segura o joystick para cima no app web → publica
   `{"type":"joystick","x":0,"y":1,"speed":40}` em `ken/motion/cmd` (≥3 Hz).
2. `MqttBridgeService` recebe e repassa por IPC → `MotionController.onCommand()`.
3. `MotionController.tick()` (20 Hz): deadzone → rampa → tetos rígidos → decide
   direção (`FORWARD`) → `drive(FORWARD)`.
4. `drive()` com `forwardMode=TRACK` (default atual): `SlamwareChassis.trackForward(0.5f)`
   = `moveTo(Location(0.5,0,0), MoveOption{MoveTypeTrack}, 0)` — rastreio por
   odometria, sem esperar câmera. Com `forwardMode=OA`: `moveBy(FORWARD)` +
   `KenMotionSdk.moverNativo(FORWARD)` (fallback CSJBot).
5. Intensidade: `KenMotionSdk.definirVelocidades(v, w)` → `setSpeed(float)` /
   `setAngularVelocity(float)` do `ChassisReqImpl`.
6. Resultado volta por polling do `IMoveAction` (`getStatus()/getReason()`),
   exibido na tela (“última FRENTE: …”) e publicado no feedback/diag.
7. Segurança: watchdog (300 ms no modo cego / config nos demais) zera alvo sem
   comando novo; `stop()` cancela ação + zera velocidades nos DOIS caminhos.

**Estado atual do bloqueio físico** (por que o código está certo e o robô ainda
não anda): o diag mostra `lidar_pts=0`, `depth_pts=0`, `Loc 0 %`,
`naviReady=false`. Sem percepção o núcleo Slamware mantém qualquer ação de
avanço em `WAITING_FOR_START` (OA) ou aceita sem gerar saída de motor (Track sem
odometria válida). Ré/giros passam porque o firmware os trata como manobra
segura de curta duração. Ver plano de destravamento na §5.

---

## 3. Design do módulo “RobotSdkMotionBridge”

O módulo pedido **já existe no projeto com o nome `KenMotionSdk`**
(`sdk/KenMotionSdk.kt`) — é a camada única e central de movimento. Este ciclo
completa a interface de alto nível que faltava. Mapa de correspondência:

| Interface pedida | Implementação real (KenMotionSdk) |
|---|---|
| `moveForward(distanceMeters?, speed?)` | **novo** — deriva duração = distância/velocidade e delega a `moverFrente(speed, durationMs)` (não há API de distância no firmware) |
| `stop()` | **novo alias** → `pararMovimento()` (cancelNavi + setSpeed(0) + setAngularVelocity(0) + cancel da ação Slamware + velocity 0) |
| `isMotionAvailable()` | **novo** — canal direto conectado OU sessão CSJBot ativa |
| baixo nível 1‑para‑1 | `moverNativo(dir)`→`Robot.moveForward/back/left/right`; `definirVelocidades(lin, ang)`→`setSpeed`/`setAngularVelocity`; `SlamwareChassis.moveBy/rotate/trackForward/cancelAction` |

Decisões de projeto (mantidas deste projeto):

- **Reflexão em toda chamada ao SDK** — o APK compila sem o AAR no host de CI e
  degrada com log (nunca derruba o controle) se uma assinatura mudar.
- **Dois caminhos com preferência fixa**: Slamware direto primeiro (menor
  latência, status legível via `IMoveAction`); CSJBot como fallback.
- **Sem estado escondido**: quem decide direção/velocidade é o
  `MotionController` (watchdog, rampa, tetos); o KenMotionSdk só traduz.
- **Parada segura em todo caminho de erro**: `garantirConectado()` bloqueia
  comando sem sessão; exceções em reflexão degradam para no‑op logado; watchdog
  zera sem comando; `stop()` atinge os dois SDKs.

---

## 4. Código (implementado neste ciclo — Kotlin)

Inicialização (já existente, `RobotBridgeService.onCreate → connectAll()`):

```kotlin
chassis.connect()                 // TCP 1445 + bind AIDL SEMPRE (desde 54d407e)
if (chassis.connected) chassis.autoActivate()   // wakeUp() + estados idle
motionSdk.inicializarConexaoRobo()              // Robot.getInstance() + ClientReqProxy
```

Interface de alto nível adicionada ao `KenMotionSdk`:

```kotlin
/** Chassi apto a receber comando de movimento agora? */
fun isMotionAvailable(): Boolean =
    chassis?.connected == true || (inicializado && estaConectado())

/**
 * FRENTE com distância/velocidade configuráveis. O firmware CT300 não expõe
 * "andar X metros" (moveBy(float) é ignorado); a distância vira DURAÇÃO
 * (t = d/v) sobre o passo contínuo, com teto de segurança.
 */
fun moveForward(distanceMeters: Float? = null, speed: Float? = null) {
    val v = (speed ?: VELOCIDADE_PADRAO).coerceIn(0.05f, VELOCIDADE_MAX)
    val durMs = distanceMeters?.let { d ->
        ((d.coerceIn(0.1f, 5f) / v) * 1000f).toLong()
    }
    moverFrente(v, durMs)
}

/** Parada segura — cancela navegação + zera velocidade nos DOIS caminhos. */
fun stop() = pararMovimento()
```

Entrada MQTT correspondente (já roteada em `MotionController.handleChassis`):

```json
{ "type": "chassis", "action": "frente", "speed": 0.3, "durationMs": 2000 }
{ "type": "chassis", "action": "parar" }
```

O comando `frente` com `dist` (novo): `{"type":"chassis","action":"frente","dist":0.6,"speed":0.3}` →
`KenMotionSdk.moveForward(0.6f, 0.3f)`.

---

## 5. Checklist de teste no tablet (ordem obrigatória)

**Pré‑condição de TODOS os testes de FRENTE: percepção viva.** Hoje
`lidar_pts=0` — enquanto isso valer, nenhum caminho de software move a frente
(nem o da fábrica). Os passos 1–3 destravam/diagnosticam isso primeiro.

1. **Inicialização**
   - [ ] Instalar APK, abrir; tela deve mostrar `SDK/Chassi: CONECTADO`.
   - [ ] Linha `Serviço bound:` — esperado `SIM` (bind agora é automático).
     Se `não`: mandar `{"type":"chassis_ctl","action":"rebind"}` e anotar
     `alvo=` do ACK. `alvo=NENHUM` ⇒ RobotSdkService não está instalado no
     tablet ⇒ instalar app RobotSDK da CSJBot (bloqueio de instalação, não de código).
   - [ ] Logcat: `adb logcat -s SlamwareChassis KenMotionSdk` sem erros de reflexão.

2. **Percepção (gate da frente)**
   - [ ] Diag MQTT (`type:"diag"`, 2 s): conferir `lidar_pts`, `depth_pts`,
     `sensors[]` (LIDAR_HEALTH/DEPTH_HEALTH: level/message).
   - [ ] Teste do conflito de câmera: botão **Parar câmera** no web → 10 s →
     `depth_pts` saiu de 0? (sim ⇒ conflito de /dev/video*; remover streamer do admin)
   - [ ] `LIDAR_HEALTH` com erro no `sensors[]` ⇒ hardware (cabo/USB do RPLIDAR)
     ⇒ suporte CSJBot. Saudável mas 0 pts ⇒ percepção não iniciada ⇒ repetir
     rebind + `wakeup` e observar.

3. **Mapa/local (só com lidar_pts > 0)**
   - [ ] `build_mode` → `begin_map` → empurrar/joystick 30–60 s → `map_cells`
     crescendo → `end_map` → `recover_localization` → `navi_ready=true`.

4. **FRENTE (área livre ≥ 2 m, alguém ao lado do E‑stop)**
   - [ ] `{"type":"chassis","action":"frente","speed":0.2,"durationMs":1500}` —
     robô avança e para sozinho. Log: `última FRENTE:` sem BLOCKED.
   - [ ] Joystick para cima (modo OA): avança contínuo enquanto pressionado.
   - [ ] `{"type":"chassis","action":"frente","dist":0.5,"speed":0.25}` — avança ~0,5 m.

5. **Erro / parada segura**
   - [ ] Obstáculo à frente (modo OA): ação vira `BLOCKED` + reason na tela;
     robô não colide.
   - [ ] E‑stop físico durante FRENTE: motor corta; diag `emergency_stop=true`;
     soltar E‑stop → `wakeup` → volta a responder.
   - [ ] Derrubar MQTT no meio da FRENTE: watchdog zera em ≤ watchdogMs.

6. **Sequência de comandos**
   - [ ] FRENTE → PARAR → TRÁS → PARAR → FRENTE, 3 ciclos: sem estado preso
     (`WAITING_FOR_START` residual, velocidade fantasma), `moveStates` volta a idle.
