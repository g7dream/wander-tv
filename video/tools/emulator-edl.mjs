// emulator-edl.mjs: writes video/edl.json, the edit list of the emulator cut (see video/SCRIPT.md), from the
// cut points below and the logo windows in video/raw/<scene>.blur.json (written by logo-blur.mjs).
//
//   node video/tools/emulator-edl.mjs [out.json]      -> video/edl.json (default)
//
// Why a script: every Wikipedia wordmark / favicon blur window has to be moved into each EDL entry's own time
// (minus 'in', divided by 'speed', padded 0.1 s), and s04-s06 are each cut into several entries. After a scene is
// re-recorded, run logo-blur.mjs and composite.mjs for it, update its in/out points and VO 'at' times here (the
// comments give the capture timestamps they were set from: <scene>-comp.times.txt, plus 0.25/0.43/0.19 s for the
// plain s03/s04/s05 TV clips), run this, then `make_video.ps1 -PlanOnly`. Hand edits to edl.json are overwritten.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, RAW, readJson } from './wanderlib.mjs';
const BADGE = 'Android TV emulator \u00b7 Fire OS APK';
const r2 = (v) => Math.round(v * 100) / 100;
const blurFile = (scene) => JSON.parse(readFileSync(join(RAW, `${scene}.blur.json`), 'utf8'));
// map source-time boxes into a segment [in,out] at speed, padded, clipped to the segment
function blurFor(boxes, inT, outT, speed = 1, pad = 0.1, extra = []) {
  const dur = (outT - inT) / speed;
  const res = [];
  for (const b of [...boxes, ...extra]) {
    const f = Math.max(b.from - pad, inT), t = Math.min(b.to + pad, outT);
    if (t <= f) continue;
    res.push({ x: b.x, y: b.y, w: b.w, h: b.h, from: r2(Math.max(0, (f - inT) / speed)), to: r2(Math.min(dur, (t - inT) / speed)) });
  }
  return res;
}
// s03: once Leo's scroll moves the page (comp 22.2) the wordmark region holds article text, so its window ends there
const S03_SCROLLED = 22.2;
const s03 = blurFile('s03-join').comp.map((b) => (b.what === 'Wikipedia wordmark' ? { ...b, to: Math.min(b.to, S03_SCROLLED) } : b));
// s06: the rescue overlay fades in at clip 27.95 (over 0.15 s). The full-size wordmark and favicon windows end there
// (padded), or they blur the top of the overlay's "A NUDGE FROM LUMO" label; the faint boxes below take over.
const S06_RESCUE = 27.95;
const s04 = blurFile('s04-cards').comp, s05 = blurFile('s05-quest').comp;
const s06 = blurFile('s06-drift').tv.map((b) => (b.from < S06_RESCUE && b.to > S06_RESCUE - 0.1 ? { ...b, to: S06_RESCUE - 0.1 } : b));
// behind Wander's rescue overlay (s06, clip time) the page still shows through the dark scrim: the faint wordmark
// (only the part above the "A nudge from Lumo" label) and, while the title strip is up, the faint "W" favicon
// (a box between that label and the headline below it)
const s06faint = [{ x: 100, y: 22, w: 254, h: 40, from: 28.0, to: 34.3 }, { x: 124, y: 92, w: 62, h: 48, from: 28.0, to: 34.3 }];
// faint wordmark behind the cards and the loading view (s04 composite time), clear of the "From: Cheetah" label
const s04faint = [{ x: 107, y: 132, w: 191, h: 40, from: 3.0, to: 27.4 }];

const COMP_CAP = { cx: 752, y: 930 };       // under the TV of the side-by-side clips
const TVB = { badge: BADGE, badge_y: 8 };    // plain TV clips: badge on the top line, above the app's own UI

// The app's own UI sounds (app/src/main/res/raw, played by ui/Sfx.kt), laid in at the moments the app played them
// in the take: screenrecord records no audio. Volumes are the app's SoundPool volumes x 0.35, so even alone they sit
// about 6 dB under Lumo's voice after the VO bus is normalized.
const SFX_VOL = { join: 0.21, chime: 0.19, whoosh: 0.16, success: 0.23, fail: 0.18, select: 0.16, back: 0.14 };
const sfx = (name, at) => ({ file: `app/src/main/res/raw/sfx_${name}.wav`, at, volume: SFX_VOL[name] });

// The TV's own voice reading card 3 aloud (on-device text-to-speech), recorded with sound by read-aloud.mjs on the
// same emulator, same page and same card, and laid in at the moment Maya's Read aloud tap triggered it in the s04
// take (tap + the measured latency). Volume: about 3 dB under Lumo's narration.
const READ = readJson(join(RAW, 's04-read.json'));
if (!READ) throw new Error('video/raw/s04-read.json missing: run node video/tools/read-aloud.mjs first');
const readVoice = (tapAt) => ({ file: 'video/raw/s04-read.wav', in: r2(READ.voiceIn - 0.08), out: r2(READ.voiceOut + 0.25),
  at: r2(tapAt + READ.latency - 0.08), volume: 2.2 });

const edl = {
  _comment: "Edit list for video/make_video.ps1, emulator cut. Times are seconds. Clip 'file' names resolve in video/raw/, VO ids resolve to video/vo/<id>.mp3, other relative paths resolve from the repo root. Scene 'in'/'out' are source times; 'at'/'from'/'to' are relative to the start of that scene in the final cut (after 'speed'); a negative VO 'at' starts the line in the previous segment. A VO entry with 'in'/'out' plays only that part of the take, with 'text' as its subtitle. Scenes split into several entries (s04, s05, s06) are one take cut down; *-comp clips are the TV and Maya's phone side by side (video/tools/composite.mjs). 'sfx' entries are the app's own UI sounds (app/src/main/res/raw) at the moments the app played them in the take, since screenrecord records no audio; likewise the TV's read-aloud voice in s04b is the app's on-device text-to-speech reading the same card on the same emulator, recorded with sound by video/tools/read-aloud.mjs and laid in at Maya's Read aloud tap (an sfx 'in'/'out' plays only that part of the file). Blur boxes hide the Wikipedia wordmark and favicon (windows from video/raw/<scene>.blur.json). See video/SCRIPT.md.",
  fps: 30, clip_volume: 0.25, max_seconds: 178,
  font: 'app/src/main/assets/fonts/AtkinsonHyperlegible-Bold.ttf',
  lines: 'video/vo/lines.json',
  title_card: { image: 'docs/title-card.png', seconds: 3, vo: [{ id: 's01', at: 0.5 }] },
  scenes: [],
  end_card: { image: 'docs/end-card.png', seconds: 3.5, vo: [{ id: 's08', at: 0.3 }] },
};
const S = edl.scenes;

// s01 Home: "I'm a moth." ends as the Home screen (and Lumo) appears; the shot ends 1.3 s after the line so the
// problem (s02) starts before 0:09
S.push({ id: 's01', file: 's01-home.mp4', in: 0, out: 5.6, ...TVB,
  captions: [{ text: 'Meet Lumo', from: 0.6, to: 5.3, cx: 1640, y: 618 }] });
// s02 remote typing: OK on the Search tile opens the field at 1.6 (tile click: select), letters c 7.3 h 10.1 e 13.4 e 14.3
// (the letters are Gboard's on-screen keyboard, which plays no Wander sound)
S.push({ id: 's02', file: 's02-remote.mp4', in: 1.0, out: 16.2, ...TVB,
  vo: [{ id: 's02', at: 0.3 }],
  sfx: [sfx('select', 0.6)],
  captions: [{ text: 'Typing with a remote: one letter at a time', from: 1.0, to: 14.8, size: 40, x: 104, y: 244 }] });

// s03 join (composite), one continuous take (re-recorded after the scroll fix, 1137733): Maya taps the name field 3.03,
// joins 6.30 (seat lights 6.35: join), Leo joins 10.67 (join), Maya taps the search field 13.67 and types until Go 18.43,
// page open 19.0, Leo's cursor turns amber 21.2, his scroll moves the page 22.05-23.0, Maya's pink drag 25.43-27.4. The
// cut ends before her tap (28.25): a tap after a phone scroll sends the page back to the top (README, known issues).
// VO s03 is split at its pauses so each phrase lands on its moment: "So we fixed it ... Maya types." (take 0-13.3) from
// comp 4.94, "Maya types." spoken 17.2-18.1 while she types; "Leo scrolls." (13.3-14.66) spoken 21.6-22.7, from his
// cursor turning amber into the scroll; "Nobody fights over the remote." (14.66-end) spoken 25.44-28.0 on Maya's drag.
const S03P1 = 'So we fixed it. Scan the code, and your phone becomes a keyboard and a touchpad. No app to install. Everyone gets a color, and the cursor wears the color of whoever moved it last. Maya types.';
S.push({ id: 's03', file: 's03-join-comp.mp4', in: 3.0, out: 28.15, badge: BADGE,
  vo: [
    { id: 's03', at: 1.94, in: 0, out: 13.3, text: S03P1 },
    { id: 's03', at: 18.46, in: 13.3, out: 14.66, text: 'Leo scrolls.' },
    { id: 's03', at: 22.3, in: 14.66, text: 'Nobody fights over the remote.' }],
  sfx: [sfx('join', 3.33), sfx('join', 7.65)],
  captions: [
    { text: 'Scan the QR: your phone is the keyboard', from: 1.2, to: 7.4, ...COMP_CAP },
    { text: 'Up to 4 phones, no app to install', from: 7.8, to: 14.6, ...COMP_CAP }],
  blur: blurFor(s03, 3.0, 28.15) });

// s04 cards (composite): tap 2.03, loading 2.95, cards land 6.55 (chime), card 2 flips 12.15 (whoosh) | card 3,
// Read aloud 18.03 (from the phone, no UI sound): the TV reads the card with its on-device voice (readVoice).
// VO s04 is split at its pause: "Long pages ... the whole room can read." (take 0-8.75) over the cards, "Want one
// read out loud? Just press OK." (8.75-end) across the cut so "Just press OK." ends 0.3 s before the TV's voice.
// s04a starts at 0.1: the composite's first two frames have no TV picture yet (TV in 0.43 s, see times.txt), which
// flashed dark at the cut from s03.
const S04_TAP = 18.03 - 17.0;
S.push({ id: 's04a', file: 's04-cards-comp.mp4', in: 0.1, out: 13.4, badge: BADGE,
  vo: [{ id: 's04', at: 0.9, in: 0, out: 8.75, text: 'Long pages are hard to read from the couch. Press Make it cards, and I turn the page into big field-guide cards the whole room can read.' }],
  sfx: [sfx('chime', 6.45), sfx('whoosh', 12.05)],
  captions: [{ text: 'Make it cards: a long page becomes big cards', from: 3.3, to: 13.1, ...COMP_CAP }],
  blur: blurFor(s04, 0.1, 13.4, 1, 0.1, s04faint) });
S.push({ id: 's04b', file: 's04-cards-comp.mp4', in: 17.0, out: 25.9, badge: BADGE,
  vo: [{ id: 's04', at: -1.4, in: 8.75, text: 'Want one read out loud? Just press OK.' }],
  sfx: [readVoice(S04_TAP)],
  captions: [{ text: "Lumo reads it aloud with the TV's on-device voice", from: 0.3, to: 8.6, ...COMP_CAP }],
  blur: blurFor(s04, 17.0, 25.9, 1, 0.1, s04faint) });

// s05 quest (composite): intro 6.17 (chime), Maya types the falcon address 8.38-14.18, her Go closes the intro 14.76,
// Peregrine falcon accepted 23.59 (success), Cat rejected 39.35 (fail), Cheetah accepted 54.34 (success), Golden eagle
// accepted 76.87 (success), finale 80.05 (success).
// s05a ends as her Go closes the intro (the TV still shows the Cheetah page for a second before the falcon loads);
// "Everyone races on their own phone." plays over her typing, the rest of s05b over the submit and the proof.
S.push({ id: 's05a', file: 's05-quest-comp.mp4', in: 3.1, out: 14.7, badge: BADGE,
  vo: [{ id: 's05a', at: 0.2 }],
  sfx: [sfx('chime', 3.07)],
  captions: [{ text: 'Family quest: Speed Safari', from: 3.3, to: 11.3, ...COMP_CAP }],
  blur: blurFor(s05, 3.1, 14.7) });
S.push({ id: 's05b', file: 's05-quest-comp.mp4', in: 20.8, out: 28.8, badge: BADGE,
  vo: [{ id: 's05b', at: -1.8 }],
  sfx: [sfx('success', 2.77)],
  captions: [{ text: 'Checked against the page, with proof', from: 0.8, to: 7.6, ...COMP_CAP }],
  blur: blurFor(s05, 20.8, 28.8) });
S.push({ id: 's05c', file: 's05-quest-comp.mp4', in: 30.9, out: 33.1, badge: BADGE, blur: blurFor(s05, 30.9, 33.1) });
S.push({ id: 's05d', file: 's05-quest-comp.mp4', in: 36.9, out: 42.6, badge: BADGE,
  vo: [{ id: 's05c', at: 0.53 }],
  sfx: [sfx('fail', 2.43)],
  captions: [{ text: 'Not quite? Lumo says why', from: 2.9, to: 5.4, ...COMP_CAP }],
  blur: blurFor(s05, 36.9, 42.6) });
// no narration from "Nice try, Leo." to "Golden eagle!": the captions and the app's sounds carry Leo's comeback
S.push({ id: 's05e', file: 's05-quest-comp.mp4', in: 53.0, out: 56.6, badge: BADGE,
  sfx: [sfx('success', 1.32)],
  captions: [{ text: 'Leo tries again: Cheetah', from: 0.3, to: 3.4, ...COMP_CAP }],
  blur: blurFor(s05, 53.0, 56.6) });
S.push({ id: 's05f', file: 's05-quest-comp.mp4', in: 75.5, out: 84.6, badge: BADGE,
  vo: [{ id: 's05d', at: 1.7 }],
  sfx: [sfx('success', 1.35), sfx('success', 4.55)],
  captions: [{ text: '+100 per find, +50 to finish', from: 0.5, to: 4.9, ...COMP_CAP }],
  blur: blurFor(s05, 75.5, 84.6) });

// s06 drift (TV, demo mode): hops Lion 1.7 Zebra 9.3 Giraffe 14.2 Hippo 20.2 Rhino 25.0 (page loads play no sound),
// rescue 28.02 (chime), remote Back ("we're fine") 34.12, overlay closes 34.2 (back)
S.push({ id: 's06a', file: 's06-drift.mp4', in: 0.8, out: 25.0, speed: 2.0, ...TVB,
  vo: [{ id: 's06a', at: 0.3 }],
  captions: [{ text: 'Lumo notices drift from timing alone, on the TV', from: 0.6, to: 11.7 }],
  blur: blurFor(s06, 0.8, 25.0, 2.0, 0.1) });
// the badge moves top-right here: top-left it covers the top of the rescue overlay's "A NUDGE FROM LUMO" label
S.push({ id: 's06b', file: 's06-drift.mp4', in: 25.0, out: 42.6, ...TVB, badge_anchor: 'top-right',
  vo: [{ id: 's06b', at: 0.3 }, { id: 's06c', at: 9.0 }],
  sfx: [sfx('chime', 3.02), sfx('back', 9.2)],
  captions: [
    { text: 'A quest, or a curious detour', from: 3.1, to: 8.8, y: 950 },
    { text: "Each \"we're fine\" = more patience next time", from: 9.5, to: 17.2, anchor: 'top', y: 72 }],
  blur: blurFor(s06, 25.0, 42.6, 1, 0.1, s06faint) });

// s07 architecture
S.push({ id: 's07', image: 'docs/architecture.png', seconds: 21, kenburns: true,
  vo: [{ id: 's07', at: 0.3 }],
  captions: [{ text: 'This recording runs in on-device mode', from: 16.5, to: 20.8, anchor: 'top-right', size: 34, y: 40 }] });

const out = process.argv[2] || join(ROOT, 'video', 'edl.json');
writeFileSync(out, JSON.stringify(edl, null, 2) + '\n');
console.log(`${out}: ${S.length} scenes`);
