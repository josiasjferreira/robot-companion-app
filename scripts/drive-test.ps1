# Teste de movimento do KEN via MQTT (Windows) — reproduz o teste de 05/07/2026,
# mas com movimento SUSTENTADO: publica {"type":"joystick",...} em ken/motion/cmd
# a ~5 Hz para vencer o watchdog de 400 ms do KenMotionBridge (um publish único
# faz o robô apenas "tentar" andar por ~0,4 s) e envia {"type":"stop"} ao final
# (inclusive em Ctrl+C).
#
# Uso (na raiz do repo):
#   .\scripts\drive-test.ps1 frente               # frente por 2 s a 50%
#   .\scripts\drive-test.ps1 frente 5 30          # frente por 5 s a 30%
#   .\scripts\drive-test.ps1 tras|esquerda|direita [DURACAO_S] [VELOCIDADE]
#   .\scripts\drive-test.ps1 stop                 # só envia o stop
#   .\scripts\drive-test.ps1 feedback             # assina feedback + telemetria
#
# Credenciais: lidas do .env da raiz (VITE_MQTT_URL/USERNAME/PASSWORD — as
# mesmas do app web). Requer o mosquitto instalado (mosquitto_pub/_sub no PATH
# ou em C:\Program Files\mosquitto). TLS usa o repositório de certificados do
# sistema (--tls-use-os-certs); use -CaFile para apontar um PEM específico.
#
# Lembrete: o bridge força v=0 com obstáculo a < 40 cm à frente (safeFrontCm).
# Se o robô "travar" indo para frente, confira o front_cm no feedback.
param(
    [Parameter(Position = 0)][ValidateSet('frente', 'tras', 'esquerda', 'direita', 'stop', 'feedback')]
    [string]$Comando = 'frente',
    [Parameter(Position = 1)][ValidateRange(1, 600)][int]$DuracaoS = 2,
    [Parameter(Position = 2)][ValidateRange(0, 100)][int]$Velocidade = 50,
    [string]$CaFile = ''
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Get-EnvValue([string]$Name) {
    $envFile = Join-Path $Root '.env'
    if (-not (Test-Path $envFile)) { return $null }
    $line = Get-Content $envFile | Where-Object { $_ -match "^$Name=" } | Select-Object -Last 1
    if ($line) { return ($line -replace "^$Name=", '').Trim() }
    return $null
}

# Host vem da URL wss:// do app web; o mosquitto usa TLS nativo na 8883.
$MqttUrl = Get-EnvValue 'VITE_MQTT_URL'
$MqttHost = if ($MqttUrl) { ([uri]$MqttUrl).Host } else { $null }
$MqttUser = Get-EnvValue 'VITE_MQTT_USERNAME'
$MqttPass = Get-EnvValue 'VITE_MQTT_PASSWORD'
$TopicCmd = if ($v = Get-EnvValue 'VITE_MQTT_TOPIC_CMD') { $v } else { 'ken/motion/cmd' }
$TopicFeedback = if ($v = Get-EnvValue 'VITE_MQTT_TOPIC_FEEDBACK') { $v } else { 'ken/motion/feedback' }
$TopicTelemetry = if ($v = Get-EnvValue 'VITE_MQTT_TOPIC_TELEMETRY') { $v } else { 'ken/sensors/telemetry' }

if (-not $MqttHost -or -not $MqttUser -or -not $MqttPass -or
    "$MqttHost$MqttPass" -match 'SEU-CLUSTER|TROQUE-ME') {
    Write-Error "Credenciais MQTT ausentes ou com placeholder. Preencha o .env da raiz (Copy-Item .env.example .env)."
}

function Find-Mosquitto([string]$Exe) {
    $cmd = Get-Command $Exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = "C:\Program Files\mosquitto\$Exe.exe"
    if (Test-Path $fallback) { return $fallback }
    Write-Error "$Exe nao encontrado. Instale o mosquitto (https://mosquitto.org/download/) ou adicione ao PATH."
}

$TlsArgs = if ($CaFile) { @('--cafile', $CaFile) } else { @('--tls-use-os-certs') }

$Pub = Find-Mosquitto 'mosquitto_pub'
function Publish-Cmd([string]$Payload) {
    & $Pub -h $MqttHost -p 8883 -u $MqttUser -P $MqttPass @TlsArgs -t $TopicCmd -m $Payload
}
function Send-Stop {
    Publish-Cmd '{"type":"stop"}'
    Write-Host 'stop enviado.'
}

if ($Comando -eq 'stop') { Send-Stop; exit 0 }

if ($Comando -eq 'feedback') {
    $Sub = Find-Mosquitto 'mosquitto_sub'
    Write-Host "Assinando $TopicFeedback e $TopicTelemetry em $MqttHost (Ctrl+C para sair)..."
    & $Sub -h $MqttHost -p 8883 -u $MqttUser -P $MqttPass @TlsArgs -t $TopicFeedback -t $TopicTelemetry -v
    exit $LASTEXITCODE
}

$Direcoes = @{
    frente   = @{ x = 0; y = 1 }
    tras     = @{ x = 0; y = -1 }
    esquerda = @{ x = 1; y = 0 }
    direita  = @{ x = -1; y = 0 }
}
$d = $Direcoes[$Comando]
$Payload = '{{"type":"joystick","x":{0},"y":{1},"speed":{2}}}' -f $d.x, $d.y, $Velocidade

Write-Host "Enviando '$Comando' por ${DuracaoS}s a ${Velocidade}% -> $TopicCmd @ $MqttHost"
Write-Host "Payload: $Payload  (republicado a cada 200 ms; Ctrl+C para abortar com stop)"

try {
    $fim = (Get-Date).AddSeconds($DuracaoS)
    while ((Get-Date) -lt $fim) {
        Publish-Cmd $Payload
        Start-Sleep -Milliseconds 200
    }
}
finally {
    Send-Stop
}
