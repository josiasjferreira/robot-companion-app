# Coloque aqui o RobotSDK do fabricante

Copie o arquivo do RobotSDK para esta pasta:

```
RobotSDK_release_i18n_2_4_0_43.aar
```

(ou versão mais nova equivalente em `.aar`/`.jar`).

O `app/build.gradle` já inclui:

```groovy
implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])
```

Depois de colocar o arquivo, o build completo é um comando na raiz do repo:
`.\scripts\build-all.ps1` (Windows) ou `./scripts/build-all.sh` (Linux/macOS).

## ⚠️ Atenção: o que NÃO serve como fonte do AAR

A pasta/zip `RobotSDK_release_i18n_2.4.0_43` que circula com o robô (e o
`RobotSDK_release_202507151348.zip`) é o **APP do fabricante descompactado**
(`classes.dex`, `lib/*.so`, recursos) — um APK, **não** um SDK de desenvolvedor.
**Não existe `.aar`/`.jar` dentro dela.** Não adianta copiar `.dex`/`.so` para cá.

## Onde obter o `.aar` de verdade

1. **SLAMTEC** — o chassi é Slamware; o "Slamware Android SDK" oficial (AAR com
   as classes `com.slamtec.slamware.*`) é distribuído pela SLAMTEC
   (developer portal / suporte).
2. **CSJBOT** — pedir ao suporte do fabricante o *RobotSDK developer package*
   (o `.aar` de integração, não o APK do app).

Enquanto o `.aar` não chega, a alternativa arquitetural é reativar o binding
AIDL com o app do fabricante instalado no robô (`useCsjbotBinding: true` no
`bridge_config.json`) — mas isso exige os `.aidl` **oficiais** do
`com.csjbot.sdkhandler` (o descriptor do Binder precisa bater com o do app).

## Importante

- O projeto **compila e gera APK mesmo sem o AAR** — a camada `SlamwareChassis.kt`
  acessa o SDK por reflexão. Sem o AAR, a ponte sobe normalmente mas o chassi
  reporta `SDK: DESCONECTADO` (nenhuma classe `com.slamtec.*` em runtime).
- Com o AAR presente, a ponte se liga às classes **reais**
  (`com.slamtec.slamware.*`) em runtime.
- O `.gitignore` da raiz ignora `*.aar`/`*.jar` desta pasta — o binário
  proprietário fica **só na sua máquina**, nunca no repositório.
- Se o pacote do fabricante trouxer arquivos `.aidl` oficiais para
  `com.csjbot.sdkhandler`, **substitua** os scaffolds em
  `app/src/main/aidl/com/csjbot/sdkhandler/` pelos do fabricante
  (o descriptor do Binder precisa bater).
