#!/usr/bin/env bash
# Deploys Lumo's AWS backend for Wander TV with one command (AWS CLI v2 only, no SAM CLI).
#
#   ./deploy.sh                                   # eu-central-1, Claude Haiku 5.5 + Nova 2 Lite fallback
#   ./deploy.sh --write-gradle                    # also write wander.apiUrl / wander.apiKey to ../gradle.properties
#   ./deploy.sh --model eu.amazon.nova-2-lite-v1:0 --fallback ""
#   ./deploy.sh --region us-east-1 --model us.anthropic.claude-haiku-5-5 --fallback us.amazon.nova-2-lite-v1:0
#
# Options: --region R  --stack NAME  --api-key KEY  --model ID  --fallback ID  --voice NAME
#          --filter-strength HIGH|MEDIUM|LOW  --profile NAME  --write-gradle  --skip-model-check
set -euo pipefail

REGION="eu-central-1"
STACK="wander-tv"
API_KEY=""
MODEL_ID="eu.anthropic.claude-haiku-5-5"
FALLBACK_ID="eu.amazon.nova-2-lite-v1:0"
VOICE_ID="Ivy"
FILTER_STRENGTH="HIGH"
PROFILE=""
WRITE_GRADLE=0
SKIP_MODEL_CHECK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    --stack) STACK="$2"; shift 2 ;;
    --api-key) API_KEY="$2"; shift 2 ;;
    --model) MODEL_ID="$2"; shift 2 ;;
    --fallback) FALLBACK_ID="$2"; shift 2 ;;
    --voice) VOICE_ID="$2"; shift 2 ;;
    --filter-strength) FILTER_STRENGTH="$2"; shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    --write-gradle) WRITE_GRADLE=1; shift ;;
    --skip-model-check) SKIP_MODEL_CHECK=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$HERE/.deploy"
KEY_FILE="$DEPLOY_DIR/api-key.txt"
mkdir -p "$DEPLOY_DIR"

step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
good() { printf '    \033[32m%s\033[0m\n' "$*"; }
warn() { printf '    \033[33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# Windows (Git Bash) needs native paths for the Windows aws.exe.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

COMMON=(--region "$REGION")
[[ -n "$PROFILE" ]] && COMMON+=(--profile "$PROFILE")
awsq() { aws "$@" "${COMMON[@]}"; }

# ------------------------------------------------------------------ 1. tools and sign-in
step "Checking the AWS CLI and your sign-in"
command -v aws >/dev/null 2>&1 || fail "AWS CLI v2 was not found. Install it from https://aws.amazon.com/cli/"
if ! ACCOUNT="$(awsq sts get-caller-identity --query Account --output text 2>"$DEPLOY_DIR/sts.err")"; then
  printf '\n\033[31mYou are not signed in to AWS (aws sts get-caller-identity failed).\033[0m\n' >&2
  cat >&2 <<'MSG'
Sign in with one of these, then run ./deploy.sh again:
  aws login                  # AWS CLI 2.32+, uses your AWS console sign-in
  aws configure sso          # IAM Identity Center
  aws configure              # access key + secret
Add --profile <name> if you use a named profile.
MSG
  printf 'AWS said: %s\n' "$(sed '/^[[:space:]]*$/d' "$DEPLOY_DIR/sts.err")" >&2
  exit 1
fi
ACCOUNT="$(printf '%s' "$ACCOUNT" | tr -d '\r\n')"
good "Signed in to account $ACCOUNT"
info "Region $REGION, stack $STACK"

# ------------------------------------------------------------------ 2. API key
step "Preparing the API key the TV will use"
if [[ -n "$API_KEY" ]]; then
  info "Using the key you passed with --api-key"
elif [[ -f "$KEY_FILE" ]]; then
  API_KEY="$(tr -d '\r\n ' < "$KEY_FILE")"
  info "Reusing the key saved in .deploy/api-key.txt"
else
  if command -v openssl >/dev/null 2>&1; then
    API_KEY="wtv_$(openssl rand -hex 24)"
  else
    API_KEY="wtv_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  fi
  info "Generated a new random key"
fi
[[ "$API_KEY" =~ ^[A-Za-z0-9_-]{16,128}$ ]] || fail "The API key must be 16-128 letters, digits, '_' or '-'."
printf '%s' "$API_KEY" > "$KEY_FILE"

# ------------------------------------------------------------------ 3. Bedrock model access
test_model() {
  printf '%s' '[{"role":"user","content":[{"text":"Reply with one word: ready"}]}]' > "$DEPLOY_DIR/ping-messages.json"
  printf '%s' '{"maxTokens":32}' > "$DEPLOY_DIR/ping-config.json"
  awsq bedrock-runtime converse --model-id "$1" \
    --messages "file://$(native "$DEPLOY_DIR/ping-messages.json")" \
    --inference-config "file://$(native "$DEPLOY_DIR/ping-config.json")" --output json \
    >/dev/null 2>"$DEPLOY_DIR/model.err"
}

if [[ "$SKIP_MODEL_CHECK" == 1 ]]; then
  step "Skipping the Bedrock model check (--skip-model-check)"
else
  step "Checking Bedrock access to $MODEL_ID"
  PRIMARY_OK=0
  if test_model "$MODEL_ID"; then good "$MODEL_ID answers."; PRIMARY_OK=1
  else warn "$MODEL_ID did not answer: $(cat "$DEPLOY_DIR/model.err")"; fi
  FALLBACK_OK=0
  if [[ -n "$FALLBACK_ID" ]]; then
    step "Checking Bedrock access to fallback $FALLBACK_ID"
    if test_model "$FALLBACK_ID"; then good "$FALLBACK_ID answers."; FALLBACK_OK=1
    else warn "$FALLBACK_ID did not answer: $(cat "$DEPLOY_DIR/model.err")"; fi
  fi
  if [[ "$PRIMARY_OK" == 0 ]]; then
    if [[ "$FALLBACK_OK" == 1 ]]; then
      warn "Using $FALLBACK_ID as the main model until $MODEL_ID is enabled (they swap places)."
      SWAP="$MODEL_ID"; MODEL_ID="$FALLBACK_ID"; FALLBACK_ID="$SWAP"
    else
      cat >&2 <<MSG

No Bedrock model answered in $REGION.
Open https://$REGION.console.aws.amazon.com/bedrock/home?region=$REGION#/model-catalog
and open the model once (Anthropic models ask for a short use-case form the first time),
or pick another model with --model. Re-run with --skip-model-check to deploy anyway
(the TV then uses its on-device fallbacks until a model works).
MSG
      exit 1
    fi
  fi
fi

# ------------------------------------------------------------------ 4. artifacts bucket
BUCKET="$(printf 'wander-tv-artifacts-%s-%s' "$ACCOUNT" "$REGION" | tr '[:upper:]' '[:lower:]')"
step "Making sure the private artifacts bucket exists ($BUCKET)"
if awsq s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  info "Bucket already exists"
else
  if [[ "$REGION" == "us-east-1" ]]; then
    awsq s3api create-bucket --bucket "$BUCKET" >/dev/null || fail "Could not create bucket $BUCKET"
  else
    awsq s3api create-bucket --bucket "$BUCKET" --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null \
      || fail "Could not create bucket $BUCKET"
  fi
  awsq s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" >/dev/null \
    || warn "Could not set the public access block"
  printf '%s' '{"Rules":[{"ID":"expire-old-artifacts","Status":"Enabled","Filter":{"Prefix":"wander-tv/"},"Expiration":{"Days":30}}]}' \
    > "$DEPLOY_DIR/lifecycle.json"
  awsq s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" \
    --lifecycle-configuration "file://$(native "$DEPLOY_DIR/lifecycle.json")" >/dev/null 2>&1 || true
  good "Created $BUCKET"
fi

# ------------------------------------------------------------------ 5. package
step "Packaging the Lambda code and template"
find "$HERE/src" -type d -name "__pycache__" -prune -exec rm -rf {} + 2>/dev/null || true
awsq cloudformation package --template-file "$(native "$HERE/template.yaml")" --s3-bucket "$BUCKET" \
  --s3-prefix wander-tv --output-template-file "$(native "$DEPLOY_DIR/packaged.yaml")" >/dev/null \
  || fail "aws cloudformation package failed"
good "Packaged to .deploy/packaged.yaml"

# ------------------------------------------------------------------ 6. deploy
step "Deploying stack $STACK (first run takes 2-4 minutes)"
STATUS="$(awsq cloudformation describe-stacks --stack-name "$STACK" --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)"
if [[ "$(printf '%s' "$STATUS" | tr -d '\r\n')" == "ROLLBACK_COMPLETE" ]]; then
  warn "A previous first deploy failed and left an empty stack (ROLLBACK_COMPLETE). Removing it first."
  awsq cloudformation delete-stack --stack-name "$STACK"
  awsq cloudformation wait stack-delete-complete --stack-name "$STACK"
fi
if ! awsq cloudformation deploy --template-file "$(native "$DEPLOY_DIR/packaged.yaml")" --stack-name "$STACK" \
    --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND --no-fail-on-empty-changeset --tags app=wander-tv \
    --parameter-overrides "ApiKey=$API_KEY" "ModelId=$MODEL_ID" "FallbackModelId=$FALLBACK_ID" \
    "VoiceId=$VOICE_ID" "FilterStrength=$FILTER_STRENGTH"; then
  printf '\n\033[31mThe deploy failed. The most recent failures:\033[0m\n' >&2
  awsq cloudformation describe-stack-events --stack-name "$STACK" --max-items 40 \
    --query "StackEvents[?contains(ResourceStatus, 'FAILED')].[LogicalResourceId,ResourceStatusReason]" \
    --output text 2>/dev/null | head -8 >&2 || true
  exit 1
fi

# ------------------------------------------------------------------ 7. outputs
step "Reading the stack outputs"
output() {
  awsq cloudformation describe-stacks --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text | tr -d '\r\n'
}
API_URL="$(output ApiUrl)"
API_URL="${API_URL%/}"
DASHBOARD_URL="$(output DashboardUrl)"
[[ -n "$API_URL" && "$API_URL" != "None" ]] || fail "The stack has no ApiUrl output."
cat > "$DEPLOY_DIR/outputs.json" <<JSON
{
  "apiUrl": "$API_URL",
  "region": "$REGION",
  "stackName": "$STACK",
  "modelId": "$MODEL_ID",
  "fallbackModelId": "$FALLBACK_ID",
  "dashboardUrl": "$DASHBOARD_URL"
}
JSON

# ------------------------------------------------------------------ 8. quick health check
step "Saying hello to Lumo"
if HEALTH="$(curl -fsS --max-time 15 -H "x-wander-key: $API_KEY" "$API_URL/health" 2>&1)"; then
  good "Healthy: $HEALTH"
else
  warn "Health check did not answer yet ($HEALTH). New APIs can take a few seconds."
fi

# ------------------------------------------------------------------ 9. gradle.properties
if [[ "$WRITE_GRADLE" == 1 ]]; then
  step "Writing wander.apiUrl / wander.apiKey into ../gradle.properties"
  GRADLE="$HERE/../gradle.properties"
  touch "$GRADLE"
  TMP="$(mktemp)"
  grep -v -E '^[[:space:]]*wander\.api(Url|Key)[[:space:]]*=' "$GRADLE" | tr -d '\r' > "$TMP" || true
  printf 'wander.apiUrl=%s\nwander.apiKey=%s\n' "$API_URL" "$API_KEY" >> "$TMP"
  mv "$TMP" "$GRADLE"
  good "Updated $(cd "$HERE/.." && pwd)/gradle.properties"
  warn "gradle.properties is tracked by git: do not commit the key (git checkout -- gradle.properties)."
fi

printf '\n\033[32mLumo'"'"'s brain is live.\033[0m\n\n'
printf '  API URL   %s\n  API key   %s\n  Model     %s (fallback: %s)\n' "$API_URL" "$API_KEY" "$MODEL_ID" "${FALLBACK_ID:-none}"
[[ -n "$DASHBOARD_URL" && "$DASHBOARD_URL" != "None" ]] && printf '  Dashboard %s\n' "$DASHBOARD_URL"
printf '\nNext:\n'
printf '  ./smoke_test.sh                       # hits every endpoint and prints latency\n'
[[ "$WRITE_GRADLE" == 1 ]] || printf '  ./deploy.sh --write-gradle            # or bake the URL + key into the next TV build\n'
printf '  adb shell am start -n tv.wander/.MainActivity --es api_url %s --es api_key %s\n' "$API_URL" "$API_KEY"
