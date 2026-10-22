# Wander TV demo video: "Lumo's Night In"

**Runtime 2:50** (the edit script refuses anything over 2:58; judges may stop at 3:00) · English · 1920x1080, 30 fps
**Narrator:** Lumo, in first person, the whole way through. 288 words of VO (about 2:04 of speech at 140 wpm), which leaves room for the app's own moments to land.

## Creative direction

The whole video is one family evening, told by the only member of the household who has spent their entire life staring at bright lights: Lumo the moth.

- **Voice.** Warm, curious, a little dry. A small creature with a big heart, not a cartoon and not a kid. Lumo is never preachy; the jokes are gentle and at Lumo's own expense ("I know circling when I see it").
- **Look.** Our own UI carries everything: the lamp-lit aubergine screens, amber focus, the four player colors. The title and end cards (`docs/title-card.png`, `docs/end-card.png`) bookend it with the moth circling the lamp, then wandering off.
- **People.** No faces needed. Hands, phones and the back of the couch, filmed over the shoulder. Two players: **Maya** (Berry, pink) and **Leo** (Sun, amber). A third phone (**Nana**, Mint) can join in scene 3 to show "up to 4".
- **Sound.** Lumo's voice, the app's own sounds and Lumo's Polly reading voice. **No music.** If a scene feels empty, add a generated room-tone or soft sound effect, never a song.
- **Where the footage comes from.** No Fire TV device was available in time, so every TV shot is recorded on the Android TV emulator (Android 11, the base of Fire OS 8) running the real Fire OS APK, and every TV shot carries the label "Android TV emulator · Fire OS APK". Phones are the real phone page in Chrome phone emulation, shown side by side with the TV. The app runs in on-device mode in this recording (the AWS account was still locked), so the voiceover never claims AWS answered on screen. If a Fire TV becomes available, re-record the same scenes on it with `video/capture.ps1` and drop the label.

## Scenes

Timecodes are for the final cut (`video/edl.json` produces exactly this timeline). VO ids match `video/vo/lines.json` and the files in `video/vo/<id>.mp3`.

### Title + Scene 1: "Hi, I'm Lumo" (0:00-0:10)

| | |
|---|---|
| **Timecode** | 0:00-0:03 title card, 0:03-0:10 Home |
| **VO** (`s01`, at 0:00.5) | "Hi, I'm Lumo. I'm a moth. I know a thing or two about staring at bright screens." |
| **On screen** | Title card: the moth silhouette circling the lamp, "Wander TV", tagline. Fades into the Home screen on the TV: Lumo hovering in the corner (curious mood), the four big tiles, the "join the couch" QR panel. Nobody has joined yet. |
| **Capture** | `.\video\capture.ps1 -Ip <tv-ip> -Scene s01-home -Seconds 20` with the remote untouched, so Lumo idles, blinks and flutters. |
| **Caption** | "Meet Lumo" · badge: "Android TV emulator · Fire OS APK" |

### Scene 2: The pain (0:10-0:24)

| | |
|---|---|
| **Timecode** | 0:10-0:24 |
| **VO** (`s02`, at 0:10.4) | "This is my family's TV. It's the biggest screen in the house, and the hardest one to type on. Cheetah, one letter... at... a... time. Still going." |
| **On screen** | The Search tile's text field and on-screen keyboard. Someone moves the D-pad letter by letter: C... H... E... E... Lumo watches, a bit concerned. Cut in a 3-4 s camera shot of a hand on the remote with the TV behind it. |
| **Capture** | TV: `.\video\capture.ps1 -Scene s02-remote -Seconds 30`, typing slowly on purpose. Camera: film over the shoulder, phone camera at 30 fps with exposure locked on the TV. Frame the remote so only the D-pad shows (the shortcut buttons carry other companies' logos). Save the camera clip as `video/raw/s02-camera.mp4` and split scene 2 into two EDL entries if you use both. |
| **Caption** | "Typing with a remote: one letter at a time" |

### Scene 3: The fix (0:24-0:50)

| | |
|---|---|
| **Timecode** | 0:24-0:50 |
| **VO** (`s03`, at 0:24.5) | "So we fixed it. Scan the code, and your phone becomes a keyboard and a touchpad. No app to install. Everyone gets a color, and the cursor wears the color of whoever moved it last. Maya types. Leo scrolls. Nobody fights over the remote." |
| **On screen** | A phone camera points at the QR on the TV, the controller page opens, Maya types her name and picks Berry. Lumo greets her by name; her chip appears on the TV. Leo joins as Sun (optional: Nana as Mint). Maya types `simple.wikipedia.org/wiki/Cheetah` and presses Go: the page opens on the TV. Leo drags on his touchpad: the cursor turns amber and the page scrolls. Maya taps a link: the cursor turns pink. |
| **Capture** | TV: `.\video\capture.ps1 -Scene s03-join -Seconds 90`. Camera: over-the-shoulder shot of the QR scan and the phone page in hand (`video/raw/s03-scan.mp4`, 5-6 s). Optional phone screen recording (`video/raw/s03-phone.mp4`); add `"crop"` in the EDL to cut away the phone's status bar and browser bar. |
| **Caption** | "Scan the QR: your phone is the keyboard", then "Up to 4 phones, no app to install" |

### Scene 4: Make it cards (0:50-1:16)

| | |
|---|---|
| **Timecode** | 0:50-1:16 |
| **VO** (`s04`, at 0:50.5) | "Long pages are hard to read from the couch. Press Make it cards, and I turn the page into big field-guide cards the whole room can read. Want one read out loud? Just press OK." |
| **On screen** | The long Cheetah article. Maya presses *Make it cards* on her phone (or Play/Pause on the remote). Lumo thinks for a moment, then 3-5 big field-guide cards appear. Leo flips right with his phone. Center on the remote: Lumo reads the card aloud, with the card's text on screen while the Polly voice plays at about 1:07. |
| **Capture** | `.\video\capture.ps1 -Scene s04-cards -Seconds 60`. screenrecord has no audio, so fetch the exact Polly audio of the card you read (see "Polly audio for scene 4" below) into `video/sfx/polly-card.mp3`; the EDL already places it at 1:07. |
| **Caption** | "Make it cards: a long page becomes big cards", then "Read aloud with Amazon Polly" |

### Scene 5: Family quest, Speed Safari (1:16-2:00)

| | |
|---|---|
| **Timecode** | 1:16-1:36 (part A), 1:36-2:00 (part B) |
| **VO** | `s05a` at 1:16.4: "Now the fun part: family quests. Tonight it's Speed Safari. Find three animals faster than a car on the highway." <br> `s05b` at 1:25.6: "Everyone races on their own phone. Found one? Submit it, and I check the page. I don't just say yes. I show you the sentence that proves it." <br> `s05c` at 1:42: "A house cat? Nice try, Leo." <br> `s05d` at 1:52: "Golden eagle! That's three. Maya wins the safari." |
| **On screen** | **A:** Quests tile, the picker, the Speed Safari 🐆 intro card. Both phones race. Maya opens *Peregrine falcon* and taps *Submit*. "Lumo is checking your page..." Accepted: "+100 Maya, Peregrine falcon" with the evidence quote from the page on screen. Keep one check in real time so viewers see it's live. **B:** Leo submits *Cat*: a kind "not quite" with the reason. Leo submits *Cheetah*: +100. Maya submits *Golden eagle*: +100 and +50 for finishing, the finale scoreboard (Maya 250, Leo 100), confetti on Maya's phone. |
| **Capture** | One long take: `.\video\capture.ps1 -Scene s05-quest -Seconds 180`, then cut it with two EDL entries (`s05a` and `s05b` both use `s05-quest.mp4` with different in/out points). Optional 2 s camera shot of Maya's phone celebrating. |
| **Caption** | "Family quest: Speed Safari", "Checked on AWS, with proof from the page", "Not quite? Lumo says why", "+100 per find, +50 to finish" |

### Scene 6: Drift rescue (2:00-2:27)

| | |
|---|---|
| **Timecode** | 2:00-2:10 (hopping, shown at 2x), 2:10-2:27 (the rescue) |
| **VO** | `s06a` at 2:00.4: "Some nights, the room just drifts. Page, page, page. I know circling when I see it." <br> `s06b` at 2:10.3: "So I get a little sleepy, and I offer something better: a quest, or a curious detour." <br> `s06c` at 2:17.5: "Say we're fine, and I believe you. I just wait longer next time. I learn your household. I never nag." |
| **On screen** | Fast, aimless hopping: link, link, link, a few seconds each (demo mode: 4+ pages in under a minute). Lumo's mood slides to sleepy. The rescue overlay: Lumo's line (e.g. "We've hopped 5 pages in a minute. I know that dizzy feeling."), the measured detail ("5 pages · 1 min · ~9 s each") and two choices: a quest or a curious detour. Leo presses Back: "we're fine". Lumo answers kindly and settles back into the corner. |
| **Capture** | `.\video\capture.ps1 -Scene s06-drift -Seconds 120` with demo mode on. The EDL speeds the hopping part up 2x and stamps a "2x speed" badge on it automatically, so nobody thinks it's faked. |
| **Caption** | "Lumo notices drift from timing alone, on the TV", then "A quest, or a curious detour", then "Each "we're fine" = more patience next time" |

### Scene 7: How it works (2:27-2:46.5)

| | |
|---|---|
| **Timecode** | 2:27-2:46.5 |
| **VO** (`s07`, at 2:27.5) | "How it works: phones talk to the TV over your own Wi-Fi, and page text leaves the TV only when you ask. On AWS, API Gateway, Lambda, Bedrock with Guardrails, DynamoDB and Polly do the thinking. No network? I still work, just like in this video." |
| **On screen** | `docs/architecture.png` with a slow push-in: phones on the left, the Fire TV app in the middle, the dashed privacy boundary, the AWS pipeline on the right. |
| **Capture** | Nothing to record: the EDL uses the PNG with `"kenburns": true`. |
| **Caption** | None (the diagram carries its own labels, including the privacy line). |

### End card (2:46.5-2:50)

| | |
|---|---|
| **Timecode** | 2:46.5-2:50 |
| **VO** (`s08`, at 2:46.6) | "Browse together. Wander well." |
| **On screen** | `docs/end-card.png`: the moth wandering off from the lamp, the tagline, the repo URL, "Built for Fire TV · Powered by AWS". Put the real repo URL into `docs/end-card.svg` and re-render first. |
| **Capture** | None. |
| **Caption** | None. |

## Making Lumo's voice (ElevenLabs)

1. Pick or design one voice for Lumo: warm, gentle, slightly husky, mid pitch, unhurried. If you want Lumo to sound like the same character inside the app, pick something close to the Polly voice the backend uses; the script calls the in-app voice Lumo's "reading voice", so a different timbre also works.
2. Generate **one file per entry** in `video/vo/lines.json`, using the text exactly as written, and save each as `video/vo/<id>.mp3` (`s01.mp3`, `s02.mp3`, `s05a.mp3`, ...). Separate files let the edit move each line to its moment.
3. Suggested starting settings: stability around 0.45, similarity around 0.75, style low (0.1-0.25), speed 1.0. Regenerate any take that rushes; the timeline has slack everywhere except the end card.
4. Optional, with a model that supports audio tags (e.g. Eleven v3): a soft `[yawns]` before "Page, page, page" in `s06a`, and `[softly]` on `s08`. Keep tags out of `lines.json`: the SRT captions are built from it.
5. Run `.\video\make_video.ps1 -PlanOnly`: it prints where every line starts and ends, and warns if a line overlaps the next one or runs past its scene.

## Pre-shoot checklist

- [ ] Fire TV on **Fire OS**, the final build installed: `adb install -r app-release.apk` (or `app-debug.apk`).
- [ ] Backend deployed and reachable: Home shows AI online. Quick check: `Invoke-RestMethod -Uri "$api/health" -Headers @{ "x-wander-key" = $key }`.
- [ ] **Demo mode on** (Menu on Home opens the Lumo panel, toggle Demo mode; or `adb shell am start -n tv.wander/.MainActivity --ez demo true`), so the rescue triggers after about a minute of hopping.
- [ ] Rehearsals change Lumo's patience (every "we're fine" is remembered). Before the real take, reset it: `adb shell pm clear tv.wander`, then re-apply settings with `adb shell am start -n tv.wander/.MainActivity --es api_url <url> --es api_key <key> --ez demo true` (skip the URL/key if the APK was built with them).
- [ ] 2-3 phones on the same Wi-Fi as the TV, brightness up, Do Not Disturb on, notifications off, battery above 50%. Names: Maya (Berry), Leo (Sun), optional Nana (Mint).
- [ ] Sites: **Simple English Wikipedia** articles: Cheetah (cards), Peregrine falcon, Cat, Cheetah, Golden eagle (quest), Saturn (backup for cards). Open each one first and confirm the page itself states a speed over 100 km/h (62 mph) for the three quest animals; if Simple English doesn't, use another article that does. Avoid pages dominated by logos, and avoid search-results pages (type the article address instead of a search, or blur the search engine's logo).
- [ ] Rehearse the quest once end to end and note how long each check takes, so the edit keeps one real-time check.
- [ ] Clean frame: TV brand logo out of shot, remote shortcut buttons out of shot or taped over, no phone brand logos visible (film the screen side), no posters or screens with other brands in the background.
- [ ] Audio plan: screenrecord records no audio. Lumo's VO comes from ElevenLabs, Lumo's reading voice from `/speak` (below), app sounds can be added from `app/src/main/res/raw/` as `sfx` entries. No music.
- [ ] `ffmpeg` on PATH (`winget install Gyan.FFmpeg`), `adb` on PATH or pass `-Adb`.

## Capture commands

Windows blocks scripts by default (execution policy Restricted). Run these from a PowerShell started with `powershell -NoProfile -ExecutionPolicy Bypass` (it applies to that window only), or prefix each one with `powershell -NoProfile -ExecutionPolicy Bypass -File`. The same goes for `make_video.ps1` below.

```powershell
.\video\capture.ps1 -Ip 192.168.1.50 -Scene s01-home   -Seconds 20
.\video\capture.ps1 -Scene s02-remote -Seconds 30
.\video\capture.ps1 -Scene s03-join   -Seconds 90
.\video\capture.ps1 -Scene s04-cards  -Seconds 60
.\video\capture.ps1 -Scene s05-quest  -Seconds 180
.\video\capture.ps1 -Scene s06-drift  -Seconds 120
.\video\capture.ps1 -Shot -Scene home      # README screenshots: home, cards, quest, rescue
```

Each recording goes to `video/raw/<scene>.mp4` (Enter or Q stops early; 180 s is screenrecord's maximum). Camera and phone clips go in `video/raw/` too, with any name; reference them from `video/edl.json`.

### Polly audio for scene 4

Use the exact text of the card Lumo reads on screen:

```powershell
$api = "https://<api-id>.execute-api.eu-central-1.amazonaws.com"; $key = "<key>"
$body = @{ text = "<card title>. <card line>" } | ConvertTo-Json
$r = Invoke-RestMethod -Method Post -Uri "$api/speak" -Headers @{ "x-wander-key" = $key } -ContentType "application/json" -Body $body
New-Item -ItemType Directory -Force video\sfx | Out-Null
[IO.File]::WriteAllBytes((Join-Path (Resolve-Path video\sfx) "polly-card.mp3"), [Convert]::FromBase64String($r.audio))
```

This is the same audio the TV plays; it's added in post only because screenrecord can't capture sound.

## Editing

1. Record the clips, generate the VO, then set each scene's `in` / `out` (seconds in the source clip) in `video/edl.json`.
2. `.\video\make_video.ps1 -PlanOnly` checks files and timing without encoding.
3. `.\video\make_video.ps1 -Draft` for a quick look; before all footage or VO exists, add `-AllowMissing` (placeholder slates) and `-TempVo` (Windows' built-in voice reads `lines.json`, for timing only).
4. `.\video\make_video.ps1` writes `video/out/wander-demo.mp4` and `video/out/wander-demo.srt`. It fails loudly if the cut runs over 2:58.

EDL fields per scene: `file` (in `video/raw/`) or `image`; `in`, `out`, `speed` (adds a "2x speed" badge automatically), `seconds` and `kenburns` for images; `captions` (`text`, `from`, `to`, relative to the scene); `badge`; `vo` (`id`, `at`); `sfx` (`file`, `at`, `volume`, `optional`); `blur` (boxes `x`, `y`, `w`, `h`, `from`, `to` in 1920x1080 output pixels, for a logo or a notification); `crop` (`x`, `y`, `w`, `h` in source pixels). Clip audio plays at `clip_volume` (25%) under the voice.

## Trademark and copyright safety

The rules forbid third-party trademarks and copyrighted music or footage without permission.

- [ ] No third-party logos on screen: website headers and wordmarks (scroll past them before recording, or `blur` them), search engine logos, phone brands, TV brands, the remote's shortcut buttons.
- [ ] No phone browser branding: film the phone in hand, or `crop` phone screen recordings to the page itself.
- [ ] No music at all; only Lumo's voice, Polly, the app's own sounds, or generated sound effects you have rights to.
- [ ] No video from websites playing on screen.
- [ ] Article text from Wikipedia is CC BY-SA 4.0: credit it in the YouTube description (template below). Keep the Wikipedia globe and wordmark out of frame.
- [ ] ElevenLabs: check your plan's terms for commercial use and attribution; credit the voice in the description, never show their logo.
- [ ] No faces needed; if anyone appears, get their OK first (especially kids).
- [ ] Our own UI, Lumo, the title/end cards and the architecture diagram are original artwork. The diagram uses text labels only, no AWS or Amazon logos.

## Upload checklist

- [ ] `video/out/wander-demo.mp4` is under 3:00 (the script enforces 2:58), plays start to finish, VO in sync.
- [ ] YouTube, visibility **Public** (not unlisted, not private). Language: English. Audience: "No, it's not made for kids" (it's a product demo for judges).
- [ ] Upload `video/out/wander-demo.srt` as English subtitles.
- [ ] Watch it once in a private window, logged out, to confirm it's public and plays.
- [ ] Paste the link into Devpost, `README.md` (Demo video) and `docs/DEVPOST.md`.

**Title:** Wander TV: browse the web together on Fire TV (hackathon demo)

**Description:**

```
Wander TV is a couch-first web browser for Fire TV. Every phone in the room becomes a keyboard and touchpad (scan a QR code, no app to install), and Lumo, a small moth, turns long pages into big cards, runs family quests judged by AI with the proof shown on screen, and notices when the room starts drifting.

Recorded on the Android TV emulator (Android 11, the base of Fire OS 8) running the Wander Fire OS APK: no Fire TV device was available in time. The AWS backend is built and tested but its deploy was blocked by AWS account verification, so this recording runs in on-device mode.
Built for the Amazon Fire TV hackathon: Fire TV track + AWS Builder challenge.

0:00 Meet Lumo
0:10 Typing with a remote
0:24 Phones as controllers
0:50 Make it cards
1:16 Family quest: Speed Safari
2:00 Drift rescue
2:27 How it works

On AWS: API Gateway (HTTP API), AWS Lambda (Python), Amazon Bedrock (Converse + tool use) with Guardrails, Amazon DynamoDB, Amazon Polly, AWS CloudFormation / SAM, Amazon CloudWatch.
Privacy: page text leaves the TV only when someone presses "Make it cards" or submits a quest.

Code (MIT): https://github.com/g7dream/wander-tv
Devpost: [FILL IN: Devpost URL]

Narration: AI-generated voice (ElevenLabs). Article text shown: Simple English Wikipedia, CC BY-SA 4.0. Fonts: Young Serif and Atkinson Hyperlegible (SIL OFL). No music.
```

**Tags:** fire tv, fire os, aws, amazon bedrock, hackathon, web browser, family, kotlin
