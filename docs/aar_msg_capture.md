# Captura do pipe AIDL `aarMsgToSDKApp` / `sdkAppMsgToAar`

Objetivo: montar o **dicionário de opcodes reais** que a UI da Delivery troca com o
`RobotSdkService` (com.csjbot.robotsdk.ten), para uma futura iteração poder falar o
mesmo protocolo — sem Frida, só logcat.

## Descoberta estrutural (fecha metade da investigação)

O pipe **não é um `byte[]` opaco**: os dois métodos do contrato AIDL carregam
`String`, e o conteúdo observado até agora é **JSON no padrão coshandler
REQ/NTF/RSP** (mesmo dialeto do `ClientReqProxy`/`ChassisReqImpl` já mapeados por
javap no `RobotSDK-client.jar`):

| Direção | Método AIDL | Interface | Onde logamos |
|---|---|---|---|
| ponte → SDK | `aarMsgToSDKApp(String)` | `IAarToSdkApp` | `SlamwareChassis.sendSdkMessage()` |
| SDK → ponte | `sdkAppMsgToAar(String)` | `ISdkAppToAar` | `SdkCallbackService` (binder) |

Cada mensagem sai no logcat com tag **`AarMsgCapture`**, no formato:

```
<in|out> len=<bytes> b64=<payload em Base64> preview=<primeiros 200 chars>
```

O Base64 preserva o payload íntegro (JSON com aspas/quebras sobrevive ao logcat);
o preview permite triagem a olho.

## Como capturar (no robô)

1. Instalar o APK da ponte com a captura (branch `claude/emy-robotsdk-aar-integration-9z9224`).
2. Em um terminal com o tablet do robô via adb:

   ```bash
   adb logcat -c                       # limpa o buffer
   adb logcat -s AarMsgCapture -v time | tee captura_$(date +%H%M%S).log
   ```

3. Com o log rodando, clicar UM botão conhecido na UI da Delivery por vez e anotar
   o timestamp do clique (parar, andar para frente, iniciar mapeamento, voltar à
   base, cancelar). Um clique = um bloco de mensagens correlacionável.
4. Decodificar cada payload: `echo '<b64>' | base64 -d | python3 -m json.tool`.

> A ponte também emite mensagens `out` próprias quando usa o caminho AIDL
> (fallback da conexão direta). Para isolar o tráfego da Delivery, capture com a
> ponte em modo direto (TCP 1445 conectado) — aí todo `in`/`out` restante é do
> stack do fabricante.

## Dicionário de mensagens (preencher na captura)

Meta de aceite: **≥ 5 mensagens categorizadas**. Template:

| # | Direção | Gatilho na UI | ts | JSON decodificado (resumo) | Opcode/campo-chave |
|---|---|---|---|---|---|
| 1 | out | (ex.: botão PARAR) | | | |
| 2 | out | (ex.: frente no teleop) | | | |
| 3 | in  | (resposta ao #2) | | | |
| 4 | out | (iniciar mapeamento) | | | |
| 5 | in  | (NTF espontânea — heartbeat/estado) | | | |

Para cada linha, colar o JSON completo decodificado em um bloco abaixo da tabela,
com o `b64` original ao lado (evidência bruta).

## Hipóteses a validar com a captura

1. O comando de FRENTE da Delivery via pipe carrega o mesmo `move(int)` do
   `ChassisReqImpl` (direção 0 = frente na tabela NAVI nativa) — ou um opcode
   distinto que contorne o desvio de obstáculo.
2. Existe uma mensagem de "habilitar/desabilitar OA" trafegando no pipe (espelho
   JSON do `ReqSetObstacleAvoidancePacket` UART). Se existir, é a alavanca que
   falta — utilizável sem serial.
3. As NTF espontâneas do SDK incluem o estado de `naviReady`/mapa — confirmação
   independente do diag da ponte.
