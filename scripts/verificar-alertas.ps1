<#
.SYNOPSIS
  Local end-to-end check of the operator's alerts (ADR 0011): OpenObserve -> Telegram.

.DESCRIPTION
  Two modes, in two terminals:

    1. -Api   starts the API exporting traces, metrics and logs to the local OpenObserve, with a
              deliberately invalid Anthropic key. It keeps the terminal busy (bootRun).
    2. (none) provisions the alerts with your bot and runs the two tests:
              A: provider down (the invalid key)          -> cauce-provider-down (+ cauce-fallback-rate)
              B: high fallback rate, healthy provider     -> cauce-fallback-rate only

  The bot token, the chat_id and the operator API key are asked with Read-Host: they live only in
  this process's memory and environment, never in a file nor in PowerShell's history.
  Prerequisites: docker compose up -d postgres redis; docker compose --profile observability up -d.
  See docs/operations/observability.md.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\verificar-alertas.ps1 -Api
  powershell -ExecutionPolicy Bypass -File scripts\verificar-alertas.ps1
#>
[CmdletBinding()]
param(
    # Starts the API instead of running the tests.
    [switch]$Api,
    # Database for the API. Another name than cauce_dev is created if missing, and the API logs a new
    # operator key on its first start (for when the operator key is lost).
    [string]$BaseDeDatos = 'cauce_dev',
    [string]$OpenObserveUrl = 'http://localhost:5080',
    [string]$OpenObserveEmail = $(if ($env:OPENOBSERVE_ROOT_EMAIL) { $env:OPENOBSERVE_ROOT_EMAIL } else { 'root@cauce.local' }),
    [string]$OpenObservePassword = $(if ($env:OPENOBSERVE_ROOT_PASSWORD) { $env:OPENOBSERVE_ROOT_PASSWORD } else { 'CauceDev_2026!' }),
    [string]$ApiUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$gitBash = 'C:\Program Files\Git\bin\bash.exe'   # not plain "bash": in PowerShell that is WSL, which does not inherit the variables
$ooAuth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${OpenObserveEmail}:${OpenObservePassword}"))

function Paso($texto) { Write-Host ''; Write-Host "==> $texto" -ForegroundColor Cyan }

function Secreto($pregunta) {
    # Read-Host input is not written to PowerShell's history; -AsSecureString keeps it off the screen.
    $seguro = Read-Host $pregunta -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# --- Mode 1: the API ---------------------------------------------------------------------------------
if ($Api) {
    if ($BaseDeDatos -ne 'cauce_dev') {
        Paso "Base de datos $BaseDeDatos"
        $existe = docker exec cauce-postgres psql -U cauce -d cauce_dev -tAc "SELECT 1 FROM pg_database WHERE datname = '$BaseDeDatos'"
        if ("$existe".Trim() -ne '1') {
            docker exec cauce-postgres psql -U cauce -d cauce_dev -qc "CREATE DATABASE $BaseDeDatos" | Out-Null
            docker exec cauce-postgres psql -U cauce -d $BaseDeDatos -qc 'CREATE EXTENSION IF NOT EXISTS vector' | Out-Null
            Write-Host "Creada. La API escribira en su log, una sola vez, la key del operador nuevo (busca 'cannot be recovered:')."
        }
        $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/$BaseDeDatos"
        $env:CAUCE_ADMIN_DATASOURCE_URL = $env:SPRING_DATASOURCE_URL
    }
    $otlp = "$OpenObserveUrl/api/default/v1"
    $env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks\temurin-21.0.5'
    $env:MANAGEMENT_OTLP_TRACING_ENDPOINT = "$otlp/traces"
    $env:MANAGEMENT_OTLP_TRACING_HEADERS_AUTHORIZATION = $ooAuth
    $env:MANAGEMENT_OTLP_LOGGING_ENDPOINT = "$otlp/logs"
    $env:MANAGEMENT_OTLP_LOGGING_HEADERS_AUTHORIZATION = $ooAuth
    $env:MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED = 'true'
    $env:MANAGEMENT_OTLP_METRICS_EXPORT_URL = "$otlp/metrics"
    $env:MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION = $ooAuth
    $env:LOGGING_LEVEL_DEV_CAUCE = 'INFO'                          # as in production
    $env:ANTHROPIC_API_KEY = 'sk-ant-invalida-para-la-prueba'      # deliberately invalid: test A
    Paso 'Arrancando la API (Ctrl+C para pararla). Espera a "Started CauceApplication".'
    Write-Host 'Mientras corre, todos los agentes de Anthropic fallan: la clave es invalida a proposito.'
    Set-Location (Join-Path $repo 'backend')
    & .\gradlew.bat :cauce-api:bootRun
    exit $LASTEXITCODE
}

# --- Mode 2: alerts and tests ------------------------------------------------------------------------
$h = $null
try {
    Paso 'Comprobando OpenObserve'
    try { Invoke-RestMethod "$OpenObserveUrl/healthz" | Out-Null }
    catch { throw "OpenObserve no responde en $OpenObserveUrl. Arrancalo con: docker compose --profile observability up -d" }

    Paso 'Esperando a la API'
    Write-Host 'Si no la has arrancado, en OTRA terminal, desde la raiz del repo:'
    Write-Host '    powershell -ExecutionPolicy Bypass -File scripts\verificar-alertas.ps1 -Api' -ForegroundColor Yellow
    while ($true) {
        try { if ((Invoke-RestMethod "$ApiUrl/actuator/health").status -eq 'UP') { break } } catch { }
        Start-Sleep -Seconds 3
    }
    Write-Host 'API arriba. Esperando a que exporte sus primeras metricas a OpenObserve (hasta 2 minutos)...'
    while ($true) {
        $streams = Invoke-RestMethod "$OpenObserveUrl/api/default/streams?type=metrics" -Headers @{ Authorization = $ooAuth }
        if ($streams.list | Where-Object { $_.name -eq 'cauce_queue_pending' }) { break }
        Start-Sleep -Seconds 5
    }

    Paso 'Tu bot de alertas'
    Write-Host 'Antes de seguir, mandale cualquier mensaje a tu bot desde Telegram.'
    $env:TELEGRAM_BOT_TOKEN = Secreto 'Token del bot'
    $chats = (Invoke-RestMethod "https://api.telegram.org/bot$($env:TELEGRAM_BOT_TOKEN)/getUpdates").result |
        ForEach-Object { $_.message.chat } | Where-Object { $_ } | Sort-Object id -Unique
    if ($chats) { $chats | ForEach-Object { Write-Host ("  chat_id {0}  ({1}{2})" -f $_.id, $_.first_name, $_.title) } }
    else { Write-Host '  El bot no tiene mensajes recientes; si sabes tu chat_id, escribelo igualmente.' }
    $env:TELEGRAM_CHAT_ID = Read-Host 'chat_id'
    $env:OPENOBSERVE_URL = $OpenObserveUrl
    $env:OPENOBSERVE_ROOT_EMAIL = $OpenObserveEmail
    $env:OPENOBSERVE_ROOT_PASSWORD = $OpenObservePassword

    Paso 'Provisionando alertas y dashboard en OpenObserve'
    & $gitBash (Join-Path $repo 'scripts/observability/provision.sh') | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw 'La provision de alertas fallo (salida arriba).' }

    Paso 'Clientes de prueba'
    $h = @{ Authorization = 'Bearer ' + (Secreto 'API key del operador') }
    function Post($ruta, $cuerpo) {
        Invoke-RestMethod -Method Post "$ApiUrl$ruta" -Headers $h -ContentType 'application/json' -Body ($cuerpo | ConvertTo-Json)
    }
    $sello = Get-Date -Format 'yyyyMMdd-HHmmss'
    $me = Invoke-RestMethod "$ApiUrl/v1/me" -Headers $h
    $partner = Post '/v1/tenants/partner' @{ name = "Pruebas alertas $sello"; operator_id = $me.tenant_id }

    # A: every message calls Anthropic with an invalid key -> 401, failed without retry, fallback reply.
    Paso 'Prueba A: proveedor caido (clave invalida)'
    $clienteA = Post '/v1/tenants/client' @{ name = "Bar prueba A $sello"; partner_id = $partner.id }
    $agenteA = Post "/v1/tenants/$($clienteA.id)/agents" @{ name = 'Camarero A'; system_prompt = 'Eres el camarero.';
        model_provider = 'anthropic'; model_name = 'claude-haiku-5-5'; tools = @() }
    $invocaciones = 1..6 | ForEach-Object {
        (Post "/v1/agents/$($agenteA.id)/messages" @{ external_identity_ref = "prueba-a-$_"; content = "Prueba de alerta $_" }).invocation_id
    }
    Write-Host "6 mensajes enviados al agente $($agenteA.id)."
    Write-Host 'En 1-3 minutos te llegan a Telegram: cauce-provider-down (proveedor anthropic) y cauce-fallback-rate.'
    Read-Host 'Pulsa Enter cuando hayan llegado' | Out-Null

    # No re-provisioning before B: each agent alerts on its own, and re-creating the rules would only
    # make A's alert fire again while A's messages are still in its window.
    # B: a client with a spend limit cannot use a model with no price -> refused before any model call.
    Paso 'Prueba B: tasa de respaldo alta con el proveedor sano'
    $clienteB = Post '/v1/tenants/client' @{ name = "Bar prueba B $sello"; partner_id = $partner.id }
    Invoke-RestMethod -Method Put "$ApiUrl/v1/tenants/$($clienteB.id)/spend-limit" -Headers $h `
        -ContentType 'application/json' -Body (@{ monthly_limit_usd = 5 } | ConvertTo-Json) | Out-Null
    $agenteB = Post "/v1/tenants/$($clienteB.id)/agents" @{ name = 'Camarero B'; system_prompt = 'Eres el camarero.';
        model_provider = 'anthropic'; model_name = 'claude-modelo-sin-precio'; tools = @() }
    1..6 | ForEach-Object {
        Post "/v1/agents/$($agenteB.id)/messages" @{ external_identity_ref = "prueba-b-$_"; content = "Hola $_" } | Out-Null
    }
    Write-Host "6 mensajes enviados al agente $($agenteB.id)."
    Write-Host 'En 1-3 minutos te llega SOLO cauce-fallback-rate del agente B (100 % de respaldo); ningun provider-down nuevo.'
    Read-Host 'Pulsa Enter cuando haya llegado' | Out-Null

    Paso 'Traza y dashboard en OpenObserve'
    Write-Host "Abre $OpenObserveUrl (usuario $OpenObserveEmail)."
    Write-Host "  Traces (stream default, ultima hora), busca:  invocation_id = '$($invocaciones[-1])'"
    Write-Host '    -> http post /v1/agents/{agentId}/messages > cauce.invocation > cauce.llm.call, un solo trace.'
    Write-Host "  Logs, busca:  invocationid = '$($invocaciones[-1])'"
    Write-Host '  Dashboards -> "Cauce — operación", rango ultima hora.'
}
finally {
    # Nothing secret outlives the script, even if it stopped half-way.
    foreach ($v in 'TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID', 'OPENOBSERVE_ROOT_PASSWORD') {
        Remove-Item "Env:$v" -ErrorAction SilentlyContinue
    }
    $h = $null
}
