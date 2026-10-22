// emulator-edl.mjs: writes video/edl.json, the edit list of the emulator cut (see video/SCRIPT.md), from the
// cut points below and the logo windows in video/raw/<scene>.blur.json (written by logo-blur.mjs).
//
//   node video/tools/emulator-edl.mjs [out.json]      -> video/edl.json (default)
//
// Why a script: every Wikipedia wordmark / favicon blur window has to be moved into each EDL entry's own time
// (minus 'in', divided by 'speed', padded 0.1 s), and s03-s06 are each cut into several entries. After a scene is
// re-recorded, run logo-blur.mjs and composite.mjs for it, update its in/out points and VO 'at' times here (the
// comments give the capture timestamps they were set from: <scene>-comp.times.txt, plus 0.25/0.43/0.19 s for the
// plain s03/s04/s05 TV clips), run this, then `make_video.ps1 -PlanOnly`. Hand edits to edl.json are overwritten.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, RAW } from './wanderlib.mjs';
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
const s03 = blurFile('s03-join').comp, s04 = blurFile('s04-cards').comp, s05 = blurFile('s05-quest').comp, s06 = blurFile('s06-drift').tv;
// faint wordmark behind Wander's rescue overlay (s06, clip time): only the part above the "A nudge from Lumo" label
const s06faint = [{ x: 100, y: 22, w: 254, h: 40, from: 28.0, to: 34.3 }];
// faint wordmark behind the cards and the loading view (s04 composite time), clear of the "From: Cheetah" label
const s04faint = [{ x: 107, y: 132, w: 191, h: 40, from: 3.0, to: 27.4 }];

const COMP_CAP = { cx: 752, y: 930 };       // under the TV of the side-by-side clips
const TVB = { badge: BADGE, badge_y: 8 };    // plain TV clips: badge on the top line, above the app's own UI

const edl = {
  _comment: "Edit list for video/make_video.ps1, emulator cut. Times are seconds. Clip 'file' names resolve in video/raw/, VO ids resolve to video/vo/<id>.mp3, other relative paths resolve from the repo root. Scene 'in'/'out' are source times; 'at'/'from'/'to' are relative to the start of that scene in the final cut (after 'speed'); a negative VO 'at' starts the line in the previous segment. A VO entry with 'in'/'out' plays only that part of the take, with 'text' as its subtitle. Scenes split into several entries (s03, s04, s05, s06) are one take cut down; *-comp clips are the TV and Maya's phone side by side (video/tools/composite.mjs). Blur boxes hide the Wikipedia wordmark and favicon (windows from video/raw/<scene>.blur.json). See video/SCRIPT.md.",
  fps: 30, clip_volume: 0.25, max_seconds: 178,
  font: 'app/src/main/assets/fonts/AtkinsonHyperlegible-Bold.ttf',
  lines: 'video/vo/lines.json',
  title_card: { image: 'docs/title-card.png', seconds: 3, vo: [{ id: 's01', at: 0.5 }] },
  scenes: [],
  end_card: { image: 'docs/end-card.png', seconds: 3.5, vo: [{ id: 's08', at: 0.3 }] },
};
const S = edl.scenes;

// s01 Home
S.push({ id: 's01', file: 's01-home.mp4', in: 0, out: 7, ...TVB,
  captions: [{ text: 'Meet Lumo', from: 0.6, to: 6.6, cx: 1640, y: 618 }] });
// s02 remote typing: OK opens the field at 1.6, letters c 7.3 h 10.1 e 13.4 e 14.3
S.push({ id: 's02', file: 's02-remote.mp4', in: 1.0, out: 16.2, ...TVB,
  vo: [{ id: 's02', at: 0.3 }],
  captions: [{ text: 'Typing with a remote: one letter at a time', from: 1.0, to: 14.8, size: 40, x: 104, y: 244 }] });

// s03 join (composite). VO part 1 "So we fixed it ... Maya types." from comp 4.94; part 2 "Nobody fights over the remote." on Maya's pink drag.
const S03P1 = 'So we fixed it. Scan the code, and your phone becomes a keyboard and a touchpad. No app to install. Everyone gets a color, and the cursor wears the color of whoever moved it last. Maya types.';
S.push({ id: 's03a', file: 's03-join-comp.mp4', in: 3.0, out: 21.9, badge: BADGE,
  vo: [{ id: 's03', at: 1.94, in: 0, out: 13.3, text: S03P1 }],
  captions: [
    { text: 'Scan the QR: your phone is the keyboard', from: 1.2, to: 7.4, ...COMP_CAP },
    { text: 'Up to 4 phones, no app to install', from: 7.8, to: 14.6, ...COMP_CAP }],
  blur: blurFor(s03, 3.0, 21.9) });
S.push({ id: 's03b', file: 's03-join-comp.mp4', in: 24.75, out: 26.2, badge: BADGE, blur: blurFor(s03, 24.75, 26.2) });
S.push({ id: 's03c', file: 's03-join-comp.mp4', in: 28.95, out: 33.0, badge: BADGE,
  vo: [{ id: 's03', at: 0.2, in: 14.66, text: 'Nobody fights over the remote.' }],
  blur: blurFor(s03, 28.95, 33.0) });

// s04 cards (composite): tap 2.03, loading 2.95, cards 6.55, card 2 12.2 | card 3, Read aloud 18.03
S.push({ id: 's04a', file: 's04-cards-comp.mp4', in: 0, out: 13.4, badge: BADGE,
  vo: [{ id: 's04', at: 1.0 }],
  captions: [{ text: 'Make it cards: a long page becomes big cards', from: 3.4, to: 13.2, ...COMP_CAP }],
  blur: blurFor(s04, 0, 13.4, 1, 0.1, s04faint) });
S.push({ id: 's04b', file: 's04-cards-comp.mp4', in: 17.5, out: 21.6, badge: BADGE,
  captions: [{ text: 'Press OK: Lumo reads the card aloud', from: 0.3, to: 3.9, ...COMP_CAP }],
  blur: blurFor(s04, 17.5, 21.6, 1, 0.1, s04faint) });

// s05 quest (composite)
S.push({ id: 's05a', file: 's05-quest-comp.mp4', in: 3.1, out: 17.6, badge: BADGE,
  vo: [{ id: 's05a', at: 0.2 }],
  captions: [{ text: 'Family quest: Speed Safari', from: 3.3, to: 11.4, ...COMP_CAP }],
  blur: blurFor(s05, 3.1, 17.6) });
S.push({ id: 's05b', file: 's05-quest-comp.mp4', in: 20.8, out: 28.3, badge: BADGE,
  vo: [{ id: 's05b', at: -2.75 }],
  captions: [{ text: 'Checked against the page, with proof', from: 0.8, to: 7.2, ...COMP_CAP }],
  blur: blurFor(s05, 20.8, 28.3) });
S.push({ id: 's05c', file: 's05-quest-comp.mp4', in: 30.9, out: 33.1, badge: BADGE, blur: blurFor(s05, 30.9, 33.1) });
S.push({ id: 's05d', file: 's05-quest-comp.mp4', in: 36.9, out: 43.0, badge: BADGE,
  vo: [{ id: 's05c', at: 0.53 }],
  captions: [{ text: 'Not quite? Lumo says why', from: 2.9, to: 6.0, ...COMP_CAP }],
  blur: blurFor(s05, 36.9, 43.0) });
S.push({ id: 's05e', file: 's05-quest-comp.mp4', in: 52.6, out: 56.6, badge: BADGE, blur: blurFor(s05, 52.6, 56.6) });
S.push({ id: 's05f', file: 's05-quest-comp.mp4', in: 75.0, out: 86.0, badge: BADGE,
  vo: [{ id: 's05d', at: 2.2 }],
  captions: [{ text: '+100 per find, +50 to finish', from: 1.0, to: 5.4, ...COMP_CAP }],
  blur: blurFor(s05, 75.0, 86.0) });

// s06 drift (TV, demo mode): hops Lion 1.7 Zebra 9.3 Giraffe 14.2 Hippo 20.2 Rhino 25.0, rescue 28.0, Back 34.1
S.push({ id: 's06a', file: 's06-drift.mp4', in: 0.8, out: 25.0, speed: 2.0, ...TVB,
  vo: [{ id: 's06a', at: 0.3 }],
  captions: [{ text: 'Lumo notices drift from timing alone, on the TV', from: 0.6, to: 11.7 }],
  blur: blurFor(s06, 0.8, 25.0, 2.0, 0.1) });
S.push({ id: 's06b', file: 's06-drift.mp4', in: 25.0, out: 42.6, ...TVB,
  vo: [{ id: 's06b', at: 0.3 }, { id: 's06c', at: 9.0 }],
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
