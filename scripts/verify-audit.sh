#!/usr/bin/env bash
# Cauce quickstart, last step: checks the demo client's audit chain twice, by two programs that
# share no code. See QUICKSTART.md and tools/audit-chain-verifier/README.md.
#
#   1. Asks the instance for its own verdict (GET /v1/tenants/{id}/audit/chain-verification).
#   2. Dumps the same chain from the database (tools/audit-chain-verifier/dump.sql, run through
#      the Postgres container) and copies the instance's published key registry out of the app
#      container, both into .quickstart/ (gitignored).
#   3. Runs the independent verifier on them: it recomputes every hash and checks every
#      signature against the registry, with Node's standard library only.
#
# Usage: ./scripts/verify-audit.sh [tenant-id]   (default: the quickstart's demo client)
# Requirements: the quickstart's stack and state (.quickstart.env), curl, and Node 18+ for step 3.

set -euo pipefail

cd "$(dirname "$0")/.."
# Git Bash would rewrite the container paths below into Windows paths.
export MSYS_NO_PATHCONV=1

read_knob() { # read_knob KEY DEFAULT — same rules as quickstart.sh: shell env wins over .env
    local key="$1" def="$2" val=""
    eval "val=\${$key:-}"
    if [ -z "$val" ] && [ -f .env ]; then
        val=$(sed -n "s/^[[:space:]]*$key=//p" .env | tail -n1)
    fi
    printf '%s' "${val:-$def}"
}

API_URL="http://localhost:$(read_knob API_PORT 8080)"
PG_USER="$(read_knob POSTGRES_USER cauce)"
PG_DB="$(read_knob POSTGRES_DB cauce_dev)"
STATE_FILE=".quickstart.env"
OUT=".quickstart"

say()  { printf '\n== %s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
json_field() { printf '%s' "$2" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1; }

[ -f "$STATE_FILE" ] || fail "No $STATE_FILE: run the quickstart first (./scripts/quickstart.sh)."
. "./$STATE_FILE"
auth="Authorization: Bearer $CAUCE_API_KEY"

tenant="${1:-${CAUCE_CLIENT_ID:-}}"
if [ -z "$tenant" ] && [ -n "${CAUCE_AGENT_ID:-}" ]; then
    tenant=$(json_field tenant_id "$(curl -fsS "$API_URL/v1/agents/$CAUCE_AGENT_ID" -H "$auth")")
fi
[ -n "$tenant" ] || fail "No tenant to verify: pass its id, or run the quickstart first."

# --- 1. the instance's verdict -----------------------------------------------

say "1. The instance verifies the chain of tenant $tenant..."
verdict=$(curl -fsS "$API_URL/v1/tenants/$tenant/audit/chain-verification" -H "$auth") \
    || fail "The verification request failed. Is the stack up? docker compose ps"
signatures=$(printf '%s' "$verdict" | sed -n 's/.*"signatures":{"verified":\([0-9]*\),"unsigned":\([0-9]*\),"unverifiable":\([0-9]*\).*/\1 signed and verified, \2 unsigned, \3 unverifiable/p')
echo "   status: $(json_field status "$verdict") · entries verified: $(json_field verified_entries "$verdict") · signatures: $signatures"

# --- 2. dump + registry ------------------------------------------------------

say "2. Dumping the same chain from the database, and the instance's key registry..."
mkdir -p "$OUT"
docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -v tenant="$tenant" -At \
    < tools/audit-chain-verifier/dump.sql > "$OUT/audit-dump.json" \
    || fail "Could not dump the chain through the postgres container. Check: docker compose ps"
if ! docker compose exec -T cauce-api cat /app/audit-keys/registry.json > "$OUT/audit-keys.json" 2>/dev/null; then
    echo "   The app container publishes no registry (not the quickstart's?): signatures will be unverifiable."
    echo '{ "registry_version": 1, "keys": [] }' > "$OUT/audit-keys.json"
fi
echo "   $OUT/audit-dump.json and $OUT/audit-keys.json written."
echo "   The dump may hold one entry more than step 1 counted: asking for a verification is"
echo "   itself recorded in the chain it verified."

# --- 3. the independent verifier ---------------------------------------------

say "3. The independent verifier (tools/audit-chain-verifier, no Cauce code) recomputes it..."
verify_cmd="node tools/audit-chain-verifier/verify.mjs $OUT/audit-dump.json $OUT/audit-keys.json"
node_major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if [ "$node_major" -lt 18 ]; then
    echo "   Node 18 or later is needed for this step (found: $( [ "$node_major" = 0 ] && echo none || echo "$node_major" ))."
    echo "   Install it and run: $verify_cmd"
    exit 0
fi
set +e
$verify_cmd | sed 's/^/   /'
verdict_code=${PIPESTATUS[0]}
set -e

cat <<EOF

   What a VALID establishes, and what it does not: tools/audit-chain-verifier/README.md
EOF
exit "$verdict_code" # the verifier's own: 0 VALID, 1 BROKEN, 2 UNVERIFIABLE, 3 could not run
