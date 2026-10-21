#!/usr/bin/env bash
# Cauce demo bar: loads the reference bar of the hospitality sector pack (examples/hosteleria)
# into a running Cauce instance and, given two Telegram bots, wires them up. See
# examples/hosteleria/README.md.
#
# What it does (each step is one plain API call):
#   1. Creates a partner and the business "Bar La Esquina" (a client tenant).
#   2. Loads its sheet, menu and agenda from examples/hosteleria/bar-ejemplo.
#   3. Creates the customer agent (prompt + tool grant of the pack) and the owner agent.
#   4. With TELEGRAM_CUSTOMER_BOT_TOKEN / TELEGRAM_OWNER_BOT_TOKEN: binds each bot to its agent
#      and, with PUBLIC_URL, registers both webhooks with Telegram.
#   5. Issues the owner's link code and prints the link that binds the owner's chat.
#
# Settings (environment or .env):
#   CAUCE_API_URL       default http://localhost:8080
#   CAUCE_API_KEY       an operator key; default: the one saved by the quickstart (.quickstart.env)
#   CAUCE_OPERATOR_ID   the operator tenant id; default: from .quickstart.env, else GET /v1/me
#   MODEL_PROVIDER      default anthropic   (needs ANTHROPIC_API_KEY on the instance)
#   MODEL_NAME          default claude-sonnet-5-5
#   TELEGRAM_CUSTOMER_BOT_TOKEN, TELEGRAM_OWNER_BOT_TOKEN   from @BotFather (optional)
#   PUBLIC_URL          public https URL that reaches the instance, e.g. a tunnel (optional)
#
# Requirements: curl, and python3 or jq. Re-running reuses the ids saved in .demo-bar.env
# (gitignored) and reloads the sheet, menu and agenda; delete that file to start over.

set -euo pipefail
cd "$(dirname "$0")/.."

read_knob() { # read_knob KEY DEFAULT
    local key="$1" def="$2" val=""
    eval "val=\${$key:-}"
    if [ -z "$val" ] && [ -f .env ]; then
        val=$(sed -n "s/^[[:space:]]*$key=//p" .env | tail -n1)
    fi
    printf '%s' "${val:-$def}"
}

PACK="examples/hosteleria"
STATE_FILE=".demo-bar.env"
API_URL="$(read_knob CAUCE_API_URL http://localhost:8080)"
MODEL_PROVIDER="$(read_knob MODEL_PROVIDER anthropic)"
MODEL_NAME="$(read_knob MODEL_NAME claude-sonnet-5-5)"
CUSTOMER_TOKEN="$(read_knob TELEGRAM_CUSTOMER_BOT_TOKEN '')"
OWNER_TOKEN="$(read_knob TELEGRAM_OWNER_BOT_TOKEN '')"
PUBLIC_URL="$(read_knob PUBLIC_URL '')"
PUBLIC_URL="${PUBLIC_URL%/}"

say()  { printf '\n== %s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# Python on Windows would read the prompt files from stdin in the ANSI code page.
export PYTHONIOENCODING=utf-8
if command -v python3 >/dev/null 2>&1 && python3 -c '' >/dev/null 2>&1; then
    json_get()    { python3 -c 'import json,sys; v=json.load(sys.stdin);
for k in sys.argv[1].split("."): v=v[k]
print(v)' "$1"; }
    json_string() { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }
    json_list()   { python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]]))' "$1" "$2"; }
elif command -v jq >/dev/null 2>&1; then
    json_get()    { jq -r ".$1"; }
    json_string() { jq -Rs .; }
    json_list()   { jq -c ".$2" "$1"; }
else
    fail "python3 or jq is required"
fi

# api METHOD PATH [JSON_BODY | @FILE] -> body on stdout; dies on non-2xx.
api() {
    local method="$1" path="$2" body="${3:-}" response http_code
    local data=()
    # Bodies go through a file or stdin, never argv: on Windows an argument is re-encoded in the
    # ANSI code page and the UTF-8 of "Hostelería" or "dueño" would not survive.
    case "$body" in
        "") ;;
        @*) data=(--data-binary "$body") ;;
        *)  data=(--data-binary @-) ;;
    esac
    response=$(printf '%s' "$body" | curl -sS -X "$method" "$API_URL$path" \
        -H "Authorization: Bearer $CAUCE_API_KEY" \
        -H "Content-Type: application/json; charset=utf-8" \
        "${data[@]}" -w $'\n%{http_code}') || fail "curl $method $path failed"
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    case "$http_code" in
        2*) printf '%s' "$body" ;;
        *)  fail "$method $path returned HTTP $http_code: $body" ;;
    esac
}

telegram() { # telegram TOKEN METHOD [curl args...]
    local token="$1" method="$2"; shift 2
    curl -sS "https://api.telegram.org/bot$token/$method" "$@"
}

# --- 0. credentials and state ------------------------------------------------

[ -f .quickstart.env ] && . ./.quickstart.env
[ -f "$STATE_FILE" ] && . "./$STATE_FILE"
CAUCE_API_KEY="$(read_knob CAUCE_API_KEY "${CAUCE_API_KEY:-}")"
[ -n "$CAUCE_API_KEY" ] || fail "Set CAUCE_API_KEY to an operator key (or run the quickstart first)."
if [ -z "${CAUCE_OPERATOR_ID:-}" ]; then
    CAUCE_OPERATOR_ID=$(api GET /v1/me | json_get tenant_id)
fi
save() { echo "$1=$2" >> "$STATE_FILE"; }
[ -f "$STATE_FILE" ] || echo "# Cauce demo bar state (ids only, no secrets). Delete to start over." > "$STATE_FILE"

# --- 1. tenants --------------------------------------------------------------

if [ -z "${BAR_TENANT_ID:-}" ]; then
    say "Creating the partner and the business 'Bar La Esquina'..."
    partner_id=$(api POST /v1/tenants/partner \
        "{\"name\":\"Demo Hostelería\",\"operator_id\":\"$CAUCE_OPERATOR_ID\"}" | json_get id)
    BAR_TENANT_ID=$(api POST /v1/tenants/client \
        "{\"name\":\"Bar La Esquina\",\"partner_id\":\"$partner_id\"}" | json_get id)
    save BAR_TENANT_ID "$BAR_TENANT_ID"
fi
echo "   Business: $BAR_TENANT_ID"

# --- 2. sheet, menu, agenda --------------------------------------------------

say "Loading the sheet, the menu and the agenda..."
api PUT "/v1/tenants/$BAR_TENANT_ID/business-profile" "@$PACK/bar-ejemplo/ficha.json" > /dev/null
api PUT "/v1/tenants/$BAR_TENANT_ID/catalog" "@$PACK/bar-ejemplo/carta.json" > /dev/null
api PUT "/v1/tenants/$BAR_TENANT_ID/agenda" "@$PACK/bar-ejemplo/agenda.json" > /dev/null
echo "   Loaded."

# --- 3. agents ---------------------------------------------------------------

agent_body() { # agent_body NAME PROMPT_FILE TOOLS_KEY
    printf '{"name":"%s","system_prompt":%s,"model_provider":"%s","model_name":"%s","tools":%s}' \
        "$1" "$(json_string < "$PACK/$2")" "$MODEL_PROVIDER" "$MODEL_NAME" \
        "$(json_list "$PACK/herramientas.json" "$3")"
}
if [ -z "${CUSTOMER_AGENT_ID:-}" ]; then
    say "Creating the customer agent ($MODEL_PROVIDER / $MODEL_NAME)..."
    CUSTOMER_AGENT_ID=$(api POST "/v1/tenants/$BAR_TENANT_ID/agents" \
        "$(agent_body 'Camarero de La Esquina' agente-clientes.prompt.md agente_clientes)" | json_get id)
    save CUSTOMER_AGENT_ID "$CUSTOMER_AGENT_ID"
    OWNER_AGENT_ID=$(api POST "/v1/tenants/$BAR_TENANT_ID/agents" \
        "$(agent_body 'Canal del dueño' agente-dueno.prompt.md agente_dueno)" | json_get id)
    save OWNER_AGENT_ID "$OWNER_AGENT_ID"
else
    say "Refreshing the prompts of the existing agents..."
    api PATCH "/v1/agents/$CUSTOMER_AGENT_ID" \
        "{\"system_prompt\":$(json_string < "$PACK/agente-clientes.prompt.md")}" > /dev/null
    api PUT "/v1/agents/$CUSTOMER_AGENT_ID/tools" \
        "{\"tools\":$(json_list "$PACK/herramientas.json" agente_clientes)}" > /dev/null
    api PATCH "/v1/agents/$OWNER_AGENT_ID" \
        "{\"system_prompt\":$(json_string < "$PACK/agente-dueno.prompt.md")}" > /dev/null
fi
echo "   Customer agent: $CUSTOMER_AGENT_ID"
echo "   Owner agent:    $OWNER_AGENT_ID"

# --- 4. Telegram bots --------------------------------------------------------

bind_bot() { # bind_bot AGENT_ID TOKEN STATE_KEY -> sets BOUND_CONFIG_ID, BOUND_SECRET
    local created
    created=$(api POST "/v1/agents/$1/channels" "{\"channel_type\":\"telegram\",\"credential\":\"$2\"}")
    BOUND_CONFIG_ID=$(printf '%s' "$created" | json_get id)
    BOUND_SECRET=$(printf '%s' "$created" | json_get webhook_secret)
    save "$3" "$BOUND_CONFIG_ID"
}
register_webhook() { # register_webhook TOKEN CONFIG_ID SECRET
    if [ -z "$PUBLIC_URL" ]; then
        echo "   PUBLIC_URL not set: register the webhook yourself (see the README)."
        echo "     url=<public url>/webhooks/channels/$2  secret_token=$3"
        return
    fi
    telegram "$1" setWebhook -d "url=$PUBLIC_URL/webhooks/channels/$2" -d "secret_token=$3" \
        -d 'allowed_updates=["message"]' | grep -q '"ok":true' || fail "Telegram setWebhook failed for config $2"
    echo "   Webhook registered: $PUBLIC_URL/webhooks/channels/$2"
}

if [ -n "$CUSTOMER_TOKEN" ] && [ -z "${CUSTOMER_BOT_CONFIG_ID:-}" ]; then
    say "Binding the customer bot..."
    bind_bot "$CUSTOMER_AGENT_ID" "$CUSTOMER_TOKEN" CUSTOMER_BOT_CONFIG_ID
    CUSTOMER_BOT_CONFIG_ID=$BOUND_CONFIG_ID
    register_webhook "$CUSTOMER_TOKEN" "$BOUND_CONFIG_ID" "$BOUND_SECRET"
fi
if [ -n "$OWNER_TOKEN" ] && [ -z "${OWNER_BOT_CONFIG_ID:-}" ]; then
    say "Binding the owner bot..."
    bind_bot "$OWNER_AGENT_ID" "$OWNER_TOKEN" OWNER_BOT_CONFIG_ID
    OWNER_BOT_CONFIG_ID=$BOUND_CONFIG_ID
    register_webhook "$OWNER_TOKEN" "$BOUND_CONFIG_ID" "$BOUND_SECRET"
fi

# --- 5. owner link -----------------------------------------------------------

if [ -n "${OWNER_BOT_CONFIG_ID:-}" ]; then
    say "Issuing the owner's link code (valid 24 h)..."
    code=$(api POST "/v1/tenants/$BAR_TENANT_ID/owner-link-codes" \
        "{\"channel_config_id\":\"$OWNER_BOT_CONFIG_ID\"}" | json_get code)
    owner_bot=$( [ -n "$OWNER_TOKEN" ] && telegram "$OWNER_TOKEN" getMe | json_get result.username || true)
    if [ -n "$owner_bot" ]; then
        echo "   Open this link on the owner's phone and press Start:"
        echo "     https://t.me/$owner_bot?start=$code"
    else
        echo "   Send this code to the owner bot: $code"
    fi
fi

say "Done."
echo "   Business:       $BAR_TENANT_ID"
[ -n "$CUSTOMER_TOKEN" ] && {
    customer_bot=$(telegram "$CUSTOMER_TOKEN" getMe | json_get result.username || true)
    [ -n "$customer_bot" ] && echo "   Customer bot:   https://t.me/$customer_bot"
}
echo "   Read the cases:  GET $API_URL/v1/tenants/$BAR_TENANT_ID/handoffs"
echo "   Summary now:     POST $API_URL/v1/tenants/$BAR_TENANT_ID/daily-digests"
