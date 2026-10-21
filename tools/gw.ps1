# Runs the Gradle wrapper with this machine's JDK + Android SDK. Usage: .\tools\gw.ps1 :app:assembleDebug
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "D:\UnityHub\Editor\6000.4.7f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK" }
Push-Location (Join-Path $PSScriptRoot "..")
try { & .\gradlew.bat @args } finally { Pop-Location }
