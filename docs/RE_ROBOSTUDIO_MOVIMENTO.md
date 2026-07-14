# Engenharia reversa — como o RoboStudio move o chassi (2 APKs)

Fonte: `CsjRobotStudio_V1.0.25.apk` e `robot_studio.apk` (apps do fabricante que
comprovadamente movem o CT300‑H), desmontados via strings dos `classes*.dex`.
Objetivo: confirmar a rotina EXATA de FRENTE e de parada.

## 1. Conjunto de comandos ao chassi (`RPSlamwareSdpAgent$Job*`)

Ambos os APKs usam um agente `com.csjbot.robotstation.agent.RPSlamwareSdpAgent`
que enfileira "jobs" sobre o `SlamwareCorePlatform`. Jobs de movimento/estado:

| Job | Chamada Slamware | Papel |
|---|---|---|
| `JobConnect` / `JobConnectToServer` | `SlamwareCorePlatform.connect(host, 1445)` | conexão (host padrão 192.168.99.2) |
| **`JobMoveBy`** | **`platform.moveBy(MoveDirection.FORWARD)`** | **FRENTE (com gate OA/navegação)** |
| `JobMoveTo` | `moveTo(Location, MoveOption, yaw)` | navegação a ponto |
| `JobSpeed` / `JobRequireSpeed` | `setSpeed(float)` / leitura | TETO de velocidade (não é drive) |
| `JobCancelAllActions` | `cancelAllActions()` / `IMoveAction.cancel()` | PARAR |
| `JobRealTimeVelocity` | `getRealTimeVelocity()` / `requireRealTimeVelocity()` | **LEITURA (telemetria)** |
| `JobRobotPose` / `JobGetTargetPose` | `getPose()` | pose |
| `JobUpdateLaserScan` / `JobRequireDepthSensorData` | `getLaserScan()` / `getDepthSensorData()` | percepção |
| `JobSwitchWorkMode` / `JobClearMap` / `JobRecoverLocalization` | mapa/localização | rota de mapa |

## 2. FRENTE = `moveBy(FORWARD)` (idêntico ao KenMotionBridge)

O `JobMoveBy` é o caminho de avanço do RoboStudio. **É a mesma chamada que a nossa
ponte já faz.** Ou seja, quando a percepção está viva, `moveBy(FORWARD)` anda —
com o desvio de obstáculo (OA) do firmware. Não há segredo nem API privada.

## 3. NÃO existe "velocidade bruta" (setRealtimeVelocity) — provado

Buscas em AMBOS os APKs por `setRealtimeVelocity`, `setRealTimeVelocity`,
`publishVelocity`, `sendVelocity` → **zero ocorrências**. O `JobRealTimeVelocity`
é de **leitura** (padrões `getRealTimeVelocity`, `requireRealTimeVelocity`,
`getRealTimeVelocityLock`, `EventGetRealTimeVelocity`). O `RealTimeVelocity` do
SDK é um *data holder* (`setLinearVelocity`/`setAngularVelocity` mexem no objeto,
não enviam nada) e o `SlamwareCorePlatform` **só expõe `getRealTimeVelocity()`**
(confirmado por javap no RobotSDK-client.jar). O `setVelocity` que aparece no dex
é do `VelocityTracker` do Android (gestos), não do chassi.

**Conclusão:** não há caminho de velocidade bruta nesta geração. O fabricante
também não usa. Implementar `setRealtimeVelocity` geraria `NoSuchMethodException`.

## 4. O modo "SEM sensor" real = `MoveTypeTrack`

O equivalente legítimo de "ignorar o gate de obstáculo" é a opção de movimento
`MoveOption.MoveType.MoveTypeTrack` (rastreio por odometria, sem OA), enviada por
`moveTo(Location à frente, MoveOption{MoveTypeTrack}, yaw)`. É o nosso
`SlamwareChassis.trackForward`. Portanto os dois modos pedidos mapeiam para:

| Modo pedido | Rota REAL (não RealTimeVelocity) |
|---|---|
| COM sensor (seguro) | `moveBy(MoveDirection.FORWARD)` — OA, para em obstáculo |
| SEM sensor (bruto) | `moveTo(Location(d,0,0), MoveOption{MoveTypeTrack}, 0)` — odometria, sem OA |

## 5. PARAR

`JobCancelAllActions` → `cancelAllActions()` no platform e/ou `cancel()` na
`IMoveAction` corrente. É o que o `SlamwareChassis.cancelAction()` já faz.

## 6. Ordem de chamadas (resumo)

```
connect(host,1445) → (opcional) setSpeed(teto)
FRENTE com sensor : moveBy(FORWARD)                         // reemitir enquanto segura
FRENTE sem sensor : moveTo(Location(d,0,0), Track, 0)       // reemitir enquanto segura
PARAR             : action.cancel() / cancelAllActions()
```

Este documento embasa a implementação do toggle "Usar sensor frontal" no
KenMotionBridge, mapeando com/sem sensor para OA/Track — a API real, não a
inexistente RealTimeVelocity.

## 7. KEEP-ALIVE de percepção — a diferença que faltava (decompilação profunda)

Decompilação completa via androguard (não só strings) revelou o
`com.csjbot.robotstation.RobotStateUpdateService`. Ele é iniciado no `onCreate`
(logo após conectar) e roda uma **thread de polling CONTÍNUO** enquanto conectado
— loop base de **33 ms (~30 Hz)** com leituras rotativas do platform:

```
inicial: requireMapData(); getRobotInfo(); requireMapData(LOCATION_MAP);
         requireMapList(); requireSpecialAreaList();
loop (sleep 33 ms):
  i%7  == 0 → requireRealTimeVelocity()   (~230 ms)
  i%6  == 0 → startGetPose()  = getPose() (~200 ms)
  i%30 == 0 → updateLaserScan() = getLaserScan()  (~1 s)
  i%30 == 0 → requireTargetPose(); (+depth se camada ligada) requireDepthSensorData()
  i%15 == 0 → requireMapData(se needed); requireMoveAction()
  i%60 == 0 → requireWorkMode()
  i%300== 0 → requireCurrentMap()
  i%333== 0 → getLocalizationQuality()   (~11 s)
```

**Interpretação:** o RoboStudio **puxa o estado do robô sem parar** desde a
conexão — getPose ~5 Hz, getLaserScan ~1 Hz, o tempo todo. A nossa ponte, antes,
lia o laser só a cada 2 s (no diag) e getPose a 1 Hz. A hipótese concreta é que
esse **polling contínuo mantém o canal de percepção do SDP ativo/fluindo** — sem
ele, o pipeline de laser pode nunca "acordar" ou lapsar.

**Implementado:** `SlamwareIntegrationService.startKeepAlive()` replica esse
serviço (base 100 ms, mesmos intervalos reais: getPose ~5 Hz, getLaserScan ~1 Hz,
depth ~1 Hz, localização ~11 s), iniciado no `onCreate` do RobotBridgeService
logo após conectar e mantido enquanto a ponte roda. É a tentativa concreta de
"acordar" a percepção pela mesma via do app do fabricante — sem inventar API.
