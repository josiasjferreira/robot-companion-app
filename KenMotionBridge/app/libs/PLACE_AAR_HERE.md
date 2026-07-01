# Coloque aqui o RobotSDK do fabricante

Copie o arquivo do RobotSDK para esta pasta:

```
RobotSDK_release_i18n_2_4_0_43.aar
```

(ou o `.aar`/`.jar` equivalente extraído do `RobotSDK_release_i18n_2_4_0_43.apk`).

## Onde obter o AAR (fontes verificadas em 2026-07-01)

O AAR **não está disponível publicamente**. Fontes checadas:

- ❌ `github.com/slamtec/robotsdk-android` — **repositório não existe** (404); a
  organização [Slamtec no GitHub](https://github.com/Slamtec) publica apenas SDKs
  de LiDAR/ROS, nenhum AAR Android.
- ❌ Maven Central — `com.slamtec` **não publicado** (404 em
  `repo1.maven.org/maven2/com/slamtec/`).
- ⚠️ `gitlab.csjbot.com` (GitLab público da CSJBot) — hospeda samples de SDK de
  outros modelos (ex.: `open/RobotSDKForYingbin-Android-Sample`, Snowbot), mas
  não o `RobotSDK_release_i18n` da linha Emy/CT300. Vale checar com login ou
  pedir acesso ao suporte CSJBot.
- ✅ **Caminho recomendado:** extrair do próprio robô (o app do SDK já está
  instalado no tablet do Emy) ou solicitar o AAR oficial ao suporte
  CSJBot/Slamtec.

## Como extrair do robô via adb

No tablet do Emy (com depuração USB/ADB habilitada):

```bash
# 1. Localizar o APK do SDK no robô
adb shell pm list packages | grep -i -E 'csjbot|robotsdk'
adb shell pm path com.csjbot.robotsdk.ten        # ajuste ao pacote encontrado

# 2. Puxar o APK
adb pull /data/app/<caminho-retornado>/base.apk robotsdk.apk

# 3. Converter classes.dex -> .jar (o APK não contém um .aar pronto)
d2j-dex2jar robotsdk.apk -o robotsdk-full.jar    # ferramenta dex2jar

# 4. Manter só os pacotes do SDK (evita conflito de classes androidx duplicadas)
mkdir sdk && cd sdk
unzip ../robotsdk-full.jar 'com/slamtec/*' 'com/csjbot/*'
zip -r ../robotsdk-classes.jar com

# 5. Copiar para esta pasta (o build.gradle já inclui *.jar via fileTree)
cp robotsdk-classes.jar KenMotionBridge/app/libs/
```

Observação: se o app-host `com.csjbot.sdkhandler` estiver rodando no robô, a
ponte AIDL funciona sem o AAR; o `.jar` acima só é necessário para a conexão
Slamware direta (`com.slamtec.slamware.*`) dentro do nosso APK.

O `app/build.gradle` já inclui:

```groovy
implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])
```

## Importante

- O projeto **compila e gera APK mesmo sem o AAR** — a camada `SlamwareChassis.kt`
  acessa o SDK por reflexão. Sem o AAR, a ponte sobe normalmente mas o chassi
  reporta `SDK: DESCONECTADO` (nenhuma classe `com.slamtec.*` em runtime).
- Com o AAR presente, a ponte se liga às classes **reais**
  (`com.slamtec.slamware.*`) em runtime.
- Se o AAR trouxer arquivos `.aidl` oficiais para `com.csjbot.sdkhandler`,
  **substitua** os scaffolds em `app/src/main/aidl/com/csjbot/sdkhandler/` pelos
  do fabricante (o descriptor do Binder precisa bater).
