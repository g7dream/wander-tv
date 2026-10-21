<#
.SYNOPSIS
  Deploys Lumo's AWS backend for Wander TV with one command (no SAM CLI needed, just AWS CLI v2).

.DESCRIPTION
  API Gateway HTTP API -> Lambda (Python 3.12, arm64) -> Amazon Bedrock (Converse, forced tool use)
  + Bedrock Guardrails -> DynamoDB cache -> Amazon Polly -> CloudWatch logs, metrics and dashboard.

  Steps: checks your AWS sign-in, checks Bedrock model access, creates a private artifacts bucket,
  packages + deploys the CloudFormation/SAM stack, prints the API URL and key, and (with -WriteGradle)
  writes them into ../gradle.properties so the next TV build talks to your backend.

  -FilterStrength and -OutputFilterStrength (HIGH, MEDIUM or LOW, both MEDIUM by default) set the family
  content filters on the page text going in and on Lumo's own words coming out. HIGH also blocks
  low-confidence matches, which can turn ordinary predator pages (cheetahs, T. rex) into "Let's skip this page".

.NOTES
  Windows ships with script running disabled (execution policy Restricted), so run the script through
  powershell.exe with -ExecutionPolicy Bypass, which applies to that one process only (examples below are
  from the repo root). If the repo arrived as a zip download, also run once:
    Get-ChildItem backend -Recurse -Filter *.ps1 | Unblock-File

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\deploy.ps1
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\deploy.ps1 -WriteGradle
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\deploy.ps1 -ModelId eu.amazon.nova-2-lite-v1:0 -FallbackModelId ""
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\deploy.ps1 -Region us-east-1 -ModelId us.anthropic.claude-haiku-5-5 -FallbackModelId us.amazon.nova-2-lite-v1:0
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\deploy.ps1 -FilterStrength HIGH -OutputFilterStrength HIGH
#>
[CmdletBinding()]
param(
    [string]$Region = "eu-central-1",
    [string]$StackName = "wander-tv",
    [string]$ApiKey = "",
    [string]$ModelId = "eu.anthropic.claude-haiku-5-5",
    [string]$FallbackModelId = "eu.amazon.nova-2-lite-v1:0",
    [string]$VoiceId = "Ivy",
    [ValidateSet("HIGH", "MEDIUM", "LOW")]
    [string]$FilterStrength = "MEDIUM",
    [ValidateSet("HIGH", "MEDIUM", "LOW")]
    [string]$OutputFilterStrength = "MEDIUM",
    [string]$Profile = "",
    [switch]$WriteGradle,
    [switch]$SkipModelCheck
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$DeployDir = Join-Path $Here ".deploy"
$KeyFile = Join-Path $DeployDir "api-key.txt"
$OutputsFile = Join-Path $DeployDir "outputs.json"
$Template = Join-Path $Here "template.yaml"
$Packaged = Join-Path $DeployDir "packaged.yaml"
New-Item -ItemType Directory -Force -Path $DeployDir | Out-Null

function Step([string]$Message) { Write-Host ""; Write-Host "==> $Message" -ForegroundColor Cyan }
function Info([string]$Message) { Write-Host "    $Message" -ForegroundColor Gray }
function Good([string]$Message) { Write-Host "    $Message" -ForegroundColor Green }
function Warn([string]$Message) { Write-Host "    $Message" -ForegroundColor Yellow }
function Fail([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

$Common = @("--region", $Region)
if ($Profile) { $Common += @("--profile", $Profile) }

# Runs the AWS CLI and captures stdout / stderr separately (Windows PowerShell 5.1 safe).
function Invoke-Aws([string[]]$Arguments) {
    $stdout = New-Object System.Collections.Generic.List[string]
    $stderr = New-Object System.Collections.Generic.List[string]
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & aws @Arguments @Common 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) {
                $line = [string]$_.Exception.Message
                if ($line.Trim() -and $line -ne "System.Management.Automation.RemoteException") { $stderr.Add($line) }
            } else { $stdout.Add([string]$_) }
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Code = $code; Out = ($stdout -join "`n"); Err = ($stderr -join "`n") }
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding $false))
}

# The command that runs a script next to this one from the current folder, past the default Restricted policy.
function Get-RunHint([string]$Name, [string]$Arguments = "") {
    $path = Join-Path $Here $Name
    try { $path = [string](Resolve-Path -LiteralPath $path -Relative -ErrorAction Stop) } catch { }
    if ($path -match '\s') { $path = '"' + $path + '"' }
    $line = "powershell -NoProfile -ExecutionPolicy Bypass -File $path"
    if ($Arguments) { $line += " $Arguments" }
    return $line
}

# ------------------------------------------------------------------ 1. tools and sign-in
Step "Checking the AWS CLI and your sign-in"
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    Fail "AWS CLI v2 was not found. Install it from https://aws.amazon.com/cli/ and open a new terminal."
}
$identity = Invoke-Aws @("sts", "get-caller-identity", "--output", "json")
if ($identity.Code -ne 0) {
    Write-Host ""
    Write-Host "You are not signed in to AWS (aws sts get-caller-identity failed)." -ForegroundColor Red
    Write-Host "Sign in with one of these, then run this script again:" -ForegroundColor Yellow
    Write-Host "  aws login                  # AWS CLI 2.32+, uses your AWS console sign-in"
    Write-Host "  aws configure sso          # IAM Identity Center"
    Write-Host "  aws configure              # access key + secret"
    Write-Host "Add -Profile <name> if you use a named profile."
    if ($identity.Err) { Write-Host ""; Write-Host "AWS said: $($identity.Err)" -ForegroundColor DarkGray }
    exit 1
}
$who = $identity.Out | ConvertFrom-Json
$Account = [string]$who.Account
Good "Signed in as $($who.Arn)"
Info "Account $Account, region $Region, stack $StackName"

# ------------------------------------------------------------------ 2. API key
Step "Preparing the API key the TV will use"
if ($ApiKey) {
    Info "Using the key you passed with -ApiKey"
} elseif (Test-Path $KeyFile) {
    $ApiKey = (Get-Content -Path $KeyFile -Raw).Trim()
    Info "Reusing the key saved in .deploy\api-key.txt"
} else {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $ApiKey = "wtv_" + (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
    Info "Generated a new random key"
}
if ($ApiKey -notmatch '^[A-Za-z0-9_\-]{16,128}$') {
    Fail "The API key must be 16-128 characters of letters, digits, '_' or '-'."
}
Write-Utf8NoBom $KeyFile $ApiKey

# ------------------------------------------------------------------ 3. Bedrock model access
function Test-Model([string]$Id) {
    $messages = Join-Path $DeployDir "ping-messages.json"
    $config = Join-Path $DeployDir "ping-config.json"
    Write-Utf8NoBom $messages '[{"role":"user","content":[{"text":"Reply with one word: ready"}]}]'
    Write-Utf8NoBom $config '{"maxTokens":32}'
    return Invoke-Aws @("bedrock-runtime", "converse", "--model-id", $Id, "--messages", "file://$messages",
        "--inference-config", "file://$config", "--output", "json")
}

if ($SkipModelCheck) {
    Step "Skipping the Bedrock model check (-SkipModelCheck)"
} else {
    Step "Checking Bedrock access to $ModelId"
    $primary = Test-Model $ModelId
    if ($primary.Code -eq 0) {
        Good "$ModelId answers."
    } else {
        Warn "$ModelId did not answer: $($primary.Err)"
    }
    $fallbackOk = $false
    if ($FallbackModelId) {
        Step "Checking Bedrock access to fallback $FallbackModelId"
        $fallback = Test-Model $FallbackModelId
        if ($fallback.Code -eq 0) { Good "$FallbackModelId answers."; $fallbackOk = $true }
        else { Warn "$FallbackModelId did not answer: $($fallback.Err)" }
    }
    if ($primary.Code -ne 0) {
        if ($fallbackOk) {
            Warn "Using $FallbackModelId as the main model until $ModelId is enabled (they swap places)."
            $swap = $ModelId; $ModelId = $FallbackModelId; $FallbackModelId = $swap
        } else {
            Write-Host ""
            Write-Host "No Bedrock model answered in $Region." -ForegroundColor Red
            Write-Host "Open https://$Region.console.aws.amazon.com/bedrock/home?region=$Region#/model-catalog"
            Write-Host "and open the model once (Anthropic models ask for a short use-case form the first time),"
            Write-Host "or pick another model with -ModelId. Re-run with -SkipModelCheck to deploy anyway"
            Write-Host "(the TV then uses its on-device fallbacks until a model works)."
            exit 1
        }
    }
}

# ------------------------------------------------------------------ 4. artifacts bucket
$Bucket = ("wander-tv-artifacts-$Account-$Region").ToLower()
Step "Making sure the private artifacts bucket exists ($Bucket)"
$head = Invoke-Aws @("s3api", "head-bucket", "--bucket", $Bucket)
if ($head.Code -ne 0) {
    if ($Region -eq "us-east-1") {
        $create = Invoke-Aws @("s3api", "create-bucket", "--bucket", $Bucket)
    } else {
        $create = Invoke-Aws @("s3api", "create-bucket", "--bucket", $Bucket,
            "--create-bucket-configuration", "LocationConstraint=$Region")
    }
    if ($create.Code -ne 0) { Fail "Could not create bucket ${Bucket}: $($create.Err)" }
    $block = Invoke-Aws @("s3api", "put-public-access-block", "--bucket", $Bucket, "--public-access-block-configuration",
        "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true")
    if ($block.Code -ne 0) { Warn "Could not set the public access block: $($block.Err)" }
    $lifecycle = Join-Path $DeployDir "lifecycle.json"
    Write-Utf8NoBom $lifecycle '{"Rules":[{"ID":"expire-old-artifacts","Status":"Enabled","Filter":{"Prefix":"wander-tv/"},"Expiration":{"Days":30}}]}'
    $null = Invoke-Aws @("s3api", "put-bucket-lifecycle-configuration", "--bucket", $Bucket,
        "--lifecycle-configuration", "file://$lifecycle")
    Good "Created $Bucket"
} else {
    Info "Bucket already exists"
}

# ------------------------------------------------------------------ 5. package
Step "Packaging the Lambda code and template"
Get-ChildItem -Path (Join-Path $Here "src") -Recurse -Directory -Filter "__pycache__" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
$package = Invoke-Aws @("cloudformation", "package", "--template-file", $Template, "--s3-bucket", $Bucket,
    "--s3-prefix", "wander-tv", "--output-template-file", $Packaged)
if ($package.Code -ne 0) { Fail "aws cloudformation package failed: $($package.Err)" }
Good "Packaged to .deploy\packaged.yaml"

# ------------------------------------------------------------------ 6. deploy
Step "Deploying stack $StackName (first run takes 2-4 minutes)"
$status = Invoke-Aws @("cloudformation", "describe-stacks", "--stack-name", $StackName,
    "--query", "Stacks[0].StackStatus", "--output", "text")
if ($status.Code -eq 0 -and $status.Out.Trim() -eq "ROLLBACK_COMPLETE") {
    Warn "A previous first deploy failed and left an empty stack (ROLLBACK_COMPLETE). Removing it first."
    $null = Invoke-Aws @("cloudformation", "delete-stack", "--stack-name", $StackName)
    $null = Invoke-Aws @("cloudformation", "wait", "stack-delete-complete", "--stack-name", $StackName)
}
$deployArgs = @("cloudformation", "deploy", "--template-file", $Packaged, "--stack-name", $StackName,
    "--capabilities", "CAPABILITY_IAM", "CAPABILITY_AUTO_EXPAND", "--no-fail-on-empty-changeset",
    "--tags", "app=wander-tv",
    "--parameter-overrides", "ApiKey=$ApiKey", "ModelId=$ModelId", "FallbackModelId=$FallbackModelId",
    "VoiceId=$VoiceId", "FilterStrength=$FilterStrength", "OutputFilterStrength=$OutputFilterStrength")
& aws @deployArgs @Common
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "The deploy failed. The most recent failures:" -ForegroundColor Red
    $events = Invoke-Aws @("cloudformation", "describe-stack-events", "--stack-name", $StackName, "--max-items", "40",
        "--query", "StackEvents[?contains(ResourceStatus, 'FAILED')].[LogicalResourceId,ResourceStatusReason]",
        "--output", "text")
    if ($events.Out) { $events.Out -split "`n" | Select-Object -First 8 | ForEach-Object { Write-Host "  $_" } }
    exit 1
}

# ------------------------------------------------------------------ 7. outputs
Step "Reading the stack outputs"
$described = Invoke-Aws @("cloudformation", "describe-stacks", "--stack-name", $StackName, "--output", "json")
if ($described.Code -ne 0) { Fail "Could not read the stack outputs: $($described.Err)" }
$outputs = @{}
foreach ($o in ($described.Out | ConvertFrom-Json).Stacks[0].Outputs) { $outputs[$o.OutputKey] = $o.OutputValue }
$ApiUrl = ([string]$outputs["ApiUrl"]).TrimEnd("/")
if (-not $ApiUrl) { Fail "The stack has no ApiUrl output." }
$saved = [ordered]@{
    apiUrl = $ApiUrl; region = $Region; stackName = $StackName; modelId = $ModelId
    fallbackModelId = $FallbackModelId; dashboardUrl = [string]$outputs["DashboardUrl"]
    filterStrength = $FilterStrength; outputFilterStrength = $OutputFilterStrength
    deployedAt = (Get-Date).ToString("s")
}
Write-Utf8NoBom $OutputsFile ($saved | ConvertTo-Json)

# ------------------------------------------------------------------ 8. quick health check
Step "Saying hello to Lumo"
try {
    $health = Invoke-RestMethod -Uri "$ApiUrl/health" -Headers @{ "x-wander-key" = $ApiKey } -TimeoutSec 15
    Good "Healthy: model $($health.model) in $($health.region), guardrail=$($health.guardrail), cache=$($health.cache)"
} catch {
    Warn "Health check did not answer yet ($($_.Exception.Message)). New APIs can take a few seconds."
}

# ------------------------------------------------------------------ 9. gradle.properties
if ($WriteGradle) {
    Step "Writing wander.apiUrl / wander.apiKey into ../gradle.properties"
    $gradle = Join-Path (Split-Path -Parent $Here) "gradle.properties"
    $lines = @()
    if (Test-Path $gradle) { $lines = @(Get-Content -Path $gradle) }
    $haveUrl = $false; $haveKey = $false
    $updated = foreach ($line in $lines) {
        if ($line -match '^\s*wander\.apiUrl\s*=') { $haveUrl = $true; "wander.apiUrl=$ApiUrl" }
        elseif ($line -match '^\s*wander\.apiKey\s*=') { $haveKey = $true; "wander.apiKey=$ApiKey" }
        else { $line }
    }
    $updated = @($updated)
    if (-not $haveUrl) { $updated += "wander.apiUrl=$ApiUrl" }
    if (-not $haveKey) { $updated += "wander.apiKey=$ApiKey" }
    Write-Utf8NoBom $gradle (($updated -join "`n") + "`n")
    Good "Updated $gradle"
    Warn "gradle.properties is tracked by git: do not commit the key (git checkout -- gradle.properties)."
}

# ------------------------------------------------------------------ done
Write-Host ""
Write-Host "Lumo's brain is live." -ForegroundColor Green
Write-Host ""
Write-Host "  API URL   $ApiUrl"
Write-Host "  API key   $ApiKey"
Write-Host "  Model     $ModelId (fallback: $(if ($FallbackModelId) { $FallbackModelId } else { 'none' }))"
Write-Host "  Filters   in $FilterStrength (page text), out $OutputFilterStrength (what Lumo says)"
if ($outputs["DashboardUrl"]) { Write-Host "  Dashboard $($outputs['DashboardUrl'])" }
Write-Host ""
Write-Host "Next:" -ForegroundColor Cyan
Write-Host "  $(Get-RunHint 'smoke_test.ps1')"
Write-Host "      hits every endpoint, prints latency and fails if a nature page is blocked"
if (-not $WriteGradle) {
    Write-Host "  $(Get-RunHint 'deploy.ps1' '-WriteGradle')"
    Write-Host "      or bake the URL + key into the next TV build"
}
Write-Host "  adb shell am start -n tv.wander/.MainActivity --es api_url $ApiUrl --es api_key $ApiKey"
Write-Host "      point an installed TV app at it without rebuilding"
