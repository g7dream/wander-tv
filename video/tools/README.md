# Capture tools: the demo on the Android TV emulator

These scripts record the scenes of `video/SCRIPT.md` on the **Android TV emulator** running the Wander
Fire OS APK, as real interactions with the real app, plus **Maya's phone**: the real phone page served by
the TV app, opened in headless Chrome with phone emulation and driven with real touch events. Every TV shot
is labeled "Android TV emulator · Fire OS APK" downstream; nothing here pretends to be a Fire TV.

Requirements: Node 24 (global `WebSocket` and `fetch`), `ffmpeg`/`ffprobe` on PATH, the Android SDK's
`adb`, Google Chrome, and an emulator (console port 5554) with `tv.wander` installed. Outputs go to
`video/raw/` (git-ignored).

## Quick start

```sh
node video/tools/record.mjs s01-home s02-remote              # TV only
node video/tools/record.mjs s03-join s04-cards s05-quest     # TV + Maya's phone (Leo is a script)
node video/tools/record.mjs s06-drift                        # TV only, demo mode
node video/tools/composite.mjs s03-join                      # side-by-side clip, also s04-cards, s05-quest
node video/tools/sheet.mjs video/raw/s03-join-comp.mp4       # contact sheet, one frame every 2 s
node video/tools/logo-blur.mjs s05-quest                     # EDL blur boxes for the site's logos
node video/tools/emulator-edl.mjs                            # video/edl.json for the emulator cut
```

Set `CAPTURE_TMP` to a scratch folder for the Chrome profiles (default: the OS temp folder).

## The tools

| File | What it does |
|---|---|
| `wanderlib.mjs` | Shared pieces: `adb` with retries, clean offline restart of Wander, the emulator-console port redirect for the phone server, `TvRecorder` (detached `screenrecord` with device-clock timestamps), `TvKeys` (D-pad/OK/Back through a `monkey --port` server), `Leo` (a WebSocket phone, SPEC.md section 5), `Phone` (CDP: phone emulation, touch taps/drags, typing, `Page.startScreencast` recording), `framesToVideo` (screencast frames to constant 30 fps). |
| `record.mjs` | The scenes. Each take checks Wander is in front, restarts it clean in on-device mode (`--es api_url ""`), records, saves `video/raw/<scene>.mp4`, `<scene>-phone.mp4` and `<scene>.events.json` (host time of both recordings and of every action). Stalled takes are retried from a clean start. |
| `composite.mjs` | 1920x1080 / 30 fps side by side: lamp-lit background, TV 1440x810 with a hairline border, phone 372x826 with rounded corners, labels in Atkinson Hyperlegible Bold. Writes `<scene>-comp.mp4` and `<scene>-comp.times.txt` (every logged action in clip seconds). `--tv-shift` / `--phone-shift` nudge the sync. |
| `sheet.mjs` | Contact sheet with time stamps, for checking a take by eye. |
| `logo-blur.mjs` | Finds when the Wikipedia wordmark (page header) and the site favicon (Wander's title strip) are on screen and writes `<scene>.blur.json` with EDL blur boxes for the plain TV clip and for the composite. |
| `emulator-edl.mjs` | Writes `video/edl.json` for the emulator cut: the cut points of every scene (s03-s06 are each cut into several entries), VO placements set from the capture timestamps, captions placed off the app's own UI, and the `<scene>.blur.json` windows moved into each entry's time. Re-run it after re-recording a scene and updating its cut points. |

## How the pieces line up

* **TV start time.** `screenrecord --verbose` runs detached on the emulator; its output lines are stamped with
  the device clock, and the device-host clock offset is measured over adb (best of 5 round trips). The first
  frame comes ~70 ms before the "Content area" line (calibrated with D-pad focus changes).
* **Phone time.** Every screencast frame carries its wall-clock timestamp; frames are held until the next
  one, so the 30 fps clip is in real time from the first frame.
* **Check.** Phone tap to TV change in the composites: Join -> seat lights 0.3 s later, Make it cards ->
  TV reacts in ~0.9 s (the emulator is slow), the order always right. Sync is within about 0.15 s.
* **Remote keys.** `adb shell input keyevent` costs ~0.9 s per key on this emulator; the monkey server takes
  a few ms, so the D-pad can be paced.
* **Touches.** Each phone touch also shows a soft white dot (like Android's "Show taps"), injected as a
  listener only; it never changes what the page does.
* **Where the TV cursor lands.** A new page puts it at (960, 497) TV px; below 120 phone px/s the touchpad gain
  is 1.3 dp per phone px, so slow strokes are predictable (`mayaPadRel`).

## Known issues found while recording

* **Phone scrolling does nothing on Wikipedia pages (app bug).** `W.scrollAt` in
  `app/src/main/assets/js/wander-bridge.js` picks `<body>` as the scroller because Wikipedia sets
  `body { overflow-y: scroll }` while `<html>` stays `visible`, so the overflow belongs to the viewport and
  `body.scrollTop` never moves. Phone scroll, the scroll wheel, remote edge nudges and page up/down all go
  through it. Fix: in `scrollerAt`, stop at `document.body` (treat it like the root) unless the computed
  `overflow-y` of `<html>` is not `visible`. Until then the "Leo scrolls" beat shows only Leo's amber cursor,
  and every Wikipedia page keeps its header (blur it, see `logo-blur.mjs`).
* **Starting a quest from Home, then closing the intro with OK or Back** opens the quest's starter web search
  (a search engine's results page, with its logo). In s05 Maya types her first answer while the intro is up and
  her Go closes it, so no search page is shown.
* **Read aloud** speaks with the emulator's own text-to-speech (Google TTS); `screenrecord` records no audio.
  The first use after a cold emulator downloads voice data and shows "Lumo's voice isn't working on this TV
  right now" until it finishes.
* **adb on this machine** crashes every few minutes (a fail-fast in `adb.exe`, typically when another adb
  server, e.g. a Unity editor's, connects to the emulator). That is why the phone port uses the emulator
  console's `redir add tcp:8098:8080` instead of `adb forward`, `screenrecord` and `monkey` run detached, and
  every adb call retries.
* **CPU starvation.** With several Unity editors busy, the phone page sometimes loads without its script or
  stylesheet, or the renderer stops producing frames. `record.mjs` checks the page booted (stylesheet,
  script, fonts) and that the phone renders at least ~16 fps before a take, and retries a stalled take.
