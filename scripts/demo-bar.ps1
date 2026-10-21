# Cauce demo bar (PowerShell twin of demo-bar.sh): loads the reference bar of the hospitality
# sector pack (examples/hosteleria) into a running Cauce instance and, given two Telegram bots,
# wires them up. See examples/hosteleria/README.md.
#
#   1. Creates a partner and the business "Bar La Esquina" (a client tenant).
#   2. Loads its sheet, menu and agenda from examples/hosteleria/bar-ejemplo.
#   3. Creates the customer agent (prompt + tool grant of the pack) and the owner agent.
#   4. With TELEGRAM_CUSTOMER_BOT_TOKEN / TELEGRAM_OWNER_BOT_TOKEN: binds each bot to its agent
#      and, with PUBLIC_URL, registers both webhooks with Telegram.
#   5. Issues the owner's link code and prints the link that binds the owner's chat.
#
# Settings (environment or .env): CAUCE_API_URL (default http://localhost:8080), CAUCE_API_KEY
# (default: the quickstart's, from .quickstart.env), CAUCE_OPERATOR_ID, MODEL_PROVIDER (default
# anthropic), MODEL_NAME (default claude-sonnet-5-5), TELEGRAM_CUSTOMER_BOT_TOKEN,
# TELEGRAM_OWNER_BOT_TOKEN, PUBLIC_URL. Re-running reuses the ids saved in .demo-bar.env
# (gitignored) and reloads the sheet, menu and agenda; delete that file to start over.

# ASCII only: Windows PowerShell reads a script without a BOM in the ANSI code page, so the
# non-ASCII letters of names below are written as char codes.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$dotenv = @{}
foreach ($file in @('.env', '.quickstart.env', '.demo-bar.env')) {
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

$Pack          = 'examples/hosteleria'
$StateFile     = '.demo-bar.env'
$ApiUrl        = Get-Setting 'CAUCE_API_URL' 'http://localhost:8080'
$ApiKey        = Get-Setting 'CAUCE_API_KEY' ''
$ModelProvider = Get-Setting 'MODEL_PROVIDER' 'anthropic'
$ModelName     = Get-Setting 'MODEL_NAME' 'claude-sonnet-5-5'
$CustomerToken = Get-Setting 'TELEGRAM_CUSTOMER_BOT_TOKEN' ''
$OwnerToken    = Get-Setting 'TELEGRAM_OWNER_BOT_TOKEN' ''
$PublicUrl     = (Get-Setting 'PUBLIC_URL' '').TrimEnd('/')

function Say($msg)  { Write-Host "`n== $msg" }
function Fail($msg) { Write-Error $msg; exit 1 }
function Save($key, $value) { Add-Content -Path $StateFile -Value "$key=$value" -Encoding ascii }
function Read-Utf8($path) { [IO.File]::ReadAllText((Resolve-Path $path), [Text.Encoding]::UTF8) }

function Invoke-Api($method, $path, $json) {
    $params = @{
        Method  = $method
        Uri     = "$ApiUrl$path"
        Headers = @{ Authorization = "Bearer $ApiKey" }
    }
    if ($json) {
        # Windows PowerShell would send a string body as ISO-8859-1: send UTF-8 bytes.
        $params.Body        = [Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try { return Invoke-RestMethod @params }
    catch {
        $detail = $_.ErrorDetails.Message
        Fail "$method $path failed: $($_.Exception.Message) $detail"
    }
}
function Invoke-Telegram($token, $method, $form) {
    $uri = "https://api.telegram.org/bot$token/$method"
    if ($form) { return Invoke-RestMethod -Method Post -Uri $uri -Body $form }
    return Invoke-RestMethod -Method Get -Uri $uri
}

# --- 0. credentials and state ------------------------------------------------

if (-not $ApiKey) { Fail 'Set CAUCE_API_KEY to an operator key (or run the quickstart first).' }
$OperatorId = Get-Setting 'CAUCE_OPERATOR_ID' ''
if (-not $OperatorId) { $OperatorId = (Invoke-Api GET '/v1/me').tenant_id }
if (-not (Test-Path $StateFile)) {
    Set-Content -Path $StateFile -Value '# Cauce demo bar state (ids only, no secrets). Delete to start over.' -Encoding ascii
}
$BarTenantId      = Get-Setting 'BAR_TENANT_ID' ''
$CustomerAgentId  = Get-Setting 'CUSTOMER_AGENT_ID' ''
$OwnerAgentId     = Get-Setting 'OWNER_AGENT_ID' ''
$CustomerConfigId = Get-Setting 'CUSTOMER_BOT_CONFIG_ID' ''
$OwnerConfigId    = Get-Setting 'OWNER_BOT_CONFIG_ID' ''

# --- 1. tenants --------------------------------------------------------------

if (-not $BarTenantId) {
    Say "Creating the partner and the business 'Bar La Esquina'..."
    $partner = Invoke-Api POST '/v1/tenants/partner' (@{ name = "Demo Hosteler$([char]0x00ED)a"; operator_id = $OperatorId } | ConvertTo-Json)
    $client = Invoke-Api POST '/v1/tenants/client' (@{ name = 'Bar La Esquina'; partner_id = $partner.id } | ConvertTo-Json)
    $BarTenantId = $client.id
    Save 'BAR_TENANT_ID' $BarTenantId
}
Write-Host "   Business: $BarTenantId"

# --- 2. sheet, menu, agenda --------------------------------------------------

Say 'Loading the sheet, the menu and the agenda...'
Invoke-Api PUT "/v1/tenants/$BarTenantId/business-profile" (Read-Utf8 "$Pack/bar-ejemplo/ficha.json") | Out-Null
Invoke-Api PUT "/v1/tenants/$BarTenantId/catalog" (Read-Utf8 "$Pack/bar-ejemplo/carta.json") | Out-Null
Invoke-Api PUT "/v1/tenants/$BarTenantId/agenda" (Read-Utf8 "$Pack/bar-ejemplo/agenda.json") | Out-Null
Write-Host '   Loaded.'

# --- 3. agents ---------------------------------------------------------------

$grants = Read-Utf8 "$Pack/herramientas.json" | ConvertFrom-Json
$customerPrompt = Read-Utf8 "$Pack/agente-clientes.prompt.md"
$ownerPrompt = Read-Utf8 "$Pack/agente-dueno.prompt.md"
function New-AgentBody($name, $prompt, $tools) {
    return (@{ name = $name; system_prompt = $prompt; model_provider = $ModelProvider; model_name = $ModelName;
               tools = @($tools) } | ConvertTo-Json -Depth 4)
}
if (-not $CustomerAgentId) {
    Say "Creating the customer agent ($ModelProvider / $ModelName)..."
    $CustomerAgentId = (Invoke-Api POST "/v1/tenants/$BarTenantId/agents" `
        (New-AgentBody 'Camarero de La Esquina' $customerPrompt $grants.agente_clientes)).id
    Save 'CUSTOMER_AGENT_ID' $CustomerAgentId
    $OwnerAgentId = (Invoke-Api POST "/v1/tenants/$BarTenantId/agents" `
        (New-AgentBody "Canal del due$([char]0x00F1)o" $ownerPrompt $grants.agente_dueno)).id
    Save 'OWNER_AGENT_ID' $OwnerAgentId
} else {
    Say 'Refreshing the prompts of the existing agents...'
    Invoke-Api PATCH "/v1/agents/$CustomerAgentId" (@{ system_prompt = $customerPrompt } | ConvertTo-Json) | Out-Null
    Invoke-Api PUT "/v1/agents/$CustomerAgentId/tools" (@{ tools = @($grants.agente_clientes) } | ConvertTo-Json) | Out-Null
    Invoke-Api PATCH "/v1/agents/$OwnerAgentId" (@{ system_prompt = $ownerPrompt } | ConvertTo-Json) | Out-Null
}
Write-Host "   Customer agent: $CustomerAgentId"
Write-Host "   Owner agent:    $OwnerAgentId"

# --- 4. Telegram bots --------------------------------------------------------

function Connect-Bot($agentId, $token, $stateKey) {
    $created = Invoke-Api POST "/v1/agents/$agentId/channels" (@{ channel_type = 'telegram'; credential = $token } | ConvertTo-Json)
    Save $stateKey $created.id
    if (-not $PublicUrl) {
        Write-Host '   PUBLIC_URL not set: register the webhook yourself (see the README).'
        Write-Host "     url=<public url>/webhooks/channels/$($created.id)  secret_token=$($created.webhook_secret)"
    } else {
        $result = Invoke-Telegram $token 'setWebhook' @{
            url = "$PublicUrl/webhooks/channels/$($created.id)"; secret_token = $created.webhook_secret
            allowed_updates = '["message"]' }
        if (-not $result.ok) { Fail "Telegram setWebhook failed for config $($created.id)" }
        Write-Host "   Webhook registered: $PublicUrl/webhooks/channels/$($created.id)"
    }
    return $created.id
}
if ($CustomerToken -and -not $CustomerConfigId) {
    Say 'Binding the customer bot...'
    $CustomerConfigId = Connect-Bot $CustomerAgentId $CustomerToken 'CUSTOMER_BOT_CONFIG_ID'
}
if ($OwnerToken -and -not $OwnerConfigId) {
    Say 'Binding the owner bot...'
    $OwnerConfigId = Connect-Bot $OwnerAgentId $OwnerToken 'OWNER_BOT_CONFIG_ID'
}

# --- 5. owner link -----------------------------------------------------------

if ($OwnerConfigId) {
    Say "Issuing the owner's link code (valid 24 h)..."
    $code = (Invoke-Api POST "/v1/tenants/$BarTenantId/owner-link-codes" (@{ channel_config_id = $OwnerConfigId } | ConvertTo-Json)).code
    $ownerBot = if ($OwnerToken) { (Invoke-Telegram $OwnerToken 'getMe' $null).result.username } else { $null }
    if ($ownerBot) {
        Write-Host "   Open this link on the owner's phone and press Start:"
        Write-Host "     https://t.me/$ownerBot`?start=$code"
    } else {
        Write-Host "   Send this code to the owner bot: $code"
    }
}

Say 'Done.'
Write-Host "   Business:       $BarTenantId"
if ($CustomerToken) { Write-Host "   Customer bot:   https://t.me/$((Invoke-Telegram $CustomerToken 'getMe' $null).result.username)" }
Write-Host "   Read the cases:  GET $ApiUrl/v1/tenants/$BarTenantId/handoffs"
Write-Host "   Summary now:     POST $ApiUrl/v1/tenants/$BarTenantId/daily-digests"
