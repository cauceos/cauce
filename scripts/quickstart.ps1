# Cauce quickstart (PowerShell twin of quickstart.sh): turns a freshly started compose stack
# into a responding agent. See QUICKSTART.md.
#
#   1. Waits for the API and checks that the chosen LLM provider is configured on it; with
#      Ollama, also waits for the model pull to finish.
#   2. Reads the one-time bootstrap operator API key from the app logs and saves
#      it to .quickstart.env (gitignored) -- the key is shown ONCE and cannot be
#      recovered later.
#   3. Creates the demo tenant chain (partner -> client) and a demo agent for the provider.
#   4. Sends "What time is it right now?" and polls until the agent answers.
#
# Provider (environment or .env): QUICKSTART_PROVIDER=ollama (default: local, no cost) or
# anthropic (needs ANTHROPIC_API_KEY in .env, where compose reads it; the model is
# ANTHROPIC_MODEL, default claude-haiku-5-5).
#
# Requirements: docker compose (the stack of QUICKSTART.md). Re-running is
# safe: ids are cached in .quickstart.env and reused.

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

# Optional local overrides (same file docker compose reads).
$dotenv = @{}
if (Test-Path .env) {
    Get-Content .env | Where-Object { $_ -match '^\s*([^#][^=]*)=(.*)$' } | ForEach-Object {
        $dotenv[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}
function Get-Setting($name, $default) {
    # Same precedence as docker compose: process environment wins over .env.
    $env = [Environment]::GetEnvironmentVariable($name)
    if ($env) { return $env }
    if ($dotenv.ContainsKey($name)) { return $dotenv[$name] }
    return $default
}

$ApiUrl    = "http://localhost:$(Get-Setting 'API_PORT' '8080')"
$OllamaUrl = "http://localhost:$(Get-Setting 'OLLAMA_PORT' '11434')"
$Provider  = Get-Setting 'QUICKSTART_PROVIDER' 'ollama'
$StateFile = '.quickstart.env'

function Say($msg)  { Write-Host "`n== $msg" }
function Fail($msg) { Write-Error $msg; exit 1 }

switch ($Provider) {
    'ollama'    { $Model = Get-Setting 'OLLAMA_MODEL' 'qwen2.5:3b'; $AgentVar = 'CAUCE_AGENT_ID' }
    'anthropic' { $Model = Get-Setting 'ANTHROPIC_MODEL' 'claude-haiku-5-5'; $AgentVar = 'CAUCE_AGENT_ID_ANTHROPIC' }
    default     { Fail "QUICKSTART_PROVIDER must be ollama or anthropic, not '$Provider'" }
}

function Save-State($name, $value) {
    # Written via .NET, not Out-File: quickstart.sh sources this same file, so it
    # must be BOM-free ASCII with LF endings (PS 5.1 Out-File emits CRLF, which
    # breaks the round-trip under WSL bash).
    $script:state[$name] = $value
    [IO.File]::AppendAllText((Join-Path (Get-Location).Path $StateFile),
        "$name=$value`n", [Text.Encoding]::ASCII)
}

function Invoke-Api($method, $path, $body) {
    $params = @{
        Method  = $method
        Uri     = "$ApiUrl$path"
        Headers = @{ Authorization = "Bearer $script:ApiKey" }
    }
    if ($body) {
        $params.Body        = $body
        $params.ContentType = 'application/json'
    }
    try { return Invoke-RestMethod @params }
    catch {
        # Surface the API's JSON error body, not just the bare WebException text.
        $detail = $_.ErrorDetails.Message
        if (-not $detail -and $_.Exception.Response) {
            try {
                $reader = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
                $detail = $reader.ReadToEnd()
            } catch {}
        }
        Fail "$method $path failed: $($_.Exception.Message) $detail"
    }
}

# --- 1. wait for the stack ---------------------------------------------------

if ($Provider -eq 'anthropic' -and -not (Get-Setting 'ANTHROPIC_API_KEY' '')) {
    Fail @'
QUICKSTART_PROVIDER=anthropic, but ANTHROPIC_API_KEY is not in .env (nor in this shell).
Add it to .env and recreate the app: docker compose --profile api up -d --build
'@
}

Say "Waiting for the API at $ApiUrl..."
$apiUp = $false
foreach ($i in 1..360) {
    try {
        $health = Invoke-RestMethod "$ApiUrl/actuator/health" -TimeoutSec 5
        if ($health.status -eq 'UP') { $apiUp = $true; break }
    } catch {}
    if ($i % 12 -eq 0) { Write-Host '   still waiting... (docker compose ps / docker compose logs cauce-api to check on it)' }
    Start-Sleep -Seconds 5
}
if (-not $apiUp) { Fail 'API not healthy after 30 minutes. Check: docker compose ps; docker compose logs cauce-api' }

if ($Provider -eq 'ollama') {
    Say "Waiting for Ollama to have the model '$Model' (first pull downloads ~2 GB)..."
    $modelReady = $false
    foreach ($i in 1..240) {
        try {
            $tags = Invoke-RestMethod "$OllamaUrl/api/tags" -TimeoutSec 5
            # Ollama lists a tag-less pull as '<name>:latest', so accept both forms.
            if ($tags.models | Where-Object { $_.name -eq $Model -or $_.name -eq "${Model}:latest" }) { $modelReady = $true; break }
        } catch {}
        if ($i % 6 -eq 0) { Write-Host "   still pulling... (docker compose logs ollama-init to watch; your own Ollama: ollama pull $Model)" }
        Start-Sleep -Seconds 5
    }
    if (-not $modelReady) {
        Fail "Model '$Model' not available at $OllamaUrl after 20 minutes. Ollama in Docker: docker compose logs ollama-init. Your own Ollama: ollama pull $Model"
    }
}

# --- 2. bootstrap API key ----------------------------------------------------

$state = @{}
if (Test-Path $StateFile) {
    Get-Content $StateFile | Where-Object { $_ -match '^\s*([^#][^=]*)=(.*)$' } | ForEach-Object {
        $state[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}

# A cached key goes stale when the database is wiped (docker compose down -v
# mints a new bootstrap key on the next boot). Probe it and start fresh if dead,
# instead of failing later with an unexplained 401.
if ($state['CAUCE_API_KEY']) {
    try {
        $null = Invoke-RestMethod "$ApiUrl/v1/tenants/$($state['CAUCE_OPERATOR_ID'])" `
            -Headers @{ Authorization = "Bearer $($state['CAUCE_API_KEY'])" } -TimeoutSec 10
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($status -eq 401 -or $status -eq 404) {
            Write-Host "   Cached state in $StateFile no longer matches the database (was it reset?). Starting fresh."
            Remove-Item $StateFile -Force
            $state = @{}
        }
    }
}

if (-not $state['CAUCE_API_KEY']) {
    Say 'Reading the one-time bootstrap API key from the app logs...'
    # Native stderr is redirected inside a Continue-preference block: under
    # ErrorActionPreference=Stop, PowerShell 5.1 turns redirected native stderr
    # lines into terminating NativeCommandError.
    $logLines = & { $ErrorActionPreference = 'Continue'; docker compose logs cauce-api 2>$null }
    $line = $logLines |
        Select-String -Pattern 'Bootstrapped operator [^(]*\(([0-9a-f-]{36})\).*cannot be recovered: (\S+)' |
        Select-Object -First 1
    if (-not $line) {
        Fail @'
Bootstrap key not found in logs. If the cauce-api container was recreated after
first boot, the key is gone (it is logged exactly once). Start over with:
docker compose --profile quickstart down -v; Remove-Item .quickstart.env; docker compose --profile quickstart up -d
'@
    }
    [IO.File]::WriteAllText((Join-Path (Get-Location).Path $StateFile),
        "# Cauce quickstart state -- contains the operator API key. Do not commit.`n", [Text.Encoding]::ASCII)
    Save-State 'CAUCE_API_KEY' $line.Matches[0].Groups[2].Value
    Save-State 'CAUCE_OPERATOR_ID' $line.Matches[0].Groups[1].Value
    Write-Host "   Key saved to $StateFile (the log line is the only other copy)."
}
$script:ApiKey = $state['CAUCE_API_KEY']

# --- 3. demo tenants + agent -------------------------------------------------

# The agent's provider must be one the instance has configured: ask it, instead of letting the
# first message fail. A key added to .env reaches the app only when compose recreates it.
$providers = @((Invoke-Api GET '/v1/llm-providers').data | Where-Object { $_.configured } | ForEach-Object { $_.id })
if ($providers -notcontains $Provider) {
    Fail "This instance has no '$Provider' provider configured (it has: $($providers -join ', ')). anthropic: put ANTHROPIC_API_KEY in .env, then recreate the app: docker compose --profile api up -d --build"
}

# A state file written before the client was saved: the client is the existing agent's tenant.
if (-not $state['CAUCE_CLIENT_ID'] -and $state['CAUCE_AGENT_ID']) {
    Save-State 'CAUCE_CLIENT_ID' (Invoke-Api GET "/v1/agents/$($state['CAUCE_AGENT_ID'])").tenant_id
}

if (-not $state['CAUCE_CLIENT_ID']) {
    Say 'Creating demo partner -> client...'
    $partner = Invoke-Api POST '/v1/tenants/partner' (@{
        name = 'Demo Partner'; operator_id = $state['CAUCE_OPERATOR_ID'] } | ConvertTo-Json)
    $client = Invoke-Api POST '/v1/tenants/client' (@{
        name = 'Demo Client'; partner_id = $partner.id } | ConvertTo-Json)
    Save-State 'CAUCE_CLIENT_ID' $client.id
}

if (-not $state[$AgentVar]) {
    Say "Creating the demo agent ($Provider / $Model)..."
    $agent = Invoke-Api POST "/v1/tenants/$($state['CAUCE_CLIENT_ID'])/agents" (@{
        name           = 'Demo Agent'
        system_prompt  = 'You are the Cauce quickstart demo agent. You have a get_current_time tool; call it whenever the user asks about the current date or time. Keep answers to one or two short sentences.'
        model_provider = $Provider
        model_name     = $Model
    } | ConvertTo-Json)
    Save-State $AgentVar $agent.id
    Write-Host "   Agent $($agent.id) ready."
}
$agentId = $state[$AgentVar]

# Agents start with no tools (ADR 0005, C4): grant the clock on every run, so an agent left
# by an earlier run (created before tools were per agent) is covered too.
Invoke-Api PUT "/v1/agents/$agentId/tools" (@{ tools = @('get_current_time') } | ConvertTo-Json) | Out-Null

# --- 4. first message --------------------------------------------------------

if ($Provider -eq 'ollama') {
    Say 'Loading the model into memory (first load takes a while on CPU)...'
    try {
        Invoke-RestMethod "$OllamaUrl/api/generate" -Method Post -TimeoutSec 600 `
            -Body (@{ model = $Model; prompt = 'hi'; stream = $false } | ConvertTo-Json) | Out-Null
    } catch { Fail 'Could not warm up the model. Check: docker compose logs ollama' }
}

Say "Asking the agent: 'What time is it right now?'"
# A unique identity per run starts a fresh conversation, so the demo is a clean
# single exchange every time (small local models get wobbly in long contexts).
$demoUser = "quickstart-user-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$posted = Invoke-Api POST "/v1/agents/$agentId/messages" (@{
    external_identity_ref = $demoUser; content = 'What time is it right now?' } | ConvertTo-Json)
Write-Host "   Accepted. Polling invocation $($posted.invocation_id) ..."

$invocation = $null
foreach ($i in 1..100) {
    $invocation = Invoke-Api GET "/v1/invocations/$($posted.invocation_id)"
    if ($invocation.status -in 'COMPLETED', 'FAILED') { break }
    Start-Sleep -Seconds 3
}
if ($invocation.status -eq 'FAILED') {
    Fail "Invocation failed (failure_reason: $($invocation.failure_reason)). The reason is in: docker compose logs cauce-api"
}
if ($invocation.status -ne 'COMPLETED') {
    Fail "Invocation still $($invocation.status) after 5 minutes. Check: docker compose logs cauce-api"
}

$page = Invoke-Api GET "/v1/conversations/$($posted.conversation_id)/messages?limit=200"
$reply = ($page.data | Where-Object { $_.role -eq 'AGENT' } | Select-Object -Last 1).content
$toolCalls = @($page.data | Where-Object { $_.role -eq 'TOOL_CALL' }).Count

Say 'Agent reply:'
Write-Host "   $reply"
if ($toolCalls -gt 0) {
    Write-Host "   (The agent called the get_current_time tool $toolCalls time(s) to answer -- that is the agentic loop.)"
} else {
    Write-Host '   (Note: the model answered without calling the get_current_time tool this run.)'
}

Say 'Done. Keep talking to it:'
Write-Host @"
   `$key = (Get-Content .quickstart.env | Select-String 'CAUCE_API_KEY=(.*)').Matches[0].Groups[1].Value
   Invoke-RestMethod -Method Post "$ApiUrl/v1/agents/$agentId/messages" ``
       -Headers @{ Authorization = "Bearer `$key" } -ContentType 'application/json' ``
       -Body '{"external_identity_ref":"$demoUser","content":"..."}'
   (Same external_identity_ref = same conversation, so the agent keeps context.)
   Your operator API key and demo ids are in $StateFile.

   Every step above was written to a signed audit chain. Check it yourself:
   powershell -ExecutionPolicy Bypass -File scripts\verify-audit.ps1
"@
