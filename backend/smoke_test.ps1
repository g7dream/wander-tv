<#
.SYNOPSIS
  Hits every Wander TV backend endpoint with realistic payloads and prints results and latency.

.DESCRIPTION
  Reads the API URL and key from .deploy\ (written by deploy.ps1) unless you pass them.
  Exits 1 if any check fails. Saves Lumo's voice sample to .deploy\lumo-says-hi.mp3.

.EXAMPLE
  .\smoke_test.ps1
.EXAMPLE
  .\smoke_test.ps1 -ApiUrl https://abc123.execute-api.eu-central-1.amazonaws.com -ApiKey wtv_...
#>
[CmdletBinding()]
param(
    [string]$ApiUrl = "",
    [string]$ApiKey = ""
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$DeployDir = Join-Path $Here ".deploy"
$Samples = Join-Path $Here "samples"

if (-not $ApiUrl) {
    $outputsFile = Join-Path $DeployDir "outputs.json"
    if (-not (Test-Path $outputsFile)) { Write-Host "No -ApiUrl given and no .deploy\outputs.json. Run .\deploy.ps1 first." -ForegroundColor Red; exit 1 }
    $ApiUrl = [string]((Get-Content -Path $outputsFile -Raw | ConvertFrom-Json).apiUrl)
}
if (-not $ApiKey) {
    $keyFile = Join-Path $DeployDir "api-key.txt"
    if (-not (Test-Path $keyFile)) { Write-Host "No -ApiKey given and no .deploy\api-key.txt." -ForegroundColor Red; exit 1 }
    $ApiKey = (Get-Content -Path $keyFile -Raw).Trim()
}
$ApiUrl = $ApiUrl.TrimEnd("/")

function Invoke-Api([string]$Method, [string]$Path, [string]$Body, [string]$Key) {
    $request = [System.Net.HttpWebRequest]::Create("$ApiUrl$Path")
    $request.Method = $Method
    $request.Timeout = 30000
    $request.ReadWriteTimeout = 30000
    if ($Key) { $request.Headers.Add("x-wander-key", $Key) }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if ($Body) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
            $request.ContentType = "application/json; charset=utf-8"
            $request.ContentLength = $bytes.Length
            $stream = $request.GetRequestStream()
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Close()
        }
        $response = $request.GetResponse()
    } catch {
        # .NET exceptions arrive wrapped; find the WebException that carries the HTTP response (4xx/5xx).
        $ex = $_.Exception
        while ($null -ne $ex -and -not ($ex -is [System.Net.WebException])) { $ex = $ex.InnerException }
        if ($null -eq $ex -or $null -eq $ex.Response) {
            return [pscustomobject]@{ Status = 0; Ms = $watch.ElapsedMilliseconds; Text = $_.Exception.Message; Json = $null }
        }
        $response = $ex.Response
    }
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Close()
    $status = [int]$response.StatusCode
    $response.Close()
    $watch.Stop()
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { $json = $null }
    return [pscustomobject]@{ Status = $status; Ms = $watch.ElapsedMilliseconds; Text = $text; Json = $json }
}

function Sample([string]$Name) { return [System.IO.File]::ReadAllText((Join-Path $Samples $Name), [System.Text.Encoding]::UTF8) }

$results = New-Object System.Collections.Generic.List[object]
function Check([string]$Name, $Response, [bool]$Ok, [string]$Detail) {
    $results.Add([pscustomobject]@{ Check = $Name; HTTP = $Response.Status; Ms = $Response.Ms; Result = $(if ($Ok) { "PASS" } else { "FAIL" }); Detail = $Detail })
    $color = if ($Ok) { "Green" } else { "Red" }
    Write-Host ("{0,-4} {1,-28} HTTP {2,-3} {3,6} ms  {4}" -f $(if ($Ok) { "OK" } else { "FAIL" }), $Name, $Response.Status, $Response.Ms, $Detail) -ForegroundColor $color
}
function Has($Object, [string]$Property) { return ($null -ne $Object) -and ($Object.PSObject.Properties.Name -contains $Property) }

Write-Host ""
Write-Host "Smoke testing $ApiUrl" -ForegroundColor Cyan
Write-Host ""

$r = Invoke-Api "GET" "/health" "" $ApiKey
Check "health" $r ($r.Status -eq 200 -and (Has $r.Json "ok") -and $r.Json.ok) $(if (Has $r.Json "model") { "model $($r.Json.model), guardrail $($r.Json.guardrail)" } else { $r.Text })

$r = Invoke-Api "GET" "/health" "" "definitely-not-the-key"
Check "wrong key is refused" $r ($r.Status -eq 401) "expects 401"

$r = Invoke-Api "POST" "/cards" (Sample "cards.json") $ApiKey
$ok = $r.Status -eq 200 -and (Has $r.Json "cards") -and @($r.Json.cards).Count -ge 1 -and @($r.Json.cards).Count -le 5
$detail = if ($ok) { (@($r.Json.cards) | ForEach-Object { $_.title }) -join " | " } else { $r.Text }
if ($ok -and (Has $r.Json "blocked")) { $detail = "BLOCKED by guardrail (try -FilterStrength MEDIUM): $detail" }
Check "cards (cheetah page)" $r $ok $detail

$r = Invoke-Api "POST" "/cards" (Sample "cards.json") $ApiKey
Check "cards again (cache)" $r ($r.Status -eq 200 -and (Has $r.Json "cached") -and $r.Json.cached) "expects cached=true from DynamoDB"

$r = Invoke-Api "POST" "/quest/check" (Sample "quest_check_pass.json") $ApiKey
Check "quest check: cheetah" $r ($r.Status -eq 200 -and (Has $r.Json "pass") -and $r.Json.pass) $(if (Has $r.Json "reason") { "$($r.Json.item): $($r.Json.reason)" } else { $r.Text })

$r = Invoke-Api "POST" "/quest/check" (Sample "quest_check_fail.json") $ApiKey
Check "quest check: box turtle" $r ($r.Status -eq 200 -and (Has $r.Json "pass") -and -not $r.Json.pass) $(if (Has $r.Json "reason") { $r.Json.reason } else { $r.Text })

$r = Invoke-Api "POST" "/quest/check" (Sample "quest_check_injection.json") $ApiKey
$injectionOk = $r.Status -eq 200 -and (Has $r.Json "pass") -and -not $r.Json.pass
$how = if (Has $r.Json "blocked") { "blocked by the prompt-attack guardrail" } elseif (Has $r.Json "reason") { "judge said no: $($r.Json.reason)" } else { $r.Text }
Check "quest check: injection page" $r $injectionOk $how

$r = Invoke-Api "POST" "/quest/new" (Sample "quest_new.json") $ApiKey
$ok = $r.Status -eq 200 -and (Has $r.Json "quest") -and ([string]$r.Json.quest.id).StartsWith("lumo-")
Check "quest new (space titles)" $r $ok $(if ($ok) { "$($r.Json.quest.title): $($r.Json.quest.prompt)" } else { $r.Text })

$r = Invoke-Api "POST" "/companion" (Sample "companion.json") $ApiKey
$ok = $r.Status -eq 200 -and (Has $r.Json "mood") -and (Has $r.Json "line")
Check "companion (rapid hopping)" $r $ok $(if ($ok) { "[$($r.Json.mood)] $($r.Json.line)" } else { $r.Text })

$r = Invoke-Api "POST" "/speak" (Sample "speak.json") $ApiKey
$ok = $r.Status -eq 200 -and (Has $r.Json "audio") -and ([string]$r.Json.audio).Length -gt 100
if ($ok) {
    $mp3 = Join-Path $DeployDir "lumo-says-hi.mp3"
    New-Item -ItemType Directory -Force -Path $DeployDir | Out-Null
    [System.IO.File]::WriteAllBytes($mp3, [System.Convert]::FromBase64String([string]$r.Json.audio))
    $detail = "voice $($r.Json.voice), saved .deploy\lumo-says-hi.mp3"
} else { $detail = $r.Text }
Check "speak (Polly)" $r $ok $detail

$r = Invoke-Api "POST" "/cards" "{not json" $ApiKey
Check "malformed JSON is refused" $r ($r.Status -eq 400) "expects 400"

$failed = @($results | Where-Object { $_.Result -eq "FAIL" }).Count
Write-Host ""
if ($failed -eq 0) {
    Write-Host "All $($results.Count) checks passed." -ForegroundColor Green
    exit 0
}
Write-Host "$failed of $($results.Count) checks failed. Logs: aws logs tail /aws/lambda/wander-tv-lumo --since 10m" -ForegroundColor Red
exit 1
