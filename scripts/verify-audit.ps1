# Cauce quickstart, last step (PowerShell twin of verify-audit.sh): checks the demo client's
# audit chain twice, by two programs that share no code. See QUICKSTART.md and
# tools/audit-chain-verifier/README.md.
#
#   1. Asks the instance for its own verdict (GET /v1/tenants/{id}/audit/chain-verification).
#   2. Dumps the same chain from the database (tools/audit-chain-verifier/dump.sql, run through
#      the Postgres container) and copies the instance's published key registry out of the app
#      container, both into .quickstart\ (gitignored).
#   3. Runs the independent verifier on them: it recomputes every hash and checks every
#      signature against the registry, with Node's standard library only.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\verify-audit.ps1 [-Tenant <id>]
# Requirements: the quickstart's stack and state (.quickstart.env), and Node 18+ for step 3.
param([string]$Tenant)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

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

$ApiUrl = "http://localhost:$(Get-Setting 'API_PORT' '8080')"
$PgUser = Get-Setting 'POSTGRES_USER' 'cauce'
$PgDb   = Get-Setting 'POSTGRES_DB' 'cauce_dev'
$StateFile = '.quickstart.env'
$Out = Join-Path (Get-Location).Path '.quickstart'

function Say($msg)  { Write-Host "`n== $msg" }
function Fail($msg) { Write-Error $msg; exit 1 }

if (-not (Test-Path $StateFile)) { Fail "No $StateFile`: run the quickstart first (scripts\quickstart.ps1)." }
$state = @{}
Get-Content $StateFile | Where-Object { $_ -match '^\s*([^#][^=]*)=(.*)$' } | ForEach-Object {
    $state[$Matches[1].Trim()] = $Matches[2].Trim()
}
$headers = @{ Authorization = "Bearer $($state['CAUCE_API_KEY'])" }

if (-not $Tenant) { $Tenant = $state['CAUCE_CLIENT_ID'] }
if (-not $Tenant -and $state['CAUCE_AGENT_ID']) {
    $Tenant = (Invoke-RestMethod "$ApiUrl/v1/agents/$($state['CAUCE_AGENT_ID'])" -Headers $headers).tenant_id
}
if (-not $Tenant) { Fail 'No tenant to verify: pass -Tenant <id>, or run the quickstart first.' }

# --- 1. the instance's verdict -----------------------------------------------

Say "1. The instance verifies the chain of tenant $Tenant..."
try {
    $verdict = Invoke-RestMethod "$ApiUrl/v1/tenants/$Tenant/audit/chain-verification" -Headers $headers
} catch { Fail "The verification request failed. Is the stack up? docker compose ps ($($_.Exception.Message))" }
$sig = $verdict.signatures
Write-Host "   status: $($verdict.status) - entries verified: $($verdict.verified_entries) - signatures: $($sig.verified) signed and verified, $($sig.unsigned) unsigned, $($sig.unverifiable) unverifiable"

# --- 2. dump + registry ------------------------------------------------------

Say "2. Dumping the same chain from the database, and the instance's key registry..."
New-Item -ItemType Directory -Force $Out | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
# Native output is decoded with the console's code page in PowerShell 5.1: read it as UTF-8.
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = $utf8
try {
    $sql = Get-Content -Raw 'tools\audit-chain-verifier\dump.sql'
    $dump = & { $ErrorActionPreference = 'Continue'; $sql | docker compose exec -T postgres psql -U $PgUser -d $PgDb -v "tenant=$Tenant" -At 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $dump) { Fail 'Could not dump the chain through the postgres container. Check: docker compose ps' }
    [IO.File]::WriteAllText((Join-Path $Out 'audit-dump.json'), (($dump -join "`n") + "`n"), $utf8)

    $registry = & { $ErrorActionPreference = 'Continue'; docker compose exec -T cauce-api cat /app/audit-keys/registry.json 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $registry) {
        Write-Host "   The app container publishes no registry (not the quickstart's?): signatures will be unverifiable."
        $registry = @('{ "registry_version": 1, "keys": [] }')
    }
    [IO.File]::WriteAllText((Join-Path $Out 'audit-keys.json'), (($registry -join "`n") + "`n"), $utf8)
} finally {
    [Console]::OutputEncoding = $previousEncoding
}
Write-Host '   .quickstart\audit-dump.json and .quickstart\audit-keys.json written.'
Write-Host '   The dump may hold a few entries more than step 1 counted: entries reach the chain'
Write-Host '   asynchronously, and asking for a verification is itself recorded in the chain it verified.'

# --- 3. the independent verifier ---------------------------------------------

Say '3. The independent verifier (tools\audit-chain-verifier, no Cauce code) recomputes it...'
$verifyCmd = 'node tools\audit-chain-verifier\verify.mjs .quickstart\audit-dump.json .quickstart\audit-keys.json'
$nodeMajor = 0
if (Get-Command node -ErrorAction SilentlyContinue) {
    # Single quotes inside: PowerShell 5.1 strips the double quotes of a native argument.
    $nodeMajor = [int](& node -p "process.versions.node.split('.')[0]")
}
if ($nodeMajor -lt 18) {
    Write-Host "   Node 18 or later is needed for this step (found: $(if ($nodeMajor) { $nodeMajor } else { 'none' }))."
    Write-Host "   Install it and run: $verifyCmd"
    exit 0
}
& node tools\audit-chain-verifier\verify.mjs (Join-Path $Out 'audit-dump.json') (Join-Path $Out 'audit-keys.json') |
    ForEach-Object { Write-Host "   $_" }
$verdictCode = $LASTEXITCODE

Write-Host ''
Write-Host '   What a VALID establishes, and what it does not: tools\audit-chain-verifier\README.md'
exit $verdictCode # the verifier's own: 0 VALID, 1 BROKEN, 2 UNVERIFIABLE, 3 could not run
