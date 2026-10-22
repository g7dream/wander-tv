// composite.mjs: synchronized side-by-side clip of the TV and Maya's phone for one scene.
//
//   node video/tools/composite.mjs s03-join [--tv-shift 0] [--phone-shift 0]
//
// Reads video/raw/<scene>.mp4 (TV), video/raw/<scene>-phone.mp4 (phone, constant 30 fps) and
// video/raw/<scene>.events.json (host wall-clock start of both, written by record.mjs). The clip covers the
// time both recordings overlap, so the same instant is on both sides. --tv-shift / --phone-shift (seconds,
// + = that recording really started later) correct the start estimates if a check frame shows an offset.
//
// Layout (1920x1080, 30 fps, no audio): lamp-lit background (#120E1C, faint amber glow top-left), TV at
// 1440x810 on the left with a hairline border, the phone at 372x826 on the right with rounded corners, both
// vertically centered, labels under them in Atkinson Hyperlegible Bold.
// Writes video/raw/<scene>-comp.mp4 and video/raw/<scene>-comp.times.txt (every event, in clip seconds).
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync, existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, RAW, probeDuration } from './wanderlib.mjs';

const args = process.argv.slice(2);
const opt = (name, d) => { const i = args.indexOf(name); if (i < 0) return d; const v = Number(args[i + 1]); args.splice(i, 2); return v; };
const tvShift = opt('--tv-shift', 0), phoneShift = opt('--phone-shift', 0);
const scene = args[0];
if (!scene) { console.log('usage: node video/tools/composite.mjs <scene> [--tv-shift s] [--phone-shift s]'); process.exit(1); }

const ev = JSON.parse(readFileSync(join(RAW, `${scene}.events.json`), 'utf8'));
const tvFile = join(RAW, `${scene}.mp4`), phFile = join(RAW, `${scene}-phone.mp4`);
const tvStart = ev.tvStart + tvShift, phStart = ev.phoneFirstFrame + phoneShift;
const tvDur = probeDuration(tvFile), phDur = probeDuration(phFile);
const T0 = Math.max(tvStart, phStart), T1 = Math.min(tvStart + tvDur, phStart + phDur);
const dur = T1 - T0, tvIn = T0 - tvStart, phIn = T0 - phStart;
if (dur <= 1) throw new Error('recordings do not overlap');

/* ---- layout */
const TV = { w: 1440, h: 810 }, PH = { w: 372, h: 826 }, GAP = 44;
const left = Math.round((1920 - (TV.w + GAP + PH.w)) / 2);
const mid = 518;
TV.x = left; TV.y = mid - TV.h / 2;
PH.x = left + TV.w + GAP; PH.y = Math.round(mid - PH.h / 2);
const LABEL_Y = 948, R = 34;

/* ---- static assets (background, phone mask, phone rim, label text) */
const A = join(RAW, '_comp');
mkdirSync(A, { recursive: true });
const rel = (p) => p.slice(ROOT.length + 1).replace(/\\/g, '/');
const ff = (a) => execFileSync('ffmpeg', ['-loglevel', 'error', '-y', ...a], { cwd: ROOT, stdio: 'inherit' });
const bg = join(A, 'bg.png'), mask = join(A, 'phone-mask.png'), rim = join(A, 'phone-rim.png');
if (!existsSync(bg)) {
  const g = (base, c) => `${base}+${c}*0.17*exp(-((X+120)*(X+120)+(Y+160)*(Y+160))/(2*560*560))+${c}*0.05*exp(-((X+120)*(X+120)+(Y+160)*(Y+160))/(2*1100*1100))`;
  ff(['-f', 'lavfi', '-i', 'color=c=black:s=1920x1080:d=1', '-vf',
    `format=rgb24,geq=r='${g(18, 255)}':g='${g(14, 181)}':b='${g(28, 71)}'`, '-frames:v', '1', rel(bg)]);
}
const rr = (w, h, r) => `clip(${r}+0.5-hypot(max(max(${r}-X\\,X-(${w}-1-${r}))\\,0)\\,max(max(${r}-Y\\,Y-(${h}-1-${r}))\\,0))\\,0\\,1)`;
if (!existsSync(mask)) {
  ff(['-f', 'lavfi', '-i', `color=c=black:s=${PH.w}x${PH.h}:d=1`, '-vf', `format=gray,geq=lum='255*${rr(PH.w, PH.h, R)}'`, '-frames:v', '1', rel(mask)]);
}
if (!existsSync(rim)) {
  const w = PH.w + 4, h = PH.h + 4;
  ff(['-f', 'lavfi', '-i', `color=c=black:s=${w}x${h}:d=1`, '-vf',
    `format=rgba,geq=r='247':g='239':b='226':a='58*${rr(w, h, R + 2)}'`, '-frames:v', '1', rel(rim)]);
}
const txt = (name, s) => { const p = join(A, name); writeFileSync(p, s, 'utf8'); return rel(p); };
const tvLabel = txt('tv-label.txt', 'TV · Android TV emulator · Fire OS APK');
const phLabel1 = txt('phone-label-1.txt', "Maya's phone");
const phLabel2 = txt('phone-label-2.txt', '(phone page, Chrome phone emulation)');
const font = 'app/src/main/assets/fonts/AtkinsonHyperlegible-Bold.ttf';

/* ---- filter graph */
const fg = [
  `[1:v]fps=30,scale=${TV.w}:${TV.h}:flags=lanczos,setsar=1[tv]`,
  `[2:v]fps=30,scale=${PH.w}:${PH.h}:flags=lanczos,setsar=1,format=rgba[ph0]`,
  `[3:v]format=gray,scale=${PH.w}:${PH.h}[m]`,
  `[ph0][m]alphamerge[ph]`,
  `[0:v]format=rgba,drawbox=x=${TV.x - 1}:y=${TV.y - 1}:w=${TV.w + 2}:h=${TV.h + 2}:color=0xF7EFE2@0.26:t=1[b1]`,
  `[b1][4:v]overlay=${PH.x - 2}:${PH.y - 2}[b2]`,
  `[b2][tv]overlay=${TV.x}:${TV.y}:shortest=1[c1]`,
  `[c1][ph]overlay=${PH.x}:${PH.y}:shortest=1[c2]`,
  `[c2]drawtext=fontfile=${font}:textfile=${tvLabel}:fontsize=24:fontcolor=0xF7EFE2@0.82:x=${TV.x + TV.w / 2}-text_w/2:y=${LABEL_Y},` +
    `drawtext=fontfile=${font}:textfile=${phLabel1}:fontsize=24:fontcolor=0xF7EFE2@0.82:x=${PH.x + PH.w / 2}-text_w/2:y=${LABEL_Y},` +
    `drawtext=fontfile=${font}:textfile=${phLabel2}:fontsize=17:fontcolor=0xB9AFCB@0.9:x=${PH.x + PH.w / 2}-text_w/2:y=${LABEL_Y + 32},` +
    `format=yuv420p[out]`,
].join(';\n');
const fgFile = join(A, `${scene}.filter.txt`);
writeFileSync(fgFile, fg);
const out = join(RAW, `${scene}-comp.mp4`);
ff(['-loop', '1', '-framerate', '30', '-i', rel(bg),
  '-ss', tvIn.toFixed(3), '-t', dur.toFixed(3), '-i', rel(tvFile),
  '-ss', phIn.toFixed(3), '-t', dur.toFixed(3), '-i', rel(phFile),
  '-loop', '1', '-framerate', '30', '-i', rel(mask),
  '-loop', '1', '-framerate', '30', '-i', rel(rim),
  '-/filter_complex', rel(fgFile), '-map', '[out]', '-t', dur.toFixed(3), '-r', '30',
  '-c:v', 'libx264', '-preset', 'medium', '-crf', '17', '-an', '-movflags', '+faststart', rel(out)]);

/* ---- event times in the clip */
let lines = `${scene}-comp.mp4  ${dur.toFixed(2)} s  (TV in ${tvIn.toFixed(2)} s, phone in ${phIn.toFixed(2)} s; tv-shift ${tvShift}, phone-shift ${phoneShift})\n`;
lines += `TV rect x=${TV.x} y=${TV.y} w=${TV.w} h=${TV.h} (TV px * 0.75 + offset); phone rect x=${PH.x} y=${PH.y} w=${PH.w} h=${PH.h}\n`;
for (const e of ev.events) {
  const c = e.t - T0, t = e.t - tvStart;
  lines += `${c.toFixed(2).padStart(7)}  (tv ${t.toFixed(2).padStart(6)})  ${e.label}\n`;
}
writeFileSync(join(RAW, `${scene}-comp.times.txt`), lines);
console.log(lines);
console.log('wrote', out);
