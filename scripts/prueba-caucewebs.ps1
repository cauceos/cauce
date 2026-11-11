<#
.SYNOPSIS
  The hidden-page test of the CauceWebs web chat (ADR 0014, W7): loads the CauceWebs pack into a
  running Cauce instance, creates its web chat with the shop-assistant mode in phase 1, and prints
  the tag to paste into the hidden page of caucewebs.es. See examples/caucewebs/prueba-oculta.md.

.DESCRIPTION
  1. Finds the client "CauceWebs . ventas" under a partner "CauceWebs" of this operator, or creates
     them: a run never makes a second business on the same instance.
  2. Loads its sheet and catalog from examples/caucewebs/caucewebs.
  3. Finds or creates the sales agent and the owner agent (prompt, tools, fixed replies and cache
     retention of the pack) and refreshes them when they exist; adds the model's price when the
     operator's table has none.
  4. Sets the client's monthly spend limit (mandatory for a web chat, W1), as its ancestor.
  5. Creates the web chat once (Turnstile keys, allowed origin, privacy policy) and, on every run,
     applies examples/caucewebs/asistente.json to it, so the instance always has the file's phase.
  6. With TELEGRAM_OWNER_BOT_TOKEN and PUBLIC_URL: binds the owner bot once, renews and checks its
     webhook on every run, and issues the owner's link code, so demo requests reach the owner.
  7. Prints the tag, with the release's SRI hash, to paste into the hidden page.

  Settings (environment or .env): CAUCE_API_URL (default http://localhost:8080), CAUCE_API_KEY
  (default: the quickstart's, from .quickstart.env), PUBLIC_URL (the tunnel's https URL: the page
  loads the widget from it), TURNSTILE_SECRET and TURNSTILE_SITE_KEY (the site's Cloudflare
  Turnstile widget; required on the first run), TEST_ORIGIN (default https://caucewebs.es),
  SPEND_LIMIT_USD (default 20), MODEL_PROVIDER (default anthropic), MODEL_NAME (default
  claude-sonnet-5-5), TELEGRAM_OWNER_BOT_TOKEN, TELEGRAM_API_URL. Ids are kept in
  .prueba-caucewebs.env (gitignored); no secret is written there.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\prueba-caucewebs.ps1
#>

# ASCII only: Windows PowerShell reads a script without a BOM in the ANSI code page, so the
# non-ASCII letters of names below are written as char codes.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$dotenv = @{}
foreach ($file in @('.env', '.quickstart.env', '.prueba-caucewebs.env')) {
    if (Test-Path $file) {
        Get-Content $file | Where-Object { $_ -match '^\s*([^#][^=]*)=(.*)$' } | ForEach-Object {
            $dotenv[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
}
function Get-Setting($name, $default) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ($value) { return $value }
    if ($dotenv.ContainsKey($name)) { return $dotenv[$name] }
    return $default
}

$Pack          = 'examples/caucewebs'
$StateFile     = '.prueba-caucewebs.env'
$ApiUrl        = (Get-Setting 'CAUCE_API_URL' 'http://localhost:8080').TrimEnd('/')
$ApiKey        = Get-Setting 'CAUCE_API_KEY' ''
$PublicUrl     = (Get-Setting 'PUBLIC_URL' '').TrimEnd('/')
$Origin        = (Get-Setting 'TEST_ORIGIN' 'https://caucewebs.es').TrimEnd('/')
$SpendLimit    = Get-Setting 'SPEND_LIMIT_USD' '20'
$ModelProvider = Get-Setting 'MODEL_PROVIDER' 'anthropic'
$ModelName     = Get-Setting 'MODEL_NAME' 'claude-sonnet-5-5'
$OwnerToken    = Get-Setting 'TELEGRAM_OWNER_BOT_TOKEN' ''
$TelegramApi   = Get-Setting 'TELEGRAM_API_URL' 'https://api.telegram.org'

function Say($msg)  { Write-Host "`n== $msg" }
function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Save($key, $value) { Add-Content -Path $StateFile -Value "$key=$value" -Encoding ascii }
function Read-Utf8($path) { [IO.File]::ReadAllText((Resolve-Path $path), [Text.Encoding]::UTF8) }

function Invoke-Api($method, $path, $json) {
    $params = @{ Method = $method; Uri = "$ApiUrl$path"; Headers = @{ Authorization = "Bearer $ApiKey" } }
    if ($json) {
        # Windows PowerShell would send a string body as ISO-8859-1: send UTF-8 bytes.
        $params.Body        = [Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try { return Invoke-RestMethod @params }
    catch { Fail "$method $path failed: $($_.Exception.Message) $($_.ErrorDetails.Message)" }
}
function Invoke-Telegram($token, $method, $form) {
    $uri = "$TelegramApi/bot$token/$method"
    try {
        if ($form) { return Invoke-RestMethod -Method Post -Uri $uri -Body $form }
        return Invoke-RestMethod -Method Get -Uri $uri
    } catch {
        if ($_.ErrorDetails.Message) { try { return $_.ErrorDetails.Message | ConvertFrom-Json } catch {} }
        return [pscustomobject]@{ ok = $false; description = $_.Exception.Message }
    }
}
# The ids of every item named $name in the list $path, oldest first, read as UTF-8.
function Get-Named($path, $name) {
    $ids = @()
    $cursor = $null
    do {
        $query = if ($cursor) { "?limit=200&cursor=$cursor" } else { '?limit=200' }
        try {
            $raw = Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$ApiUrl$path$query" `
                -Headers @{ Authorization = "Bearer $ApiKey" }
        } catch { Fail "GET $path failed: $($_.Exception.Message) $($_.ErrorDetails.Message)" }
        $page = [Text.Encoding]::UTF8.GetString($raw.RawContentStream.ToArray()) | ConvertFrom-Json
        $ids += @($page.data | Where-Object { $_.name -ceq $name } | ForEach-Object { $_.id })
        $cursor = $page.next_cursor
    } while ($cursor)
    return $ids
}
function Get-FirstNamed($path, $name) { return @(Get-Named $path $name) | Select-Object -First 1 }

# --- 0. credentials and state ------------------------------------------------

if (-not $ApiKey) { Fail 'Set CAUCE_API_KEY to an operator key (or run the quickstart first).' }
if (-not $PublicUrl.StartsWith('https://')) {
    Fail "PUBLIC_URL must be the tunnel's https:// URL (the page at $Origin loads the widget from it), not '$PublicUrl'."
}
$OperatorId = (Invoke-Api GET '/v1/me').tenant_id
$providers = @((Invoke-Api GET '/v1/llm-providers').data | Where-Object { $_.configured } | ForEach-Object { $_.id })
if ($providers -notcontains $ModelProvider) {
    Fail "The instance has no '$ModelProvider' provider configured (it has: $($providers -join ', '))."
}
if (-not (Test-Path $StateFile)) {
    Set-Content -Path $StateFile -Value '# CauceWebs hidden-page test state (ids only, no secrets). Delete to start over.' -Encoding ascii
}
$ClientId      = Get-Setting 'CW_CLIENT_ID' ''
$SalesAgentId  = Get-Setting 'CW_SALES_AGENT_ID' ''
$OwnerAgentId  = Get-Setting 'CW_OWNER_AGENT_ID' ''
$WebChatId     = Get-Setting 'CW_WEB_CHAT_ID' ''
$OwnerConfigId = Get-Setting 'CW_OWNER_BOT_CONFIG_ID' ''

# --- 1. tenants --------------------------------------------------------------

$PartnerName = 'CauceWebs'
$ClientName  = "CauceWebs $([char]0x00B7) ventas"
if (-not $ClientId) {
    foreach ($partnerId in @(Get-Named "/v1/tenants/$OperatorId/children" $PartnerName)) {
        $ClientId = Get-FirstNamed "/v1/tenants/$partnerId/children" $ClientName
        if ($ClientId) { break }
    }
    if ($ClientId) {
        Say "Reusing the client '$ClientName' already on this instance..."
    } else {
        Say "Creating the partner '$PartnerName' and the client '$ClientName'..."
        $partnerId = Get-FirstNamed "/v1/tenants/$OperatorId/children" $PartnerName
        if (-not $partnerId) {
            $partnerId = (Invoke-Api POST '/v1/tenants/partner' (@{ name = $PartnerName; operator_id = $OperatorId } | ConvertTo-Json)).id
        }
        $ClientId = (Invoke-Api POST '/v1/tenants/client' (@{ name = $ClientName; partner_id = $partnerId } | ConvertTo-Json)).id
    }
    Save 'CW_CLIENT_ID' $ClientId
}
Write-Host "   Client: $ClientId"

# --- 2. sheet and catalog ----------------------------------------------------

Say 'Loading the sheet and the catalog...'
Invoke-Api PUT "/v1/tenants/$ClientId/business-profile" (Read-Utf8 "$Pack/caucewebs/ficha.json") | Out-Null
Invoke-Api PUT "/v1/tenants/$ClientId/catalog" (Read-Utf8 "$Pack/caucewebs/catalogo.json") | Out-Null

# --- 3. agents and the model's price ------------------------------------------

$grants      = Read-Utf8 "$Pack/herramientas.json" | ConvertFrom-Json
$fallbacks   = Read-Utf8 "$Pack/respaldo.json" | ConvertFrom-Json
$unsupported = Read-Utf8 "$Pack/no-soportado.json" | ConvertFrom-Json
$retention   = Read-Utf8 "$Pack/retencion-cache.json" | ConvertFrom-Json
$agents = @(
    @{ key = 'agente_clientes'; name = 'Asistente de ventas'; prompt = "$Pack/agente-clientes.prompt.md"; state = 'CW_SALES_AGENT_ID' },
    @{ key = 'agente_dueno'; name = "Canal del due$([char]0x00F1)o"; prompt = "$Pack/agente-dueno.prompt.md"; state = 'CW_OWNER_AGENT_ID' }
)
$ids = @{ agente_clientes = $SalesAgentId; agente_dueno = $OwnerAgentId }
foreach ($agent in $agents) {
    $id = $ids[$agent.key]
    if (-not $id) { $id = Get-FirstNamed "/v1/tenants/$ClientId/agents" $agent.name; if ($id) { Save $agent.state $id } }
    $prompt = Read-Utf8 $agent.prompt
    if (-not $id) {
        Say "Creating the agent '$($agent.name)' ($ModelProvider / $ModelName)..."
        $id = (Invoke-Api POST "/v1/tenants/$ClientId/agents" (@{ name = $agent.name; system_prompt = $prompt
            model_provider = $ModelProvider; model_name = $ModelName; tools = @($grants.($agent.key))
            fallback_reply = $fallbacks.($agent.key); unsupported_message_reply = $unsupported.($agent.key) } |
            ConvertTo-Json -Depth 4)).id
        Save $agent.state $id
    } else {
        Say "Refreshing the agent '$($agent.name)'..."
        Invoke-Api PATCH "/v1/agents/$id" (@{ system_prompt = $prompt; fallback_reply = $fallbacks.($agent.key)
            unsupported_message_reply = $unsupported.($agent.key) } | ConvertTo-Json) | Out-Null
        Invoke-Api PUT "/v1/agents/$id/tools" (@{ tools = @($grants.($agent.key)) } | ConvertTo-Json) | Out-Null
    }
    Invoke-Api PATCH "/v1/agents/$id" (@{ prompt_cache_retention = $retention.($agent.key) } | ConvertTo-Json) | Out-Null
    $ids[$agent.key] = $id
}
$SalesAgentId = $ids.agente_clientes
$OwnerAgentId = $ids.agente_dueno

# The list prices checked in examples/hosteleria/evals/precios.yaml; a price is never changed.
$hasPrice = $false
$cursor = $null
do {
    $page = Invoke-Api GET ("/v1/llm-prices?limit=200" + $(if ($cursor) { "&cursor=$cursor" } else { '' }))
    if (@($page.data | Where-Object { $_.provider -eq $ModelProvider -and $_.model -eq $ModelName }).Count -gt 0) { $hasPrice = $true }
    $cursor = $page.next_cursor
} while ($cursor -and -not $hasPrice)
if (-not $hasPrice) {
    switch ("$ModelProvider/$ModelName") {
        'anthropic/claude-sonnet-5-5' { $p = @(2.00, 10.00, 0.10, 2.50, 4.00) }
        'anthropic/claude-haiku-5-5'  { $p = @(0.10, 0.50, 0.01, 0.125, 0.20) }
        default { Fail "$ModelProvider / $ModelName has no price, and the spend limit would refuse every message. Add it with POST /v1/llm-prices." }
    }
    Say "Adding the price of $ModelProvider / $ModelName..."
    Invoke-Api POST '/v1/llm-prices' ([ordered]@{ provider = $ModelProvider; model = $ModelName
        input_usd_per_mtok = $p[0]; output_usd_per_mtok = $p[1]; cache_read_usd_per_mtok = $p[2]
        cache_write_usd_per_mtok = $p[3]; cache_write_long_usd_per_mtok = $p[4] } | ConvertTo-Json) | Out-Null
}

# --- 4. spend limit ----------------------------------------------------------

Say "Setting the monthly spend limit to $SpendLimit US`$..."
Invoke-Api PUT "/v1/tenants/$ClientId/spend-limit" (@{ monthly_limit_usd = [decimal]::Parse($SpendLimit,
    [Globalization.CultureInfo]::InvariantCulture) } | ConvertTo-Json) | Out-Null

# --- 5. the web chat, and its shop-assistant mode from asistente.json ----------

if (-not $WebChatId) {
    $secret = Get-Setting 'TURNSTILE_SECRET' ''
    $siteKey = Get-Setting 'TURNSTILE_SITE_KEY' ''
    if (-not $secret -or -not $siteKey) { Fail 'Set TURNSTILE_SECRET and TURNSTILE_SITE_KEY (the Turnstile widget of the site) for the first run.' }
    Say "Creating the web chat for $Origin..."
    $created = Invoke-Api POST "/v1/agents/$SalesAgentId/web-chat-channels" (@{ turnstile_secret = $secret
        turnstile_site_key = $siteKey; allowed_origins = @($Origin)
        privacy_policy_url = "$Origin/privacidad" } | ConvertTo-Json)
    $WebChatId = $created.id
    Save 'CW_WEB_CHAT_ID' $WebChatId
}
$assistantJson = Read-Utf8 "$Pack/asistente.json"
$assistant = $assistantJson | ConvertFrom-Json
Invoke-Api PUT "/v1/web-chat-channels/$WebChatId/assistant" $assistantJson | Out-Null
$channel = Invoke-Api GET "/v1/web-chat-channels/$WebChatId"
$phase = if ($assistant.proactive_opening) { '2 (opens on its own)' } else { '1 (bubble only)' }
Write-Host "   Web chat:   $WebChatId  (public key $($channel.public_key))"
Write-Host "   Assistant:  phase $phase, variant $(@($assistant.variants | ForEach-Object { $_.id }) -join ', ')"

# --- 6. the owner bot --------------------------------------------------------

if ($OwnerToken) {
    Say 'Connecting the owner bot...'
    $me = Invoke-Telegram $OwnerToken 'getMe' $null
    if (-not $me.ok) { Fail "Telegram rejects the owner bot token: $($me.description)" }
    if (-not $OwnerConfigId) {
        $binding = Invoke-Api POST "/v1/agents/$OwnerAgentId/channels" (@{ channel_type = 'telegram'; credential = $OwnerToken } | ConvertTo-Json)
        $OwnerConfigId = $binding.id
        Save 'CW_OWNER_BOT_CONFIG_ID' $OwnerConfigId
    } else {
        $binding = Invoke-Api POST "/v1/channel-configs/$OwnerConfigId/webhook-secret" $null
    }
    $url = "$PublicUrl/webhooks/channels/$OwnerConfigId"
    $set = Invoke-Telegram $OwnerToken 'setWebhook' @{ url = $url; secret_token = $binding.webhook_secret; allowed_updates = '["message"]' }
    if (-not $set.ok) { Fail "Telegram setWebhook failed: $($set.description)" }
    if ((Invoke-Telegram $OwnerToken 'getWebhookInfo' $null).result.url -ne $url) { Fail "The owner bot's webhook did not take." }
    $code = (Invoke-Api POST "/v1/tenants/$ClientId/owner-link-codes" (@{ channel_config_id = $OwnerConfigId } | ConvertTo-Json)).code
    Write-Host "   Owner: open https://t.me/$($me.result.username)?start=$code on the owner's phone (valid 24 h)."
} else {
    Write-Host "`n   No TELEGRAM_OWNER_BOT_TOKEN: demo requests stay as cases, GET $ApiUrl/v1/tenants/$ClientId/handoffs"
}

# --- 7. the tag for the hidden page -------------------------------------------

$version = (Read-Utf8 'frontend/web-widget/package.json' | ConvertFrom-Json).version
$embed = "frontend/web-widget/dist/release/$version/embed.html"
Say 'The tag for the hidden page'
if (Test-Path $embed) {
    $integrity = [regex]::Match((Read-Utf8 $embed), 'integrity="([^"]+)"').Groups[1].Value
    Write-Host @"
   <script src="$PublicUrl/web/widget/$version/cauce-widget.js"
           integrity="$integrity" crossorigin="anonymous"
           data-cauce-key="$($channel.public_key)" data-cauce-instance="$PublicUrl" defer></script>
"@
    Write-Host '   The instance must serve that release: the dev profile reads frontend/web-widget/dist/release.'
} else {
    Write-Host "   Build the widget first (cd frontend/web-widget; npm run build) and run this again: $embed is missing."
}
Write-Host "`n   Then follow examples/caucewebs/prueba-oculta.md."
