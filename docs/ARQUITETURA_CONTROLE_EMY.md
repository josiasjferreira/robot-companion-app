# Arquitetura de controle do Emy (CT300‑H) — DOF, transporte e nova lógica

Respostas fundamentadas nos binários reais (RobotSDK‑client.jar e
RoboStudio 1.0.25 desmontados por `javap`/strings). Onde a config física depende
do modelo, está marcado **[confirmar no robô]** com o método de verificação.

---

## 1. Graus de liberdade (servos/atuadores)

O RobotSDK é **genérico para vários modelos** (Alice, Snow, Ken/Emy/Amy). Ele
**expõe** o superconjunto de atuadores abaixo; quais existem fisicamente no Emy
CT300‑H depende da variante. O Emy CT300‑H é um robô de **recepção/entrega com
bandeja** (não humanoide) — logo, provável ausência de braços.

### Superconjunto exposto pelo SDK (`Robot` / `IAction` / `IActionV2`)

| Subsistema | API | DOF | No Emy CT300‑H |
|---|---|---|---|
| **Base (locomoção)** | `moveForward/Back/Left/Right`, `turnLeft/Right`; Slamware `moveBy/moveTo/rotate` | **2** (linear v + angular ω, tração diferencial, 2 motores de roda) | ✅ **presente** (é o que usamos) |
| **Cabeça** | `AliceHeadUp/Down` (pitch), `AliceHeadLeft/Right` (yaw), `AliceHeadHReset`, `setNewHeadMaxUp(int)` | até **2** (pitch + yaw) | ⚠️ [confirmar] — provável 1–2 (pescoço motorizado) |
| **Braço esq.** | `leftLargeArm{Up,Down}` (ombro), `leftSmallArm{Up,Down}` (cotovelo), `AliceLeftArm…`, `SnowLeftArmSwing` | até **2** | ❌ provável **ausente** (robô de bandeja) |
| **Braço dir.** | `rightLargeArm…`, `rightSmallArm…`, `AliceRightArm…`, `SnowRightArmSwing` | até **2** | ❌ provável **ausente** |
| **Rosto/expressão** | `expression(int)`, `happy/sad/angry/smile/surprised/normal/sleepiness/lightning` | **0 servos** (tela digital) | ✅ presente (renderizado no display) |
| **Onda/gesto** | `startWave(int)/stopWave`, `snowDoubleArm` | — (compõe braços) | ❌ se sem braços |

### DOF físico provável do Emy CT300‑H (a confirmar)

- **Locomoção:** 2 DOF de movimento (v, ω) via **2 motores de roda** (diferencial,
  não‑holonômico) + rodízios livres (não atuados).
- **Cabeça:** 1–2 DOF **[confirmar]** — muitos CT300 têm pescoço com inclinação
  (nod) e/ou giro.
- **Braços:** provavelmente **0** (modelo bandeja).
- **Rosto:** **0 servos** — é display; "expressão" é imagem, não mecânica.

**Total mecânico provável: 2 (base) + 0–2 (cabeça) ≈ 2 a 4 DOF motorizados.**

### Como confirmar os DOF reais em runtime (não chutar)

O SDK descobre o modelo pelo mainboard Linux:

```kotlin
Robot.getInstance().setLinuxRobotTypeListener { tipo ->
    // ex.: "CT300H", "Alice", "Snow" — habilita só os atuadores reais do modelo
    Log.i("DOF", "modelo=$tipo")
}
```

Complementos: `setOnMotorListener`/`pushRobotMotorStatus` (nº e estado de motores),
`OnRobotSerialPowerInfoListener`, `getVersion`. Chamar `AliceHead…`/`…Arm…` num
modelo sem o atuador é no‑op no firmware — ótimo para sondar empiricamente.

---

## 2. Protocolo de comunicação no APK

**Não existe Bluetooth em nenhuma camada do SDK** (varredura confirmou: nenhuma
classe `bluetooth/BLE/gatt`). Os canais reais:

| Canal | Uso | Onde |
|---|---|---|
| **Wi‑Fi/Ethernet — TCP** | núcleo Slamware (SLAM, chassi) porta **1445** em `192.168.99.2` | rede do robô |
| **Wi‑Fi — MQTT** | comandos/telemetria da nossa ponte (`ken/motion/*`) | internet/broker |
| **Wi‑Fi — WebSocket** | dados/scan do mainboard (`connectDataWebSocket`, `connectScanWebSocket`) | rede |
| **AIDL (IPC no tablet)** | corpo/expressão/voz via `RobotSdkService` do fabricante | **dentro do tablet** |
| **Serial/UART (interno)** | tablet Android ⇄ mainboard Linux (motores, energia, E‑stop) | interno, **não acessível de fora** |
| **USB (interno)** | RPLIDAR + câmera RGBD | interno |

### Recomendação por cenário

- **APK RODANDO NO TABLET DO ROBÔ** (nosso caso): **AIDL** para corpo/expressão
  (`Robot.getInstance()` do RobotSDK) **+ TCP 1445** para chassi/SLAM
  (`SlamwareCorePlatform`). É o que o KenMotionBridge já faz.
- **APK EXTERNO** (celular/tablet separado): **Wi‑Fi** é o único caminho —
  conectar ao hotspot do robô (`192.168.99.x`) e falar **TCP 1445** (chassi) e/ou
  **MQTT/WebSocket** para o resto. **Bluetooth e USB não são opções** de controle.

Ou seja: **Wi‑Fi (TCP/MQTT/WebSocket) para o mundo externo; AIDL para o corpo
dentro do tablet.** Bluetooth: descartado (inexistente). USB: só periféricos
internos.

---

## 3. Tempo real estrito × ações pré‑programadas

**Veredito: o robô é nativamente ACTION‑BASED. Tempo real estrito de velocidade
NÃO é suportado pela plataforma** — e isso está provado, não suposto:

- **Não existe** `setVelocity(vx, vy, vθ)`/`setRealtimeVelocity()` público em 5
  binários. `getRealTimeVelocity()` é **somente leitura**.
- Movimento é sempre **ação discreta assíncrona**: `moveBy(MoveDirection)` /
  `moveTo(Location, MoveOption)` → retornam `IMoveAction` com estado
  (`WAITING_FOR_START → RUNNING → FINISHED/BLOCKED`). Há uma **fila/máquina de
  estados no firmware** entre o comando e o motor.
- A latência do caminho (rede TCP + fila de ação + firmware + rampa) torna
  **impossível** um laço de controle rígido (kHz) como em servo direto.

### Consequência de projeto

- **Ações pré‑programadas / event‑driven = modelo NATIVO e confiável.**
  Navegação a pontos (`navi`/`moveTo`), rotinas de gesto/expressão, sequências.
- **"Tempo real" (teleop/joystick) só como SOFT‑real‑time EMULADO**: reemitir
  ações discretas curtas a ~3–5 Hz com **watchdog** (dead‑man). É exatamente o
  que o `MotionController` já faz (reemite `moveBy`/`trackForward` a cada ~300 ms
  enquanto o joystick segura). Funciona para teleop suave, **não** para controle
  de precisão em malha fechada.

Regra prática: **projete em torno de INTENÇÕES e AÇÕES, com feedback por
eventos**; trate o teleop contínuo como um caso especial de "ação repetida com
watchdog", não como streaming de velocidade.

---

## 4. Nova lógica de biblioteca de controle (proposta)

Arquitetura em 4 camadas que abraça o modelo action‑based, descobre capacidades
em runtime e unifica base + corpo + expressão sob uma única fila de ações com
watchdog. Nomes de SDK usados são reais (confirmados por javap).

```
┌─ CommandIntent (o QUE se quer) ─────────────────────────────┐
│  MotionIntent(v, ω) | DiscreteMove(dir) | NavigateTo(point) │
│  HeadIntent(pitch,yaw) | ExpressionIntent(mood) | Gesture(id)│
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─ CapabilityModel (o que o robô PODE) ───────────────────────┐
│  descoberto via linuxRobotType + motorListener;             │
│  filtra intents para os atuadores REAIS (ex.: sem braço →   │
│  Gesture vira no‑op logado, não erro)                       │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─ ActionScheduler (COMO executar) ───────────────────────────┐
│  fila single‑thread; watchdog dead‑man; reemissão de ação   │
│  discreta p/ soft‑real‑time; cancelamento; 1 ação de base    │
│  por vez; corpo/expressão em paralelo à base                │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─ SdkTransport (ONDE) ───────────────────────────────────────┐
│  ChassisPort  → SlamwareCorePlatform (TCP 1445)             │
│  BodyPort     → Robot/coshandler (AIDL)                     │
│  eventos: IMoveAction status + listeners → StateBus         │
└─────────────────────────────────────────────────────────────┘
```

### Esboço em Kotlin (proposta — não é produção ainda)

```kotlin
// 1) Capacidades descobertas em runtime.
data class RobotCapabilities(
    val model: String,
    val hasHeadPitch: Boolean, val hasHeadYaw: Boolean,
    val hasLeftArm: Boolean, val hasRightArm: Boolean,
    val driveType: DriveType = DriveType.DIFFERENTIAL, // v + ω
) {
    companion object {
        /** Preenche a partir do linuxRobotType + probing de motores. */
        fun discover(robot: Any, onReady: (RobotCapabilities) -> Unit) { /* setLinuxRobotTypeListener… */ }
    }
}

// 2) Intenção — independe de transporte/atuador.
sealed interface CommandIntent
data class MotionIntent(val v: Double, val omega: Double) : CommandIntent   // soft‑real‑time
data class DiscreteMove(val dir: Direction) : CommandIntent                 // passo
data class NavigateTo(val point: String) : CommandIntent                    // ação pré‑programada
data class HeadIntent(val pitchDeg: Float?, val yawDeg: Float?) : CommandIntent
data class ExpressionIntent(val mood: Mood) : CommandIntent
data class Gesture(val id: GestureId) : CommandIntent
object StopAll : CommandIntent

// 3) Agendador — fila + watchdog + reemissão (o coração da lógica).
class ActionScheduler(
    private val caps: RobotCapabilities,
    private val chassis: ChassisPort,   // moveBy/moveTo/rotate/cancel + IMoveAction
    private val body: BodyPort,         // head/arm/expression (no‑op se sem atuador)
    private val watchdogMs: Long = 400,
) {
    fun submit(intent: CommandIntent) = when (intent) {
        is MotionIntent  -> teleop(intent)          // reemite ação curta @~3–5Hz
        is DiscreteMove  -> chassis.moveBy(intent.dir)
        is NavigateTo    -> chassis.moveTo(intent.point) // devolve IMoveAction observável
        is HeadIntent    -> if (caps.hasHeadPitch || caps.hasHeadYaw) body.moveHead(intent) else noop("head")
        is ExpressionIntent -> body.expression(intent.mood) // sempre disponível (tela)
        is Gesture       -> if (caps.hasLeftArm || caps.hasRightArm) body.gesture(intent.id) else noop("gesture")
        StopAll          -> { chassis.cancel(); body.stopAll() }
    }

    // Soft‑real‑time: mantém a base viva reemitindo passos curtos; watchdog zera.
    private var lastCmdAt = 0L
    private fun teleop(m: MotionIntent) {
        lastCmdAt = now()
        val dir = classify(m.v, m.omega) ?: return chassis.cancel()
        chassis.setSpeed(m.v, m.omega)          // intensidade (setSpeed/setAngularVelocity)
        chassis.reemitIfStale(dir, sinceMs = 300) // reemite moveBy/track enquanto segurar
    }
    fun tickWatchdog() { if (now() - lastCmdAt > watchdogMs) chassis.cancel() }
}

// 4) Portas de transporte — encapsulam as chamadas SDK reais.
interface ChassisPort {                 // impl atual: SlamwareChassis (TCP 1445)
    fun moveBy(dir: Direction): MoveHandle
    fun moveTo(point: String): MoveHandle
    fun setSpeed(v: Double, omega: Double)
    fun reemitIfStale(dir: Direction, sinceMs: Long)
    fun cancel()
}
interface BodyPort {                     // impl: Robot/coshandler via AIDL
    fun moveHead(h: HeadIntent); fun gesture(id: GestureId)
    fun expression(m: Mood); fun stopAll()
}
```

### Por que essa lógica é a ideal para o Emy

1. **Descoberta de capacidade** — nunca comanda um atuador que o modelo não tem;
   `Gesture` num robô de bandeja vira no‑op logado, não crash. Portável entre
   CT300‑H, Alice, Snow sem `if` espalhado.
2. **Intenção separada de transporte** — a mesma `NavigateTo`/`HeadIntent` vale
   por MQTT (externo) ou AIDL (no tablet); só troca a `*Port`.
3. **Fila única com watchdog** — abraça o modelo action‑based real e resolve o
   teleop por reemissão (soft‑real‑time), sem fingir um `setVelocity` que não
   existe. É a formalização do que o `MotionController` já faz, agora extensível
   a cabeça/expressão.
4. **Feedback por eventos** — `IMoveAction.getStatus()` + listeners do coshandler
   alimentam um `StateBus`, casando com o diag que já publicamos.

### Caminho de adoção incremental (sem reescrever tudo)

- **Fase 1:** extrair `ChassisPort` da `SlamwareChassis` atual (já implementa
  tudo) e `BodyPort` do `KenMotionSdk`. Zero comportamento novo.
- **Fase 2:** introduzir `CommandIntent` + `ActionScheduler` reusando o watchdog
  do `MotionController`.
- **Fase 3:** `RobotCapabilities.discover()` via `setLinuxRobotTypeListener` para
  habilitar cabeça/expressão só quando existirem.
- **Fase 4:** engine de rotinas pré‑programadas (sequências de intents) — o caso
  de uso natural do robô de recepção/entrega.

> Importante: nada disso destrava o bloqueio atual de **percepção morta**
> (lidar/depth/odometria em zero — ver `RELATORIO_SENSORES_MORTOS.md`). Esta
> arquitetura é a evolução do CONTROLE; a base física da frente só volta quando o
> LIDAR do robô voltar a publicar.
