#!/usr/bin/env bash
# Regression test of the alert rules provision.sh creates, against a throwaway OpenObserve.
#
# It pushes the metrics two agents would export, each answering 6 of 6 messages with the fallback,
# one after the other, and checks what reaches a local receiver standing in for Telegram:
#   - each agent gets its own cauce-fallback-rate: one agent's alert never silences another's
#     (it did, when the silence was per rule: a second bar was muted for an hour);
#   - the first agent's alert is not repeated while silenced;
#   - the message is readable: "100 % de respaldo" and the time in UTC, said so.
#
# Needs docker, curl and python3; takes about three minutes; touches no other container and no
# file outside a temporary directory. Not part of the build: run it after changing provision.sh.
#
#   scripts/observability/test-alerts.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PORT="${TEST_OPENOBSERVE_PORT:-5089}"
HOOK_PORT="${TEST_HOOK_PORT:-8089}"
NAME=cauce-openobserve-alerts-test
USER_EMAIL=root@example.com
USER_PASS='Test#Alerts2026'
URL="http://localhost:$PORT"
WORK="$(mktemp -d)"
HOOK_PID=

cleanup() {
  [ -n "$HOOK_PID" ] && kill "$HOOK_PID" 2>/dev/null || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; echo "--- received:" >&2; cat "$WORK/hooks.log" >&2 || true; exit 1; }

echo "Starting a throwaway OpenObserve on port $PORT..."
docker rm -f "$NAME" >/dev/null 2>&1 || true
# Outbound checks off only here: the receiver is on this machine, which OpenObserve refuses by default.
docker run -d --name "$NAME" -p "$PORT:5080" --add-host host.docker.internal:host-gateway \
  -e ZO_ROOT_USER_EMAIL="$USER_EMAIL" -e ZO_ROOT_USER_PASSWORD="$USER_PASS" -e ZO_DATA_DIR=/data \
  -e ZO_SKIP_SSRF_CHECKS=true -e ZO_TELEMETRY=false openobserve/openobserve:v1.0.4 >/dev/null
for _ in $(seq 1 60); do curl -fs -m 2 "$URL/healthz" >/dev/null 2>&1 && break; sleep 1; done
curl -fs -m 2 "$URL/healthz" >/dev/null || fail "OpenObserve did not start"

# The receiver: appends each POST body, one per line.
cat > "$WORK/hook.py" <<'PY'
import http.server, sys
out, port = sys.argv[1], int(sys.argv[2])
class Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with open(out, "ab") as f:
            f.write(body.replace(b"\n", b" ") + b"\n")
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"ok":true}')
    def log_message(self, *args):
        pass
http.server.HTTPServer(("0.0.0.0", port), Hook).serve_forever()
PY
: > "$WORK/hooks.log"
python3 -I "$WORK/hook.py" "$WORK/hooks.log" "$HOOK_PORT" &
HOOK_PID=$!

# One export step of an agent: 6 messages, 6 fallbacks, as delta counters (what the API sends).
push() { # agent
  python3 -I - "$URL" "$USER_EMAIL:$USER_PASS" "$1" <<'PY'
import base64, json, sys, time, urllib.request
url, cred, agent = sys.argv[1:4]
now = time.time_ns(); start = now - 60 * 10**9
tags = [{"key": "tenant_id", "value": {"stringValue": "tenant-" + agent}},
        {"key": "agent_id", "value": {"stringValue": agent}}]
def delta(name, value, extra=()):
    return {"name": name, "sum": {"aggregationTemporality": 1, "isMonotonic": True, "dataPoints": [
        {"attributes": tags + list(extra), "startTimeUnixNano": str(start), "timeUnixNano": str(now),
         "asDouble": value}]}}
metrics = [{"name": "cauce.queue.pending", "gauge": {"dataPoints": [{"timeUnixNano": str(now), "asDouble": 0}]}},
           delta("cauce.orchestration.invocations.requested", 6),
           delta("cauce.orchestration.fallbacks", 6,
                 [{"key": "failure_type", "value": {"stringValue": "AGENT_CONFIGURATION"}}])]
body = {"resourceMetrics": [{"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "cauce"}}]},
                             "scopeMetrics": [{"metrics": metrics}]}]}
request = urllib.request.Request(url + "/api/default/v1/metrics", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Basic " + base64.b64encode(cred.encode()).decode()})
urllib.request.urlopen(request).read()
PY
}

wait_for() { # pattern seconds
  for _ in $(seq 1 "$2"); do grep -q "$1" "$WORK/hooks.log" && return 0; sleep 1; done
  return 1
}

push agent-one
OPENOBSERVE_URL="$URL" OPENOBSERVE_ROOT_EMAIL="$USER_EMAIL" OPENOBSERVE_ROOT_PASSWORD="$USER_PASS" \
  TELEGRAM_CHAT_ID=0 ALERT_WEBHOOK_URL="http://host.docker.internal:$HOOK_PORT/sendMessage" \
  bash "$HERE/provision.sh" >/dev/null

echo "Waiting for the first agent's alert..."
wait_for 'agent-one' 180 || fail "no cauce-fallback-rate for the first agent"
echo "Second agent, while the first one's alert is silenced..."
push agent-two
wait_for 'agent-two' 180 || fail "no cauce-fallback-rate for the second agent: one agent's alert silenced another's"

[ "$(grep -c 'agent-one' "$WORK/hooks.log")" = 1 ] || fail "the first agent's alert was repeated while silenced"
grep 'agent-two' "$WORK/hooks.log" | grep -q 'cauce-fallback-rate' || fail "the second alert is not cauce-fallback-rate"
grep 'agent-two' "$WORK/hooks.log" | grep -q '100 % de respaldo' || fail "the value is not readable as a percentage"
grep 'agent-two' "$WORK/hooks.log" | grep -q 'UTC' || fail "the time does not say its zone"
echo "PASS: one alert per agent, none repeated, readable."
