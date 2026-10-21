# Scripts Wander injects into web pages

| File | What it does | License |
|---|---|---|
| `wander-bridge.js` | Wander's own page helper. Reports text-field focus to the TV (so a phone can offer its keyboard) and gives the TV small helpers: scroll whatever is under the cursor, page up/down, type into the focused field, tell the cursor whether it is over a link or a text box, and pull readable text out of the page. Plain ES5 so older Fire OS WebViews can run it. | Same as Wander TV |
| `Readability.js` | [Mozilla Readability](https://github.com/mozilla/readability), the library behind Firefox Reader View. Wander uses it to find the article on a page when someone presses "Make it cards" or submits a quest answer. | Apache License 2.0 |

## Mozilla Readability

- Source: `https://raw.githubusercontent.com/mozilla/readability/main/Readability.js`
- Upstream commit at download time: `ab4027a8b37669745016869a37a504727992b2ba` (main, 2026-07-09)
- Copyright (c) 2010 Arc90 Inc and Mozilla contributors. Licensed under the Apache License,
  Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
- The file is included **unmodified**, with its license header intact.

How Wander runs it: the TV wraps the file in a function before injecting it
(`(function(){ var module; /* Readability.js */ window.__wanderReadability = Readability; })()`), so it
never adds a page global or touches a page's own `module`. It parses a clone of the document
(`new Readability(document.cloneNode(true)).parse()`), so the page itself is never changed. If an
older WebView can't parse the library, `wander-bridge.js` falls back to the text of the page's main
landmark, minus menus, footers and reference lists.

## Privacy

Extraction only runs when someone asks for cards or submits a quest answer. Nothing in these scripts
sends anything anywhere; the TV decides what, if anything, leaves the living room.
