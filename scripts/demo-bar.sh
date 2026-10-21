#!/usr/bin/env bash
# Cauce demo bar: loads the reference bar of the hospitality sector pack (examples/hosteleria)
# into a running Cauce instance and, given two Telegram bots, wires them up. See
# examples/hosteleria/README.md.
#
# What it does (each step is one plain API call):
#   1. Creates a partner and the business "Bar La Esquina" (a client tenant).
#   2. Loads its sheet, menu and agenda from examples/hosteleria/bar-ejemplo.
#   3. Creates the customer agent (prompt + tool grant of the pack) and the owner agent.
#   4. With TELEGRAM_CUSTOMER_BOT_TOKEN / TELEGRAM_OWNER_BOT_TOKEN (and PUBLIC_URL): binds each
#      bot to its agent once and, on every run, renews its webhook secret, registers the webhook
#      with Telegram and checks with getWebhookInfo that it took. Any failure stops the script.
#   5. Issues the owner's link code and prints the link that binds the owner's chat.
#
# Settings (environment or .env):
#   CAUCE_API_URL       default http://localhost:8080
#   CAUCE_API_KEY       an operator key; default: the one saved by the quickstart (.quickstart.env)
#   CAUCE_OPERATOR_ID   the operator tenant id; default: from .quickstart.env, else GET /v1/me
#   MODEL_PROVIDER      default anthropic   (needs ANTHROPIC_API_KEY on the instance)
#   MODEL_NAME          default claude-sonnet-5-5
#   TELEGRAM_CUSTOMER_BOT_TOKEN, TELEGRAM_OWNER_BOT_TOKEN   from @BotFather (optional)
#   PUBLIC_URL          public https URL that reaches the instance, e.g. a tunnel (required with a token)
#   TELEGRAM_API_URL    default https://api.telegram.org (a stub, for testing this script)
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
TELEGRAM_API_URL="$(read_knob TELEGRAM_API_URL https://api.telegram.org)"

say()  { printf '\n== %s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# Python on Windows would read the prompt files from stdin in the ANSI code page.
export PYTHONIOENCODING=utf-8
if command -v python3 >/dev/null 2>&1 && python3 -c '' >/dev/null 2>&1; then
    # json_get PATH: the value at a dotted PATH; json_opt PATH: the same, or empty when absent.
    json_get()    { python3 -c 'import functools,json,sys; print(functools.reduce(lambda v, k: v[k], sys.argv[1].split("."), json.load(sys.stdin)))' "$1"; }
    json_opt()    { python3 -c 'import functools,json,sys; v=functools.reduce(lambda v, k: v.get(k) if isinstance(v, dict) else None, sys.argv[1].split("."), json.load(sys.stdin)); print("" if v is None else v)' "$1"; }
    json_string() { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }
    json_list()   { python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]]))' "$1" "$2"; }
elif command -v jq >/dev/null 2>&1; then
    json_get()    { jq -r ".$1"; }
    json_opt()    { jq -r ".$1 // \"\""; }
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
    curl -sS "$TELEGRAM_API_URL/bot$token/$method" "$@"
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

# connect_bot LABEL AGENT_ID TOKEN STATE_KEY: binds the bot once, then on EVERY run registers its
# webhook again and checks it took. Cauce keeps only the hash of a webhook secret, so an existing
# binding gets a fresh secret (POST /v1/channel-configs/{id}/webhook-secret) and Telegram is
# pointed at it; a run that failed half-way is repaired by the next one.
connect_bot() {
    local label="$1" agent_id="$2" token="$3" state_key="$4" config_id secret result url info actual last_error
    say "Connecting the $label bot..."
    case "$PUBLIC_URL" in
        https://*) ;;
        "") fail "PUBLIC_URL is required to connect the $label bot: Telegram must reach the instance (e.g. a cloudflared tunnel URL)." ;;
        *)  fail "PUBLIC_URL must be an https:// URL (Telegram only calls https webhooks), not '$PUBLIC_URL'." ;;
    esac
    result=$(telegram "$token" getMe)
    [ "$(printf '%s' "$result" | json_opt ok)" = "True" ] || [ "$(printf '%s' "$result" | json_opt ok)" = "true" ] \
        || fail "Telegram rejects the $label bot token: $(printf '%s' "$result" | json_opt description)"

    eval "config_id=\${$state_key:-}"
    if [ -z "$config_id" ]; then
        result=$(api POST "/v1/agents/$agent_id/channels" "{\"channel_type\":\"telegram\",\"credential\":\"$token\"}")
        config_id=$(printf '%s' "$result" | json_get id)
        save "$state_key" "$config_id"
        echo "   Bound: channel config $config_id"
    else
        result=$(api POST "/v1/channel-configs/$config_id/webhook-secret") \
            || fail "Could not renew the secret of channel config $config_id (from $STATE_FILE). If that binding is gone, delete the $state_key line and run again."
        echo "   Existing binding: channel config $config_id (new webhook secret issued)"
    fi
    secret=$(printf '%s' "$result" | json_get webhook_secret)
    eval "$state_key=\$config_id"

    url="$PUBLIC_URL/webhooks/channels/$config_id"
    result=$(telegram "$token" setWebhook -d "url=$url" -d "secret_token=$secret" -d 'allowed_updates=["message"]')
    case "$(printf '%s' "$result" | json_opt ok)" in
        True|true) ;;
        *) fail "Telegram setWebhook failed for the $label bot: $(printf '%s' "$result" | json_opt description)" ;;
    esac
    info=$(telegram "$token" getWebhookInfo)
    actual=$(printf '%s' "$info" | json_opt result.url)
    [ "$actual" = "$url" ] || fail "The $label bot's webhook is '$actual' after setWebhook, expected '$url'."
    last_error=$(printf '%s' "$info" | json_opt result.last_error_message)
    echo "   Webhook verified: $url"
    [ -n "$last_error" ] && echo "   (Telegram's last delivery error, from before this run: $last_error)"
    return 0
}

[ -n "$CUSTOMER_TOKEN" ] && connect_bot customer "$CUSTOMER_AGENT_ID" "$CUSTOMER_TOKEN" CUSTOMER_BOT_CONFIG_ID
[ -n "$OWNER_TOKEN" ] && connect_bot owner "$OWNER_AGENT_ID" "$OWNER_TOKEN" OWNER_BOT_CONFIG_ID

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
