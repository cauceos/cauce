<#
.SYNOPSIS
  Real test of the bar on Telegram with voice notes (ADR 0012): Sonnet as the agent, a local
  Whisper large-v3-turbo as the transcriber.

.DESCRIPTION
  Step by step, pausing between steps:

    1. Starts the local stack with the transcription profile and the turbo model. The first time it
       downloads the speaches image (about 850 MB) and the model (about 1.6 GB).
    2. Prints the exact command that starts the API with transcription configured, for a second
       terminal, and waits until it answers. The Anthropic key is the one already exported in that
       terminal (ANTHROPIC_API_KEY): this script never asks for it nor stores it.
    3. Asks for the public URL of the tunnel (cloudflared, in a third terminal) and runs
       demo-bar.ps1 with it, which reloads the bar and re-registers the bots' webhooks.
    4. Prints what to test and what to see.

  The bot tokens are read from the environment or .env (TELEGRAM_CUSTOMER_BOT_TOKEN,
  TELEGRAM_OWNER_BOT_TOKEN), as demo-bar.ps1 reads them; when missing, they are asked with
  Read-Host -AsSecureString and live only in this process. The operator key comes from
  .quickstart.env or CAUCE_API_KEY, as for demo-bar.ps1.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\prueba-real.ps1
#>
[CmdletBinding()]
param(
    [string]$Model = 'deepdml/faster-whisper-large-v3-turbo-ct2',
    [string]$Language = 'es',
    [string]$ApiUrl = 'http://localhost:8080'
)

# ASCII only: Windows PowerShell reads a script without a BOM in the ANSI code page.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$dotenv = @{}
if (Test-Path '.env') {
    Get-Content '.env' | Where-Object { $_ -match '^\s*([^#][^=]*)=(.*)$' } | ForEach-Object {
        $dotenv[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}
function Get-Setting($name, $default) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ($value) { return $value }
    if ($dotenv.ContainsKey($name)) { return $dotenv[$name] }
    return $default
}
function Step($n, $msg) { Write-Host "`n== Paso $n. $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Pause-Step($msg) { Read-Host "`n$msg (Enter para seguir)" | Out-Null }

$whisperPort = Get-Setting 'WHISPER_PORT' '8000'

# --- 1. the stack, with the transcription profile and the turbo model ------------------------------

Step 1 "Stack local con el perfil de transcripcion y el modelo $Model"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'docker no esta en el PATH.' }
Write-Host '   La primera vez se descargan la imagen de speaches (~850 MB) y el modelo turbo (~1,6 GB),'
Write-Host '   que queda en el volumen whisper-data: las siguientes veces no se descarga nada.'
Pause-Step 'Listo para levantar el stack'

# TRANSCRIPTION_MODEL in this process wins over .env for docker compose: whisper-init downloads it.
$env:TRANSCRIPTION_MODEL = $Model
docker compose --profile transcription up -d
if ($LASTEXITCODE -ne 0) { Fail 'docker compose --profile transcription up -d ha fallado.' }

$init = (docker compose --profile transcription ps -aq whisper-init | Select-Object -First 1)
if (-not $init) { Fail 'No encuentro el contenedor whisper-init.' }
Write-Host "   Esperando a que whisper-init tenga el modelo (con la descarga, varios minutos)..."
$exit = (docker wait $init)
if ($exit -ne '0') {
    docker logs $init
    Fail "whisper-init termino con codigo $($exit): el modelo no esta descargado."
}
Write-Host "   Modelo listo. Whisper en http://localhost:$whisperPort/v1"

# --- 2. the API, with transcription configured --------------------------------------------------------

Step 2 'Arranca la API con la transcripcion configurada'
Write-Host '   En OTRA terminal, la que ya tiene ANTHROPIC_API_KEY exportada, ejecuta:'
Write-Host ''
Write-Host "     cd `"$repo\backend`"" -ForegroundColor Yellow
if (-not $env:JAVA_HOME) {
    Write-Host '     $env:JAVA_HOME = "<la carpeta de un JDK 21>"' -ForegroundColor Yellow
}
Write-Host "     `$env:CAUCE_SPEECH_OPENAICOMPATIBLE_ENABLED = 'true'" -ForegroundColor Yellow
Write-Host "     `$env:CAUCE_SPEECH_OPENAICOMPATIBLE_BASEURL = 'http://localhost:$whisperPort/v1'" -ForegroundColor Yellow
Write-Host "     `$env:CAUCE_SPEECH_OPENAICOMPATIBLE_MODEL = '$Model'" -ForegroundColor Yellow
Write-Host "     `$env:CAUCE_SPEECH_OPENAICOMPATIBLE_LANGUAGE = '$Language'" -ForegroundColor Yellow
Write-Host '     .\gradlew :cauce-api:bootRun' -ForegroundColor Yellow
Write-Host ''
if ($env:JAVA_HOME) {
    Write-Host "   (JAVA_HOME aqui es $($env:JAVA_HOME); en esa terminal tiene que ser un JDK 21.)"
}
if (-not $env:ANTHROPIC_API_KEY) {
    Write-Host '   (En esta terminal no hay ANTHROPIC_API_KEY: comprueba que la tiene la de la API.)'
}
Pause-Step 'Cuando la API este arrancando'

Write-Host "   Esperando a $ApiUrl/actuator/health..."
$deadline = (Get-Date).AddMinutes(5)
$up = $false
while ((Get-Date) -lt $deadline) {
    try {
        if ((Invoke-RestMethod "$ApiUrl/actuator/health" -TimeoutSec 5).status -eq 'UP') { $up = $true; break }
    } catch { }
    Start-Sleep -Seconds 3
}
if (-not $up) { Fail "La API no responde UP en $ApiUrl/actuator/health tras 5 minutos." }
Write-Host '   API arriba.'

# --- 3. the tunnel, then demo-bar.ps1 with its URL ----------------------------------------------------

Step 3 'Tunel publico y bots de Telegram'
Write-Host '   En una TERCERA terminal:'
Write-Host ''
Write-Host "     cloudflared tunnel --url $ApiUrl" -ForegroundColor Yellow
Write-Host ''
Write-Host '   y copia la URL https://....trycloudflare.com que imprime.'
$publicUrl = (Read-Host '   URL publica del tunel').Trim().TrimEnd('/')
if (-not $publicUrl.StartsWith('https://')) { Fail "La URL tiene que empezar por https://, no '$publicUrl'." }
try {
    $health = Invoke-RestMethod "$publicUrl/actuator/health" -TimeoutSec 15
    Write-Host "   El tunel llega a la API: $($health.status)"
} catch {
    Fail "No llego a la API por $publicUrl ($($_.Exception.Message)). Espera unos segundos a que el tunel este listo y vuelve a ejecutar."
}

foreach ($name in @('TELEGRAM_CUSTOMER_BOT_TOKEN', 'TELEGRAM_OWNER_BOT_TOKEN')) {
    if (-not (Get-Setting $name '')) {
        $secure = Read-Host "   $name (Enter para omitirlo)" -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        if ($plain) { [Environment]::SetEnvironmentVariable($name, $plain, 'Process') }
    }
}
if (-not (Get-Setting 'TELEGRAM_CUSTOMER_BOT_TOKEN' '')) { Fail 'Sin el token del bot de clientes no hay prueba en Telegram.' }

$env:PUBLIC_URL = $publicUrl
$env:CAUCE_API_URL = $ApiUrl
& (Join-Path $PSScriptRoot 'demo-bar.ps1')
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { Fail 'demo-bar.ps1 ha fallado (arriba, el motivo).' }

# --- 4. what to test ------------------------------------------------------------------------------------

Step 4 'Que probar, desde tu movil, al bot de clientes'
Write-Host @'

  Mientras se transcribe, el bot muestra "escribiendo..." hasta la respuesta, sin ningun mensaje
  extra. Una nota tarda unos 20-30 s en CPU (la primera, unos 40: carga el modelo); un texto, pocos
  segundos. Datos inventados; no digas datos reales en las notas.

  1. TEXTO: "Hola, abris el domingo a mediodia?"
     -> responde en pocos segundos con el horario de la ficha; "escribiendo..." apenas se ve.

  2. NOTA, reserva: "Quiero mesa para cuatro el sabado a las nueve y media de la noche"
     -> "escribiendo..." enseguida y durante la transcripcion; puede consultar la disponibilidad,
        pero repite lo que ha entendido (cuatro personas) y te pide confirmarlo antes de proponer.

  3. NOTA, telefono: "A nombre de Marta Ruiz, mi telefono es seis, cero, cero, uno, dos, tres,
     cuatro, cinco, seis"
     -> repite el nombre y el telefono tal como le han llegado y te pide confirmarlos antes de
        proponer. Si oye ocho cifras, lo dice ("me han llegado ocho cifras") y te pide el numero:
        nunca completa ni corrige una cifra por su cuenta.

  4. NOTA, alergenos: "Mi hija es celiaca y alergica a los frutos secos, que puede comer?"
     -> consulta la carta antes de contestar, sin inventar platos, y nombra en su respuesta los
        alergenos que ha entendido (gluten, frutos secos).

  5. (opcional) NOTA de mas de 2 minutos
     -> sin transcribir: el texto fijo que pide un mensaje escrito o una nota mas corta.

  En ningun caso menciona la transcripcion: para el cliente, le ha escuchado.

  Despues: el playground (Conversacion) muestra cada nota con su etiqueta y su transcripcion; si
  algo falla, mira el log de la API por el conversation_id.
'@
