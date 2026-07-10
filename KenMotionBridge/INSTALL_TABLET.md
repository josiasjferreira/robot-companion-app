# Instalação no tablet do KEN

Guia para instalar e deixar o **KenMotionBridge** rodando 24/7 no tablet do robô.

## 1. Gerar / obter o APK

- Via CI: baixe o artifact `KenMotionBridge-debug-apk` da aba **Actions** (workflow
  `Build APK`); ou
- Localmente:
  ```bash
  cd KenMotionBridge
  ./gradlew assembleDebug
  # -> app/build/outputs/apk/debug/app-debug.apk
  ```

> Antes de buildar: coloque `RobotSDK_release_i18n_2_4_0_43.aar` em `app/libs/` e
> ajuste `app/src/main/assets/bridge_config.json` (host/porta/usuário/senha do HiveMQ).

## 2. Instalar via ADB

```bash
adb connect <IP_DO_TABLET>:5555      # se for via rede; ou conecte por USB
adb install -r app-debug.apk
adb shell am start -n br.com.onlife.kenmotionbridge/.MainActivity
```

## 3. Conceder permissões e travar em foreground

No tablet (Android 13+), ao abrir o app aceite a permissão de **notificações**
(necessária para o serviço foreground). Depois, garanta que o sistema não mate o serviço:

1. **Configurações → Apps → KenMotionBridge → Bateria → Sem restrições**
   (desativar otimização de bateria / "permitir atividade em segundo plano").
2. **Inicialização automática** (em ROMs CSJBot/chinesas costuma haver um menu
   *Autostart* / *Inicialização automática*): habilite para o KenMotionBridge.
3. Opcional — fixar o app na tela (Tela fixa / Pin) para evitar fechamento acidental.

O serviço já usa `START_STICKY` + wakelock parcial + `BootReceiver` (reinicia no
boot), então sobrevive à tela apagada e a reinícios.

## 4. Verificar funcionamento

Na tela do app você deve ver:

```
Broker MQTT: CONECTADO ✅
SDK / Chassi: CONECTADO ✅      (precisa do AAR + chassi acessível em TCP 1445)
Serviço: RODANDO
```

Teste de ponta a ponta:

```bash
# publique um comando de joystick no MESMO broker (exemplo com mosquitto_pub/TLS)
mosquitto_pub -h SEU-CLUSTER.s1.eu.hivemq.cloud -p 8883 \
  -u ken-robot -P SENHA --capath /etc/ssl/certs \
  -t ken/motion/cmd -m '{"type":"joystick","x":0,"y":0.5,"speed":50}'

# pare
mosquitto_pub ... -t ken/motion/cmd -m '{"type":"stop"}'
```

> **Atenção ao watchdog (400 ms):** um publish único faz o robô apenas *tentar*
> andar por ~0,4 s e parar. Para movimento sustentado, republique o comando a
> cada ~200 ms — ou use o atalho `scripts/drive-test.sh` (raiz do repo), que lê
> as credenciais do `.env`, republica a 5 Hz e envia `stop` ao final:
>
> ```bash
> ../scripts/drive-test.sh frente 5 30   # frente por 5 s a 30% (Linux/macOS)
> ../scripts/drive-test.sh feedback      # acompanha feedback + telemetria
> ```
>
> No Windows: `..\scripts\drive-test.ps1 frente 5 30`.

Acompanhe o feedback (resolve o "SEM SINAL"):

```bash
mosquitto_sub -h SEU-CLUSTER... -p 8883 -u ken-robot -P SENHA --capath /etc/ssl/certs \
  -t 'ken/motion/feedback' -t 'ken/sensors/telemetry'
```

## 5. Ajustes sem recompilar

Crie `/sdcard/kenmotion/config.json` (mesmo formato do asset) para sobrescrever
broker/credenciais/limites direto no tablet:

```bash
adb push config.json /sdcard/kenmotion/config.json
adb shell am force-stop br.com.onlife.kenmotionbridge
adb shell am start -n br.com.onlife.kenmotionbridge/.MainActivity
```

## Troubleshooting

| Sintoma | Causa provável | Ação |
|---|---|---|
| `Broker: DESCONECTADO` | URI/credencial errada, ou TLS | Confira `mqtt.uri` (`ssl://host:8883`) e usuário/senha; veja `adb logcat -s MqttManager` |
| `SDK: DESCONECTADO` | AAR ausente ou chassi inacessível | Confira `app/libs/*.aar` e ping no `chassis.ip:1445`; veja `adb logcat -s SlamwareChassis` |
| Robô não anda | sem `front_cm` e obstáculo, ou método de velocidade diverge | `adb logcat -s SlamwareChassis MotionController` e ajuste `sdk/SlamwareChassis.kt` |
| Serviço morre com tela apagada | otimização de bateria ativa | Passo 3 acima |
