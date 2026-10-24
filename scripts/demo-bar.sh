#!/usr/bin/env bash
# Cauce demo bar: loads the reference bar of the hospitality sector pack (examples/hosteleria)
# into a running Cauce instance and, given two Telegram bots, wires them up. See
# examples/hosteleria/README.md.
#
# What it does (each step is one plain API call):
#   1. Finds the business "Bar La Esquina" under a partner "Demo Hostelería" of this operator,
#      or creates them: a run never makes a second business on the same instance.
#   2. Loads its sheet, menu and agenda from examples/hosteleria/bar-ejemplo.
#   3. Finds or creates the customer agent (prompt + tool grant of the pack) and the owner agent,
#      applies their prompt cache retention (retencion-cache.json), and adds the price of their
#      model to the operator's price table when it has none.
#   4. With TELEGRAM_CUSTOMER_BOT_TOKEN / TELEGRAM_OWNER_BOT_TOKEN (and PUBLIC_URL): binds each
#      bot to its agent once and, on every run, renews its webhook secret, registers the webhook
#      with Telegram and checks with getWebhookInfo that it took. Any failure stops the script.
#   5. Issues the owner's link code and prints the link that binds the owner's chat.
#
# Settings (environment or .env):
#   CAUCE_API_URL       default http://localhost:8080
#   CAUCE_API_KEY       an operator key; default: the one saved by the quickstart (.quickstart.env)
#   CAUCE_OPERATOR_ID   the operator tenant id; default: from .quickstart.env, else GET /v1/me
#   MODEL_PROVIDER      default anthropic   (needs ANTHROPIC_API_KEY on the instance; checked first)
#   MODEL_NAME          default claude-sonnet-5-5
#   MODEL_PRICE_INPUT, MODEL_PRICE_OUTPUT, MODEL_PRICE_CACHE_READ, MODEL_PRICE_CACHE_WRITE,
#   MODEL_PRICE_CACHE_WRITE_LONG (the one-hour write)
#                       US$ per million tokens; default: the list price of claude-sonnet-5-5 or
#                       claude-haiku-5-5, none for
#                       another model (its usage is then reported unpriced)
#   TELEGRAM_CUSTOMER_BOT_TOKEN, TELEGRAM_OWNER_BOT_TOKEN   from @BotFather (optional)
#   PUBLIC_URL          public https URL that reaches the instance, e.g. a tunnel (required with a token)
#   TELEGRAM_API_URL    default https://api.telegram.org (a stub, for testing this script)
#
# Requirements: curl, and python3 or jq. Re-running reuses the ids saved in .demo-bar.env
# (gitignored) and reloads the sheet, menu and agenda. Without that file the business and its
# agents are found again by name (the oldest of each when several match), so losing it does not
# duplicate them; only the bot bindings, which the API does not list, are made again.

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
    # json_named NAME_JSON: the ids of the items of .data named NAME_JSON, on one line (Windows
    # Python ends lines with CR LF, and the shell drops only the last CR). The name
    # is a JSON string so that "Hostelería" travels as \u00ed: argv is re-encoded on Windows.
    json_named()  { python3 -c 'import json,sys; n=json.loads(sys.argv[1]); print(" ".join(x["id"] for x in json.load(sys.stdin)["data"] if x.get("name") == n))' "$1"; }
    # json_priced PROVIDER MODEL: "yes" when a price of .data is for that provider and model.
    json_priced() { python3 -c 'import json,sys; print("yes" if any(x["provider"] == sys.argv[1] and x["model"] == sys.argv[2] for x in json.load(sys.stdin)["data"]) else "")' "$1" "$2"; }
elif command -v jq >/dev/null 2>&1; then
    json_get()    { jq -r ".$1"; }
    json_opt()    { jq -r ".$1 // \"\""; }
    json_string() { jq -Rs .; }
    json_list()   { jq -c ".$2" "$1"; }
    json_named()  { jq -r --argjson n "$1" '[.data[] | select(.name == $n) | .id] | join(" ")'; }
    json_priced() { jq -r --arg p "$1" --arg m "$2" 'if any(.data[]; .provider == $p and .model == $m) then "yes" else "" end'; }
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

# The agents' provider must be configured on the instance, or every message they get fails: ask
# it before creating anything.
providers=$(api GET /v1/llm-providers)
printf '%s' "$providers" | grep -q "\"id\":\"$MODEL_PROVIDER\",\"configured\":true" || fail "The instance has no '$MODEL_PROVIDER' provider configured (it answered: $providers).
       anthropic on the quickstart's stack: put ANTHROPIC_API_KEY in .env and recreate the app,
       docker compose --profile api up -d. Or the local model (small, a weaker waiter):
       MODEL_PROVIDER=ollama MODEL_NAME=qwen2.5:3b ./scripts/demo-bar.sh"
[ -f "$STATE_FILE" ] || echo "# Cauce demo bar state (ids only, no secrets). Delete to start over." > "$STATE_FILE"

# all_named PATH NAME_JSON: the ids of every item named NAME_JSON in the list PATH, oldest first
# (every page; ids are UUIDv7, so list order is creation order).
all_named() {
    local path="$1" name="$2" cursor="" page
    while :; do
        page=$(api GET "$path?limit=200${cursor:+&cursor=$cursor}")
        printf '%s ' "$(printf '%s' "$page" | json_named "$name")"
        cursor=$(printf '%s' "$page" | json_opt next_cursor)
        [ -n "$cursor" ] || return 0
    done
}
first_named() { set -- $(all_named "$1" "$2"); printf '%s' "${1:-}"; }

# --- 1. tenants --------------------------------------------------------------

PARTNER_NAME='"Demo Hosteler\u00eda"'
BAR_NAME='"Bar La Esquina"'
if [ -z "${BAR_TENANT_ID:-}" ]; then
    # No saved id (a first run, or the state file is gone): reuse the business already here.
    for partner_id in $(all_named "/v1/tenants/$CAUCE_OPERATOR_ID/children" "$PARTNER_NAME"); do
        BAR_TENANT_ID=$(first_named "/v1/tenants/$partner_id/children" "$BAR_NAME")
        [ -n "$BAR_TENANT_ID" ] && break
    done
    if [ -n "${BAR_TENANT_ID:-}" ]; then
        say "Reusing the business 'Bar La Esquina' already on this instance..."
    else
        say "Creating the partner and the business 'Bar La Esquina'..."
        partner_id=$(first_named "/v1/tenants/$CAUCE_OPERATOR_ID/children" "$PARTNER_NAME")
        if [ -z "$partner_id" ]; then
            partner_id=$(api POST /v1/tenants/partner \
                "{\"name\":\"Demo Hostelería\",\"operator_id\":\"$CAUCE_OPERATOR_ID\"}" | json_get id)
        fi
        BAR_TENANT_ID=$(api POST /v1/tenants/client \
            "{\"name\":\"Bar La Esquina\",\"partner_id\":\"$partner_id\"}" | json_get id)
    fi
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

# The agents' configuration from the pack: prompt, tool grant, fallback reply (respaldo.json,
# the text a person gets when an invocation fails for good, ADR 0005 C3) and the reply to a message
# Cauce does not read (no-soportado.json: a voice note, a photo; ADR 0006 W5).
agent_body() { # agent_body NAME PROMPT_FILE KEY
    printf '{"name":"%s","system_prompt":%s,"model_provider":"%s","model_name":"%s","tools":%s,"fallback_reply":%s,"unsupported_message_reply":%s}' \
        "$1" "$(json_string < "$PACK/$2")" "$MODEL_PROVIDER" "$MODEL_NAME" \
        "$(json_list "$PACK/herramientas.json" "$3")" "$(json_list "$PACK/respaldo.json" "$3")" \
        "$(json_list "$PACK/no-soportado.json" "$3")"
}
agent_patch() { # agent_patch PROMPT_FILE KEY
    printf '{"system_prompt":%s,"fallback_reply":%s,"unsupported_message_reply":%s}' \
        "$(json_string < "$PACK/$1")" "$(json_list "$PACK/respaldo.json" "$2")" \
        "$(json_list "$PACK/no-soportado.json" "$2")"
}
# Without saved ids, the business's own agents are found by name before any is created.
if [ -z "${CUSTOMER_AGENT_ID:-}" ] || [ -z "${OWNER_AGENT_ID:-}" ]; then
    CUSTOMER_AGENT_ID=${CUSTOMER_AGENT_ID:-$(first_named "/v1/tenants/$BAR_TENANT_ID/agents" '"Camarero de La Esquina"')}
    OWNER_AGENT_ID=${OWNER_AGENT_ID:-$(first_named "/v1/tenants/$BAR_TENANT_ID/agents" '"Canal del due\u00f1o"')}
    if [ -n "$CUSTOMER_AGENT_ID" ] && [ -n "$OWNER_AGENT_ID" ]; then
        say "Reusing the agents already on this business..."
        save CUSTOMER_AGENT_ID "$CUSTOMER_AGENT_ID"
        save OWNER_AGENT_ID "$OWNER_AGENT_ID"
    fi
fi
if [ -z "$CUSTOMER_AGENT_ID" ] || [ -z "$OWNER_AGENT_ID" ]; then
    say "Creating the agents ($MODEL_PROVIDER / $MODEL_NAME)..."
    if [ -z "$CUSTOMER_AGENT_ID" ]; then
        CUSTOMER_AGENT_ID=$(api POST "/v1/tenants/$BAR_TENANT_ID/agents" \
            "$(agent_body 'Camarero de La Esquina' agente-clientes.prompt.md agente_clientes)" | json_get id)
    fi
    if [ -z "$OWNER_AGENT_ID" ]; then
        OWNER_AGENT_ID=$(api POST "/v1/tenants/$BAR_TENANT_ID/agents" \
            "$(agent_body 'Canal del dueño' agente-dueno.prompt.md agente_dueno)" | json_get id)
    fi
    save CUSTOMER_AGENT_ID "$CUSTOMER_AGENT_ID"
    save OWNER_AGENT_ID "$OWNER_AGENT_ID"
else
    say "Refreshing the prompts, tools and fixed replies of the existing agents..."
    api PATCH "/v1/agents/$CUSTOMER_AGENT_ID" "$(agent_patch agente-clientes.prompt.md agente_clientes)" > /dev/null
    api PUT "/v1/agents/$CUSTOMER_AGENT_ID/tools" \
        "{\"tools\":$(json_list "$PACK/herramientas.json" agente_clientes)}" > /dev/null
    api PATCH "/v1/agents/$OWNER_AGENT_ID" "$(agent_patch agente-dueno.prompt.md agente_dueno)" > /dev/null
fi
# How long each agent asks the provider to keep its prompt cached (retencion-cache.json, ADR 0010 S9).
for pair in "$CUSTOMER_AGENT_ID agente_clientes" "$OWNER_AGENT_ID agente_dueno"; do
    set -- $pair
    api PATCH "/v1/agents/$1" "{\"prompt_cache_retention\":$(json_list "$PACK/retencion-cache.json" "$2")}" > /dev/null
done
echo "   Customer agent: $CUSTOMER_AGENT_ID"
echo "   Owner agent:    $OWNER_AGENT_ID"

# --- 3b. the model's price ----------------------------------------------------

# Usage is priced from the operator's price table (ADR 0010, S1). The demo adds the price of the
# model its agents use, once: a price is never changed, a new one is a new row.
# The list prices checked in examples/hosteleria/evals/precios.yaml (Haiku's, for prompts up to 100K).
case "$MODEL_PROVIDER/$MODEL_NAME" in
    anthropic/claude-sonnet-5-5) default_in=2.00 default_out=10.00 default_read=0.10 default_write=2.50 default_long=4.00 ;;
    anthropic/claude-haiku-5-5)  default_in=0.10 default_out=0.50  default_read=0.01 default_write=0.125 default_long=0.20 ;;
    *)                           default_in="" default_out="" default_read="" default_write="" default_long="" ;;
esac
PRICE_IN="$(read_knob MODEL_PRICE_INPUT "$default_in")"
PRICE_OUT="$(read_knob MODEL_PRICE_OUTPUT "$default_out")"
PRICE_READ="$(read_knob MODEL_PRICE_CACHE_READ "$default_read")"
PRICE_WRITE="$(read_knob MODEL_PRICE_CACHE_WRITE "$default_write")"
PRICE_LONG="$(read_knob MODEL_PRICE_CACHE_WRITE_LONG "$default_long")"
has_price() { # every page of the price table
    local cursor="" page
    while :; do
        page=$(api GET "/v1/llm-prices?limit=200${cursor:+&cursor=$cursor}")
        if [ -n "$(printf '%s' "$page" | json_priced "$MODEL_PROVIDER" "$MODEL_NAME")" ]; then
            return 0
        fi
        cursor=$(printf '%s' "$page" | json_opt next_cursor)
        [ -n "$cursor" ] || return 1
    done
}
if has_price; then
    echo "   $MODEL_PROVIDER / $MODEL_NAME already has a price."
elif [ -z "$PRICE_IN" ] || [ -z "$PRICE_OUT" ]; then
    echo "   $MODEL_PROVIDER / $MODEL_NAME has no price: its usage is reported unpriced, and a spend"
    echo "   limit would refuse it. Set MODEL_PRICE_INPUT and MODEL_PRICE_OUTPUT (US\$ per million tokens)."
else
    say "Adding the price of $MODEL_PROVIDER / $MODEL_NAME..."
    api POST /v1/llm-prices "$(printf '{"provider":"%s","model":"%s","input_usd_per_mtok":%s,"output_usd_per_mtok":%s%s%s%s}' \
        "$MODEL_PROVIDER" "$MODEL_NAME" "$PRICE_IN" "$PRICE_OUT" \
        "${PRICE_READ:+,\"cache_read_usd_per_mtok\":$PRICE_READ}" \
        "${PRICE_WRITE:+,\"cache_write_usd_per_mtok\":$PRICE_WRITE}" \
        "${PRICE_LONG:+,\"cache_write_long_usd_per_mtok\":$PRICE_LONG}")" > /dev/null
    echo "   Added."
fi

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
echo "   Talk to the waiter (the customer agent) without Telegram — the reply arrives"
echo "   asynchronously; read it from the conversation the 202 names:"
echo "     curl -X POST $API_URL/v1/agents/$CUSTOMER_AGENT_ID/messages \\"
echo "          -H \"Authorization: Bearer \$CAUCE_API_KEY\" -H \"Content-Type: application/json\" \\"
echo "          -d '{\"external_identity_ref\":\"cliente-1\",\"content\":\"¿Tenéis mesa para cuatro esta noche?\"}'"
echo "     curl $API_URL/v1/conversations/<conversation_id>/messages -H \"Authorization: Bearer \$CAUCE_API_KEY\""
echo "   (CAUCE_API_KEY: the operator key, in .quickstart.env.)"
echo "   Read the cases:  GET $API_URL/v1/tenants/$BAR_TENANT_ID/handoffs"
echo "   Summary now:     POST $API_URL/v1/tenants/$BAR_TENANT_ID/daily-digests"
