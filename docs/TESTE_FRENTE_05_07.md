# Cenário de teste — "FRENTE 05/07 revisitado"

Teste controlado que **replica a sequência de comandos de 05/07/2026** (a que fez
o robô iniciar avanço, mesmo travando) com telemetria estruturada, para comparar
com o comportamento após as atualizações da ponte. **Não altera a lógica de
produção** — só orquestra as funções já existentes do KenMotionBridge.

## 1. Desenho do cenário

Classe: `motion/FrontMotionTestScenario2026_07_05.kt`. Ponto de entrada:
`fun run(): FrontMotionTestResult`. Sequência executada (mesma ordem do dia):

| # | Passo | Função da ponte reutilizada | Chamada Slamware real |
|---|---|---|---|
| 0 | snapshot `antes` | `diagJson()` | leitura |
| 1 | `build_mode` | `chassisCtl("build_mode")` | `switchWorkMode(MODE_BUILD_MAP)` |
| 2 | `begin_map` | `mapCtl("begin_map")` | `clearMap()` + `beginBuildMap()` |
| 2b | `upd_on` | `chassisCtl("upd_on")` | `setMapUpdate(true)` |
| 3 | `recover_localization` | `mapCtl("recover_localization")` | `recoverLocalization(pose, 4×4m)` |
| — | snapshot `apos_setup` | `diagJson()` | leitura |
| 4 | `track_forward` (0.5 m) | `trackForward(0.5f)` | `moveTo(Location(0.5,0,0), MoveOption{MoveTypeTrack}, 0)` |
| — | observação ~4 s | `diagJson()` + `lastForwardStatus()` a cada 0,5 s | polling `IMoveAction.getStatus/getReason` |
| 5 | `moveby_forward` | `moveBy(FORWARD)` | `moveBy(MoveDirection.FORWARD)` |
| — | observação ~4 s | idem | polling |
| 6 | `stop` | `cancelAction()` | `IAction.cancel()` |
| — | snapshot `depois` | `diagJson()` | leitura |

Cada snapshot captura: `lidar_pts`, `depth_pts`, `pose(x,y,yaw)`,
`localization`, `work_mode`, `navi_ready`, `move_states`, `forward_status`
(estado + motivo da ação de avanço).

## 2. Resultado (`FrontMotionTestResult`) — ponte × hardware

O resultado sai como JSON em `ken/motion/feedback` (`type:"front_test_result"`)
**e** é salvo em `filesDir/front_test_<ts>.json` no tablet. Campos de veredito,
com a **distinção crítica** que impede culpar o app quando o robô é que está
travado:

| Campo | Significado | Camada |
|---|---|---|
| `bridge_ok` | todos os comandos enviados/aceitos sem exceção | **PONTE** |
| `moved_from_waiting` | status saiu de `WAITING_FOR_START` → `RUNNING` | firmware |
| `returned_to_waiting` | …e reverteu a `WAITING_FOR_START` | firmware |
| `sensors_revived` | algum snapshot com `lidar_pts>0` ou `depth_pts>0` | **HARDWARE** |
| `pose_advanced` | deslocou > 5 cm da origem | **HARDWARE** |
| `operator_moved` | operador observou avanço físico (preenchível) | **HARDWARE** |
| `verdict` | resumo automático (ver abaixo) | — |

Vereditos automáticos possíveis:

- `FALHA DE PONTE` — algum comando não foi enviado/aceito (raro; olhar `steps`).
- `SUCESSO` — ponte OK **e** robô avançou (pose/observação confirmam).
- `PARCIAL` — ponte OK, sensores voltaram, mas sem avanço confirmado.
- `PONTE OK, HARDWARE BLOQUEADO` — avanço aceitou (RUNNING) e reverteu a
  WAITING_FOR_START; LIDAR/depth/odometria seguem mortos → **problema é do robô**.
- `PONTE OK, SEM MOVIMENTO` — comandos aceitos, sensores em 0 → percepção/SLAM do
  robô continua o bloqueio, não a ponte.

## 3. Como disparar

- **Botão na UI:** tela principal → **"Teste FRENTE 05/07"** (envia
  `ACTION_FRONT_TEST` ao `RobotBridgeService`).
- **Por MQTT:** publicar em `ken/motion/cmd`:
  `{"type":"front_test","note":"área livre 2 m, operador ao lado do E-stop"}`
  (o campo `note` é opcional e vai para `operator_note` no resultado).

O teste dura ~10 s (2 janelas de observação de 4 s + setup). É reentrante-seguro:
uma segunda chamada durante a execução é recusada com `ack ok=false`.

## 4. Checklist de execução no tablet

**Antes:**
1. [ ] Área **livre e segura** ≥ 2 m à frente do robô; operador ao lado do E-stop.
2. [ ] KenMotionBridge conectado: tela mostra `SDK/Chassi: CONECTADO`,
   `getDCIsConnected=true` (telemetria ativa), `saúde chassi: OK`.
3. [ ] Abrir o log: `adb logcat -s FrontMotionTest0705 SlamwareChassis` (opcional,
   para acompanhar em tempo real).

**Executar:**
4. [ ] Tocar **"Teste FRENTE 05/07"** (ou publicar o comando MQTT).
5. [ ] Observar durante os ~10 s:
   - [ ] o robô **fisicamente inicia avanço**? (anotar sim/não)
   - [ ] a **pose atualiza** (`x` cresce) e `localization > 0%`?
   - [ ] `lidar_pts` e `depth_pts` **saem de 0**?
   - [ ] `forward_status` mostra `RUNNING`? volta a `WAITING_FOR_START`?

**Registrar:**
6. [ ] O resultado é salvo automaticamente em
   `filesDir/front_test_<ts>.json` e publicado no feedback. Puxar com:
   `adb shell run-as br.com.onlife.kenmotionbridge ls files/` e
   `... cat files/front_test_<ts>.json`.
7. [ ] Anotar a observação do operador (avançou? quanto?) — enviar
   `{"type":"front_test","note":"NÃO avançou; pose estática"}` numa próxima
   execução, ou anexar a nota ao arquivo salvo.

**Se o robô NÃO avançar (esperado enquanto a percepção estiver morta):**
8. [ ] Confirmar no resultado: `verdict` = `PONTE OK, HARDWARE BLOQUEADO` ou
   `PONTE OK, SEM MOVIMENTO`.
9. [ ] Confirmar `sensors_revived=false` e `pose_advanced=false`.
10. [ ] Conclusão a registrar: **a ponte de movimento está correta (comandos
    enviados/aceitos, estados lidos); o bloqueio permanece na percepção/SLAM do
    robô (LIDAR/depth/odometria em zero).** Encaminhar para o fluxo de hardware
    (reboot na doca → `clear_health` → teste-oráculo RoboStudio → suporte CSJBot,
    ver `docs/RELATORIO_SENSORES_MORTOS.md`).

## 5. Interpretação — não confundir as duas camadas

- **Sucesso de PONTE** (`bridge_ok=true`): o KenMotionBridge fez a parte dele —
  colocou o robô em MODE_BUILD_MAP, iniciou mapa, enviou `moveTo TRACK`/`moveBy`,
  leu todos os estados. Isso se repete de forma confiável.
- **Sucesso de HARDWARE** (`pose_advanced`/`sensors_revived`/`operator_moved`):
  depende do robô ter percepção viva. Hoje, com os três sensores em zero, este
  nível **falha por causa do robô**, não do app.

Este teste existe justamente para **provar essa separação a cada execução** — e
gerar um artefato (JSON salvo) comparável entre tentativas e anexável a um
chamado de suporte.
