<#
.SYNOPSIS
  Records one demo scene (or takes one screenshot) straight from the Fire TV over ADB.

.DESCRIPTION
  Video: runs `adb shell screenrecord` on the TV at 1920x1080 / 8 Mbps, then pulls the file to
  video/raw/<Scene>.mp4 and deletes it from the TV. Press Enter (or Q) to stop early; otherwise it
  stops after -Seconds (screenrecord's hard maximum is 180 s). screenrecord captures NO audio:
  app sounds and Lumo's Polly voice are added in post by make_video.ps1.

  Screenshot (-Shot): `screencap` to docs/screens/<Scene>.png (for the README).

.EXAMPLE
  .\video\capture.ps1 -Ip 192.168.1.50 -Scene s03-join -Seconds 60
.EXAMPLE
  .\video\capture.ps1 -Scene s05-quest -Seconds 180
.EXAMPLE
  .\video\capture.ps1 -Shot -Scene home
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Scene,
    [int]$Seconds = 90,
    [string]$Ip = "",
    [string]$Adb = "",
    [switch]$Shot,
    [string]$Size = "1920x1080",
    [int]$BitRate = 8000000,
    [int]$Countdown = 3,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "capture FAILED: $msg" -ForegroundColor Red
    exit 1
}

if ($Scene -notmatch '^[A-Za-z0-9_-]+$') { Fail "Scene names may only use letters, digits, - and _ (got '$Scene')" }
if (-not $Shot -and ($Seconds -lt 1 -or $Seconds -gt 180)) { Fail "-Seconds must be 1-180 (screenrecord stops at 180 s)" }

# ---- find adb: -Adb, then PATH, then the Android SDK that ships with this machine's Unity install
$adbExe = $null
if ($Adb) {
    if (-not (Test-Path $Adb)) { Fail "adb not found at $Adb" }
    $adbExe = $Adb
} else {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { $adbExe = $c.Source }
    else {
        $candidates = @(
            "D:\UnityHub\Editor\6000.4.7f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe"
        )
        if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
        if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe") }
        foreach ($p in $candidates) { if ($p -and (Test-Path $p)) { $adbExe = $p; break } }
    }
}
if (-not $adbExe) { Fail "adb not found. Install Android platform-tools or pass -Adb C:\path\to\adb.exe" }

function Invoke-Adb([string[]]$argv, [switch]$AllowFail) {
    $all = @()
    if ($script:Serial) { $all += @("-s", $script:Serial) }
    $all += $argv
    # adb prints notices on stderr; in Windows PowerShell that must not count as a terminating error.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { $out = & $adbExe @all 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $prev }
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFail) { Fail ("adb " + ($argv -join " ") + " failed:`n" + ($out -join "`n")) }
    return ($out -join "`n")
}

# ---- connect
$script:Serial = ""
if ($Ip) {
    $target = $Ip
    if ($target -notmatch ':\d+$') { $target = "${target}:5555" }
    $res = Invoke-Adb @("connect", $target) -AllowFail
    if ($res -notmatch 'connected to') {
        Fail ("could not connect to ${target}: $res`n  Check: same Wi-Fi, Settings > My Fire TV > Developer Options > ADB Debugging is on, and accept the prompt on the TV.")
    }
    $script:Serial = $target
}

$list = Invoke-Adb @("devices") -AllowFail
$devices = @($list -split "`n" | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($script:Serial) {
    if ($devices -notcontains $script:Serial) {
        if ($list -match [regex]::Escape($script:Serial) + '\s+unauthorized') { Fail "the TV has not authorized this computer yet: accept the 'Allow USB debugging?' prompt on the TV and run again" }
        Fail "$($script:Serial) is not listed as a ready device:`n$list"
    }
} else {
    if ($devices.Count -eq 0) { Fail "no device connected. Pass -Ip <fire-tv-ip> (Settings > My Fire TV > About > Network shows it)" }
    if ($devices.Count -gt 1) { Fail ("more than one device is connected (" + ($devices -join ", ") + "): pass -Ip to pick the Fire TV") }
    $script:Serial = $devices[0]
}
$model = (Invoke-Adb @("shell", "getprop", "ro.product.model") -AllowFail).Trim()
Write-Host "Device: $($script:Serial)  ($model)" -ForegroundColor Cyan

# ---- screenshot
if ($Shot) {
    $dir = Join-Path $Root "docs\screens"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $dest = Join-Path $dir "$Scene.png"
    $remote = "/sdcard/wander-$Scene.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, $dest) | Out-Null
    Invoke-Adb @("shell", "rm", "-f", $remote) -AllowFail | Out-Null
    if (-not (Test-Path $dest) -or (Get-Item $dest).Length -lt 1000) { Fail "screenshot was not saved" }
    Write-Host "Saved $dest" -ForegroundColor Green
    exit 0
}

# ---- video
$rawDir = Join-Path $Root "video\raw"
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
$dest = Join-Path $rawDir "$Scene.mp4"
if ((Test-Path $dest) -and -not $Force) { Fail "$dest already exists (use -Force to overwrite, or pick another -Scene name)" }
$remote = "/sdcard/wander-$Scene.mp4"
Invoke-Adb @("shell", "rm", "-f", $remote) -AllowFail | Out-Null

for ($i = $Countdown; $i -gt 0; $i--) { Write-Host "Recording '$Scene' in $i..." -ForegroundColor Yellow; Start-Sleep -Seconds 1 }

$recArgs = @()
if ($script:Serial) { $recArgs += @("-s", $script:Serial) }
$recArgs += @("shell", "screenrecord", "--size", $Size, "--bit-rate", "$BitRate", "--time-limit", "$Seconds", $remote)
$proc = Start-Process -FilePath $adbExe -ArgumentList $recArgs -NoNewWindow -PassThru
Write-Host "REC  $Scene  (up to $Seconds s). Press Enter or Q to stop early." -ForegroundColor Red

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$stoppedEarly = $false
$canReadKeys = $true
while (-not $proc.HasExited) {
    Start-Sleep -Milliseconds 200
    if ($canReadKeys) {
        try {
            if ([Console]::KeyAvailable) {
                $k = [Console]::ReadKey($true)
                if ($k.Key -eq [ConsoleKey]::Enter -or $k.Key -eq [ConsoleKey]::Q) { $stoppedEarly = $true; break }
            }
        } catch { $canReadKeys = $false }
    }
    if ($sw.Elapsed.TotalSeconds -gt $Seconds + 10) { break }
}
if (-not $proc.HasExited) {
    # SIGINT lets screenrecord finish writing the MP4 properly.
    Invoke-Adb @("shell", "pkill -INT screenrecord || kill -INT `$(pidof screenrecord)") -AllowFail | Out-Null
    if (-not $proc.WaitForExit(8000)) { $proc.Kill() }
}
$took = [math]::Round($sw.Elapsed.TotalSeconds, 1)
Start-Sleep -Seconds 2
if ($stoppedEarly) { Write-Host "Stopped after $took s" } else { Write-Host "Recorded $took s" }

Invoke-Adb @("pull", $remote, $dest) | Out-Null
Invoke-Adb @("shell", "rm", "-f", $remote) -AllowFail | Out-Null
if (-not (Test-Path $dest) -or (Get-Item $dest).Length -lt 10000) { Fail "the recording is missing or empty ($dest). Some screens (DRM video) can't be recorded." }

$ffprobe = Get-Command ffprobe -ErrorAction SilentlyContinue
if ($ffprobe) {
    $dur = (& $ffprobe.Source -v error -show_entries format=duration -of csv=p=0 $dest) | Select-Object -First 1
    Write-Host "Saved $dest  ($dur s)" -ForegroundColor Green
} else {
    Write-Host "Saved $dest" -ForegroundColor Green
}
Write-Host "Next: set in/out for '$Scene' in video\edl.json, then run .\video\make_video.ps1 -PlanOnly"
