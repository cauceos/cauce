#!/usr/bin/env bash
# Cauce quickstart: turns a freshly started `docker compose --profile quickstart`
# stack into a responding agent, end to end. See QUICKSTART.md.
#
# What it does (each step is one plain API call — the curls are documented in
# QUICKSTART.md if you prefer to run them yourself):
#   1. Waits for the API and for the Ollama model pull to finish.
#   2. Reads the one-time bootstrap operator API key from the app logs and saves
#      it to .quickstart.env (gitignored) — the key is shown ONCE and cannot be
#      recovered later.
#   3. Creates the demo tenant chain (partner -> client) and a demo agent.
#   4. Sends "What time is it right now?" and polls until the agent answers.
#
# Requirements: docker compose (running the quickstart profile) and curl.
# Re-running is safe: ids are cached in .quickstart.env and reused.

set -euo pipefail

cd "$(dirname "$0")/.."

# Optional local overrides. Only the quickstart knobs are read from .env —
# never source it: compose's dotenv dialect is not shell (a password with a
# space or '$' would abort or mangle this script). Shell env wins, like compose.
read_knob() { # read_knob KEY DEFAULT
    local key="$1" def="$2" val=""
    eval "val=\${$key:-}"
    if [ -z "$val" ] && [ -f .env ]; then
        val=$(sed -n "s/^[[:space:]]*$key=//p" .env | tail -n1)
    fi
    printf '%s' "${val:-$def}"
}

API_URL="http://localhost:$(read_knob API_PORT 8080)"
OLLAMA_URL="http://localhost:$(read_knob OLLAMA_PORT 11434)"
MODEL="$(read_knob OLLAMA_MODEL qwen2.5:3b)"
STATE_FILE=".quickstart.env"

say()  { printf '\n== %s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# --- helpers -----------------------------------------------------------------

# api METHOD PATH [JSON_BODY] -> body on stdout; dies on non-2xx.
api() {
    local method="$1" path="$2" body="${3:-}" response http_code
    response=$(curl -sS -X "$method" "$API_URL$path" \
        -H "Authorization: Bearer $CAUCE_API_KEY" \
        -H "Content-Type: application/json" \
        ${body:+-d "$body"} \
        -w $'\n%{http_code}') || fail "curl $method $path failed"
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    case "$http_code" in
        2*) printf '%s' "$body" ;;
        *)  fail "$method $path returned HTTP $http_code: $body" ;;
    esac
}

# First "id" field of a JSON object (our responses put "id" first).
extract_id() {
    printf '%s' "$1" | sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p' | head -n1
}

# json_field NAME JSON -> first string value of that field.
json_field() {
    printf '%s' "$2" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n1
}

# Last AGENT message content from the messages page. Prefers python3/jq (correct
# JSON handling); falls back to a sed heuristic good enough for short replies.
# The python3 probe actually runs it: on Windows, a Microsoft Store alias stub
# named python3.exe exists on PATH but only prints an install hint and fails.
extract_agent_reply() {
    if command -v python3 >/dev/null 2>&1 && python3 -c '' >/dev/null 2>&1; then
        printf '%s' "$1" | python3 -c '
import json, sys
msgs = [m for m in json.load(sys.stdin)["data"] if m["role"] == "AGENT"]
print(msgs[-1]["content"] if msgs else "")'
    elif command -v jq >/dev/null 2>&1; then
        printf '%s' "$1" | jq -r '[.data[] | select(.role == "AGENT")] | last | .content // ""'
    else
        printf '%s' "$1" | sed -n 's/.*"role":"AGENT","content":"\([^"]*\)".*/\1/p' | tail -n1
    fi
}

# --- 1. wait for the stack ---------------------------------------------------

say "Waiting for the API at $API_URL (the first run also builds the app image and pulls ~6 GB — be patient)..."
for i in $(seq 1 360); do
    if curl -fsS "$API_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; then
        api_up=1; break
    fi
    [ $((i % 12)) -eq 0 ] && echo "   still waiting... (docker compose ps / docker compose logs cauce-api to check on it)"
    sleep 5
done
[ "${api_up:-}" = 1 ] || fail "API not healthy after 30 minutes. Check: docker compose ps && docker compose logs cauce-api"

say "Waiting for Ollama to have the model '$MODEL' (first pull downloads ~2 GB)..."
for i in $(seq 1 240); do
    # Ollama lists a tag-less pull as '<name>:latest', so accept both forms.
    if curl -fsS "$OLLAMA_URL/api/tags" 2>/dev/null | grep -Eq "\"$MODEL(:latest)?\""; then
        model_ready=1; break
    fi
    [ $((i % 6)) -eq 0 ] && echo "   still pulling... (docker compose logs ollama-init to watch)"
    sleep 5
done
[ "${model_ready:-}" = 1 ] || fail "Model '$MODEL' not available after 20 minutes. Check: docker compose logs ollama-init"

# --- 2. bootstrap API key ----------------------------------------------------

if [ -f "$STATE_FILE" ]; then . "./$STATE_FILE"; fi

# A cached key goes stale when the database is wiped (docker compose down -v
# mints a new bootstrap key on the next boot). Probe it and start fresh if dead,
# instead of failing later with an unexplained 401.
if [ -n "${CAUCE_API_KEY:-}" ]; then
    probe=$(curl -sS -o /dev/null -w '%{http_code}' "$API_URL/v1/tenants/${CAUCE_OPERATOR_ID:-missing}" \
        -H "Authorization: Bearer $CAUCE_API_KEY" || echo 000)
    if [ "$probe" = 401 ] || [ "$probe" = 404 ]; then
        echo "   Cached state in $STATE_FILE no longer matches the database (was it reset?). Starting fresh."
        rm -f "$STATE_FILE"
        CAUCE_API_KEY=""; CAUCE_OPERATOR_ID=""; CAUCE_AGENT_ID=""
    fi
fi

if [ -z "${CAUCE_API_KEY:-}" ]; then
    say "Reading the one-time bootstrap API key from the app logs..."
    bootstrap_line=$(docker compose logs cauce-api 2>/dev/null | tr -d '\r' | grep 'cannot be recovered: ' | head -n1 || true)
    [ -n "$bootstrap_line" ] || fail "Bootstrap key not found in logs. If the cauce-api container was recreated after
       first boot, the key is gone (it is logged exactly once). Start over with:
       docker compose --profile quickstart down -v && rm -f $STATE_FILE && docker compose --profile quickstart up -d"
    CAUCE_API_KEY=$(printf '%s' "$bootstrap_line" | sed 's/.*cannot be recovered: //' | awk '{print $1}')
    CAUCE_OPERATOR_ID=$(printf '%s' "$bootstrap_line" | sed -n 's/.*Bootstrapped operator [^(]*(\([0-9a-f-]\{36\}\)).*/\1/p')
    [ -n "$CAUCE_API_KEY" ] && [ -n "$CAUCE_OPERATOR_ID" ] || fail "Could not parse the bootstrap log line: $bootstrap_line"
    {
        echo "# Cauce quickstart state — contains the operator API key. Do not commit."
        echo "CAUCE_API_KEY=$CAUCE_API_KEY"
        echo "CAUCE_OPERATOR_ID=$CAUCE_OPERATOR_ID"
    } > "$STATE_FILE"
    echo "   Key saved to $STATE_FILE (the log line is the only other copy)."
fi

# --- 3. demo tenants + agent -------------------------------------------------

if [ -z "${CAUCE_AGENT_ID:-}" ]; then
    say "Creating demo partner -> client -> agent..."
    partner=$(api POST /v1/tenants/partner "{\"name\":\"Demo Partner\",\"operator_id\":\"$CAUCE_OPERATOR_ID\"}")
    partner_id=$(extract_id "$partner")

    client=$(api POST /v1/tenants/client "{\"name\":\"Demo Client\",\"partner_id\":\"$partner_id\"}")
    client_id=$(extract_id "$client")

    agent=$(api POST "/v1/tenants/$client_id/agents" "{
        \"name\": \"Demo Agent\",
        \"system_prompt\": \"You are the Cauce quickstart demo agent. You have a get_current_time tool; call it whenever the user asks about the current date or time. Keep answers to one or two short sentences.\",
        \"model_provider\": \"ollama\",
        \"model_name\": \"$MODEL\"
    }")
    CAUCE_AGENT_ID=$(extract_id "$agent")
    [ -n "$CAUCE_AGENT_ID" ] || fail "Agent creation returned no id: $agent"
    echo "CAUCE_AGENT_ID=$CAUCE_AGENT_ID" >> "$STATE_FILE"
    echo "   Agent $CAUCE_AGENT_ID ready."
fi

# --- 4. first message --------------------------------------------------------

say "Loading the model into memory (first load takes a while on CPU)..."
curl -sS --max-time 600 "$OLLAMA_URL/api/generate" \
    -d "{\"model\":\"$MODEL\",\"prompt\":\"hi\",\"stream\":false}" > /dev/null \
    || fail "Could not warm up the model. Check: docker compose logs ollama"

say "Asking the agent: 'What time is it right now?'"
# A unique identity per run starts a fresh conversation, so the demo is a clean
# single exchange every time (small local models get wobbly in long contexts).
demo_user="quickstart-user-$(date +%s)"
posted=$(api POST "/v1/agents/$CAUCE_AGENT_ID/messages" \
    "{\"external_identity_ref\":\"$demo_user\",\"content\":\"What time is it right now?\"}")
conversation_id=$(json_field conversation_id "$posted")
invocation_id=$(json_field invocation_id "$posted")
[ -n "$invocation_id" ] || fail "Unexpected 202 body: $posted"

echo "   Accepted. Polling invocation $invocation_id ..."
status=PENDING
for _ in $(seq 1 100); do
    invocation=$(api GET "/v1/invocations/$invocation_id")
    status=$(json_field status "$invocation")
    case "$status" in
        COMPLETED|FAILED) break ;;
    esac
    sleep 3
done

if [ "$status" != "COMPLETED" ]; then
    [ "$status" = "FAILED" ] && fail "Invocation failed (failure_reason: $(json_field failure_reason "$invocation")).
       Check: docker compose logs cauce-api"
    fail "Invocation still $status after 5 minutes. Check: docker compose logs cauce-api"
fi

messages=$(api GET "/v1/conversations/$conversation_id/messages?limit=200")
reply=$(extract_agent_reply "$messages")
# grep exits 1 on zero matches — '|| true' keeps set -e/pipefail from killing
# the script right when the model happens to answer without calling the tool.
tool_calls=$(printf '%s' "$messages" | { grep -o '"role":"TOOL_CALL"' || true; } | wc -l | tr -d ' ')

say "Agent reply:"
printf '   %s\n' "${reply:-<could not extract the reply — raw page below>}"
[ -n "$reply" ] || printf '%s\n' "$messages"
if [ "$tool_calls" -gt 0 ]; then
    echo "   (The agent called the get_current_time tool $tool_calls time(s) to answer — that is the agentic loop.)"
else
    echo "   (Note: the model answered without calling the get_current_time tool this run.)"
fi

say "Done. Keep talking to it:"
cat <<EOF
   curl -X POST $API_URL/v1/agents/$CAUCE_AGENT_ID/messages \\
        -H "Authorization: Bearer \$(grep CAUCE_API_KEY= .quickstart.env | cut -d= -f2)" \\
        -H "Content-Type: application/json" \\
        -d '{"external_identity_ref":"$demo_user","content":"..."}'
   (Same external_identity_ref = same conversation, so the agent keeps context.)
   Your operator API key and demo ids are in $STATE_FILE.
EOF
