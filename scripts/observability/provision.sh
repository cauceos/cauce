#!/usr/bin/env bash
# Provisions Cauce's alerts and dashboard in OpenObserve (ADR 0011, A1): a template and a destination
# that send to the operator's own Telegram bot, the minimum rules, and the operator's dashboard
# (dashboard.json, next to this script). Idempotent: re-running replaces them.
#
# The bot token stays in OpenObserve's configuration (the destination's URL), never in Cauce.
# Needs curl and python3 (to read OpenObserve's JSON). See docs/operations/observability.md.
#
#   OPENOBSERVE_URL=http://localhost:5080 OPENOBSERVE_ROOT_EMAIL=... OPENOBSERVE_ROOT_PASSWORD=... \
#   TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... scripts/observability/provision.sh
#
# ALERT_WEBHOOK_URL replaces the Telegram URL (to test the rules against a local receiver).
set -euo pipefail

OO_URL="${OPENOBSERVE_URL:-http://localhost:5080}"
OO_ORG="${OPENOBSERVE_ORG:-default}"
OO_USER="${OPENOBSERVE_ROOT_EMAIL:?set OPENOBSERVE_ROOT_EMAIL}"
OO_PASS="${OPENOBSERVE_ROOT_PASSWORD:?set OPENOBSERVE_ROOT_PASSWORD}"
CHAT_ID="${TELEGRAM_CHAT_ID:?set TELEGRAM_CHAT_ID}"
if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
  WEBHOOK="$ALERT_WEBHOOK_URL"
else
  WEBHOOK="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN:?set TELEGRAM_BOT_TOKEN}/sendMessage"
fi

TEMPLATE=cauce_telegram
DESTINATION=cauce_operator_telegram
PREFIX=cauce-

api() { # method path [json]; fails on any HTTP error, printing OpenObserve's answer
  if [ $# -ge 3 ]; then
    curl -sS --fail-with-body -u "$OO_USER:$OO_PASS" -X "$1" -H 'Content-Type: application/json' --data "$3" "$OO_URL$2"
  else
    curl -sS --fail-with-body -u "$OO_USER:$OO_PASS" -X "$1" "$OO_URL$2"
  fi
}

json() { # python expression over `d`, the JSON on stdin (read as bytes: UTF-8 whatever the locale)
  python3 -c "import json,sys; d=json.loads(sys.stdin.buffer.read()); print($1)" | tr -d '\r'
}

upsert() { # collection name json
  local status
  status=$(curl -sS -o /dev/null -w '%{http_code}' -u "$OO_USER:$OO_PASS" "$OO_URL/api/$OO_ORG/alerts/$1/$2")
  if [ "$status" = 200 ]; then api PUT "/api/$OO_ORG/alerts/$1/$2" "$3"; else api POST "/api/$OO_ORG/alerts/$1" "$3"; fi
  echo
}

# --- The message: ids and counts only (ADR 0011, L2) ----------------------------------------------
TEMPLATE_BODY=$(python3 - "$CHAT_ID" <<'PY'
import json, sys
text = "Cauce: {alert_name}\n{alert_description}\nValor: {alert_agg_value}\n{rows}\n{alert_trigger_time_str}"
print(json.dumps({"chat_id": sys.argv[1], "text": text}))
PY
)
upsert templates "$TEMPLATE" "$(python3 -c 'import json,sys; print(json.dumps({"name": sys.argv[1], "type": "http", "body": sys.argv[2]}))' "$TEMPLATE" "$TEMPLATE_BODY")"
upsert destinations "$DESTINATION" "$(python3 -c 'import json,sys; print(json.dumps({"name": sys.argv[1], "type": "http", "url": sys.argv[2], "method": "post", "template": sys.argv[3], "headers": {"Content-Type": "application/json"}, "skip_tls_verify": False}))' "$DESTINATION" "$WEBHOOK" "$TEMPLATE")"

# --- The rules ---------------------------------------------------------------------------------------
# Each is a PromQL expression over the metrics the application exports: a series it returns is a
# firing condition. Counters arrive as deltas (one point per export step), so a window's count is
# sum_over_time. Evaluated every minute; once fired, silent for `silence` minutes. OpenObserve
# attaches an alert to a stream that must exist: cauce_queue_pending, which the application exports
# every minute from its start. Run this once the application has been exporting for a minute or two.
alert() { # name period_minutes silence_minutes description promql row_template
  local existing body
  existing=$(api GET "/api/v2/$OO_ORG/alerts" | json "' '.join(a.get('alert_id') or a.get('id') or '' for a in d.get('list', []) if a.get('name') == '$1')")
  for id in $existing; do api DELETE "/api/v2/$OO_ORG/alerts/$id" >/dev/null; done
  body=$(python3 - "$@" "$DESTINATION" <<'PY'
import json, sys
name, period, silence, description, promql, row, destination = sys.argv[1:8]
print(json.dumps({
    "name": name, "description": description, "enabled": True, "is_real_time": False,
    "stream_type": "metrics", "stream_name": "cauce_queue_pending",
    "query_condition": {"type": "promql", "promql": promql,
                        "promql_condition": {"column": "value", "operator": ">", "value": 0}},
    "trigger_condition": {"period": int(period), "frequency": 1, "frequency_type": "minutes",
                          "operator": ">=", "threshold": 1, "silence": int(silence)},
    "destinations": [destination],
    # One line per series that fired: its labels, ids only.
    "row_template": row, "row_template_type": "String",
}))
PY
)
  api POST "/api/v2/$OO_ORG/alerts" "$body"
  echo
}

alert "${PREFIX}provider-down" 5 30 \
  "Más de la mitad de las llamadas a un proveedor fallaron en 5 minutos (al menos 3 llamadas)." \
  'sum by (provider) (sum_over_time(cauce_orchestration_llm_failures[5m])) / sum by (provider) (sum_over_time(cauce_orchestration_llm_calls[5m])) > 0.5 and sum by (provider) (sum_over_time(cauce_orchestration_llm_calls[5m])) >= 3'   'proveedor {provider}'

alert "${PREFIX}fallback-rate" 15 60 \
  "Un agente respondió con el mensaje de respaldo a más del 20 % de los mensajes en 15 minutos (al menos 5)." \
  'sum by (tenant_id, agent_id) (sum_over_time(cauce_orchestration_fallbacks[15m])) / sum by (tenant_id, agent_id) (sum_over_time(cauce_orchestration_invocations_requested[15m])) > 0.2 and sum by (tenant_id, agent_id) (sum_over_time(cauce_orchestration_invocations_requested[15m])) >= 5'   'cliente {tenant_id} agente {agent_id}'

alert "${PREFIX}queue-stuck" 5 15 \
  "La invocación más antigua de la cola lleva más de 2 minutos esperando: el worker no avanza." \
  'max(cauce_queue_oldest_due_age) > 120'   ''

alert "${PREFIX}audit-chain-behind" 10 30 \
  "Hay eventos de auditoría sin encadenar desde hace más de 5 minutos: la cadena no se está escribiendo." \
  'max(cauce_audit_outbox_oldest_age) > 300'   ''

alert "${PREFIX}spend-limit-reached" 15 720 \
  "Un cliente alcanzó su límite de gasto: sus agentes responden con el mensaje de respaldo." \
  'sum by (tenant_id) (sum_over_time(cauce_orchestration_fallbacks{failure_type="SPEND_LIMIT_REACHED"}[15m])) > 0'   'cliente {tenant_id}'

# --- The dashboard -----------------------------------------------------------------------------------
DASHBOARD="$(dirname "$0")/dashboard.json"
# The dashboards with this one's title, compared inside Python: no title crosses the shell's encoding.
for id in $(api GET "/api/$OO_ORG/dashboards" | python3 -c '
import json, sys
title = json.load(open(sys.argv[1], encoding="utf-8"))["title"]
for item in json.loads(sys.stdin.buffer.read()).get("dashboards", []):
    for version in item.values():
        if isinstance(version, dict) and version.get("title") == title:
            print(version["dashboardId"])' "$DASHBOARD" | tr -d '\r'); do
  api DELETE "/api/$OO_ORG/dashboards/$id" >/dev/null
done
curl -sS --fail-with-body -o /dev/null -u "$OO_USER:$OO_PASS" -H 'Content-Type: application/json' \
  --data-binary "@$DASHBOARD" "$OO_URL/api/$OO_ORG/dashboards"

echo "Alerts and dashboard provisioned in $OO_URL (org $OO_ORG), alerts sent to destination $DESTINATION."
