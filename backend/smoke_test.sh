#!/usr/bin/env bash
# Hits every Wander TV backend endpoint with realistic payloads and prints results and latency.
#
#   ./smoke_test.sh                                  # URL + key from .deploy/ (written by deploy.sh)
#   ./smoke_test.sh https://abc.execute-api.eu-central-1.amazonaws.com wtv_...
#
# Needs curl and python (for JSON checks). Exits 1 if any check fails.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$HERE/.deploy"
SAMPLES="$HERE/samples"

PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import json" >/dev/null 2>&1; then PY="$candidate"; break; fi
done
[[ -n "$PY" ]] || { echo "python is needed for the JSON checks" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is needed" >&2; exit 1; }

API_URL="${1:-}"
API_KEY="${2:-}"
if [[ -z "$API_URL" ]]; then
  [[ -f "$DEPLOY_DIR/outputs.json" ]] || { echo "No URL given and no .deploy/outputs.json. Run ./deploy.sh first." >&2; exit 1; }
  API_URL="$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1]))["apiUrl"])' "$DEPLOY_DIR/outputs.json")"
fi
if [[ -z "$API_KEY" ]]; then
  [[ -f "$DEPLOY_DIR/api-key.txt" ]] || { echo "No key given and no .deploy/api-key.txt." >&2; exit 1; }
  API_KEY="$(tr -d '\r\n ' < "$DEPLOY_DIR/api-key.txt")"
fi
API_URL="${API_URL%/}"
API_URL="$(printf '%s' "$API_URL" | tr -d '\r\n')"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASSED=0
FAILED=0

# call METHOD PATH BODY_FILE|- KEY  -> sets STATUS, MS, and writes the body to $TMP/body.json
call() {
  local method="$1" path="$2" body="$3" key="$4"
  local args=(-s -o "$TMP/body.json" -w "%{http_code} %{time_total}" -X "$method" --max-time 30)
  [[ -n "$key" ]] && args+=(-H "x-wander-key: $key")
  if [[ "$body" != "-" ]]; then
    args+=(-H "content-type: application/json; charset=utf-8" --data-binary "@$body")
  fi
  local out
  out="$(curl "${args[@]}" "$API_URL$path" 2>/dev/null || echo "000 0")"
  STATUS="${out%% *}"
  MS="$("$PY" -c 'import sys; print(int(float(sys.argv[1]) * 1000))' "${out##* }")"
}

# check NAME PYTHON_EXPR_ON_j_AND_status DETAIL_EXPR
check() {
  local name="$1" expr="$2" detail="$3"
  local verdict
  verdict="$("$PY" - "$TMP/body.json" "$STATUS" "$expr" "$detail" <<'PY'
import json, sys
path, status, expr, detail = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
try:
    j = json.load(open(path, encoding="utf-8"))
except Exception:
    j = {}
try:
    ok = bool(eval(expr, {"j": j, "status": status}))
except Exception:
    ok = False
try:
    text = str(eval(detail, {"j": j, "status": status}))
except Exception:
    text = json.dumps(j)[:160]
print(("PASS" if ok else "FAIL") + "\t" + text.replace("\n", " ")[:160])
PY
)"
  local result="${verdict%%$'\t'*}" text="${verdict#*$'\t'}"
  if [[ "$result" == "PASS" ]]; then
    PASSED=$((PASSED + 1)); printf '\033[32mOK   %-28s HTTP %-3s %6s ms  %s\033[0m\n' "$name" "$STATUS" "$MS" "$text"
  else
    FAILED=$((FAILED + 1)); printf '\033[31mFAIL %-28s HTTP %-3s %6s ms  %s\033[0m\n' "$name" "$STATUS" "$MS" "$text"
  fi
}

printf '\n\033[36mSmoke testing %s\033[0m\n\n' "$API_URL"

call GET /health - "$API_KEY"
check "health" 'status == 200 and j.get("ok") is True' '"model %s, guardrail %s" % (j["model"], j["guardrail"])'

call GET /health - "definitely-not-the-key"
check "wrong key is refused" 'status == 401' '"expects 401"'

call POST /cards "$SAMPLES/cards.json" "$API_KEY"
check "cards (cheetah page)" 'status == 200 and 1 <= len(j["cards"]) <= 5' \
  '("BLOCKED by guardrail (try --filter-strength MEDIUM): " if j.get("blocked") else "") + " | ".join(c["title"] for c in j["cards"])'

call POST /cards "$SAMPLES/cards.json" "$API_KEY"
check "cards again (cache)" 'status == 200 and j.get("cached") is True' '"expects cached=true from DynamoDB"'

call POST /quest/check "$SAMPLES/quest_check_pass.json" "$API_KEY"
check "quest check: cheetah" 'status == 200 and j["pass"] is True' '"%s: %s" % (j["item"], j["reason"])'

call POST /quest/check "$SAMPLES/quest_check_fail.json" "$API_KEY"
check "quest check: box turtle" 'status == 200 and j["pass"] is False' 'j["reason"]'

call POST /quest/check "$SAMPLES/quest_check_injection.json" "$API_KEY"
check "quest check: injection page" 'status == 200 and j["pass"] is False' \
  '"blocked by the prompt-attack guardrail" if j.get("blocked") else "judge said no: " + j["reason"]'

call POST /quest/new "$SAMPLES/quest_new.json" "$API_KEY"
check "quest new (space titles)" 'status == 200 and j["quest"]["id"].startswith("lumo-")' \
  '"%s: %s" % (j["quest"]["title"], j["quest"]["prompt"])'

call POST /companion "$SAMPLES/companion.json" "$API_KEY"
check "companion (rapid hopping)" 'status == 200 and j["mood"] in ("curious","happy","sleepy","concerned") and j["line"]' \
  '"[%s] %s" % (j["mood"], j["line"])'

call POST /speak "$SAMPLES/speak.json" "$API_KEY"
check "speak (Polly)" 'status == 200 and len(j["audio"]) > 100' '"voice %s, %d base64 chars" % (j["voice"], len(j["audio"]))'
if [[ "$STATUS" == "200" ]]; then
  mkdir -p "$DEPLOY_DIR"
  "$PY" -c 'import base64,json,sys; open(sys.argv[2],"wb").write(base64.b64decode(json.load(open(sys.argv[1]))["audio"]))' \
    "$TMP/body.json" "$DEPLOY_DIR/lumo-says-hi.mp3" 2>/dev/null && printf '     saved .deploy/lumo-says-hi.mp3\n'
fi

printf '%s' '{not json' > "$TMP/bad.json"
call POST /cards "$TMP/bad.json" "$API_KEY"
check "malformed JSON is refused" 'status == 400' '"expects 400"'

TOTAL=$((PASSED + FAILED))
echo
if [[ "$FAILED" == 0 ]]; then
  printf '\033[32mAll %d checks passed.\033[0m\n' "$TOTAL"
  exit 0
fi
printf '\033[31m%d of %d checks failed. Logs: aws logs tail /aws/lambda/wander-tv-lumo --since 10m\033[0m\n' "$FAILED" "$TOTAL"
exit 1
