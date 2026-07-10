# Relatório técnico — LIDAR, câmera de profundidade e odometria em zero (CT300‑H)

**Robô:** CSJBot CT300‑H (Emy/Ken) · **SDK:** RobotSDK 2.4.0 (núcleo Slamware)
**App de teste:** KenMotionBridge (ponte MQTT ⇄ SlamwareCorePlatform, TCP 1445)
**Data da consolidação:** 2026‑07‑09 · **Atualizado:** 2026‑07‑10 (teste‑oráculo
concluído) · **Status: CAUSA CONFIRMADA no robô (hardware/firmware de
percepção‑navegação), não no cliente/ponte.**

---

## 1. Sumário executivo

O robô movimenta‑se em **ré, esquerda e direita**, mas **não avança para a
frente**. A investigação (reverse engineering do SDK, do app oficial RoboStudio
e testes instrumentados no robô) isolou a causa raiz: **três streams de
percepção reportam zero simultaneamente e de forma persistente**:

1. **LIDAR** — `getLaserScan()` retorna **0 pontos**.
2. **Câmera de profundidade (RGBD)** — `getDepthSensorData()` retorna **0 pontos**.
3. **Odometria / pose** — `getPose()` fica **congelada na origem** (`x≈0.01,
   y≈0.01, yaw≈0°`) durante todo o comando de avanço; `LocalizationQuality = 0%`.

A sessão TCP do chassi está **saudável** (bateria lida, `getDCIsConnected=true`,
comandos aceitos), mas o **pipeline SLAM/percepção atrás dela não publica dado de
sensor algum**. Sem odometria/percepção, o núcleo Slamware mantém qualquer ação
de avanço em `WAITING_FOR_START` (ou aceita e reverte para ele), e nenhuma via de
software — nossa OU da fábrica — consegue mover a frente.

**Paradoxo central a destacar para o suporte:** `getRobotHealth()` reporta
**"OK, sem erros"** ao mesmo tempo em que os três sensores estão zerados. Ou seja,
o firmware **não acusa** LIDAR/câmera desconectados, mas também **não entrega os
dados** deles.

---

## 2. Ambiente e canais confirmados

| Item | Valor observado | Fonte |
|---|---|---|
| IP do chassi | `192.168.99.2` (eth0 `192.168.99.200/24`) | tela diag |
| Porta SDK | `1445` TCP — `OK (rota padrão)` | probe |
| `192.168.99.1:1445` | `RECUSADO (Connection refused)` | probe |
| `192.168.11.1:1445` | `TIMEOUT` | probe |
| Classe SDK ativa | `com.slamtec.slamware.SlamwareCorePlatform` | tela |
| Serviço AIDL bound | `não` (irrelevante — ver §5) | tela |
| Apps CSJBot instalados | **só** `com.csjbot.diningcar` | packageManager |
| Bateria | 87–98 %, `dc_connected=true` | telemetria |
| `getDCIsConnected` | `true` (sessão viva) | SDK |

---

## 3. Evidência dos sensores (constante em todos os testes)

Snapshot representativo (idêntico do início ao fim dos dois vídeos de teste de
2026‑07‑09, em `MODE_BUILD_MAP` com comando de avanço ativo):

```
Último comando : frente(track) → moveTo TRACK 0.5m enviado
v = 0.16 → 0.00 m/s     w = 0.00 rad/s
Pose  : x=0.01  y=0.01  yaw=0°      (NUNCA mudou)
Loc   : 0%
modo  : work=MODE_BUILD_MAP · naviReady=false · moveStates=2
        · mapLoc=false · mapUpd=false
lidar : 0 pts
depth : 0 pts (câmera sem leitura)
saúde chassi : OK (sem erros reportados)
última FRENTE: RUNNING → WAITING_FOR_START
```

Três fatos que, juntos, definem o diagnóstico:

- **lidar 0 + depth 0 + pose congelada** = **falha correlacionada de 3
  subsistemas**. Um cabo de LIDAR solto não congela a odometria; a
  simultaneidade aponta para o serviço de percepção/SLAM inteiro parado, não
  para 3 defeitos físicos independentes.
- **`saúde: OK`** apesar disso ⇒ os latches de erro do firmware não disparam;
  o dado simplesmente não flui.
- **`moveStates=2` (WAITING_FOR_START)** ⇒ o núcleo aceita o comando mas espera
  uma condição de percepção/localização que nunca chega.

---

## 4. O que foi testado e falhou (cronológico)

Cada linha é uma tentativa real feita no robô ou provada por engenharia reversa.
Todas mantiveram `lidar=0 / depth=0 / pose congelada`.

| # | Tentativa | Mecanismo | Resultado |
|---|---|---|---|
| 1 | `moveBy(FORWARD)` direto | `SlamwareCorePlatform.moveBy(MoveDirection.FORWARD)` | ação entra em **WAITING_FOR_START** e não sai; robô parado |
| 2 | Avanço cego por odometria | `moveTo(Location(0.5,0,0), MoveOption{MoveTypeTrack}, 0)` | ação vira **RUNNING** e reverte a **WAITING_FOR_START**; pose não avança (odometria morta) |
| 3 | Modo cego (`blind_move`) | watchdog 300 ms + track | idem #2 — sem odometria não há saída de motor |
| 4 | Primitivo nativo CSJBot | `Robot.moveForward()` → `ChassisReqImpl.move(0)` | mesma cadeia de firmware do #1; sem efeito |
| 5 | Ajuste de velocidade | `setSystemParameter("max_linear_vel", …)`, `setSpeed()` | aceita, mas irrelevante com percepção morta |
| 6 | Rota A — construir mapa | `build_mode` (switchWorkMode MODE_BUILD_MAP) + `begin_map` (clearMap+beginBuildMap) | `mapUpd` fica **false**, `map_cells` não cresce, `lidar=0` — sem pontos não há occupancy grid |
| 7 | Bind AIDL forçado | `rebind` → varredura `com.csjbot.*`/`com.slamtec.*` por `RobotSdkService` | serviço do RobotSDK **não existe** neste tablet (só `diningcar`); e o app oficial nem usa bind p/ mover (§5) |
| 8 | Recalibração RGBD | `startRGBDParamCalibration()` | recupera **depth**, não **lidar**; sem lidar, não destrava |
| 9 | Limpar saúde | `clearRobotHealth(0)` | nada a limpar — `saúde` já reporta OK |
| 10 | Localização | `recoverLocalization(pose, 4×4m)` | sem nuvem de pontos não há como relocalizar; `Loc` fica 0% |

APIs de "velocidade bruta" foram **descartadas por prova**, não por hipótese:
inexistem `setVelocity(vx,vy,vθ)` / `setRealtimeVelocity()` públicos em 5 binários
analisados (RobotSDK‑client.jar, RoboStudio 1.0.8 e 1.0.25, SDK Slamware 2.0.4,
manifest). `getRealTimeVelocity()` é **somente leitura**. O toggle de desvio de
obstáculo só existe na camada UART/serial (`ReqSetObstacleAvoidancePacket`), fora
do alcance do TCP 1445.

---

## 5. O que foi DESCARTADO como causa (com evidência)

| Suspeita | Por que foi descartada |
|---|---|
| Comando de frente errado no nosso app | O teleop oficial da Delivery usa **o mesmo** `moveBy(FORWARD)` (provado em bytecode). O comando está correto. |
| Falta de bind no `RobotSdkService` | O **RoboStudio 1.0.25** (app da fábrica, APK desmontado) conecta **idêntico** — `SlamwareCorePlatform.connect(192.168.99.2)` — e **não binda AIDL** para mover/mapear. `Serviço bound: não` **não é o bloqueador**. |
| Falta de mapa / navigation não pronta | Mapa é **consequência** do LIDAR. Sem nuvem de pontos não há mapa possível — nem no app oficial. |
| Câmera RGBD em uso exclusivo (streamer) | Explicaria `depth=0`, mas **não** `lidar=0` nem a odometria congelada. Não cobre os 3. |
| Firmware acusando erro de sensor | `getRobotHealth()` = **OK, sem erros**; `sensors[]` sem flag de desconexão. |

---

## 6. Análise técnica — por que os 3 zeros apontam para o robô

O `SlamwareCorePlatform` expõe **dois planos** sobre a mesma sessão TCP:

1. **Plano de comando/estado** (vivo): `connect`, `getDCIsConnected`,
   `getBatteryPercentage`, `moveBy`/`moveTo` **aceitam** e retornam `IMoveAction`.
2. **Plano de percepção** (morto): `getLaserScan`, `getDepthSensorData`,
   `getPose`/odometria, `getLocalizationQuality` retornam vazio/origem/0.

Quando **os dois planos** estão vivos, o vídeo oficial de mapeamento mostra a
pose atualizando e a nuvem de pontos do LIDAR renderizando em tempo real ao
empurrar o robô. No nosso robô, **só o plano 1 está vivo**. A conclusão é que o
**subsistema de percepção/SLAM do robô não está em execução** (não subiu no boot,
travou, ou perdeu o barramento dos sensores), enquanto o servidor de comando
Slamware continua respondendo.

Isso explica **todos** os sintomas de uma vez:
- avanço vira `WAITING_FOR_START` (o núcleo espera localização/percepção);
- ré/giros passam (o firmware os trata como manobra segura de curta duração,
  sem exigir percepção);
- `saúde OK` (o supervisor de comando não detecta erro — o dado só não flui).

---

## 7. TESTE‑ORÁCULO CONCLUÍDO (2026‑07‑10) — causa confirmada no robô

Executado exatamente como planejado: instalado o **RoboStudio 1.0.25** (app
oficial do fabricante) no mesmo tablet, conexão **local direta** em
`192.168.99.2` (sem qualquer código nosso no caminho), entrada no modo de
mapeamento (`未命名` / scan mode).

**Resultado, em duas etapas:**

1. **Área de mapa permaneceu vazia** (cinza, sem nuvem de pontos) ao entrar no
   modo de scan — mesmo sintoma do `lidar_pts=0` que a nossa ponte já reportava.
2. **O próprio RoboStudio caiu com o erro nativo:**

   > **断开连接** (Desconectado) — *"您和导航的连接已经断开，请重新登录。"*
   > ("Sua conexão com a navegação foi interrompida, faça login novamente.")

**Por que isso fecha o diagnóstico:** esse popup é gerado pelo **software do
próprio fabricante**, sem nenhuma linha do KenMotionBridge envolvida. O
RoboStudio, conectado localmente ao mesmo robô, **perdeu a sessão com o
subsistema de navegação/SLAM** — a mesmíssima classe de falha observada pela
nossa ponte (percepção que não publica dados). É a terceira confirmação
independente do mesmo problema:

| # | Cliente | Resultado |
|---|---|---|
| 1 | KenMotionBridge (nosso) | `lidar_pts=0`, `depth_pts=0`, pose congelada |
| 2 | RoboStudio 1.0.25 (fábrica) — modo scan | área de mapa vazia, sem nuvem de pontos |
| 3 | RoboStudio 1.0.25 (fábrica) — durante o uso | **desconexão nativa do módulo de navegação** ("断开连接…导航…") |

**Conclusão: a causa está isolada no robô — o serviço de percepção/navegação
(núcleo Slamware/SLAM) está instável ou fora do ar no firmware do CT300‑H.**
Não é bug de cliente, não é falha de protocolo MQTT, não é ausência de bind
AIDL, não é configuração de app. É hardware/firmware do robô.

---

## 8. Ações recomendadas (ordem de custo/risco)

1. ~~Reboot do robô na doca~~ — se ainda não tentado, é o próximo passo mais
   barato antes de escalar (remédio do manual RobotStation §2.4.2). Depois,
   reabrir o RoboStudio OU o KenMotionBridge e checar `lidar_pts` antes de
   qualquer comando.
2. ~~Teste‑oráculo com o app da fábrica~~ — **concluído em 2026‑07‑10, ver §7.**
   Resultado: confirma hardware/firmware, não cliente.
3. **Abrir chamado com a CSJBot / fornecedor** com o texto do §9 — a evidência
   já está completa e não depende mais de nenhum teste adicional do nosso lado.
4. **Inspeção física**, se solicitada pelo suporte: verificar alimentação e
   cabo/USB do RPLIDAR e da câmera de profundidade, e o procedimento de
   reinício do serviço de percepção (SLAM/scan) do CT300‑H.

Só com **`lidar_pts > 0`** (confirmado por qualquer um dos dois apps) faz
sentido retomar o software: `build_mode` → `begin_map` → empurrar/joystick
(ver `map_cells` subir) → `end_map` → `recover_localization` → FRENTE.

---

## 9. Texto para o chamado CSJBot (evidência completa)

> CT300‑H: sessão Slamware (TCP 1445, 192.168.99.2) ativa e respondendo
> (`getDCIsConnected=true`, bateria e comandos OK), porém **LIDAR
> (`getLaserScan`=0 pts), câmera de profundidade (`getDepthSensorData`=0 pts) e
> odometria (`getPose` congelada na origem, `LocalizationQuality`=0%) reportam
> zero simultaneamente e de forma persistente após o boot**. `getRobotHealth()`
> retorna "sem erros" (nenhuma flag de LIDAR/câmera desconectados). Movimentos
> de ré e giro funcionam; qualquer avanço para frente fica preso em
> `WAITING_FOR_START`.
>
> **Teste com o app oficial RoboStudio 1.0.25, no mesmo robô, conexão local
> direta (sem app de terceiros no caminho): (1) o modo de mapeamento não exibe
> nuvem de pontos (área do mapa permanece vazia); (2) o próprio RoboStudio
> reporta desconexão nativa do módulo de navegação ("断开连接 — 您和导航的连接
> 已经断开，请重新登录").** Isso confirma que o problema está no subsistema de
> percepção/navegação (SLAM) do robô, não em nenhum software cliente.
>
> Solicitamos o procedimento de reinício do serviço de navegação/percepção do
> CT300‑H e/ou verificação de hardware do RPLIDAR e da câmera de profundidade.

---

## 10. Recursos de diagnóstico já disponíveis no KenMotionBridge

Para instrumentar os próximos testes sem depender de foto da tela:

- **Diag MQTT** (`ken/motion/feedback`, `type:"diag"`, a cada 2 s): `lidar_pts`,
  `depth_pts`, `pose`, `localization`, `navi_ready`, `work_mode`, `move_states`,
  `robot_health` (flags `lidar_disconnected`/`depth_camera_disconnected`/…),
  `sensors[]` (`getSensorHealthInfoList`: LIDAR_HEALTH/DEPTH_HEALTH/ODOM_HEALTH).
- **Comandos de recuperação** (`ken/motion/cmd`, `type:"chassis_ctl"`):
  `rebind`, `clear_health`, `rgbd_calib`, `wakeup`, `build_mode`, `begin_map`,
  `continue_map`, `recover_localization`.
- **Captura do pipe AIDL** (`adb logcat -s AarMsgCapture`): JSON in/out do
  transporte do fabricante, para comparar com o RoboStudio.
