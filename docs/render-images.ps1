<#
.SYNOPSIS
  Renders the hand-written SVGs in docs/ (architecture, title card, end card) to 1920x1080 PNGs.

.DESCRIPTION
  Uses headless Chrome or Edge, because the SVGs load the app's own fonts (Young Serif, Atkinson
  Hyperlegible) from app/src/main/assets/fonts. Falls back to ffmpeg only when ffmpeg was built with
  librsvg (the common Windows "full" builds are not), and then the fonts fall back to Georgia/Segoe UI.

.EXAMPLE
  .\docs\render-images.ps1
  .\docs\render-images.ps1 -Names end-card
#>
param(
    [string[]]$Names = @("architecture", "title-card", "end-card"),
    [string]$Browser = ""
)

$ErrorActionPreference = "Stop"
$docs = $PSScriptRoot

function Find-Browser {
    param([string]$Preferred)
    if ($Preferred -and (Test-Path $Preferred)) { return $Preferred }
    $candidates = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

$browserExe = Find-Browser $Browser
$profileDir = Join-Path ([System.IO.Path]::GetTempPath()) ("wander-render-" + [guid]::NewGuid().ToString("N"))

try {
    foreach ($name in $Names) {
        $svg = Join-Path $docs "$name.svg"
        $png = Join-Path $docs "$name.png"
        if (-not (Test-Path $svg)) { throw "Missing $svg" }
        if (Test-Path $png) { Remove-Item $png -Force }

        if ($browserExe) {
            $uri = ([System.Uri](Resolve-Path $svg).Path).AbsoluteUri
            # The SVGs are 1600x900; a 1.2 device scale factor gives a crisp 1920x1080 screenshot.
            $argList = @(
                "--headless=new", "--disable-gpu", "--hide-scrollbars", "--disable-extensions",
                "--allow-file-access-from-files", "--no-first-run", "--no-default-browser-check",
                "--user-data-dir=`"$profileDir`"",
                "--window-size=1600,900", "--force-device-scale-factor=1.2",
                "--screenshot=`"$png`"", "`"$uri`""
            )
            $p = Start-Process -FilePath $browserExe -ArgumentList $argList -Wait -PassThru -WindowStyle Hidden
            if (-not (Test-Path $png)) { throw "Browser render failed for $name (exit $($p.ExitCode))" }
        } else {
            $ff = Get-Command ffmpeg -ErrorAction SilentlyContinue
            if (-not $ff) { throw "Need Chrome/Edge or ffmpeg with librsvg to render $name" }
            & ffmpeg -hide_banner -loglevel error -y -width 1920 -height 1080 -i $svg -frames:v 1 $png
            if ($LASTEXITCODE -ne 0) { throw "ffmpeg could not decode SVG (no librsvg?). Install Chrome or Edge." }
        }
        Write-Host "rendered $png"
    }
} finally {
    if (Test-Path $profileDir) { Remove-Item $profileDir -Recurse -Force -ErrorAction SilentlyContinue }
}
