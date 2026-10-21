<#
.SYNOPSIS
  Assembles the Wander TV demo video: clips from video/raw, title + end cards, burned-in captions,
  and Lumo's voiceover, into video/out/wander-demo.mp4 (plus a matching .srt for YouTube).

.DESCRIPTION
  Reads video/edl.json (scenes, in/out points, captions, VO placements). Every segment is normalized
  to 1920x1080, 30 fps, then concatenated. Clip audio is ducked to clip_volume (default 25%) and the
  VO / sound files are laid on top at their scene-relative times (adelay + amix).
  The script FAILS if the cut is longer than max_seconds (default 178 s = 2:58): the hackathon rules
  let judges stop watching at 3:00.

  Windows PowerShell 5.1 + ffmpeg (any 2023+ build with libx264 and libfreetype).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\video\make_video.ps1 -PlanOnly          # check timing and files, encode nothing
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\video\make_video.ps1 -AllowMissing -TempVo -Draft   # rough cut before all footage / VO exists
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\video\make_video.ps1                    # the final cut
#>
[CmdletBinding()]
param(
    [string]$Edl = (Join-Path $PSScriptRoot "edl.json"),
    [string]$RawDir = (Join-Path $PSScriptRoot "raw"),
    [string]$VoDir = (Join-Path $PSScriptRoot "vo"),
    [string]$Out = (Join-Path $PSScriptRoot "out\wander-demo.mp4"),
    [string]$Ffmpeg = "",
    [double]$MaxSeconds = 0,
    [switch]$AllowMissing,   # placeholder slates for missing clips, skip missing VO / sounds
    [switch]$TempVo,         # synthesize missing VO lines with Windows' built-in voice (timing check only)
    [switch]$Draft,          # fast, low-quality encode
    [switch]$NoLoudnorm,
    [switch]$PlanOnly,
    [switch]$KeepTemp
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Inv = [System.Globalization.CultureInfo]::InvariantCulture
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$W = 1920
$H = 1080
$script:Warnings = New-Object System.Collections.ArrayList
$script:Failed = $false

# ---------------------------------------------------------------- helpers

function N([double]$v) { return $v.ToString("0.####", $Inv) }

function Get-Prop($o, [string]$name, $default = $null) {
    if ($null -eq $o) { return $default }
    $p = $o.PSObject.Properties[$name]
    if ($null -eq $p -or $null -eq $p.Value) { return $default }
    return $p.Value
}

function Resolve-From([string]$base, [string]$p) {
    if ([System.IO.Path]::IsPathRooted($p)) { return [System.IO.Path]::GetFullPath($p) }
    return [System.IO.Path]::GetFullPath((Join-Path $base $p))
}

function Format-Clock([double]$s) {
    $m = [math]::Floor($s / 60)
    $r = $s - 60 * $m
    return ("{0}:{1}" -f $m, $r.ToString("00.0", $Inv))
}

function Add-Warning([string]$msg) {
    [void]$script:Warnings.Add($msg)
    Write-Host "  warning: $msg" -ForegroundColor Yellow
}

function Quote-Arg([string]$a) {
    if ($a.Length -gt 0 -and $a -notmatch '[\s"]') { return $a }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $bs = 0
    foreach ($ch in $a.ToCharArray()) {
        if ($ch -eq [char]'\') { $bs++; continue }
        if ($ch -eq [char]'"') {
            [void]$sb.Append(('\' * (2 * $bs + 1)))
            [void]$sb.Append('"')
            $bs = 0
            continue
        }
        if ($bs -gt 0) { [void]$sb.Append(('\' * $bs)); $bs = 0 }
        [void]$sb.Append($ch)
    }
    if ($bs -gt 0) { [void]$sb.Append(('\' * (2 * $bs))) }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Invoke-Native([string]$exe, [string[]]$argv) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = (($argv | ForEach-Object { Quote-Arg $_ }) -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return [pscustomobject]@{ Code = $p.ExitCode; Out = $outTask.Result; Err = $err }
}

function Invoke-Ffmpeg([string[]]$argv, [string]$what) {
    $r = Invoke-Native $script:FF (@("-hide_banner", "-nostdin", "-nostats", "-loglevel", "error", "-y") + $argv)
    if ($r.Code -ne 0) {
        $tail = ($r.Err -split "`n" | Select-Object -Last 25) -join "`n"
        throw "ffmpeg failed while $what (exit $($r.Code)):`n$tail"
    }
}

function Get-Media([string]$file) {
    $r = Invoke-Native $script:FP @("-v", "error", "-show_entries", "format=duration:stream=codec_type,width,height", "-of", "json", $file)
    if ($r.Code -ne 0) { throw "ffprobe could not read $file`n$($r.Err)" }
    $j = $r.Out | ConvertFrom-Json
    $dur = 0.0
    $fd = Get-Prop $j.format "duration" $null
    if ($fd) { $dur = [double]::Parse([string]$fd, $Inv) }
    $streams = @(Get-Prop $j "streams" @())
    return [pscustomobject]@{
        Duration = $dur
        HasAudio = (@($streams | Where-Object { $_.codec_type -eq "audio" }).Count -gt 0)
        HasVideo = (@($streams | Where-Object { $_.codec_type -eq "video" }).Count -gt 0)
    }
}

# drawtext / filter paths: forward slashes, drive colon escaped ("C\:/..."), quotes escaped.
function Escape-FilterPath([string]$path) {
    $p = $path -replace '\\', '/'
    $p = $p -replace ':', '\:'
    $p = $p -replace "'", "'\\''"
    return $p
}

function Write-TextFile([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, $Utf8NoBom)
}

$script:Measure = $null
function Measure-TextWidth([string]$text, [double]$px) {
    try {
        if ($null -eq $script:Measure) {
            Add-Type -AssemblyName System.Drawing
            $pfc = New-Object System.Drawing.Text.PrivateFontCollection
            $pfc.AddFontFile($script:FontPath)
            $bmp = New-Object System.Drawing.Bitmap(8, 8)
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
            $fmt = [System.Drawing.StringFormat]::GenericTypographic
            $fmt.FormatFlags = $fmt.FormatFlags -bor [System.Drawing.StringFormatFlags]::MeasureTrailingSpaces
            $script:Measure = @{ Pfc = $pfc; G = $g; Fmt = $fmt; Fonts = @{} }
        }
        $key = [string]$px
        if (-not $script:Measure.Fonts.ContainsKey($key)) {
            $fam = $script:Measure.Pfc.Families[0]
            $style = [System.Drawing.FontStyle]::Regular
            if (-not $fam.IsStyleAvailable($style)) { $style = [System.Drawing.FontStyle]::Bold }
            $script:Measure.Fonts[$key] = New-Object System.Drawing.Font($fam, [single]$px, $style, [System.Drawing.GraphicsUnit]::Pixel)
        }
        $size = $script:Measure.G.MeasureString($text, $script:Measure.Fonts[$key], 100000, $script:Measure.Fmt)
        return [double]$size.Width
    } catch {
        return 0.56 * $px * $text.Length
    }
}

# A rounded "pill" with a colored dot and cream text, faded in/out between $from and $to.
# Appends graph statements to $lines and returns the new video label.
$script:PillN = 0
function Add-Pill($lines, [string]$inLabel, [string]$text, [double]$fontPx, [int]$ph, [string]$anchor,
                  [int[]]$dot, [double]$from, [double]$to, [int]$frames, [double]$fps, [string]$tmp) {
    $script:PillN++
    $n = $script:PillN
    $textW = Measure-TextWidth $text $fontPx
    $r = $ph / 2.0
    $dotCx = [math]::Round($r * 0.78, 1)
    $dotR = [math]::Round($ph * 0.105, 1)
    $textX = [math]::Round($dotCx + $dotR + $ph * 0.2)
    $pw = [int][math]::Ceiling($textX + $textW + $ph * 0.45)
    if ($pw -gt ($W - 120)) {
        Add-Warning "caption is wider than the safe area, shorten it: '$text'"
        $pw = $W - 120
    }
    switch ($anchor) {
        "top-left"  { $x = 64; $y = 54 }
        "top-right" { $x = $W - 64 - $pw; $y = 54 }
        default     { $x = [int](($W - $pw) / 2); $y = $H - 80 - $ph }
    }
    $fade = [math]::Min(0.25, ($to - $from) / 3)
    $dr = $dot[0]; $dg = $dot[1]; $db = $dot[2]
    $sr = N $r; $sr5 = N ($r + 0.5); $scx = N $dotCx; $sdr5 = N ($dotR + 0.5)
    $mix = "clip($sdr5-hypot(X-$scx,Y-$sr),0,1)"
    $geq = "geq=r='27+($dr-27)*$mix':g='21+($dg-21)*$mix':b='41+($db-41)*$mix':a='235*clip($sr5-hypot(X-clip(X,$sr,W-$sr),Y-$sr),0,1)'"
    [void]$lines.Add("color=c=black@0.0:s=${pw}x${ph}:r=$(N $fps):d=$(N (1.0 / $fps)),format=rgba,$geq,loop=loop=$($frames - 1):size=1:start=0,setpts=N/$(N $fps)/TB,fade=t=in:st=$(N $from):d=$(N $fade):alpha=1,fade=t=out:st=$(N ($to - $fade)):d=$(N $fade):alpha=1[pill$n]")
    $txt = Join-Path $tmp ("pill$n.txt")
    Write-TextFile $txt $text
    $alpha = "if(lt(t,$(N $from)),0,if(lt(t,$(N ($from + $fade))),(t-$(N $from))/$(N $fade),if(lt(t,$(N ($to - $fade))),1,if(lt(t,$(N $to)),($(N $to)-t)/$(N $fade),0))))"
    $font = Escape-FilterPath $script:FontPath
    $tf = Escape-FilterPath $txt
    $en = "between(t,$(N $from),$(N $to))"
    [void]$lines.Add("[$inLabel][pill$n]overlay=x=${x}:y=${y}:eof_action=pass:enable='$en'[po$n]")
    [void]$lines.Add("[po$n]drawtext=fontfile='$font':textfile='$tf':expansion=none:fontsize=$(N $fontPx):fontcolor=0xF7EFE2:x=$($x + $textX):y=$y+($ph-th)/2:alpha='$alpha':enable='$en'[pt$n]")
    return "pt$n"
}

function Get-VoText([string]$id) {
    if ($script:LineText.ContainsKey($id)) { return $script:LineText[$id] }
    return $null
}

function New-TempVo([string]$id, [string]$tmp) {
    $text = Get-VoText $id
    if (-not $text) { return $null }
    Add-Type -AssemblyName System.Speech
    $wav = Join-Path $tmp ("tempvo-$id.wav")
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try { $s.SelectVoice("Microsoft Zira Desktop") } catch { }
    $s.Rate = 1
    $s.SetOutputToWaveFile($wav)
    $s.Speak(($text -replace '\.\.\.', ','))
    $s.SetOutputToNull()
    $s.Dispose()
    return $wav
}

function Split-Subtitle([string]$text) {
    $chunks = New-Object System.Collections.ArrayList
    foreach ($sentence in ($text -split '(?<=[.!?])\s+')) {
        if ($sentence.Trim().Length -eq 0) { continue }
        $cur = ""
        foreach ($word in ($sentence -split '\s+')) {
            if ($cur.Length -gt 0 -and ($cur.Length + 1 + $word.Length) -gt 84) { [void]$chunks.Add($cur); $cur = $word }
            elseif ($cur.Length -gt 0) { $cur = "$cur $word" }
            else { $cur = $word }
        }
        if ($cur.Length -gt 0) {
            $last = $chunks.Count - 1
            if ($last -ge 0 -and ($chunks[$last].Length + 1 + $cur.Length) -le 60) { $chunks[$last] = $chunks[$last] + " " + $cur }
            else { [void]$chunks.Add($cur) }
        }
    }
    return , $chunks
}

function Wrap-Two([string]$chunk) {
    if ($chunk.Length -le 42) { return $chunk }
    $words = $chunk -split '\s+'
    $best = $chunk; $bestScore = [double]::MaxValue
    for ($i = 1; $i -lt $words.Count; $i++) {
        $a = ($words[0..($i - 1)] -join " "); $b = ($words[$i..($words.Count - 1)] -join " ")
        $score = [math]::Max($a.Length, $b.Length)
        if ($score -lt $bestScore) { $bestScore = $score; $best = "$a`r`n$b" }
    }
    return $best
}

function Format-SrtTime([double]$s) {
    if ($s -lt 0) { $s = 0 }
    $ms = [int][math]::Round($s * 1000)
    $h = [math]::Floor($ms / 3600000); $ms -= $h * 3600000
    $m = [math]::Floor($ms / 60000); $ms -= $m * 60000
    $sec = [math]::Floor($ms / 1000); $ms -= $sec * 1000
    return ("{0:00}:{1:00}:{2:00},{3:000}" -f [int]$h, [int]$m, [int]$sec, [int]$ms)
}

function Write-Loud([string]$msg) {
    Write-Host ""
    Write-Host ("#" * 76) -ForegroundColor Red
    foreach ($l in ($msg -split "`n")) { Write-Host ("#  " + $l) -ForegroundColor Red }
    Write-Host ("#" * 76) -ForegroundColor Red
    Write-Host ""
}

# ---------------------------------------------------------------- main

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wander-video-" + [guid]::NewGuid().ToString("N").Substring(0, 10))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

try {
    # tools
    if ($Ffmpeg) { $script:FF = $Ffmpeg }
    else {
        $c = Get-Command ffmpeg -ErrorAction SilentlyContinue
        if (-not $c) { throw "ffmpeg not found. Install it (winget install Gyan.FFmpeg) or pass -Ffmpeg C:\path\ffmpeg.exe" }
        $script:FF = $c.Source
    }
    $probe = Join-Path (Split-Path -Parent $script:FF) "ffprobe.exe"
    if (-not (Test-Path $probe)) {
        $c = Get-Command ffprobe -ErrorAction SilentlyContinue
        if (-not $c) { throw "ffprobe not found next to ffmpeg or on PATH" }
        $probe = $c.Source
    }
    $script:FP = $probe
    $ver = (Invoke-Native $script:FF @("-version")).Out
    $major = 7
    if ($ver -match 'ffmpeg version n?(\d+)\.') { $major = [int]$Matches[1] }
    if ($major -ge 7) { $scriptOpt = "-/filter_complex" } else { $scriptOpt = "-filter_complex_script" }

    # edit list
    if (-not (Test-Path $Edl)) { throw "Edit list not found: $Edl" }
    $e = Get-Content -Raw -Encoding UTF8 $Edl | ConvertFrom-Json
    $fps = [double](Get-Prop $e "fps" 30)
    $clipVol = [double](Get-Prop $e "clip_volume" 0.25)
    $limit = $MaxSeconds
    if ($limit -le 0) { $limit = [double](Get-Prop $e "max_seconds" 178) }
    $script:FontPath = Resolve-From $Root (Get-Prop $e "font" "app/src/main/assets/fonts/AtkinsonHyperlegible-Bold.ttf")
    if (-not (Test-Path $script:FontPath)) { throw "Caption font not found: $($script:FontPath)" }
    $script:LineText = @{}
    $linesPath = Resolve-From $Root (Get-Prop $e "lines" "video/vo/lines.json")
    if (Test-Path $linesPath) {
        # (Windows PowerShell's ConvertFrom-Json returns a JSON array as one object: assign it, then loop)
        $lineList = Get-Content -Raw -Encoding UTF8 $linesPath | ConvertFrom-Json
        foreach ($l in $lineList) { $script:LineText[[string]$l.id] = [string]$l.text }
    }

    $RawDir = [System.IO.Path]::GetFullPath($RawDir)
    $VoDir = [System.IO.Path]::GetFullPath($VoDir)
    Write-Host "Wander TV video build" -ForegroundColor Cyan
    Write-Host "  edit list : $Edl"
    Write-Host "  clips     : $RawDir"
    Write-Host "  voiceover : $VoDir"
    Write-Host "  ffmpeg    : $($script:FF) (v$major)"

    # ---- collect segments
    $segDefs = New-Object System.Collections.ArrayList
    $tc = Get-Prop $e "title_card" $null
    if ($tc) { [void]$segDefs.Add(@{ Def = $tc; Id = "title"; Card = "title" }) }
    foreach ($s in @(Get-Prop $e "scenes" @())) { [void]$segDefs.Add(@{ Def = $s; Id = [string](Get-Prop $s "id" ("scene" + $segDefs.Count)); Card = "" }) }
    $ec = Get-Prop $e "end_card" $null
    if ($ec) { [void]$segDefs.Add(@{ Def = $ec; Id = "end"; Card = "end" }) }
    if ($segDefs.Count -eq 0) { throw "The edit list has no scenes" }

    $missing = New-Object System.Collections.ArrayList
    $segs = New-Object System.Collections.ArrayList
    foreach ($sd in $segDefs) {
        $d = $sd.Def
        $seg = [ordered]@{ Id = $sd.Id; Card = $sd.Card; Kind = ""; Path = ""; In = 0.0; Speed = 1.0; Seconds = 0.0; HasAudio = $false; KenBurns = $false; Def = $d; Note = "" }
        $image = Get-Prop $d "image" $null
        $file = Get-Prop $d "file" $null
        if ($image) {
            $seg.Kind = "image"
            $seg.Path = Resolve-From $Root $image
            $seg.Seconds = [double](Get-Prop $d "seconds" 3)
            $seg.KenBurns = [bool](Get-Prop $d "kenburns" $false)
            if (-not (Test-Path $seg.Path)) {
                if ($AllowMissing) { $seg.Kind = "missing"; $seg.Note = $image } else { [void]$missing.Add("image  $($seg.Path)") }
            }
        } elseif ($file) {
            $seg.Kind = "clip"
            $seg.Path = Resolve-From $RawDir $file
            $seg.In = [double](Get-Prop $d "in" 0)
            $seg.Speed = [double](Get-Prop $d "speed" 1)
            if ($seg.Speed -le 0) { throw "Scene $($seg.Id): speed must be > 0" }
            $outPt = Get-Prop $d "out" $null
            if (Test-Path $seg.Path) {
                $m = Get-Media $seg.Path
                if (-not $m.HasVideo) { throw "Scene $($seg.Id): $file has no video stream" }
                $seg.HasAudio = $m.HasAudio
                if ($null -eq $outPt) { $outPt = $m.Duration }
                if ([double]$outPt -gt $m.Duration + 0.1) { throw ("Scene $($seg.Id): out={0} is past the end of $file ({1} s)" -f (N $outPt), (N $m.Duration)) }
            } else {
                if ($null -eq $outPt) { throw "Scene $($seg.Id): $file is missing and has no 'out' time" }
                if ($AllowMissing) { $seg.Kind = "missing"; $seg.Note = $file } else { [void]$missing.Add("clip   $($seg.Path)") }
            }
            if ([double]$outPt -le $seg.In) { throw "Scene $($seg.Id): out must be greater than in" }
            $seg.Seconds = ([double]$outPt - $seg.In) / $seg.Speed
        } else {
            throw "Scene $($seg.Id) needs either 'file' (a clip in video/raw) or 'image'"
        }
        $frames = [int][math]::Round($seg.Seconds * $fps)
        if ($frames -lt 1) { throw "Scene $($seg.Id) is shorter than one frame" }
        $seg.Frames = $frames
        $seg.Seconds = $frames / $fps
        [void]$segs.Add($seg)
    }

    # ---- timeline
    $t = 0.0
    foreach ($seg in $segs) { $seg.Start = $t; $t += $seg.Seconds }
    $total = $t

    # ---- audio placements
    $placements = New-Object System.Collections.ArrayList
    foreach ($seg in $segs) {
        foreach ($v in @(Get-Prop $seg.Def "vo" @())) {
            $id = [string](Get-Prop $v "id" "")
            $f = Get-Prop $v "file" $null
            if ($f) { $path = Resolve-From $VoDir $f }
            else {
                $path = Join-Path $VoDir "$id.mp3"
                foreach ($ext in @(".wav", ".m4a", ".ogg")) { if (-not (Test-Path $path)) { $alt = Join-Path $VoDir "$id$ext"; if (Test-Path $alt) { $path = $alt } } }
            }
            if (-not (Test-Path $path)) {
                if ($TempVo -and $id) {
                    $gen = New-TempVo $id $tmp
                    if ($gen) { $path = $gen; Add-Warning "VO $id is a temporary Windows voice (generate the real line from video/vo/lines.json)" }
                }
            }
            if (-not (Test-Path $path)) {
                if ($AllowMissing) { Add-Warning "VO $id missing, skipped ($path)"; continue }
                [void]$missing.Add("vo     $path"); continue
            }
            [void]$placements.Add([ordered]@{ Kind = "vo"; Id = $id; Path = $path; At = $seg.Start + [double](Get-Prop $v "at" 0); Volume = [double](Get-Prop $v "volume" 1.0); Scene = $seg })
        }
        foreach ($x in @(Get-Prop $seg.Def "sfx" @())) {
            $f = [string](Get-Prop $x "file" "")
            $path = Resolve-From $Root $f
            if (-not (Test-Path $path)) {
                if ([bool](Get-Prop $x "optional" $false) -or $AllowMissing) { Add-Warning "sound $f missing, skipped"; continue }
                [void]$missing.Add("sound  $path"); continue
            }
            [void]$placements.Add([ordered]@{ Kind = "sfx"; Id = [System.IO.Path]::GetFileNameWithoutExtension($path); Path = $path; At = $seg.Start + [double](Get-Prop $x "at" 0); Volume = [double](Get-Prop $x "volume" 1.0); Scene = $seg })
        }
    }

    if ($missing.Count -gt 0) {
        throw ("Missing files (record them, or run with -AllowMissing for a rough cut):`n  " + ($missing -join "`n  "))
    }

    foreach ($p in $placements) { $p.Duration = (Get-Media $p.Path).Duration }

    # ---- report
    Write-Host ""
    Write-Host ("  {0,-8} {1,-8} {2,-8} {3,-7} {4}" -f "scene", "start", "end", "secs", "source")
    foreach ($seg in $segs) {
        $src = Split-Path -Leaf $seg.Path
        if ($seg.Kind -eq "missing") { $src = "MISSING " + $seg.Note }
        if ($seg.Speed -ne 1) { $src = "$src  ({0}x)" -f (N $seg.Speed) }
        Write-Host ("  {0,-8} {1,-8} {2,-8} {3,-7} {4}" -f $seg.Id, (Format-Clock $seg.Start), (Format-Clock ($seg.Start + $seg.Seconds)), (N ([math]::Round($seg.Seconds, 2))), $src)
        foreach ($p in ($placements | Where-Object { [object]::ReferenceEquals($_.Scene, $seg) })) {
            Write-Host ("      {0,-5} {1,-6} {2} - {3}" -f $p.Kind, $p.Id, (Format-Clock $p.At), (Format-Clock ($p.At + $p.Duration))) -ForegroundColor DarkGray
        }
    }
    Write-Host ("  TOTAL    {0}  (limit {1})" -f (Format-Clock $total), (Format-Clock $limit)) -ForegroundColor Cyan

    if ($total -gt $limit + 0.001) {
        throw ("TOO LONG: the cut is {0} but the limit is {1}. Trim {2} s from the scenes above (edit 'in'/'out' in edl.json). Judges may stop watching at 3:00." -f (Format-Clock $total), (Format-Clock $limit), (N ([math]::Round($total - $limit, 2))))
    }

    $vos = @($placements | Where-Object { $_.Kind -eq "vo" } | Sort-Object { $_.At })
    for ($i = 0; $i -lt $vos.Count; $i++) {
        $p = $vos[$i]
        if ($p.At -lt 0) { throw "VO $($p.Id) starts before 0:00" }
        if ($p.At + $p.Duration -gt $total + 0.01) { throw ("VO {0} ends at {1}, after the video ends ({2}). Move it earlier or lengthen the last scene." -f $p.Id, (Format-Clock ($p.At + $p.Duration)), (Format-Clock $total)) }
        if ($i + 1 -lt $vos.Count) {
            $q = $vos[$i + 1]
            $overlap = $p.At + $p.Duration - $q.At
            if ($overlap -gt 0.05) { Add-Warning ("VO {0} overlaps {1} by {2} s: move {1} later or trim {0}" -f $p.Id, $q.Id, (N ([math]::Round($overlap, 2)))) }
        }
        $sceneEnd = $p.Scene.Start + $p.Scene.Seconds
        if ($p.Scene.Card -ne "title" -and $p.At + $p.Duration -gt $sceneEnd + 1.0) { Add-Warning ("VO {0} runs {1} s past the end of scene {2}" -f $p.Id, (N ([math]::Round($p.At + $p.Duration - $sceneEnd, 2))), $p.Scene.Id) }
    }

    if ($PlanOnly) { Write-Host "`nPlan only: nothing encoded." -ForegroundColor Cyan; return }

    # ---- encode segments
    if ($Draft) { $segPreset = "ultrafast"; $segCrf = "24"; $finPreset = "veryfast"; $finCrf = "26" }
    else { $segPreset = "veryfast"; $segCrf = "14"; $finPreset = "medium"; $finCrf = "18" }
    $fit = "scale=${W}:${H}:force_original_aspect_ratio=decrease:flags=lanczos,pad=${W}:${H}:(ow-iw)/2:(oh-ih)/2:color=0x120E1C,setsar=1"
    $segFiles = New-Object System.Collections.ArrayList
    $idx = 0
    foreach ($seg in $segs) {
        $idx++
        $dur = $seg.Seconds
        $frames = $seg.Frames
        $d = $seg.Def
        $inputs = New-Object System.Collections.ArrayList
        $g = New-Object System.Collections.ArrayList
        $vchain = New-Object System.Collections.ArrayList

        switch ($seg.Kind) {
            "clip" {
                [void]$inputs.AddRange(@("-ss", (N $seg.In), "-t", (N ($dur * $seg.Speed + 0.05)), "-i", $seg.Path))
                [void]$vchain.Add("setpts=(PTS-STARTPTS)/$(N $seg.Speed)")
                $crop = Get-Prop $d "crop" $null
                if ($crop) {
                    # source pixels, e.g. a phone recording without its status bar and browser bar
                    [void]$vchain.Add(("crop={0}:{1}:{2}:{3}" -f [int](Get-Prop $crop "w" 0), [int](Get-Prop $crop "h" 0), [int](Get-Prop $crop "x" 0), [int](Get-Prop $crop "y" 0)))
                }
                [void]$vchain.Add($fit)
                [void]$vchain.Add("fps=$(N $fps)")
            }
            "image" {
                if ($seg.KenBurns) {
                    [void]$inputs.AddRange(@("-i", $seg.Path))
                    [void]$vchain.Add("scale=3840:2160:force_original_aspect_ratio=decrease:flags=lanczos,pad=3840:2160:(ow-iw)/2:(oh-ih)/2:color=0x120E1C,setsar=1")
                    [void]$vchain.Add("zoompan=z='1+0.05*on/$frames':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=${frames}:s=${W}x${H}:fps=$(N $fps)")
                } else {
                    [void]$inputs.AddRange(@("-loop", "1", "-framerate", (N $fps), "-t", (N ($dur + 0.5)), "-i", $seg.Path))
                    [void]$vchain.Add($fit)
                    [void]$vchain.Add("fps=$(N $fps)")
                }
            }
            "missing" {
                [void]$inputs.AddRange(@("-f", "lavfi", "-i", "color=c=0x1B1529:s=${W}x${H}:r=$(N $fps):d=$(N ($dur + 0.5))"))
                $mt = Join-Path $tmp "missing$idx.txt"
                Write-TextFile $mt ("Missing footage: " + $seg.Note)
                [void]$vchain.Add("drawtext=fontfile='$(Escape-FilterPath $script:FontPath)':textfile='$(Escape-FilterPath $mt)':expansion=none:fontsize=54:fontcolor=0xFFB547:x=(w-tw)/2:y=(h-th)/2-60")
            }
        }
        if ($seg.Card -eq "title") {
            [void]$vchain.Add("fade=t=in:st=0:d=0.5:color=0x120E1C")
            [void]$vchain.Add("fade=t=out:st=$(N ($dur - 0.45)):d=0.45:color=0x120E1C")
        } elseif ($seg.Card -eq "end") {
            [void]$vchain.Add("fade=t=in:st=0:d=0.5:color=0x120E1C")
            [void]$vchain.Add("fade=t=out:st=$(N ($dur - 0.7)):d=0.7:color=0x120E1C")
        }
        [void]$vchain.Add("format=yuv420p")
        [void]$g.Add("[0:v]" + ($vchain -join ",") + "[v0]")
        $label = "v0"

        # blur boxes (hide a logo, a notification, a face)
        $bn = 0
        foreach ($b in @(Get-Prop $d "blur" @())) {
            $bn++
            $bx = [int](Get-Prop $b "x" 0); $by = [int](Get-Prop $b "y" 0)
            $bw = [int](Get-Prop $b "w" 0); $bh = [int](Get-Prop $b "h" 0)
            $bf = [double](Get-Prop $b "from" 0); $bt = [double](Get-Prop $b "to" $dur)
            $bw = [math]::Min($bw, $W - $bx); $bh = [math]::Min($bh, $H - $by)
            if ($bw -lt 8 -or $bh -lt 8) { Add-Warning "blur box $bn in $($seg.Id) is too small or off screen, skipped"; continue }
            $lr = [math]::Max(1, [math]::Min(30, [math]::Floor([math]::Min($bw, $bh) / 2) - 1))
            $cr = [math]::Max(1, [math]::Min($lr, [math]::Floor([math]::Min($bw, $bh) / 4) - 1))
            [void]$g.Add("[$label]split=2[bA$bn][bB$bn]")
            [void]$g.Add("[bB$bn]crop=${bw}:${bh}:${bx}:${by},boxblur=luma_radius=${lr}:luma_power=3:chroma_radius=${cr}:chroma_power=3[bC$bn]")
            [void]$g.Add("[bA$bn][bC$bn]overlay=x=${bx}:y=${by}:enable='between(t,$(N $bf),$(N $bt))'[bO$bn]")
            $label = "bO$bn"
        }

        # badges and captions
        $badge = Get-Prop $d "badge" $null
        if ($badge) { $label = Add-Pill $g $label ([string]$badge) 28 56 "top-left" @(61, 220, 151) 0.3 ($dur - 0.3) $frames $fps $tmp }
        if ($seg.Speed -ne 1 -and $seg.Kind -ne "image") {
            $label = Add-Pill $g $label ("{0}x speed" -f (N $seg.Speed)) 28 56 "top-right" @(90, 180, 255) 0.2 ($dur - 0.2) $frames $fps $tmp
        }
        foreach ($c in @(Get-Prop $d "captions" @())) {
            $text = ([string](Get-Prop $c "text" "")).Trim()
            if (-not $text) { continue }
            $from = [math]::Max(0.0, [double](Get-Prop $c "from" 0.4))
            $to = [math]::Min($dur, [double](Get-Prop $c "to" ($dur - 0.3)))
            if ($to -le $from + 0.3) { Add-Warning "caption '$text' in $($seg.Id) is too short or outside the scene, skipped"; continue }
            $label = Add-Pill $g $label $text 46 88 "bottom" @(255, 181, 71) $from $to $frames $fps $tmp
        }
        [void]$g.Add("[$label]null[vout]")

        # audio: the clip's own sound, ducked, or silence
        if ($seg.Kind -eq "clip" -and $seg.HasAudio) {
            $tempo = New-Object System.Collections.ArrayList
            $sp = $seg.Speed
            while ($sp -gt 2.0) { [void]$tempo.Add("atempo=2.0"); $sp = $sp / 2.0 }
            while ($sp -lt 0.5) { [void]$tempo.Add("atempo=0.5"); $sp = $sp / 0.5 }
            if ([math]::Abs($sp - 1.0) -gt 0.0001) { [void]$tempo.Add("atempo=$(N $sp)") }
            $chain = @("asetpts=PTS-STARTPTS") + @($tempo) + @("aresample=48000", "aformat=sample_fmts=fltp:channel_layouts=stereo", "volume=$(N $clipVol)", "apad", "atrim=0:$(N $dur)")
            [void]$g.Add("[0:a]" + ($chain -join ",") + "[aout]")
        } else {
            [void]$inputs.AddRange(@("-f", "lavfi", "-t", (N ($dur + 0.1)), "-i", "anullsrc=r=48000:cl=stereo"))
            [void]$g.Add("[1:a]atrim=0:$(N $dur),asetpts=PTS-STARTPTS[aout]")
        }

        $graphPath = Join-Path $tmp ("seg{0:00}.graph" -f $idx)
        Write-TextFile $graphPath (($g -join ";`n") + "`n")
        $segOut = Join-Path $tmp ("seg{0:00}.mkv" -f $idx)
        $argv = @() + $inputs + @($scriptOpt, $graphPath, "-map", "[vout]", "-map", "[aout]",
            "-frames:v", "$frames", "-t", (N $dur), "-r", (N $fps),
            "-c:v", "libx264", "-preset", $segPreset, "-crf", $segCrf, "-pix_fmt", "yuv420p",
            "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", $segOut)
        Write-Host ("  encoding {0,-8} {1} s" -f $seg.Id, (N ([math]::Round($dur, 2))))
        Invoke-Ffmpeg $argv "encoding scene $($seg.Id) (graph: $graphPath)"
        [void]$segFiles.Add($segOut)
    }

    # ---- final: concat + voiceover mix
    $argv = New-Object System.Collections.ArrayList
    foreach ($f in $segFiles) { [void]$argv.AddRange(@("-i", $f)) }
    $mixInputs = @($placements | Sort-Object { $_.At })
    foreach ($p in $mixInputs) { [void]$argv.AddRange(@("-i", $p.Path)) }
    $g = New-Object System.Collections.ArrayList
    $cat = ""
    for ($i = 0; $i -lt $segFiles.Count; $i++) { $cat += "[${i}:v][${i}:a]" }
    [void]$g.Add("${cat}concat=n=$($segFiles.Count):v=1:a=1[cv][ca]")
    $mixLabels = "[ca]"
    for ($j = 0; $j -lt $mixInputs.Count; $j++) {
        $p = $mixInputs[$j]
        $inIdx = $segFiles.Count + $j
        $ms = [int][math]::Round($p.At * 1000)
        [void]$g.Add("[${inIdx}:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=$(N $p.Volume),adelay=delays=${ms}:all=1[m$j]")
        $mixLabels += "[m$j]"
    }
    # VO + sounds go on their own bus, which is loudness-normalized; the clip bed stays at clip_volume.
    if ($mixInputs.Count -gt 0) {
        $busLabels = $mixLabels.Substring(4)
        $bus = "${busLabels}amix=inputs=$($mixInputs.Count):duration=longest:dropout_transition=0:normalize=0"
        if (-not $NoLoudnorm) { $bus += ",loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000" }
        [void]$g.Add($bus + "[vobus]")
        [void]$g.Add("[ca][vobus]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,alimiter=limit=0.97[aout]")
    } else {
        [void]$g.Add("[ca]alimiter=limit=0.97[aout]")
    }
    $graphPath = Join-Path $tmp "final.graph"
    Write-TextFile $graphPath (($g -join ";`n") + "`n")
    $outFull = [System.IO.Path]::GetFullPath($Out)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outFull) | Out-Null
    [void]$argv.AddRange(@($scriptOpt, $graphPath, "-map", "[cv]", "-map", "[aout]",
        "-c:v", "libx264", "-preset", $finPreset, "-crf", $finCrf, "-pix_fmt", "yuv420p", "-r", (N $fps),
        "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", $outFull))
    Write-Host "  mixing voiceover and writing $outFull"
    Invoke-Ffmpeg ([string[]]$argv) "mixing the final cut (graph: $graphPath)"

    # ---- verify
    $final = Get-Media $outFull
    if ($final.Duration -gt $limit + 0.05) {
        $bad = [System.IO.Path]::ChangeExtension($outFull, ".TOO-LONG.mp4")
        Move-Item -Force $outFull $bad
        throw ("TOO LONG after encoding: {0} > {1}. Output renamed to {2}" -f (Format-Clock $final.Duration), (Format-Clock $limit), $bad)
    }

    # ---- captions file for YouTube
    $srt = New-Object System.Text.StringBuilder
    $n = 0
    foreach ($p in $vos) {
        $text = Get-VoText $p.Id
        if (-not $text) { continue }
        $chunks = Split-Subtitle $text
        $totalChars = 0
        foreach ($c in $chunks) { $totalChars += $c.Length }
        $cursor = $p.At
        foreach ($c in $chunks) {
            $len = $p.Duration * ($c.Length / [double]$totalChars)
            $n++
            [void]$srt.Append("$n`r`n$(Format-SrtTime $cursor) --> $(Format-SrtTime ($cursor + $len - 0.04))`r`n$(Wrap-Two $c)`r`n`r`n")
            $cursor += $len
        }
    }
    $srtPath = [System.IO.Path]::ChangeExtension($outFull, ".srt")
    Write-TextFile $srtPath $srt.ToString()

    Write-Host ""
    Write-Host ("Done: {0}  ({1})" -f $outFull, (Format-Clock $final.Duration)) -ForegroundColor Green
    Write-Host "Captions: $srtPath (upload to YouTube as English subtitles)" -ForegroundColor Green
    if ($script:Warnings.Count -gt 0) { Write-Host ("{0} warning(s) above." -f $script:Warnings.Count) -ForegroundColor Yellow }
}
catch {
    $script:Failed = $true
    Write-Loud ("make_video FAILED`n" + $_.Exception.Message)
}
finally {
    if ($KeepTemp) { Write-Host "Temp files kept in $tmp" }
    else { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
}
if ($script:Failed) { exit 1 }
exit 0
