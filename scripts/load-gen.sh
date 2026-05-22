#!/usr/bin/env bash
# load-gen.sh — send test log events to the Log-to-Insight REST API
#
# Requires: bash, curl, awk (standard on macOS and any Linux distro)
#
# Usage:
#   ./scripts/load-gen.sh [OPTIONS]
#
# Options:
#   --api-url URL      API base URL  (env: LOGINSIGHT_API_URL, default: http://localhost:8081)
#   --count N          Number of events to send (default: 200)
#   --rate N           Target requests per second (default: 20)
#   --service NAME     Fix the service name; default is random from the built-in list
#   --level LEVEL      Fix the log level: INFO | WARN | ERROR; default is status-derived
#   --status CODE      Fix the HTTP status code; default is weighted-random
#   --burst-errors     After normal traffic, fire 80 rapid errors on payment-service
#                      — enough to exceed the AnomalyDetector's 6× baseline threshold
#   --help, -h         Print this help text and exit
#
# Examples:
#   # 500 realistic events at 30 req/s, then trigger the anomaly detector
#   ./scripts/load-gen.sh --count 500 --rate 30 --burst-errors
#
#   # Baseline load test without error spikes
#   ./scripts/load-gen.sh --count 1000 --rate 50 --service auth-service
#
#   # Single error event for manual debugging
#   ./scripts/load-gen.sh --count 1 --status 500 --service payment-service

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
API_URL="${LOGINSIGHT_API_URL:-http://localhost:8081}"
COUNT=200
RATE=20
FIX_SERVICE=""
FIX_LEVEL=""
FIX_STATUS=""
BURST_ERRORS=false

# ── Arg parsing ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --api-url)      API_URL="$2";      shift 2 ;;
    --count)        COUNT="$2";        shift 2 ;;
    --rate)         RATE="$2";         shift 2 ;;
    --service)      FIX_SERVICE="$2";  shift 2 ;;
    --level)        FIX_LEVEL="$2";    shift 2 ;;
    --status)       FIX_STATUS="$2";   shift 2 ;;
    --burst-errors) BURST_ERRORS=true; shift ;;
    --help|-h)      sed -n '3,30p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1  (run with --help for usage)" >&2; exit 1 ;;
  esac
done

# ── Data tables ───────────────────────────────────────────────────────────────
SERVICES=("auth-service" "checkout-service" "payment-service" "search-service" "user-service")
MSGS_200=("Request completed successfully" "Resource retrieved" "Cache hit — fast path"
          "Auth token validated" "Payment processed" "Operation committed")
MSGS_4XX=("Invalid request payload" "Authentication required" "Resource not found"
          "Rate limit exceeded" "Malformed query parameter")
MSGS_5XX=("Database connection timeout" "Downstream service unavailable"
          "Circuit breaker open" "Memory pressure — GC stall" "Unhandled exception in handler")

SLEEP_SEC=$(awk "BEGIN{printf \"%.4f\", 1/$RATE}")

# ── Helpers ───────────────────────────────────────────────────────────────────
pick_status() {
  local r=$(( RANDOM % 100 ))
  if   [[ $r -lt 60 ]]; then echo 200
  elif [[ $r -lt 75 ]]; then echo 201
  elif [[ $r -lt 83 ]]; then echo 400
  elif [[ $r -lt 88 ]]; then echo 401
  elif [[ $r -lt 95 ]]; then echo 404
  elif [[ $r -lt 98 ]]; then echo 500
  else echo 503; fi
}

pick_message() {
  local status=$1
  if   [[ $status -ge 500 ]]; then echo "${MSGS_5XX[$(( RANDOM % ${#MSGS_5XX[@]} ))]}"
  elif [[ $status -ge 400 ]]; then echo "${MSGS_4XX[$(( RANDOM % ${#MSGS_4XX[@]} ))]}"
  else                              echo "${MSGS_200[$(( RANDOM % ${#MSGS_200[@]} ))]}"
  fi
}

send_entry() {
  local svc="$1" level="$2" status="$3" msg="$4"
  msg="${msg//\\/\\\\}"; msg="${msg//\"/\\\"}"
  curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_URL/api/v1/logs" \
    -H "Content-Type: application/json" \
    -d "{\"service\":\"$svc\",\"level\":\"$level\",\"statusCode\":$status,\"message\":\"$msg\"}"
}

# ── Pre-flight check ──────────────────────────────────────────────────────────
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$API_URL/api/v1/health" 2>/dev/null || echo "000")
if [[ "$HEALTH" != "200" ]]; then
  echo "ERROR: API not reachable at $API_URL (got HTTP $HEALTH). Is the server running?" >&2
  exit 1
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo "load-gen: sending $COUNT events → $API_URL at ~${RATE} req/s"
[[ -n "$FIX_SERVICE" ]]  && echo "  service  : $FIX_SERVICE (fixed)"
[[ -n "$FIX_STATUS" ]]   && echo "  status   : $FIX_STATUS (fixed)"
[[ -n "$FIX_LEVEL" ]]    && echo "  level    : $FIX_LEVEL (fixed)"
$BURST_ERRORS            && echo "  burst    : 80 errors on payment-service after normal traffic"
echo ""

# ── Main loop ─────────────────────────────────────────────────────────────────
ERROR_COUNT=0
for i in $(seq 1 "$COUNT"); do
  svc="${FIX_SERVICE:-${SERVICES[$(( RANDOM % ${#SERVICES[@]} ))]}}"
  status="${FIX_STATUS:-$(pick_status)}"

  if [[ -n "$FIX_LEVEL" ]]; then
    level="$FIX_LEVEL"
  elif [[ $status -ge 500 ]]; then level="ERROR"
  elif [[ $status -ge 400 ]]; then level="WARN"
  else level="INFO"; fi

  msg="$(pick_message "$status")"

  http_code=$(send_entry "$svc" "$level" "$status" "$msg")
  if [[ "$http_code" != "201" ]]; then
    echo "" && echo "  WARN: API returned HTTP $http_code for event $i" >&2
  fi
  if [[ $status -ge 400 ]]; then ERROR_COUNT=$(( ERROR_COUNT + 1 )); fi

  if [[ $(( i % 10 )) -eq 0 ]]; then
    printf "\r  %d / %d sent" "$i" "$COUNT"
  fi

  sleep "$SLEEP_SEC"
done
printf "\r  %d / %d sent\n" "$COUNT" "$COUNT"

# ── Burst mode — triggers AnomalyDetector ─────────────────────────────────────
if $BURST_ERRORS; then
  echo ""
  echo "load-gen: firing 80-event error burst on payment-service..."
  for i in $(seq 1 80); do
    if [[ $(( RANDOM % 10 )) -lt 7 ]]; then bstatus=500; else bstatus=503; fi
    msg="$(pick_message "$bstatus")"
    send_entry "payment-service" "ERROR" "$bstatus" "$msg" > /dev/null
    sleep 0.05
  done
  echo "load-gen: burst complete — check GET /api/v1/alerts in a few seconds"
fi

echo ""
echo "load-gen: done. total=$COUNT  errors=$ERROR_COUNT"
