// logo-blur.mjs: finds when the Wikipedia wordmark (page header) and the site's favicon (in Wander's title
// strip) are on screen in a TV recording, and prints EDL "blur" boxes for them, in plain-TV pixels and, when a
// composite exists, in composite pixels/seconds too.
//
//   node video/tools/logo-blur.mjs s05-quest            -> video/raw/s05-quest.blur.json (+ a summary)
//
// It samples the TV clip at 10 fps and measures brightness in three fixed regions of Simple English Wikipedia
// as Wander shows it at 1920x1080 (desktop skin, page not scrolled):
//   wordmark  x106-346 y34-74     bright (>100) = the page header is showing
//   strip     x700-1200 y85-135   dark (<120) while Wander's title strip (with the site favicon) is down
//   banner    x80-580 y165-235    dark (<120) while a quest banner covers the header's lower half (it also
//                                 covers the strip region, so a dark strip region counts only without it)
// Boxes (TV px): wordmark {x:100,y:26,w:254,h:56}; wordmark above a banner {x:100,y:26,w:254,h:31};
// favicon {x:118,y:76,w:76,h:72}. Re-measure if the site, skin or zoom changes.
import { spawnSync } from 'node:child_process';
import { writeFileSync, existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { RAW } from './wanderlib.mjs';

const scene = process.argv[2];
if (!scene) { console.log('usage: node video/tools/logo-blur.mjs <scene>'); process.exit(1); }
const tvFile = join(RAW, `${scene}.mp4`);

const BOX = {
  wordmark: { x: 100, y: 26, w: 254, h: 56 },
  wordmarkTop: { x: 100, y: 26, w: 254, h: 31 },
  favicon: { x: 118, y: 76, w: 76, h: 72 },
};

function measure(crop) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-i', tvFile, '-vf',
    `fps=10,crop=${crop},signalstats,metadata=print:key=lavfi.signalstats.YAVG`, '-f', 'null', '-'], { encoding: 'utf8', maxBuffer: 64 << 20 });
  const vals = [];
  let t = null;
  for (const line of r.stderr.split(/\r?\n/)) {
    const m = line.match(/pts_time:([0-9.]+)/); if (m) t = Number(m[1]);
    const y = line.match(/YAVG=([0-9.]+)/); if (y && t !== null) vals.push({ t, y: Number(y[1]) });
  }
  return vals;
}

const wm = measure('240:40:106:34'), st = measure('500:50:700:85'), bn = measure('500:70:80:165');
const frames = wm.map((w, i) => {
  const header = w.y > 100;
  const banner = bn[i] && bn[i].y < 120;
  const strip = st[i] && st[i].y < 120 && !banner;
  const boxes = [];
  if (header) {
    if (strip) boxes.push('wordmark', 'favicon');
    else if (banner) boxes.push('wordmarkTop');
    else boxes.push('wordmark');
  }
  return { t: w.t, boxes };
});

// merge into windows per box kind, padded by 0.2 s
const windows = [];
for (const kind of Object.keys(BOX)) {
  let cur = null;
  for (const f of frames) {
    const on = f.boxes.includes(kind);
    if (on && !cur) cur = { kind, from: f.t, to: f.t + 0.1 };
    else if (on) cur.to = f.t + 0.1;
    else if (cur) { windows.push(cur); cur = null; }
  }
  if (cur) windows.push(cur);
}
const pad = (w) => ({ ...w, from: Math.max(0, +(w.from - 0.2).toFixed(2)), to: +(w.to + 0.2).toFixed(2) });
const tv = windows.map(pad).sort((a, b) => a.from - b.from)
  .map((w) => ({ ...BOX[w.kind], from: w.from, to: w.to, what: w.kind === 'favicon' ? 'site favicon in the title strip' : 'Wikipedia wordmark' }));

const result = { scene, note: 'TV px = 1920x1080 plain TV clip; times in seconds of the clip', tv };
const evFile = join(RAW, `${scene}.events.json`);
const compFile = join(RAW, `${scene}-comp.mp4`);
if (existsSync(evFile) && existsSync(compFile)) {
  const ev = JSON.parse(readFileSync(evFile, 'utf8'));
  if (ev.phoneFirstFrame) {
    const tvIn = Math.max(ev.tvStart, ev.phoneFirstFrame) - ev.tvStart;
    const s = 0.75, ox = 32, oy = 113; // composite.mjs layout
    result.comp = tv.map((b) => ({
      x: Math.floor(ox + b.x * s), y: Math.floor(oy + b.y * s), w: Math.ceil(b.w * s), h: Math.ceil(b.h * s),
      from: Math.max(0, +(b.from - tvIn).toFixed(2)), to: +(b.to - tvIn).toFixed(2), what: b.what,
    }));
    result.compNote = `composite px/seconds (TV inset at 32,113 scaled 0.75; clip starts ${tvIn.toFixed(2)} s into the TV clip)`;
  }
}
const out = join(RAW, `${scene}.blur.json`);
writeFileSync(out, JSON.stringify(result, null, 1));
for (const b of tv) console.log(`tv  ${b.from.toFixed(1).padStart(6)}-${b.to.toFixed(1).padEnd(6)} x=${b.x} y=${b.y} w=${b.w} h=${b.h}  ${b.what}`);
for (const b of result.comp || []) console.log(`comp ${b.from.toFixed(1).padStart(5)}-${b.to.toFixed(1).padEnd(6)} x=${b.x} y=${b.y} w=${b.w} h=${b.h}  ${b.what}`);
console.log('wrote', out);
