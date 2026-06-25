# KenMotionBridge

App Android (Kotlin) que roda no **tablet do robô KEN** (CSJBOT família amy, chassi
Slamtec **Slamware**) e faz a ponte entre o **broker MQTT do app web** (HiveMQ Cloud)
e o **RobotSDK** do fabricante, que fala com o chassi via **TCP 1445**.

```
App Web (HiveMQ Cloud, wss/tls)
        │  publica ken/motion/cmd
        ▼
┌─────────────────────────────────────────────┐
│  KenMotionBridge (foreground service)         │
│   MqttManager  ⇄  MotionController  ⇄  SDK    │
│   (Paho TLS)      (rampa/limites)    (Slamware)│
└─────────────────────────────────────────────┘
        │  publica ken/motion/feedback + ken/sensors/telemetry
        ▼  controla            ▼ lê sensores
   chassi Slamware (TCP 1445)
```

## O que ele faz

1. **Conecta** ao mesmo broker do app web (HiveMQ Cloud, TLS) e assina `ken/motion/cmd`.
2. **Traduz** comandos:
   - `{ "type":"joystick", "x":-1..1, "y":-1..1, "speed":0..100, "boost":false }`
     - `y` → linear (frente +/trás −), `x` → angular (girar esq +/dir −)
     - deadzone **0.08**, rampa suave
     - tetos: **V_MAX 0.4 m/s** (boost 0.7), **W_MAX 0.8 rad/s**
   - `{ "type":"stop" }` → `CancelAction()` + zera velocidade
3. **Loop de controle ~20 Hz** com **watchdog 400 ms** (sem comando ⇒ zera velocidade).
4. **Segurança**: lê distância frontal do SDK; se `< 40 cm` e `v>0`, força `v=0`.
   Limites de velocidade são **rígidos no código** (nunca excedem, mesmo com payload ruim).
5. **Feedback** (resolve o "SEM SINAL"):
   - a cada 1 s em `ken/motion/feedback`:
     `{ "online":true, "v":<linear>, "w":<angular>, "front_cm":<dist>, "ts":<epoch> }`
   - republica telemetria nativa (bateria/IMU) em `ken/sensors/telemetry`
     para alimentar a página `/admin/sensores`.
6. Roda como **serviço foreground** (sobrevive à tela apagada, `START_STICKY`,
   wakelock parcial, reinício no boot) e tem uma **tela mínima de status**.

## Estrutura

| Arquivo | Papel |
|---|---|
| `BridgeConfig.kt` | Carrega `assets/bridge_config.json` (+ override `/sdcard/kenmotion/config.json`). |
| `mqtt/MqttManager.kt` | Paho MQTT TLS (`ssl://` ou `wss://`), auto-reconnect, assina/publica. |
| `motion/MotionController.kt` | Deadzone, rampa, limites rígidos, watchdog, segurança frontal. |
| `sdk/SlamwareChassis.kt` | **Única** camada de contato com o RobotSDK (reflexão + binding AIDL). |
| `service/BridgeService.kt` | Foreground service: loop 20 Hz + feedback/telemetria 1 Hz. |
| `MainActivity.kt` | Tela de status (broker, SDK, último comando, v/w/distância/bateria). |
| `aidl/com/csjbot/sdkhandler/*.aidl` | Scaffold do binding `ISdkAppToAar` / `IAarToSdkApp`. |

## Configuração

Edite `app/src/main/assets/bridge_config.json` com os dados do **seu** cluster HiveMQ
Cloud (mesmas credenciais do app web):

```json
{
  "mqtt": {
    "uri": "ssl://SEU-CLUSTER.s1.eu.hivemq.cloud:8883",
    "username": "ken-robot",
    "password": "TROQUE-ME"
  },
  "chassis": { "ip": "192.168.11.1", "port": 1445 }
}
```

- `ssl://HOST:8883` = MQTT/TLS nativo (recomendado no Android).
- `wss://HOST:8884/mqtt` = WebSocket Secure (mesmo broker do navegador).
- Para ajustar no tablet sem recompilar, crie `/sdcard/kenmotion/config.json`
  com o mesmo formato (sobrepõe o asset).

## RobotSDK (dependência do fabricante)

Coloque `RobotSDK_release_i18n_2_4_0_43.aar` em `app/libs/` (ver
`app/libs/PLACE_AAR_HERE.md`). O projeto **compila sem o AAR** (a ponte usa reflexão);
com o AAR presente, liga-se às classes reais (`com.slamtec.slamware.*`) em runtime.

Classes reais usadas: `SlamwareCorePlatform` (handle TCP 1445), `RealTimeVelocity`
(`setLinearVelocity`/`setAngularVelocity`), `MoveDirection`, `cancelAction`,
e o binding AIDL `com.csjbot.sdkhandler.ISdkAppToAar` / `IAarToSdkApp`.

## Build

```bash
cd KenMotionBridge
./gradlew assembleDebug      # gera app/build/outputs/apk/debug/app-debug.apk
# ou release (configure assinatura):
./gradlew assembleRelease
```

Instalar no tablet:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Requisitos: JDK 17, Android SDK (compileSdk 34). minSdk 22.

### CI

`.github/workflows/android-build.yml` gera o APK de debug a cada push e publica como
artifact (`KenMotionBridge-debug-apk`). Se o projeto ficar numa subpasta do repo,
mova o workflow para `<raiz>/.github/workflows/` e ajuste `PROJECT_DIR`.

### Instalação no tablet

Passo a passo (ADB, permissões, autostart, testes MQTT, troubleshooting) em
[`INSTALL_TABLET.md`](INSTALL_TABLET.md).
