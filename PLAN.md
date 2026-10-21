# Wander TV — Fire TV Hackathon Build Plan

Oct 18, 2026 · @Lucian Catalin Cojocaru

## Goal and pitch

Ship a working Fire TV browser for the living room by Oct 23, 2026 at 22:00 Bucharest time (12:00 PDT), aiming to submit by 18:00 for buffer.

Wander TV (working name) is a browser for families on the couch. Your phone becomes the keyboard and touchpad. An on-screen companion turns long pages into big TV cards and nudges you out of aimless scrolling. Family quests turn browsing into a shared game.

- **Primary track:** Fire TV. It hits three of their priority categories: family entertainment, AI-enhanced viewing and multi-modal UX.
- **Mini challenge:** AWS Builder. The companion's AI runs on Amazon Bedrock through AWS Lambda.
- **Positioning:** Silk is a browser that happens to run on a TV. Wander is browsing designed for a room full of people.

## Scope

Four features make the demo. Everything else waits until after the hackathon.

1. **TV shell.** A home screen with big tiles (Search, Quests, Favorites, Companion), a browsing view in a WebView, D-pad navigation with clear focus highlights, and a Back button that behaves sensibly.
2. **Phone controller.** A QR code on the TV opens a web page on the phone, no install. It gives a text box for search and URLs, a touchpad for scrolling and pointing, and Back and Home buttons. Up to four phones join, each with a name and color.
3. **Companion.** A character in the corner with four moods: curious, happy, sleepy, concerned. A "Make it a card" action turns the current page into 3–5 large cards. Simple local rules spot aimless browsing and trigger a rescue suggestion.
4. **One family quest.** Example: "Find three animals faster than a car." A player submits the page on screen from their phone, the AI checks it, and a scoreboard tracks points.

Not doing: accounts, sync, bookmark import, multiple tabs, passwords or autofill, ad blocking, DRM video sites, store publishing, and languages other than English.

## Tech stack and architecture

Build a native Fire OS app in Kotlin and test it on a physical Fire TV Stick. The Vega simulator supports WebView only on Mac M-series machines, so it can't run a browser on Windows ([Amazon Vega docs](https://developer.amazon.com/docs/vega/0.23/run-apps-overview)).

&#91;embedded content: Wander TV architecture · phones, TV app, AWS\]

Phones and the remote drive the browser locally. Page text reaches AWS only for cards and quest checks.

| Part | Choice | Why |
| --- | --- | --- |
| TV app | Kotlin, Android Studio, Jetpack Compose with androidx.tv components, Android WebView | Plain Android, runs on any Fire OS stick |
| Test device | Fire TV Stick 4K or 4K Max (Fire OS) | Avoid the 4K Select and the new Stick HD: they run Vega OS, with no ADB and no APKs ([How-To Geek](https://tech.yahoo.com/streaming/articles/fire-tv-stick-developer-menu-184517731.html)) |
| Phone controller | One HTML/JS page served by the TV app, talking over WebSocket on the home Wi-Fi | No install, low latency, no cloud relay |
| Embedded server | Ktor (CIO engine) inside the TV app, port 8080 | Serves the phone page and the WebSocket |
| AI backend | API Gateway HTTP API, AWS Lambda (Python), Amazon Bedrock Converse API | Qualifies for AWS Builder and keeps AWS keys off the TV |
| Model | A fast Bedrock model (Claude Haiku-class or Amazon Nova Lite) | Target under 3 seconds per answer |
| Page text | Readability.js injected into the WebView, text cut to about 6,000 characters | Clean input for cards and quest checks |

**Remote and cursor.** In the browsing view, the D-pad moves an on-screen cursor that speeds up when held. Select taps at the cursor by sending touch events to the WebView. Back goes back in history, and Menu opens the companion's action bar. The phone touchpad drives the same cursor.

**Lambda endpoints.**

- `POST /cards`: page title and text in, 3–5 cards out as JSON (title, one line, icon keyword).
- `POST /companion`: session signals in, a mood, one short line and one rescue suggestion out.
- `POST /quest/check`: quest and page text in, pass or fail plus a one-line reason out.

**Privacy rule.** Page text leaves the TV only when someone presses "Make it a card" or submits a quest answer. Drift detection runs on the TV using timing signals only.

**Drift rules** (starting values, tune after testing):

- 6 or more page loads in 5 minutes, each under 20 seconds
- 3 or more minutes of scrolling without a click
- The same site reopened 5 or more times in 15 minutes
- After 23:00, with a session longer than 30 minutes

**Fire TV manifest basics.** Add a LEANBACK\_LAUNCHER intent filter and a 320×180 TV banner so the app shows in the app list, and mark the touchscreen as not required.

## Day-by-day plan

Five build days, feature freeze Thursday night, submit Friday by 18:00. Each day ends with a test on the real stick.

### Sun Oct 18 — Setup (1–2 hours tonight)

- [ ] Register for the hackathon on Devpost
- [ ] Get a Fire OS stick (Fire TV Stick 4K or 4K Max) if you don't have one
- [ ] On the stick: Settings > My Fire TV > About, press Select 7 times on the device name, then turn on ADB Debugging ([Amazon ADB guide](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html))
- [ ] From the PC: `adb connect <stick-ip>:5555`, then accept the prompt on the TV
- [ ] Create the Android Studio project and a private GitHub repo
- [ ] AWS: confirm you can call a Bedrock model in Frankfurt (or wherever your model is available), and create the Lambda and API Gateway skeleton
- [ ] Brief your artists: the companion in 4 moods (transparent PNGs) plus the app banner, needed by Wednesday morning
- [ ] Start a friction log file and add to it every time a tool fights you

### Mon Oct 19 — TV shell

- [ ] Home screen with 4 big tiles and clear focus highlights
- [ ] Browsing view: WebView, D-pad cursor, Select to tap, Back, Menu
- [ ] Search tile opens search results in the WebView
- [ ] A few favorites that render well on a TV (Wikipedia, NASA, a kids' science site)

Done when: you open a site from Home with the remote, click a link, and get back to Home.

### Tue Oct 20 — Phone controller

- [ ] Ktor server in the TV app serving the phone page and the WebSocket
- [ ] QR code on Home with the TV's local address
- [ ] Phone page: text box, touchpad, Back, Home, player name and color
- [ ] Up to 4 phones; one shared cursor that takes the color of whoever moved it last

Done when: two phones control the TV, and a search typed on a phone opens on the TV.

### Wed Oct 21 — AI and companion

- [ ] Lambda endpoints `/cards`, `/companion` and `/quest/check` answer correctly from curl
- [ ] "Make it a card" shows 3–5 big cards over the page; left and right flip them
- [ ] Companion in the corner with its 4 moods and a speech bubble
- [ ] Drift rules running on the TV; a rescue offer appears with 2 choices

Done when: a long Wikipedia article becomes cards in under 5 seconds, and fast fake browsing triggers a rescue.

### Thu Oct 22 — Quest, polish, docs (feature freeze tonight)

- [ ] One quest end to end: start it, submit a page from a phone, AI check, scoreboard
- [ ] Polish: focus animations, sounds, loading states, friendly error messages
- [ ] README with setup steps and the AWS integration written out, plus an architecture image
- [ ] Run the full demo script three times on the stick

Done when: the demo script runs start to finish with no crash.

### Fri Oct 23 — Video and submission (submit by 18:00)

- [ ] Record the TV with `adb shell screenrecord` (3-minute clips), and film the phones in people's hands
- [ ] Edit to under 3 minutes and upload to YouTube as public, in English
- [ ] Write the description, product feedback and friction log entries
- [ ] Add the reviewers to the private repo, or make it public with an open-source license
- [ ] Submit on Devpost: Fire TV track plus the AWS Builder mini challenge

## Demo video script

Keep it under 3 minutes with the best material first, since judges aren't required to watch past 3:00. Every TV shot must show the app running on the real Fire TV.

1. **0:00–0:15, the hook.** Someone on the couch struggles to type a URL with the remote. Cut to a phone scanning the QR code.
2. **0:15–0:50, phone as controller.** Two phones join with names and colors. One types a search, the other scrolls with the touchpad.
3. **0:50–1:25, make it a card.** A long article turns into 4 big cards the whole room can read.
4. **1:25–2:05, family quest.** "Find three animals faster than a car." Players submit pages, the companion checks them, the scoreboard updates.
5. **2:05–2:30, rescue moment.** Fast, aimless browsing makes the companion sleepy, and it offers a quest or a curious detour.
6. **2:30–2:55, how it works.** The architecture image: Fire OS app, local WebSocket, Lambda and Bedrock, plus one line on privacy.

## Submission checklist

Everything the [hackathon page](https://amazonappdev2026.devpost.com/) asks for, in the order you'll do it on Friday.

- [ ] **Description:** what Wander does and how it works
- [ ] **Code repo:** all source, assets and run instructions. Simplest is public with an MIT license file. If it stays private, add the reviewers listed on the hackathon page on submission day, because GitHub invites expire after 7 days
- [ ] **Video:** under 3 minutes, public on YouTube or Vimeo, in English, app running on the real Fire TV, no copyrighted music
- [ ] **Product feedback** for every tool used, including which AWS services you used and how
- [ ] **Tracks:** Fire TV, plus the AWS Builder mini challenge with its integrations documented in the README
- [ ] **Friction log** (optional, up to 10% judging bonus): task, steps, expected vs. actual, severity, workaround, suggestion
- [ ] **Feature requests** (optional): what, why, and how urgent

## Risks and fallbacks

The biggest risk is the device, so sort it out tonight.

| Risk | Fallback |
| --- | --- |
| No Fire OS stick by Monday | Develop on the Android TV emulator meanwhile, but record the final video on a real stick |
| The stick you get runs Vega OS | Exchange it for a 4K or 4K Max; the Vega route can't run a WebView browser on Windows |
| Bedrock answers take over 5 seconds | A "thinking" animation on the companion, cards cached per URL, shorter text sent |
| Sites look bad on TV or show cookie walls | Use the curated favorites in the demo; add a mobile user-agent toggle |
| Phones can't reach the TV (router isolation) | Show IP and port on screen; for the demo, put everything on one phone hotspot |

If time runs short, cut in this order: polish, then the scoreboard, then multi-phone support. Never cut the TV shell, phone typing, or the cards.

## Building it with Claude

The fastest path is Claude Code on your PC, working inside the repo with the stick connected over ADB, so it can build, install and read crash logs itself.

1. Export this doc as Markdown and save it in the repo as `PLAN.md`.
2. Start each session with one day: "Read PLAN.md and build Monday's tasks. Stop when the done-when check passes."
3. Keep the `adb connect` session alive so Claude can run `gradlew installDebug` and watch `adb logcat`.
4. Let it deploy the Lambda functions with the AWS CLI or SAM, using credentials you configured yourself.
5. Test on the TV at the end of each day, then commit before starting the next.
6. Feed back what you notice from the couch, like "focus ring too thin" or "cursor too slow." That's where the TV experience gets good.
