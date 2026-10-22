// sheet.mjs: contact sheet of a clip for a quick visual check: one frame every N seconds (default 2), each
// stamped with its time in the clip, tiled 4 across.
//   node video/tools/sheet.mjs video/raw/s03-join-comp.mp4 [out.png] [--every 2] [--from 0] [--to 999] [--width 480]
import { execFileSync } from 'node:child_process';
import { dirname, basename, join, relative } from 'node:path';
import { ROOT, probeDuration } from './wanderlib.mjs';

const args = process.argv.slice(2);
const opt = (name, d) => { const i = args.indexOf(name); if (i < 0) return d; const v = args[i + 1]; args.splice(i, 2); return Number(v); };
const every = opt('--every', 2), from = opt('--from', 0), to = opt('--to', 1e9), width = opt('--width', 480);
const [clip, outArg] = args;
const out = outArg || join(dirname(clip), basename(clip).replace(/\.mp4$/, '') + '-sheet.png');
const dur = Math.min(to, probeDuration(clip)) - from;
const n = Math.max(1, Math.floor(dur / every) + 1);
const cols = 4, rows = Math.ceil(n / cols);
const font = 'app/src/main/assets/fonts/AtkinsonHyperlegible-Bold.ttf';
const vf = `fps=1/${every}:start_time=0,scale=${width}:-2,` +
  `drawtext=fontfile=${font}:text='%{pts\\:hms}':x=8:y=8:fontsize=22:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=4,` +
  `tile=${cols}x${rows}:padding=4:color=0x222222`;
execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-ss', String(from), '-t', String(dur + 0.01), '-i', relative(ROOT, clip) || clip,
  '-vf', vf, '-frames:v', '1', relative(ROOT, out)], { cwd: ROOT, stdio: 'inherit' });
console.log(out);
