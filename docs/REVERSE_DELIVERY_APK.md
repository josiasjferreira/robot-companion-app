# Engenharia reversa — movimento "cego" (odometria) no chassi Slamware do Emy CT300

Objetivo: replicar na ponte KenMotionBridge o "andar para frente cego" (sem
esperar câmera / sem obstacle avoidance) que o app **Delivery_i18n_amy** usa.

Fonte primária desta análise: **`RobotSDK-client.jar`** (classes extraídas do
APK oficial `RobotSDK_release_i18n_2.4.0_43` do próprio robô), que embute o
**mesmo SDK Slamware `com.slamtec.slamware`** e o **mesmo middleware CSJBot
`com.csjbot.coshandler` / `com.csjbot.cosclientng`** que o Delivery usa.
Método: `javap -c -p` (desmontagem de bytecode) em cada classe do caminho de
movimento. Nada aqui é suposição — cada afirmação tem a classe/método de origem.

---

## 1. O caminho de movimento REAL do stack do fabricante

```
Operador (app)  →  com.csjbot.coshandler.core.Robot
                     .moveForward()/moveBack()/moveLeft()/moveRight()
                 →  ClientReqProxy.move(int)          [0=frente,1=ré,2=esq,3=dir]
                 →  ChassisReqImpl.move(int)
                        cmd = "NAVI_ROBOT_MOVE_REQ", arg "direction"=int
                 →  BaseClientReq.sendReq(json)
                 →  CosClientAgentNG.getRosClientAgent().sendMessage(json)   ◀── TRANSPORTE
                 →  (o RosClientAgent entrega ao núcleo Slamware)
                 →  com.csjbot.slamagent.slamware.action.SlamAction (handler do REQ)
                 →  AbstractSlamwarePlatform.moveBy(MoveDirection)   ◀── EXECUÇÃO
```

Evidências (bytecode):
- `Robot.moveForward` → `ClientReqProxy.move(0)` (`iconst_0; invokevirtual move:(I)V`).
- `ChassisReqImpl.move(int)`: `ldc "NAVI_ROBOT_MOVE_REQ"; ldc "direction"; getJson(...); sendReq(...)`.
- `BaseClientReq.sendReq`: `... CosClientAgentNG.getRosClientAgent()` → `sendMessage`.
- `SlamAction` (handler de `NAVI_ROBOT_MOVE_REQ`): `tableswitch` sobre
  `MoveDirection.ordinal()` → `AbstractSlamwarePlatform.moveBy(dir)`.

**Ponto crítico:** o comando de movimento do fabricante trafega pelo
**`CosClientAgentNG`** (agente "ROS" NG — transporte serial/UART interno do robô,
inicializado por `createRosClientAgent(context, listener, …)` no boot do stack).
Esse agente **não existe no processo da nossa ponte** — nós só temos a conexão
TCP 1445 direta ao Slamware. Por isso `Robot.move*()` chamado de fora é no-op:
`getRosClientAgent()` retorna null. Confirmado.

---

## 2. O caminho que NÓS temos (TCP 1445 direto) e por que a FRENTE trava

`SlamwareCorePlatform.connect(ip,1445)` → `SlamwareSdpPlatform` (via `CsjSlamCore`).
Primitivos de movimento REAIS deste build (SDK `com.slamtec.slamware`, versão do
RobotSDK 2.4.0):

| Método (SlamwareSdpPlatform) | Comando SDP | Percepção exigida |
| --- | --- | --- |
| `moveBy(MoveDirection)` | `move_by {direction}` | **SIM — OA frontal** |
| `moveTo(Location, MoveOption, yaw)` | `move_to_pose` / `move_to_precisely` | SIM — pose/mapa |
| `rotate(Rotation)` / `rotateTo` | rotação no eixo | não |
| `getRealTimeVelocity()` | leitura | — (somente leitura) |

Mapa de direção (`MoveDirection`, ordem do enum → `ordinal()`):
`BACKWARD=0, FORWARD=1, TURN_LEFT=2, TURN_RIGHT=3`.

Por que **ré e giros andam, mas a frente não**: `moveBy(FORWARD)` é um movimento
com **obstacle avoidance** — o firmware espera a câmera de profundidade frontal
(RGBD) liberar o caminho. Na tela: `depth: 0 pts (câmera sem leitura)` →
a liberação nunca chega → a ação fica em `WAITING_FOR_START` para sempre.
Ré e giros não passam por essa checagem frontal.

---

## 3. Achado decisivo: este SDK NÃO tem "velocidade bruta"

Busca exaustiva no jar inteiro:
- **Nenhum** `setVelocity`, `setRealTimeVelocity`, `setRealtimeVelocity`,
  `publishVelocity`, `sendVelocity`, `directDrive`, `blindMove`, `deadReckoning`.
- `RealTimeVelocity` tem `setLinearVelocity/setAngularVelocity` mas é só um
  **DTO de leitura** (retornado por `getRealTimeVelocity()`); não há caminho para
  empurrá-lo ao chassi.
- `getMotion_mode()` existe mas é **read-only** (não há `setMotionMode`).
- `MoveByReqBean$…$OptionsBean` tem `flags: List` + `fail_retry_count`, mas
  **nenhuma string de flag** de "desliga OA" aparece no core (as flags visíveis
  são só de mapa: `load_map`, `upload_map_data`, `move_to_precisely`…).

Também tentado e confirmado sem efeito nos testes de campo:
- `moveTo((dist,0,0), MoveOption{MoveTypeTrack}, 0)` → a ação vai a `RUNNING`
  (`moveStates=2`, `última FRENTE: RUNNING`) **mas as rodas não giram** — sem
  localização/mapa o alvo é degenerado e o núcleo não gera trajetória de motor.

**Conclusão:** o "andar cego por velocidade" do Delivery **não está exposto na
versão do SDK Slamware (2.4.0) embutida no RobotSDK do robô.** Ele vem por um de
dois caminhos que a nossa ponte, conectada só ao TCP 1445, não alcança:
1. o **transporte NG serial** (`CosClientAgentNG`) do stack do fabricante; ou
2. uma **versão mais nova do SDK Slamware** (≥ 2.6/4.x) que exponha
   `setRealtimeVelocity`/RTV push — o Delivery é V5.4.3, muito mais novo que o
   RobotSDK 2.4.0, e provavelmente embute um SDK Slamware mais recente com essa API.

---

## 4. O que falta para fechar a RE (precisa das classes do Delivery)

Para confirmar qual das duas rotas o Delivery usa e replicar exatamente, é
necessário abrir o **`Delivery_i18n_amy_V5.4.3_255.apk`**. O ambiente desta
sessão não consegue baixá-lo (162 MB; hosts do Google Drive bloqueados pelo proxy;
o canal MCP do Drive devolve base64 ao contexto, inviável nesse tamanho).

Comandos para extrair a resposta no SEU PC (com o APK em mãos) — colar a saída aqui:

```bash
# 1) versão do SDK Slamware que o Delivery embute
jadx -d out Delivery_i18n_amy_V5.4.3_255.apk
grep -rn "setRealtimeVelocity\|setRealTimeVelocity\|RealTimeVelocity\|moveByVelocity\|setVelocity" out/sources/com/slamtec/ | head

# 2) como o controle manual dispara "frente"
grep -rn "moveForward\|manualControl\|joystick\|blind\|deadReck\|setSpeed\|move_by\|move_to" \
     out/sources/com/csjbot/ | grep -i "manual\|joystick\|move\|drive" | head -40

# 3) empacotar SÓ as classes do SDK Slamware (pequeno) e me enviar via Drive:
cd out/sources && zip -r ~/slamware_delivery.zip com/slamtec/slamware
```

Se o passo (1) listar um `setRealtimeVelocity`/RTV push, é a rota 2 — e a
solução é dropar esse AAR/SDK mais novo em `app/libs/` e chamar esse método
(implemento em minutos). Se não, é a rota 1 (transporte NG serial), que exige
subir o `CosClientAgentNG` com acesso à porta serial — inviável sem permissão
de sistema, e o caminho passa a ser rodar o app do fabricante em paralelo.

---

## 5. Alternativa oficial (sem depender do APK do Delivery)

A SLAMTEC publica o **Slamware Android SDK 4.x** (developer.slamtec.com), que nas
versões ≥ 2.6 expõe controle de **velocidade em tempo real**. Baixar esse AAR,
colocar em `KenMotionBridge/app/libs/` e usar o push de velocidade é o caminho
mais limpo e suportado para o "modo cego" — sem engenharia reversa de um APK de
terceiros.

---

## 6. Confirmação pelo AndroidManifest (upload decompilado de 08/07)

Lido o `AndroidManifest.xml` do decompilado enviado (pasta Drive). É o app
**`com.csjbot.robotsdk.ten`**, `i18n_2.4.0` — o **RobotSDK** (o mesmo que já
analisamos), NÃO o Delivery. Evidências extraídas do manifest:

- Serviços do stack: `com.csjbot.robotsdk.service.RobotSdkService`,
  **`com.csjbot.coshandler.service.CameraService`** (dirige a câmera),
  `HandlerMsgService`, `com.aidlagent.AIDLClientService`.
- Atividade **`com.csjbot.cosclientng.test_ui.SerialPortTestAcitivity`** →
  confirma que o `cosclientng` fala com o chassi por **PORTA SERIAL** (o
  transporte NG que a nossa ponte não alcança).
- Permissões/recursos: `android.hardware.usb.host`, `USB_DEVICE_ATTACHED`,
  `android.hardware.camera`, `camera.front` → **a câmera de profundidade é um
  dispositivo USB**, transmitido pelo `CameraService` do app do fabricante.
- `lib/armeabi-v7a`: libzstd-jni, libmmkv, libssl, Microsoft Speech, ffavc —
  **nenhuma** `.so` de motor/velocidade; motion é 100% via protocolo (não nativo).

**Conclusão reforçada:** a percepção frontal (`depth`) é alimentada pelo
**`CameraService` (USB) do app do fabricante**. Sem esse app rodando (ou com a
câmera USB desconectada), `depth: 0 pts` e o `moveBy(FORWARD)` OA nunca libera.
O "andar cego por velocidade" continua ausente nesta versão 2.4.0; só existe no
Delivery V5.4.3 (mais novo) ou num Slamware SDK ≥ 2.6.

### Dois caminhos concretos para FECHAR (escolher um)

1. **Ligar a percepção do fabricante (frente funciona com o que já temos):**
   instalar E manter rodando o app `com.csjbot.robotsdk.ten` (ou o `diningcar`),
   com a **câmera USB conectada**. O `CameraService` alimenta o chassi → `depth`
   ganha pontos → `moveBy(FORWARD)` libera → a frente anda pelo joystick atual.
   (É a via suportada; não é "cego", mas é a que o hardware exige.)

2. **Modo cego real (velocidade bruta):** obter o SDK Slamware mais novo com push
   de velocidade — extrair `com/slamtec/slamware` do **Delivery_i18n_amy_V5.4.3**
   (procurar `setRealtimeVelocity`/RTV) OU baixar o **Slamware Android SDK 4.x**
   oficial. Dropar o AAR em `app/libs/` e implementar `blindDrive(v,w)` com esse
   método. (Atende ao requisito de andar sem câmera/mapa.)
