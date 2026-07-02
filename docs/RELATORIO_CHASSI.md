# Relatório — Rota de acesso ao chassi KEN e comunicação com o robô

**Projeto:** KenMotionBridge (ponte Android: MQTT ⇄ RobotSDK/chassi Slamware)
**Data:** 2026-06-24
**Status:** rota de rede ao chassi **CONQUISTADA**; bloqueio restante isolado na camada de **protocolo do SDK Slamware**.

---

## 1. Resumo executivo

Depois de uma sequência de testes instrumentados no tablet do robô, com um painel de
**diagnóstico de redes + sondas TCP** embutido no app, chegamos a conclusões firmes:

- ✅ **A porta do chassi `192.168.99.2:1445` está ABERTA e é ALCANÇÁVEL** (provado por sonda TCP L4
  direta — `chassi 1445: eth=OK default=OK`).
- ✅ **A rota CONFIÁVEL ao chassi é o Wi-Fi `RoboKen_Controle`** (rede do próprio robô, `192.168.99.x`).
- ⚠️ **O cabo `eth0` (USB-Ethernet) é INTERMITENTE** — em alguns testes alcança o chassi, em outros
  dá `Network is unreachable`, mesmo com IP/prefixo corretos (`192.168.99.200/24`).
- ✅ **O broker MQTT (HiveMQ Cloud) funciona** quando há internet (hotspot do celular): `broker wlan=OK`.
- ❌ **Bloqueio atual:** `com.slamtec.slamware.SlamwareCorePlatform.connect()` lança
  `ConnectionFailException: Connection Failed` **mesmo com a porta 1445 acessível** → é
  **handshake/protocolo do SDK**, não roteamento.

**Tradução:** já sabemos *como chegar* no chassi (rede resolvida). Falta *falar a língua dele*
(a conexão de protocolo Slamware/SDP está sendo recusada apesar do TCP abrir).

---

## 2. Topologia de rede confirmada (dados reais do tablet)

O tablet (Android, **sem chip SIM**) tem várias interfaces simultâneas. Inventário real coletado:

| Interface | Exemplo de IP | Sub-rede | Tem internet? | Papel |
|-----------|---------------|----------|---------------|-------|
| `eth0` (cabo / USB-Ethernet) | `192.168.99.200/24` | rede do chassi | ❌ não (val=OK é **falso**) | chassi (instável) |
| `wlan0` = **RoboKen_Controle** | `192.168.99.199/24` | rede do chassi | ❌ não | **chassi (confiável)** |
| `wlan0` = hotspot **"teste"** | `192.168.121.203/24` | internet do celular | ✅ sim | internet/MQTT |
| USB tethering (potencial) | `rndis0` | internet do celular | ✅ sim | internet/MQTT (alternativa) |

> **Importante:** tanto `eth0` quanto `RoboKen_Controle` ficam na **mesma sub-rede do chassi
> (`192.168.99.x`) e NÃO têm internet**. A internet só existe pelo **hotspot do celular** (ou
> tethering USB). Como o tablet só conecta a **um** Wi-Fi por vez, ter chassi **e** internet ao
> mesmo tempo exige duas interfaces distintas (ex.: Wi-Fi no robô + USB tethering p/ internet).

### O problema clássico de "dual-homing"
O Android elege **uma** rede padrão e manda todo socket não-amarrado por ela. Quando o Wi-Fi
(internet) é o padrão, a tabela de rotas dele **não** contém `192.168.99.0/24` → o socket do
RobotSDK não chega no chassi. A solução aplicada (e validada) foi **amarrar o processo à rede do
chassi** (`bindProcessToNetwork`) e tratar a internet (MQTT) à parte.

---

## 3. Conquistas (o que já está resolvido)

1. **Diagnóstico de redes no app** — painel "Redes (diagnóstico)" lista todas as interfaces
   (transporte, internet/validada, iface, endereços **com prefixo**) e roda **sondas TCP L4** ao
   chassi e ao broker, separando rota/porta/DNS/internet. Isso transformou "adivinhação" em fatos.

2. **Seleção de rede dirigida por dados** — o app identifica a rede do chassi pela **sub-rede**
   (não por tipo de transporte, que é ambíguo aqui) e, entre as candidatas, **escolhe a que
   realmente responde** na porta 1445 agora.

3. **Bind do processo ao chassi** (`bindProcessToNetwork`) — confirmado funcionando para sockets
   Java: `chassi 1445: default=OK`. Re-amarra a cada reconexão (lida com `eth0` oscilando).

4. **Prova de alcance L4 ao chassi** — `192.168.99.2:1445` aceita conexão TCP quando o tablet está
   na `RoboKen_Controle`. **A rede está resolvida.**

5. **Broker MQTT operacional** — `ssl://...hivemq.cloud:8883` conecta quando há internet
   (`broker wlan=OK`). O elo **web ⇄ ponte** (MQTT) está saudável.

6. **Exceção crua do SDK exposta** — em vez de mascarar como "sem rota", o app mostra a cadeia
   real: `InvocationTargetException ← ConnectionFailException: Connection Failed`. Foi isso que
   permitiu separar "rede" de "protocolo".

---

## 4. O bloqueio atual (isolado)

```
SDK / Chassi: DESCONECTADO
↳ SDK: InvocationTargetException ← ConnectionFailException: Connection Failed.
→ chassi 1445: eth=OK  default=OK        (porta ACESSÍVEL)
```

`SlamwareCorePlatform.connect("192.168.99.2", 1445)` **abre o TCP mas o handshake do protocolo
Slamware (SDP) é recusado**. Hipóteses prováveis, em ordem:

1. **O chassi só aceita UMA conexão SDP por vez** e o serviço do fabricante
   (`RobotSdkService`/`robot_studio`) já está conectado → nossa conexão direta é recusada.
   *(Indício: no app, "Serviço bound: não" — nunca usamos a via AIDL do fabricante.)*
2. **A comunicação correta é via o serviço do fabricante (AIDL `RobotSdkService`)**, não direta.
3. **Porta 1445 aberta por outro serviço** (não o SDP esperado), ou versão de firmware/SDK
   incompatível com o AAR embarcado.

---

## 5. Rotas e dados já conquistados (referência para reuso — NÃO re-derivar)

> Estes são fatos verificados em hardware. Qualquer próxima tentativa (inclusive geração via
> Lovable) deve **partir daqui**.

### Chassi (camada local)
- **Endereço/porta:** `192.168.99.2:1445` (TCP) — **porta aberta e alcançável** ✔
- **Rota confiável:** tablet conectado ao **Wi-Fi `RoboKen_Controle`** (`192.168.99.x`, sem internet)
- **Rota instável:** cabo `eth0` (`192.168.99.200/24`) — evitar como único caminho
- **SDK direto:** `com.slamtec.slamware.SlamwareCorePlatform.connect(ip, port)` → **recusado no
  handshake** (não usar como única via)
- **Via do fabricante (a explorar):** serviço AIDL
  - ação: `com.csjbot.robotsdkservice.startservice`
  - pacote/serviço: `com.csjbot.robotsdk.ten` / `com.csjbot.robotsdk.service.RobotSdkService`
  - interfaces: `com.csjbot.sdkhandler.ISdkAppToAar` / `IAarToSdkApp`

### Internet / MQTT (camada nuvem)
- **Broker:** `ssl://bc0a5bf3bb994fdaa6dddc72096bb220.s1.eu.hivemq.cloud:8883` (Paho nativo)
  - navegador/web usa `wss://...:8884/mqtt`
- **Usuário:** `kenrobot` (mesmas credenciais do app web)
- **Fonte de internet do tablet:** hotspot do celular (Wi-Fi `teste`) **ou** tethering USB
- **Tópicos (contrato atual):**
  - `ken/motion/cmd` (web→ponte): `{type:"joystick",x,y,speed,boost}` · `{type:"stop"}` ·
    `{type:"chassis",action,speed,angle,durationMs}`
  - `ken/motion/feedback` (ponte→web): `{online,v,w,front_cm,ts}`
  - `ken/sensors/telemetry` (ponte→web): `{battery,charging,pose{x,y,yaw_deg},imu{...},localization,ts}`

### Comportamento de rede do Android (aprendido)
- `bindProcessToNetwork` é **por-processo** e funciona para sockets Java (sondas confirmam).
- `Network.getAllByName` + `Network.socketFactory` permitem **DNS e socket escopados** por rede.
- `NET_CAPABILITY_VALIDATED`/`INET` da Ethernet do robô pode ser **falso-positivo** (mostra "OK"
  sem internet real) — confiar na **sonda de broker**, não na flag.

---

## 6. Sugestões para a comunicação com o robô (para o Lovable / próximos passos)

O elo **web (Lovable) ⇄ broker MQTT ⇄ ponte** já funciona. O elo que falta é **ponte ⇄ chassi**.
Abaixo, caminhos concretos aproveitando o que já foi conquistado.

### A. Resolver o chassi pela via do fabricante (recomendado — maior chance)
Como o TCP abre mas o SDP direto é recusado, o caminho mais provável é **falar com o
`RobotSdkService` (AIDL)** do fabricante, que provavelmente é o dono da conexão com o chassi.
- Priorizar o **bind AIDL** (Abordagem 2) e descobrir o **descriptor real** da interface
  (`ISdkAppToAar`) — hoje o handshake AIDL não casa.
- Extrair o **AAR/serviço do `robot_studio.apk`** (que comprovadamente fala com o chassi) e
  espelhar exatamente a sequência de init/connect que ele usa.

### B. Confirmar o protocolo na porta 1445 (diagnóstico de protocolo)
Já que a porta abre, descobrir **o que** responde:
- Capturar o tráfego do `robot_studio` (que funciona) e comparar o handshake.
- Testar se o chassi limita a **1 conexão SDP** (fechar o app do fabricante e tentar a conexão
  direta isolada).

### C. Arquitetura de rede para chassi + internet ao mesmo tempo
Para a ponte ter **chassi e nuvem** simultaneamente, sem brigar com o roteamento:
- **Recomendado:** Wi-Fi do tablet em **`RoboKen_Controle`** (chassi) **+ tethering USB** do celular
  (internet). Duas interfaces estáveis e independentes.
- **Evitar** depender do cabo `eth0` como único caminho (intermitente).
- **MQTT em processo separado** (`android:process=":mqtt"`) **amarrado à rede de internet**, enquanto
  o processo principal fica amarrado ao chassi — assim cada processo usa a rota certa sem hacks de
  socket. (Solução limpa para o dual-homing; pendente de implementação.)

### D. O que o Lovable (app web) pode assumir como pronto
- **Pode** continuar publicando comandos e lendo telemetria pelos tópicos MQTT acima — o contrato
  está estável e a ponte os processa.
- **Não** precisa saber de IP/rede do robô: a ponte abstrai o chassi. O web fala só com o broker.
- Quando o elo ponte⇄chassi fechar (via A/B), **nada muda no web** — telemetria e feedback passam a
  fluir nos mesmos tópicos. Vale o web já tratar:
  - estado `online:false` (ponte sem chassi) com aviso amigável;
  - ausência de telemetria (campos vazios) sem quebrar a UI;
  - eco de `feedback` para confirmar recebimento de comandos.

### E. Plano B de comunicação (se o SDK continuar recusando)
- Verificar se o chassi Slamware expõe **API REST/HTTP** (Slamware costuma ter REST em outra porta)
  — a ponte poderia usar HTTP em vez do SDP binário.
- Implementar um **cliente mínimo do protocolo SDP** sobre o socket TCP (já comprovadamente aberto),
  replicando só os comandos necessários (velocidade/parada/telemetria).

---

## 7. Próxima ação sugerida (quando retomar)
1. Fixar a rede em **`RoboKen_Controle` + tethering USB** (chassi confiável + internet).
2. Atacar o **bloqueio de protocolo** pela via **A** (AIDL `RobotSdkService`) e/ou **B** (captura do
   `robot_studio`).
3. Em paralelo, mover o **MQTT para processo separado** para destravar o broker durante o bind ao chassi.
