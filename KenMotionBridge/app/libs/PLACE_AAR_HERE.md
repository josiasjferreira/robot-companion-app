# Coloque aqui o RobotSDK do fabricante

Copie o arquivo do RobotSDK para esta pasta:

```
RobotSDK_release_i18n_2_4_0_43.aar
```

(ou o `.aar`/`.jar` equivalente extraído do `RobotSDK_release_i18n_2_4_0_43.apk`).

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
