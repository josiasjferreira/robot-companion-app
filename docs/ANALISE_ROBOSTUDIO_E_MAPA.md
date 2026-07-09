# Análise dos anexos: RobotStation (PDF) + RoboStudio 1.0.25 (APK) + vídeo de mapeamento

Consolidação do que os três materiais oficiais provam sobre o bloqueio da FRENTE
e sobre o `lidar: 0 pts`. Método: PDF lido, APK `CsjRobotStudio_V1.0.25.apk`
desmontado (strings dos 3 `classes.dex`), vídeo amostrado em frames.

## 1. O que o app OFICIAL faz — e como ele se conecta

**RoboStudio 1.0.25 conecta EXATAMENTE como o KenMotionBridge:**

- `com.slamtec.slamware.SlamwareCorePlatform.connect(...)` direto no IP `192.168.99.2`
  (strings `robotPlatform connect`, `EVENT_CONNECT_SUCCESS`, `192.168.99.2`).
- **NÃO binda um serviço AIDL para o movimento core** — só existe um `CusAidlService`
  auxiliar. O controle de chassi/mapa é TCP direto, igual ao nosso Caminho A.

**Consequência direta:** o `Serviço bound: não` da nossa tela **não é o
bloqueador da FRENTE**. O app da fábrica também não binda e mapeia normalmente.
O bind AIDL segue útil (é o que inicializa periféricos CSJBot e o `chassis_ctl`),
mas não é o que falta para o LIDAR entregar pontos.

## 2. Fluxo oficial de mapeamento (PDF + vídeo) = o que já implementamos

| Passo oficial (PDF §2.4 / vídeo) | Equivalente no KenMotionBridge |
|---|---|
| "Enter scanning mode" / `setMappingMode(MODE_BUILD_MAP)` | `chassis_ctl build_mode` (switchWorkMode MODE_BUILD_MAP) |
| Diálogo "Create an empty map" (frame 010) | `begin_map` = clearMap + beginBuildMap |
| "Continue drawing" (frame 010) | **novo:** `continue_map` = startContinueBuildMap |
| "Slide the joystick... move forward" / empurrar o robô (frames 011, 014) | joystick `y=1` / empurrar fisicamente |
| Pose e nuvem de pontos crescendo na tela (frame 011: `[-0.03,0.00,0.19]`) | diag `pose` + `lidar_pts`/`map_cells` |
| "Save Map" + nome (PDF §2.4.4) | `end_map` = setMapUpdate(false) + saveMap |

**Nada no fluxo oficial usa uma API secreta.** É o mesmo conjunto de chamadas
Slamware que já temos. O vídeo mostra o método físico: colocam o celular com
RoboStudio na bandeja e **empurram o robô** ("We have to push the Robot to
complete your map", "try to make 360°").

## 3. A prova visual do que é um sistema SAUDÁVEL (frame 011)

No vídeo, durante o scan:

- **pose atualiza** em tempo real (`[-0.03, 0.00, 0.19]` no canto);
- **nuvem de pontos do LIDAR renderiza** (traços brancos = paredes detectadas);
- **mapa cresce** conforme empurram.

O nosso robô, no mesmo estado de "modo build + andando", mostra o oposto exato:
`lidar: 0 pts`, `depth: 0 pts`, `Loc: 0%`, `pose x=0 y=0` estático,
`naviReady=false`. **Mesmo app-classe, mesma conexão, resultado oposto** ⇒ a
diferença está no ROBÔ (percepção), não no cliente.

## 4. Veredito sobre o `lidar: 0 pts`

Cadeia de eliminação, agora fechada com evidência dos anexos:

1. ❌ Não é o cliente/ponte — RoboStudio conecta igual e depende do mesmo
   `getLaserScan`. Sem pontos, ele também não mapeia.
2. ❌ Não é o bind AIDL — o app oficial não binda para mover/mapear.
3. ❌ Não é falta de mapa — mapa é *consequência* do LIDAR, não causa. Sem nuvem
   de pontos não há occupancy grid (o próprio manual, §3, é todo sobre garantir
   que o radar enxergue).
4. ✅ **É a percepção do robô que não está entregando dados.** Com
   `saúde: OK (sem erros)` + `lidar: 0 pts`, dois cenários restam:
   - **(a) LIDAR físico/energia** — cabo/USB do RPLIDAR solto, módulo sem
     alimentação, ou girando mas sem dados. O `sensors[]` do diag (LIDAR_HEALTH)
     e o `getSensorHealthInfoList` desambiguam.
   - **(b) serviço de percepção do robô parado** — o núcleo Slamware/scan não
     subiu no boot. Remédio conhecido do próprio manual (§2.4.2): **desligar o
     robô, encostar na doca e reiniciar**; um mapa "torto" ou scan ruim é sinal
     disso.

## 5. Alavancas de recuperação que o app oficial tem — agora portadas

Extraídas do RoboStudio (assinaturas confirmadas por javap) e adicionadas ao
`chassis_ctl`:

| Ação MQTT | Método Slamware | Para quê |
|---|---|---|
| `clear_health` | `clearRobotHealth(0)` | limpa latches de erro presos que travam navegação/motor |
| `rgbd_calib` | `startRGBDParamCalibration()` + estado | recalibra a câmera de profundidade (recupera `depth`; RoboStudio tem `COLOR_SPACE_UNCALIBRATED`/`CALI_STATE_*`) |
| `continue_map` | `startContinueBuildMap()` | retomar mapa existente ("Continue drawing" do vídeo) |

Observação honesta: `rgbd_calib` recupera **depth**, não **lidar**. Se
`lidar_pts` seguir 0 após `clear_health` + reboot, o problema é físico no LIDAR
e nenhuma dessas alavancas resolve — é troca de cabo/módulo (suporte CSJBot).

## 6. Plano de ação priorizado (com os anexos em mãos)

1. **Reboot físico do robô na doca** (remédio §2.4.2 do manual). Depois: abrir o
   KenMotionBridge e ler `lidar_pts` no diag ANTES de qualquer comando.
2. Se `lidar_pts` ainda 0: `chassis_ctl clear_health` → esperar 10 s → reler.
3. **Teste cruzado decisivo:** instalar o próprio **RoboStudio 1.0.25** (o APK
   anexado) no tablet, conectar em `192.168.99.2`, entrar em scan mode e empurrar
   o robô. Se o RoboStudio TAMBÉM mostrar 0 pontos / mapa vazio ⇒ **prova
   definitiva de hardware** (o app da fábrica falha no mesmo robô) ⇒ suporte
   CSJBot com evidência irrefutável. Se o RoboStudio mostrar pontos e nós não ⇒
   aí sim há algo no nosso cliente a corrigir (e teremos o `AarMsgCapture` para
   comparar).
4. Só com `lidar_pts > 0`: `build_mode` → `begin_map` → empurrar/joystick →
   `map_cells` sobe → `end_map` → `recover_localization` → FRENTE.

O passo 3 é o teste que fecha o caso de uma vez: usa o app do próprio fabricante
como oráculo no mesmo hardware.
