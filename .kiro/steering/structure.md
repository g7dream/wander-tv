---
inclusion: always
---

# Structure

```
app/src/main/java/tv/wander/
  MainActivity.kt      the hub: layer stack, mode machine, key routing, phone commands, flows
  Config.kt            runtime settings (backend URL/key, demo mode, nudges)
  model/Models.kt      shared value types (Player, PageText, Card, Quest, Verdict, TvState...)
  ui/                  Theme.kt (Palette, TypeScale, Dimens, Fonts, Ui helpers, LampBackdrop), Async, Sfx
  home/                HomeView, Favorites, LumoActionBar (the Menu-key bar), ToastChip
  browse/              BrowseLayer (WebView host), SearchUrls, CursorView/CursorMotion, TitleStrip, PageTexts
  companion/           LumoView (the moth, drawn on Canvas), CompanionLayer, DriftDetector, Lines, CompanionPanel
  rescue/              RescueOverlay
  speech/              Narrator (Polly via backend, TextToSpeech fallback)
  cards/               CardsOverlay, FieldCardView
  quest/               QuestCatalog, QuestEngine, QuestOverlay and its views
  ai/                  AiClient (backend client), OfflineAi (on-device fallbacks)
  net/                 PhoneHub (phone protocol), LanAddress, Qr
app/src/main/assets/   phone/ (phone controller page), js/ (wander-bridge.js, Readability.js), fonts/
app/src/test/java/tv/wander/   tests mirror the main packages; testing/Snap.kt renders views to PNG
couchlink/             the LAN HTTP + WebSocket server library, tests and a desktop phone harness
backend/               SAM template, Lambda source (src/), deploy and smoke-test scripts, tests
docs/, video/          README material, Devpost text, architecture diagram, demo video kit
SPEC.md                the contract: protocol, keys, design system, quests, backend API
```

Key flows: phone commands arrive in `PhoneHub` and are routed by `MainActivity.onCommand`; the browser lives in
`BrowseLayer` (navigation decisions in its `Client.shouldOverrideUrlLoading` -> `routeUrl`); what the user types
becomes a URL in `SearchUrls.resolve`.

Shared files (`model/Models.kt`, `ui/Theme.kt`, `Config.kt`, build files, manifest) affect every area: change
them only when a task truly needs it, and keep the change minimal.
