# Build de ponta a ponta (Windows/PowerShell): UI (Capacitor) + KenMotionBridge (APK).
# Uso:  .\scripts\build-all.ps1            # apenas builda
#       .\scripts\build-all.ps1 -Install   # builda e instala a ponte via adb
param(
    [switch]$Install
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

Write-Host "== 1/4 Verificacoes ==" -ForegroundColor Cyan
if (-not (Test-Path ".env")) {
    Write-Error "ERRO: .env nao encontrado. Rode: Copy-Item .env.example .env e preencha as credenciais MQTT."
}
if (Select-String -Path ".env" -Pattern "TROQUE-ME|SEU-CLUSTER" -Quiet) {
    Write-Error "ERRO: .env ainda contem placeholders (TROQUE-ME/SEU-CLUSTER). Preencha antes de buildar."
}
$aar = Get-ChildItem "KenMotionBridge\app\libs\*.aar" -ErrorAction SilentlyContinue
if (-not $aar) {
    Write-Host "AVISO: nenhum RobotSDK .aar em KenMotionBridge\app\libs\." -ForegroundColor Yellow
    Write-Host "       O APK da ponte compila mesmo assim, mas o robo NAO se move sem ele." -ForegroundColor Yellow
    Write-Host "       Veja KenMotionBridge\app\libs\PLACE_AAR_HERE.md para obter o arquivo." -ForegroundColor Yellow
}

Write-Host "== 2/4 UI (web -> Capacitor/Android) ==" -ForegroundColor Cyan
if (-not (Test-Path "node_modules")) { npm install; if ($LASTEXITCODE -ne 0) { throw "npm install falhou" } }
npm run build
if ($LASTEXITCODE -ne 0) { throw "npm run build falhou" }
if (-not (Test-Path "android")) {
    npm install @capacitor/android
    if ($LASTEXITCODE -ne 0) { throw "npm install @capacitor/android falhou" }
    npx cap add android
    if ($LASTEXITCODE -ne 0) { throw "npx cap add android falhou" }
}
npx cap sync android
if ($LASTEXITCODE -ne 0) { throw "npx cap sync falhou" }
Write-Host "UI pronta. Para gerar/instalar o APK da UI: npx cap open android (Android Studio)."

Write-Host "== 3/4 KenMotionBridge (APK da ponte) ==" -ForegroundColor Cyan
Push-Location "KenMotionBridge"
try {
    .\gradlew.bat --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug falhou" }
} finally {
    Pop-Location
}

$apk = "KenMotionBridge\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "== 4/4 Saidas ==" -ForegroundColor Cyan
Write-Host "Ponte: $apk"
if ($Install) {
    Write-Host "Instalando a ponte via adb..."
    adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install falhou (tablet conectado? adb connect IP:5555)" }
    adb shell am start -n br.com.onlife.kenmotionbridge/.MainActivity
}
Write-Host "Concluido." -ForegroundColor Green
