# Wander TV: build spec (the contract every builder follows)

Read this whole file before writing code. `PLAN.md` is the original product plan; this file turns it into
concrete contracts. Where they disagree, this file wins.

## 1. What we are building

Wander TV is a **couch-first web browser for Fire TV** (Fire OS, native Kotlin app). The whole family
browses together. Every phone becomes a keyboard and touchpad by scanning a QR code (no install). A
companion called **Lumo** turns long pages into big readable cards, runs family quests judged by AI, and
notices aimless browsing and offers a better detour.

**Identity: not generic.** Lumo is a small, fuzzy **moth**. Moths circle bright lights for no reason;
Lumo knows that feeling, which is why it notices when the family is circling the screen. The visual
world is a **lamp-lit living room at night**: deep aubergine ink, warm lamp-amber light, cream text, dust
motes in the lamp glow. Cards look like collectible **field-guide cards**. Copy is warm, short and a
little funny, never preachy. Lumo never shames anyone; it offers.

Tagline: *"Browse together. Wander well."*

Hackathon judging (equal weights): Tech Implementation, Design (coherent, TV-appropriate interaction
model), Potential Impact, Quality of Idea (creative > obvious; multi-modal UX, AI that adapts to the
household). AWS Builder mini-challenge prefers a multi-service pipeline over "a single Bedrock call".
The video can't show third-party trademarks, so our own UI must carry the demo.

## 2. Repo layout and ownership

```
app/                         Android app (package tv.wander), Kotlin, plain Android Views (no Compose)
  src/main/java/tv/wander/
    MainActivity.kt          integration hub: layers, mode machine, key routing      [shell]
    Config.kt                runtime settings (done)                                   [shared, frozen]
    model/Models.kt          all shared data types (done)                              [shared, frozen]
    ui/Theme.kt, Async.kt    design tokens, helpers (done)                             [shared, frozen*]
    ui/Sfx.kt                sound effects                                             [shell]
    home/                    HomeView, Favorites, LumoActionBar (the Menu-key bar)     [shell]
    browse/                  BrowseLayer, SearchUrls, cursor, WebView scripts          [browse]
    companion/               LumoView, CompanionLayer, DriftDetector, Lines, CompanionPanel [companion]
    rescue/                  RescueOverlay                                             [companion]
    speech/                  Narrator                                                  [companion]
    cards/                   CardsOverlay                                              [cards-quest]
    quest/                   QuestEngine, QuestCatalog, QuestOverlay                   [cards-quest]
    ai/                      AiClient, OfflineAi                                       [backend]
    net/                     PhoneHub, PhoneCommand, LanAddress, Qr                    [phone]
  src/main/assets/
    fonts/                   YoungSerif + AtkinsonHyperlegible TTFs (done; also served to phones)
    phone/                   the phone controller web app (index.html, phone.css, phone.js) [phone]
    js/                      Readability.js + wander-bridge.js injected into pages      [browse]
  src/main/res/              raw/ sounds, drawable/ icon + banner                       [shell]
  src/test/java/tv/wander/   JVM + Robolectric tests; testing/Snap.kt renders views to PNG
couchlink/                   zero-dependency HTTP + WebSocket server (pure JVM)          [phone]
  src/harness/kotlin/        desktop TV simulator for testing the phone page in a browser
backend/                     AWS: SAM template, Lambda (Python), deploy scripts, tests  [backend]
docs/, video/                README material, architecture diagram, video script        [docs]
```

\* `Theme.kt`/`Async.kt`/`Models.kt`/`Config.kt` are frozen: do not edit. If you truly need a new shared
token or type, define it privately in your own files.

**Rules for builders**
- Only create/edit files you own (table above). Public signatures in the stubs are a contract: keep
  every existing public class, constructor, method and property with the same signature. You may add
  private members, new private/internal classes and new files inside your own package.
- Plain Android Views + Canvas. No Compose, no AndroidX UI libs, no new Gradle dependencies (the
  build already has couchlink, ZXing core, JUnit, org.json for tests, Robolectric).
- minSdk 25 (Fire OS 6). Do not use APIs above 25 without a `Build.VERSION.SDK_INT` guard. No
  `java.time`, no `java.util.Base64` in app code.
- Never block the main thread; use `tv.wander.ui.Async`.
- Every user-visible string is English, short, warm. No lorem ipsum.

## 3. Design system (see `ui/Theme.kt`)

- Canvas: 960x540dp (1080p at xhdpi). Keep content inside the overscan-safe area: 48dp left/right,
  27dp top/bottom (`Dimens.SAFE_H/SAFE_V`).
- Colors (`Palette`): INK background, NIGHT/DUSK surfaces, HAIRLINE borders, CREAM text, MUTED
  secondary, LAMP accent + focus, players `PLAYERS[0..3]` = Sun (amber), Berry (pink), Mint, Sky.
  CORAL for "not quite". SCRIM over web pages. Full-screen screens use `LampBackdrop()` as background.
- Type (`TypeScale`, sp): DISPLAY 44, H1 32, H2 24, BODY 20, CAPTION 16 (minimum). Headings use
  `Fonts.display` (Young Serif); everything else `Fonts.body` / `Fonts.bodyBold` (Atkinson Hyperlegible).
  Build text with `Ui.text(...)`.
- Shapes: 20dp radius (`Dimens.RADIUS`), 28dp for hero cards. Surfaces = `Ui.roundRect(...)`.
- Focus: every D-pad target uses `Ui.makeFocusable(view)` (scale 1.06, lift, 3dp lamp ring, focus
  sound). Focus must always be visible and never get lost: set `nextFocus*` where layouts are irregular.
- Motion: 160-280ms, decelerate for entrances, overshoot only for celebrations. Nothing flickers.
- Emoji are fine for icons (Fire OS renders color emoji).

## 4. Remote keys (Fire TV remote)

| Key | Home | Browse | Cards | Quest picker / intro / finale | Rescue |
|---|---|---|---|---|---|
| D-pad | move focus | move cursor (accelerates when held; scrolls page at edges) | left/right flip | move focus | move focus |
| Center | activate | tap at cursor | read card aloud | activate | choose |
| Back | (exit app prompt via system) | page back; at first page -> Home | close cards | close | "we're fine" (dismiss) |
| Menu (≡) | open Lumo panel | Lumo action bar (Make cards / Submit to quest / Home / Zoom) | close | - | - |
| Rewind / Fast-fwd | - | page up / page down | prev / next | - | - |
| Play/Pause | - | Make it cards | read aloud | - | - |

`KEYCODE_DPAD_*`, `KEYCODE_DPAD_CENTER`, `KEYCODE_ENTER`, `KEYCODE_BACK`, `KEYCODE_MENU`,
`KEYCODE_MEDIA_REWIND`, `KEYCODE_MEDIA_FAST_FORWARD`, `KEYCODE_MEDIA_PLAY_PAUSE`.
Overlays expose `handleKey(event): Boolean`; MainActivity routes `dispatchKeyEvent` to the top-most
visible layer first, and only handles a key itself if the layer returns false.

## 5. Phone protocol (WebSocket JSON at `ws://<tv-ip>:8080/ws`)

The TV serves the phone app over plain HTTP on port 8080: `GET /` -> `assets/phone/index.html`,
`GET /fonts/*` -> `assets/fonts/*`, `GET /<file>` -> `assets/phone/<file>`. Every message is one JSON
object with a `t` (type) field. Player colors are indexes 0-3 into the shared palette:
`#FFB547` Sun, `#FF5D8F` Berry, `#3DDC97` Mint, `#5AB4FF` Sky.

Phone -> TV
```
{"t":"hello","name":"Maya","color":1,"pid":"p3"|null}   first message; pid = remembered id to rejoin
{"t":"move","dx":12.5,"dy":-3}          touchpad delta in phone CSS px (phone batches at ~60Hz)
{"t":"tap"}                              click at the cursor
{"t":"scroll","dx":0,"dy":120}           page scroll in phone CSS px (positive = down)
{"t":"go","text":"cheetah top speed"}    URL or search
{"t":"type","text":"hello","submit":true}  type into the focused page field
{"t":"key","key":"back"}                 back|home|menu|up|down|left|right|enter|playpause
{"t":"cards"}                            make it cards
{"t":"quest_submit"}                     "this page answers the quest!"
{"t":"quest_start","id":"speed-safari"}  id "lumo" = Lumo's pick (AI-generated)
{"t":"read"}                             read the current card aloud
{"t":"rescue","choice":0}                0/1 = option, -1 = we're fine
{"t":"zoom","delta":1}                   +1 / -1 text zoom
{"t":"ping"}
```

TV -> Phone
```
{"t":"welcome","pid":"p3","name":"Maya","color":1}
{"t":"reject","reason":"full"}           5th phone; reasons: full | bad_hello
{"t":"players","players":[{"pid":"p3","name":"Maya","color":1,"score":200}]}
{"t":"state","mode":"browse","title":"...","url":"...","checking":false,"ai":true,
  "field":{"focused":true,"label":"Search"},
  "cards":{"index":0,"count":4,"title":"Built for speed"} | null,
  "quest":{"id":"speed-safari","title":"Speed Safari","prompt":"...","emoji":"🐆","goal":3,
           "found":[{"item":"Cheetah","pid":"p3","evidence":"..."}],"complete":false,
           "players":[{"pid":"p3","name":"Maya","color":1,"score":200}]} | null,
  "rescue":{"headline":"...","detail":"...","options":[{"label":"...","detail":"...","kind":"quest"}]} | null,
  "quests":[{"id":"speed-safari","title":"Speed Safari","emoji":"🐆","prompt":"..."}]}
{"t":"toast","text":"Lumo is checking your page...","kind":"info"|"good"|"bad"}
{"t":"celebrate","text":"+100 Cheetah!","color":1}   sent to the scoring player (confetti + vibrate)
{"t":"pong"}
```
**Asset mapping.** `PhoneHub`'s `assets: AssetSource` takes *asset-relative* paths without a leading
slash (`phone/index.html`, `fonts/YoungSerif-Regular.ttf`); PhoneHub maps URL paths to them as above.
MainActivity passes `AssetSource { p -> runCatching { assets.open(p).use { it.readBytes() } }.getOrNull() }`.
The desktop harness applies the same mapping to a directory.

`quest.players` is the TV's scoreboard (it includes the remote's "The couch" player once it has
points); `rescue.options[].kind` is `quest` | `detour` | `wind_down`. Both are additive: phones fall back
when they are missing. Browser WebSocket handshakes must use the TV's IP literal or `localhost` as Host
(a DNS-rebinding guard); the QR code and the harness URLs already do.

`mode` is the lowercase `Mode` enum name (`home`, `browse`, `cards`, `quest_picker`, `quest_intro`,
`rescue`, `companion`). Hello rules: max 4 connected players. A `pid` matching a disconnected player
resumes it (same score/color). A taken color gets the first free one. Names are trimmed to 16 chars;
empty -> the color name. The server sends `welcome`, then `players` to everyone, then the latest
`state` to the newcomer.

## 6. Backend HTTP API (API Gateway HTTP API -> Lambda, Python)

Base URL from `Config.apiUrl`. Every request sends header `x-wander-key: <Config.apiKey>`; 401 if
wrong. JSON in/out, UTF-8. Timeouts on the TV: 8s (cards, quest), 4s (companion, health), 10s (speak).

```
GET  /health          -> {"ok":true,"model":"...","region":"..."}
POST /cards           {"url","title","text"}                    text <= 6000 chars
                      -> {"cards":[{"title","line","emoji","icon"}],"cached":bool}   3-5 cards
POST /quest/check     {"quest":{"id","title","prompt","rubric","goal"},
                       "page":{"url","title","text"},"found":["Cheetah"]}
                      -> {"pass":bool,"item":"Peregrine falcon"|null,"reason":"...","evidence":"..."|null}
POST /quest/new       {"recent":["Cheetah","Saturn"]}            page titles only
                      -> {"quest":{"id","title","prompt","goal","emoji","rubric"}}
POST /companion       {"signal":{"reason","headline","detail"},"stats":{SessionStats fields}}
                      -> {"mood":"sleepy","line":"...","suggestion":{"kind":"quest"|"detour","label","detail","url"?,"questId"?}|null}
POST /speak           {"text","voice"?}                         -> {"audio":"<base64 mp3>","voice":"Ivy"}
```
Card text limits: title <= 32 chars, line <= 110 chars, one emoji. Quest reason <= 120 chars,
evidence <= 140 chars and must be a near-verbatim quote from the page. A guardrail block returns 200
with `{"blocked":true}` plus a safe fallback body of the same shape.

AWS pipeline: API Gateway HTTP API -> Lambda (Python 3.12, boto3 only) -> Amazon Bedrock Converse
with forced tool-use (JSON schema output) + Bedrock Guardrails (family-safe content, prompt-attack
filter on untrusted page text) -> DynamoDB (card cache per URL, TTL 7 days) -> Amazon Polly (Lumo's
voice) -> CloudWatch logs. Region default eu-central-1. Model is an env var.

**Privacy rule (say it everywhere):** page text leaves the TV only when someone presses "Make it
cards" or submits a quest answer. Drift detection runs on the TV from timing alone. "Lumo's pick"
sends recent page titles only when someone asks for it.

## 7. Drift rules (DriftDetector)

Normal thresholds (persisted multipliers adapt them per household):
- RAPID_HOPPING: >= 6 page loads in 5 min, each viewed < 20 s
- ENDLESS_SCROLL: >= 3 min of scrolling with no click
- SITE_LOOP: same host opened >= 5 times in 15 min
- LATE_NIGHT: local hour >= 23 (or < 5) and session > 30 min

Demo mode (`Config.demoMode`): hopping >= 4 loads in 60 s each < 20 s; scroll 45 s; site loop 3 in
3 min; late night ignored. Cooldown between signals: 10 min (demo: 45 s). `dismissed()` multiplies
the fired rule's thresholds by 1.25 (cap 3x) and persists via `Store`; `accepted()` nudges them back
toward 1.0. Headlines are Lumo's voice, e.g. "We've hopped 7 pages in 4 minutes. I know that dizzy
feeling." Detail is the plain measurement: "7 pages · 4 min · ~12 s each".

## 8. Quests (QuestCatalog)

Points: 100 per accepted distinct item to the submitting player; +50 bonus to whoever completes the
quest. Duplicate items score 0 and Lumo says who found it first.

| id | emoji | title | prompt | goal |
|---|---|---|---|---|
| speed-safari | 🐆 | Speed Safari | Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph). | 3 |
| moon-collectors | 🪐 | Moon Collectors | Find two planets that have more than 20 moons. | 2 |
| deep-dive | 🐙 | Deep Dive | Find three creatures that live deeper than 1,000 meters in the ocean. | 3 |
| dino-time | 🦕 | Time Travelers | Find two dinosaurs that lived in the Cretaceous period. | 2 |
| tiny-titans | 🐜 | Tiny Titans | Find three animals that can lift or pull many times their own body weight. | 3 |

Each needs a rubric for the AI judge and offline answers (item + keywords + one-line fact) for the
on-device judge. Offline judge: pass when the page title/text names an accepted item (not already
found) and, where relevant, mentions a qualifying number.

## 9. Building and testing

```
tools/gw.sh :app:assembleDebug                 # APK -> app/build/outputs/apk/debug/app-debug.apk
tools/gw.sh :app:testDebugUnitTest             # JVM + Robolectric tests
tools/gw.sh :app:testDebugUnitTest --tests '*Home*'
tools/gw.sh :couchlink:test
tools/gw.sh :couchlink:runPhoneHarness         # phone page + simulated TV at http://localhost:8080
```
(PowerShell: `tools\gw.ps1 ...`.) There is **no device or emulator** on the build machine. Verify UI
with Robolectric screenshots: see `app/src/test/java/tv/wander/testing/Snap.kt`; PNGs land in
`app/build/snaps/` and you can open them to look at your work. Make the screens look great at
1920x1080 before you finish. WebView does not render under Robolectric; test browse logic in plain
JVM tests where possible.

Installing on a Fire TV: `adb connect <ip>:5555` then `adb install -r app-debug.apk`.
