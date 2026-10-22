---
inclusion: always
---

# Tech

## Stack

- **TV app:** Kotlin, plain Android Views and Canvas (no Compose, no AndroidX UI libraries), Android WebView.
  minSdk 25 (Fire OS 6), targetSdk 35. Dependencies are only couchlink, ZXing core and the test libraries:
  **do not add Gradle dependencies.**
- **couchlink:** zero-dependency HTTP + WebSocket server (pure JVM, `java.net`) that serves the phone page and
  the phone protocol on port 8080.
- **Phone page:** plain HTML/CSS/JS in `app/src/main/assets/phone/` (no frameworks, no CDNs, works over plain
  http on iOS Safari and Android Chrome).
- **Backend:** AWS SAM stack (API Gateway HTTP API, Lambda Python 3.12 with boto3 only, Bedrock Converse with
  forced tool use + Guardrails, DynamoDB cache, Polly) in `backend/`.

## Rules

- No APIs above API 25 without a `Build.VERSION.SDK_INT` guard. No `java.time` or `java.util.Base64` in app code.
- Never block the main thread: use `tv.wander.ui.Async`. Never let an exception crash the app: catch at
  boundaries and degrade gracefully.
- Keep existing public signatures; additions are fine.
- Write code that reads like the surrounding code: same comment density, naming and idioms. Comments explain
  why, not what.
- User-visible text: English, short, warm, in Lumo's voice where it is Lumo speaking.

## Build and test (Windows PowerShell from the repo root)

`tools\gw.ps1` sets JAVA_HOME and ANDROID_HOME for this machine, then runs the Gradle wrapper.

```powershell
.\tools\gw.ps1 :app:testDebugUnitTest                          # all JVM + Robolectric tests
.\tools\gw.ps1 :app:testDebugUnitTest --tests "*SearchUrls*"   # one class
.\tools\gw.ps1 :app:assembleDebug                              # APK: app\build\outputs\apk\debug\app-debug.apk
.\tools\gw.ps1 :couchlink:test
python -m unittest discover -s backend/tests                   # backend tests (boto3 is stubbed)
```

If PowerShell refuses to run the script, use
`powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\gw.ps1 <tasks>`. In Git Bash use `tools/gw.sh`.

Tests are JUnit 4. Pure logic gets plain JVM tests (`org.json` is on the test classpath). Views are checked with
Robolectric screenshots via `app/src/test/java/tv/wander/testing/Snap.kt` (PNGs in `app/build/snaps/`).

Every change must leave `:app:testDebugUnitTest` and `:app:assembleDebug` green.
