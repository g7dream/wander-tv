---
inclusion: always
---

# Product: Wander TV

Wander TV is a couch-first web browser for Fire TV (Fire OS). The whole family browses together on the big
screen:

- **Phones are the keyboard and touchpad.** Scanning the QR code on the TV opens a web page on the phone (no
  install). Up to four phones join, each with a name and a color; the shared cursor takes the color of whoever
  moved it last.
- **Lumo**, a small fuzzy moth, is the companion. Moths circle bright lights without getting anywhere, so Lumo
  notices when the family is circling the screen. Lumo is warm, short, a little funny, and never preachy: it
  offers, it never shames.
- **Make it cards** turns a long page into 3-5 big field-guide cards the room can read, and can read one aloud.
- **Family quests** ("Speed Safari: find three animals faster than a car") are judged by AI, with the sentence
  that proves the answer shown on screen.
- **Drift rescue**: timing signals on the TV spot aimless hopping or endless scrolling, and Lumo offers a quest
  or a curious detour. Each "we're fine" makes it more patient for this household.

## Audience and context

Families on the couch, kids included. Everything must read from 3 m away on a 55" TV, work with only the Fire TV
remote, and stay kid-safe by default (strict SafeSearch, Bedrock Guardrails on AI output).

## Principles that every change must keep

- **Privacy rule:** page text leaves the TV only when someone presses "Make it cards" or submits a quest answer.
  Drift detection runs on the TV from timing alone. "Lumo's pick" sends recent page titles only when asked.
- **Offline first:** every feature has an on-device fallback when the AWS backend is unset or unreachable.
- **Lamp-lit living room at night:** deep aubergine ink, warm lamp amber for focus, cream text, four player
  colors (Sun, Berry, Mint, Sky). No generic template look.

This is a hackathon entry (Amazon Developer hackathon, Fire TV track + AWS Builder mini challenge). The full
contract is `SPEC.md`; the original plan is `PLAN.md`.
