# Kiro task: Family SafeSearch everywhere

Paste the prompt below into Kiro (Crew or a spec session). Kiro reads the project context from
`.kiro/steering/` (product, tech, structure).

---

**Prompt**

> Create a spec and implement it: **Family SafeSearch everywhere** for Wander TV.
>
> Today, searches typed on a phone or in the Home search field go through `SearchUrls.resolve` and use
> DuckDuckGo with strict SafeSearch (`kp=1`). But a family can still reach unfiltered results: opening
> `duckduckgo.com` directly gives "Safe search: moderate", a search link or form on a page goes wherever it
> points, and Google or Bing searches are not covered at all.
>
> Requirements:
> 1. Add a pure function in `app/src/main/java/tv/wander/browse/SearchUrls.kt` that takes a URL and returns the
>    same URL with strict SafeSearch enforced, or null when nothing needs to change:
>    - DuckDuckGo (`duckduckgo.com`, `html.duckduckgo.com`, `lite.duckduckgo.com`): `kp=1`, replacing any other
>      `kp` value (for example `kp=-1` or `kp=-2`).
>    - Google web search (`www.google.<tld>/search`, `www.google.<tld>/` with a `q`): `safe=active`, replacing
>      `safe=off` or `safe=images`.
>    - Bing (`www.bing.com/search`): `adlt=strict`, replacing other values.
>    - Keep every other parameter, the path and the fragment; leave non-search URLs and malformed input alone.
> 2. Apply it in `BrowseLayer` to every main-frame navigation (links, form submits, redirects, and URLs passed
>    to `open`) so the rewritten URL is what loads. Only rewrite when the value actually differs, so there is no
>    redirect loop.
> 3. Add JUnit tests to `app/src/test/java/tv/wander/browse/SearchUrlsTest.kt` for each engine, for replacing
>    existing values, for already-strict URLs (returns null), for non-search URLs and for malformed input.
> 4. Add one line to the feature list in `README.md` about SafeSearch being enforced on DuckDuckGo, Google and
>    Bing.
>
> Constraints: follow `.kiro/steering/tech.md` (minSdk 25, no new dependencies, match the surrounding code
> style). Done means `.\tools\gw.ps1 :app:testDebugUnitTest` and `.\tools\gw.ps1 :app:assembleDebug` both pass.

---

Optional second task (if there is time): **"Lumo panel minutes include quest time"**. The Lumo panel's
minutes come from `DriftDetector.stats()`, which skips time spent on an active quest, so after a long quest the
panel undercounts the evening. Make the panel show the whole session length while drift detection keeps
ignoring quest pages, with a unit test.
