// wanderlib.mjs: shared building blocks for recording the Wander TV demo on the Android TV emulator.
//
//   TV        screenrecord (no audio) started detached on the emulator, its verbose output stamped with the
//             device clock, so every recording gets a wall-clock start time (used to line it up with the
//             phone recording) and survives the host's adb server going away mid-take.
//   Remote    TvKeys: D-pad / OK / Back through a `monkey --port` server on the emulator (a few ms per
//             key; `adb shell input keyevent` costs ~0.9 s per key on the emulator, too slow to pace).
//   Phone     Phone: the real phone page (http://localhost:<port>/ served by the TV app, reached through the
//             emulator console's `redir`) in headless Chrome with phone emulation, driven over the Chrome
//             DevTools Protocol with real touch events, recorded with Page.startScreencast (every frame keeps
//             its wall-clock timestamp, so the clip can be rebuilt at a constant 30 fps and synced).
//   Helper    Leo: an invisible second phone, a plain WebSocket client speaking SPEC.md section 5.
//
// Why the detours: on this machine the adb server crashes every few minutes (Windows logs a fail-fast in
// adb.exe, typically right after another adb server, e.g. a Unity editor's, connects to the emulator). That
// drops every `adb forward` and kills attached `adb shell` commands. So the phone port uses the emulator's own
// port redirection (no adb involved), screenrecord runs detached, and every adb call retries through a restart.
//
// Every scene writes video/raw/<scene>.events.json: the host times of the TV recording, the phone frames and
// every scripted action, so in-clip timestamps can be computed after the fact (see composite.mjs).
//
// Environment overrides: ADB, ADB_PORT (adb server port; default: adb's own), EMU_CONSOLE (default 5554),
// CHROME, WANDER_PORT (host port for the TV's phone server 8080, default 8098), MONKEY_PORT.
import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import net from 'node:net';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const RAW = join(ROOT, 'video', 'raw');
export const ADB = process.env.ADB || 'C:/Users/luc/AppData/Local/Android/Sdk/platform-tools/adb.exe';
export const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
export const PORT = Number(process.env.WANDER_PORT || 8098);
export const MONKEY_PORT = Number(process.env.MONKEY_PORT || 1080);
export const EMU_CONSOLE = Number(process.env.EMU_CONSOLE || 5554);
export const PKG = 'tv.wander';
const A = (args) => (process.env.ADB_PORT ? ['-P', String(process.env.ADB_PORT), ...args] : args);

export const now = () => Date.now() / 1000;
export const wait = (ms) => new Promise((r) => setTimeout(r, ms));
export const jitter = (ms, frac = 0.25) => Math.round(ms * (1 - frac + Math.random() * 2 * frac));
const sleepSync = (ms) => execFileSync(process.execPath, ['-e', `setTimeout(()=>{},${ms})`]);

mkdirSync(RAW, { recursive: true });

/** adb with retries: right after an adb server restart the emulator reads "offline" for a few seconds. */
export function adb(args, { allowFail = false, timeout = 30000 } = {}) {
  let last = null;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      return execFileSync(ADB, A(args), { encoding: 'utf8', timeout, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      last = e;
      const msg = String(e.stderr || '') + String(e.message || '');
      if (/offline|daemon|no devices|device .* not found|cannot connect|closed|protocol fault/i.test(msg) && attempt < 4) {
        try { execFileSync(ADB, A(['wait-for-device']), { timeout: 30000, stdio: 'ignore' }); } catch { /* retry anyway */ }
        sleepSync(1500);
        continue;
      }
      break;
    }
  }
  if (allowFail) return (last.stdout || '') + (last.stderr || '');
  throw new Error(`adb ${args.join(' ')} failed: ${last.stderr || last.message}`);
}

export function focusedWindow() {
  const out = adb(['shell', 'dumpsys', 'window'], { allowFail: true });
  const m = out.match(/mCurrentFocus=.*/);
  return m ? m[0].trim() : '';
}

/** Another session sometimes uses this emulator: make sure Wander is the app in front before a take. */
export async function ensureFront({ tries = 5 } = {}) {
  for (let i = 0; i < tries; i++) {
    const f = focusedWindow();
    if (f.includes(`${PKG}/`)) return f;
    console.log(`[front] not Wander (${f || 'nothing'}), bringing it back`);
    adb(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`], { allowFail: true });
    await wait(3000);
  }
  throw new Error('Wander is not in front of the emulator');
}

/* ------------------------------------------------------------------ emulator console */

/** Commands on the emulator console (telnet port 5554, token from ~/.emulator_console_auth_token). */
export async function emuConsole(...lines) {
  const token = readFileSync(join(homedir(), '.emulator_console_auth_token'), 'utf8').trim();
  const sock = net.connect(EMU_CONSOLE, '127.0.0.1');
  let buf = '';
  sock.on('data', (d) => { buf += d.toString(); });
  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  const okCount = () => (buf.match(/^(OK|KO.*)\s*$/gm) || []).length;
  const send = async (line) => {
    const n = okCount();
    sock.write(line + '\n');
    for (let i = 0; i < 40 && okCount() <= n; i++) await wait(25);
  };
  for (let i = 0; i < 40 && !/OK\s*$/m.test(buf); i++) await wait(25);
  await send(`auth ${token}`);
  const start = buf.length;
  for (const l of lines) await send(l);
  sock.write('quit\n');
  sock.destroy();
  return buf.slice(start);
}

/** Host port PORT -> the TV app's phone server (8080) through the emulator itself, not adb. */
export async function forwardPhonePort() {
  adb(['forward', '--remove', `tcp:${PORT}`], { allowFail: true });
  const list = await emuConsole('redir list');
  if (!new RegExp(`tcp:${PORT}\\s*=>\\s*8080`).test(list)) await emuConsole(`redir add tcp:${PORT}:8080`);
}

/** Clean, offline restart: empty api_url is saved by the first start, the second start runs with it. */
export async function restartWander({ demo = false } = {}) {
  adb(['shell', 'am', 'force-stop', PKG]);
  adb(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`, '--es', 'api_url', '""', '--ez', 'demo', String(demo)]);
  await wait(3000);
  adb(['shell', 'am', 'force-stop', PKG]);
  adb(['shell', 'am', 'start', '-n', `${PKG}/.MainActivity`]);
  await wait(3500);
  await forwardPhonePort();
  await ensureFront();
  await waitPhoneServer();
}

export async function waitPhoneServer() {
  for (let i = 0; i < 30; i++) {
    try { const r = await fetch(`http://localhost:${PORT}/`); if (r.ok) return; } catch { /* not yet */ }
    await wait(500);
  }
  throw new Error('phone server did not come up on localhost:' + PORT);
}

export function screenshot(file) {
  writeFileSync(file, execFileSync(ADB, A(['exec-out', 'screencap', '-p']), { maxBuffer: 64 << 20 }));
  return file;
}

/** Device clock minus host clock (seconds), from the adb round trip with the smallest delay. */
export function deviceClockOffset(samples = 5) {
  let best = null;
  for (let i = 0; i < samples; i++) {
    const t1 = now();
    const d = Number(adb(['shell', 'date', '+%s.%N']).trim());
    const t2 = now();
    if (!best || t2 - t1 < best.rtt) best = { rtt: t2 - t1, offset: d - (t1 + t2) / 2 };
  }
  return best;
}

/* ------------------------------------------------------------------ event log */

export class EventLog {
  constructor(scene) { this.scene = scene; this.events = []; this.meta = { scene }; }
  mark(label, extra = {}) {
    const e = { t: now(), label, ...extra };
    this.events.push(e);
    console.log(`[${new Date(e.t * 1000).toISOString().slice(11, 23)}] ${label}`);
    return e;
  }
  save(file = join(RAW, `${this.scene}.events.json`)) {
    writeFileSync(file, JSON.stringify({ ...this.meta, events: this.events }, null, 2));
    return file;
  }
}

/* ------------------------------------------------------------------ TV recording */

export class TvRecorder {
  constructor(name, { bitRate = 12000000, limit = 180 } = {}) {
    this.name = name; this.bitRate = bitRate; this.limit = Math.min(180, limit);
    this.remote = `/sdcard/${name}.mp4`;
    this.remoteLog = `/sdcard/${name}.log`;
    this.lines = [];
  }
  /** Starts screenrecord detached; resolves with `startT`, the host time its encoder started. */
  async start() {
    this.clock = deviceClockOffset();
    adb(['shell', 'rm', '-f', this.remote, this.remoteLog], { allowFail: true });
    this.spawnT = now();
    const rec = `screenrecord --verbose --bit-rate ${this.bitRate} --time-limit ${this.limit} ${this.remote}`;
    const stamped = `${rec} 2>&1 | while IFS= read -r l; do echo "$(date +%s.%N) $l"; done > ${this.remoteLog}`;
    adb(['shell', `setsid nohup sh -c '${stamped}' > /dev/null 2>&1 < /dev/null &`]);
    for (let i = 0; i < 60; i++) {
      await wait(150);
      this.readLog();
      if (this.contentT) break;
    }
    // "Content area is ..." is printed right before the encoder starts: measured against TV frames, the first
    // frame is ~70 ms before it. composite.mjs can refine with --tv-shift after looking at a sync event.
    // Calibrated against D-pad focus changes: the first frame lands ~70 ms before the "Content area" line.
    this.startT = this.contentT ? this.contentT - this.clock.offset - 0.07 : this.spawnT + 0.4;
    return this.startT;
  }
  readLog() {
    const out = adb(['shell', 'cat', this.remoteLog], { allowFail: true });
    this.lines = out.split(/\r?\n/).filter(Boolean).map((l) => { const m = l.match(/^(\d+\.\d+) (.*)$/); return m ? { dev: Number(m[1]), line: m[2] } : { line: l }; });
    const c = this.lines.find((x) => /Content area is/.test(x.line));
    if (c && c.dev) this.contentT = c.dev;
    return this.lines;
  }
  async stop(localFile = join(RAW, `${this.name}.mp4`)) {
    this.stopT = now();
    adb(['shell', 'pkill', '-INT', 'screenrecord'], { allowFail: true });
    for (let i = 0; i < 40; i++) {
      await wait(250);
      if (!adb(['shell', 'pidof', 'screenrecord'], { allowFail: true }).trim()) break;
    }
    await wait(300);
    this.readLog();
    adb(['pull', this.remote, localFile], { timeout: 120000 });
    adb(['shell', 'rm', '-f', this.remote, this.remoteLog], { allowFail: true });
    this.file = localFile;
    return localFile;
  }
  info() { return { file: this.file, spawnT: this.spawnT, startT: this.startT, stopT: this.stopT, clock: this.clock, lines: this.lines }; }
}

/* ------------------------------------------------------------------ remote keys (monkey server) */

const KEYCODES = {
  up: 'KEYCODE_DPAD_UP', down: 'KEYCODE_DPAD_DOWN', left: 'KEYCODE_DPAD_LEFT', right: 'KEYCODE_DPAD_RIGHT',
  ok: 'KEYCODE_DPAD_CENTER', center: 'KEYCODE_DPAD_CENTER', back: 'KEYCODE_BACK', menu: 'KEYCODE_MENU',
  playpause: 'KEYCODE_MEDIA_PLAY_PAUSE', rew: 'KEYCODE_MEDIA_REWIND', ff: 'KEYCODE_MEDIA_FAST_FORWARD',
};

export class TvKeys {
  /** Starts a fresh monkey server on the emulator (detached, so an adb restart does not kill it). */
  static async open() {
    const k = new TvKeys();
    adb(['shell', 'pkill', '-f', 'com.android.commands.monkey'], { allowFail: true });
    await wait(500);
    adb(['shell', `setsid nohup monkey --port ${MONKEY_PORT} > /dev/null 2>&1 < /dev/null &`]);
    for (let i = 0; i < 20; i++) {
      await wait(300);
      if (adb(['shell', 'pidof', 'com.android.commands.monkey'], { allowFail: true }).trim()) break;
    }
    await wait(800);
    await k._connect();
    return k;
  }
  async _connect() {
    if (this.sock) this.sock.destroy();
    adb(['forward', `tcp:${MONKEY_PORT}`, `tcp:${MONKEY_PORT}`]);
    const sock = net.connect(MONKEY_PORT, '127.0.0.1');
    await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
    sock.setNoDelay(true);
    this.sock = sock; this.waiters = []; this.buf = '';
    sock.on('data', (d) => {
      this.buf += d.toString();
      let i;
      while ((i = this.buf.indexOf('\n')) >= 0) { const l = this.buf.slice(0, i); this.buf = this.buf.slice(i + 1); const w = this.waiters.shift(); if (w) w(l); }
    });
    sock.on('error', () => {});
    const r = await Promise.race([this.cmd('wake'), wait(3000).then(() => 'timeout')]);
    if (!String(r).startsWith('OK')) throw new Error('monkey server does not answer: ' + r);
  }
  cmd(line) { return new Promise((r) => { this.waiters.push(r); try { this.sock.write(line + '\n'); } catch { r('write failed'); } }); }
  /** press('down') / press('ok') / press('back') or a raw KEYCODE_* name; reconnects once if adb dropped. */
  async press(key) {
    const code = KEYCODES[key] || key;
    for (let attempt = 0; attempt < 2; attempt++) {
      const r = await Promise.race([this.cmd(`press ${code}`), wait(1500).then(() => 'timeout')]);
      if (String(r).startsWith('OK')) return;
      if (attempt === 0) { try { await this._connect(); continue; } catch { /* fall through */ } }
      throw new Error(`monkey press ${code}: ${r}`);
    }
  }
  /** Stops the monkey server (it would otherwise keep ActivityManager.isUserAMonkey() true). */
  async close() {
    try { await Promise.race([this.cmd('quit'), wait(1000)]); } catch { /* gone */ }
    try { this.sock.destroy(); } catch { /* gone */ }
    adb(['shell', 'pkill', '-f', 'com.android.commands.monkey'], { allowFail: true });
    adb(['forward', '--remove', `tcp:${MONKEY_PORT}`], { allowFail: true });
  }
}

/* ------------------------------------------------------------------ helper phone over WebSocket */

export class Leo {
  static async connect({ name = 'Leo', color = 0, pid = null, log = null } = {}) {
    const l = new Leo(name, log);
    await l._open({ t: 'hello', name, color, pid });
    return l;
  }
  constructor(name, log) { this.name = name; this.log = log; this.state = null; this.listeners = []; }
  _open(hello) {
    return new Promise((res, rej) => {
      const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
      this.ws = ws;
      ws.onerror = (e) => rej(new Error('Leo websocket error'));
      ws.onclose = () => { this.closed = true; if (this.log && !this.closing) this.log.mark(`${this.name} DISCONNECTED`); };
      ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.t === 'welcome') { this.pid = m.pid; res(this); }
        if (m.t === 'state') this.state = m;
        if (m.t !== 'state' && m.t !== 'pong' && this.log) this.log.mark(`${this.name} <- ${e.data.slice(0, 160)}`);
        for (const f of [...this.listeners]) f(m);
      };
      ws.onopen = () => ws.send(JSON.stringify(hello));
    });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  /** Resolves with the first message matching `pred` (or null after `timeout` ms). */
  waitFor(pred, timeout = 15000) {
    return new Promise((res) => {
      if (this.state && pred(this.state)) return res(this.state);
      const f = (m) => { if (pred(m)) { clearTimeout(tm); this.listeners = this.listeners.filter((x) => x !== f); res(m); } };
      const tm = setTimeout(() => { this.listeners = this.listeners.filter((x) => x !== f); res(null); }, timeout);
      this.listeners.push(f);
    });
  }
  /** A natural-feeling scroll: several small deltas instead of one jump. */
  async scroll(dy, steps = 8, gap = 45) {
    for (let i = 0; i < steps; i++) { this.send({ t: 'scroll', dx: 0, dy: dy / steps }); await wait(gap); }
  }
  close() { this.closing = true; try { this.ws.close(); } catch { /* closed */ } }
}

/* ------------------------------------------------------------------ the visible phone (CDP) */

// Shows each touch as a soft white dot, like Android's "Show taps" developer option. It only listens
// (capture phase, passive) and never changes what the page does.
const TOUCH_DOTS = `(() => {
  if (window.__touchDots) return; window.__touchDots = 1;
  const css = '.__td{position:fixed;z-index:2147483647;width:46px;height:46px;margin:-23px 0 0 -23px;border-radius:50%;' +
    'background:rgba(255,255,255,.30);border:2px solid rgba(255,255,255,.55);pointer-events:none;transition:opacity .25s, transform .25s}';
  const add = () => { const s = document.createElement('style'); s.textContent = css; document.documentElement.appendChild(s); };
  if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);
  const dots = new Map();
  const show = (e) => { if (e.pointerType !== 'touch') return; let d = dots.get(e.pointerId);
    if (!d) { d = document.createElement('div'); d.className = '__td'; document.documentElement.appendChild(d); dots.set(e.pointerId, d); }
    d.style.left = e.clientX + 'px'; d.style.top = e.clientY + 'px'; d.style.opacity = '1'; d.style.transform = 'scale(1)'; };
  const hide = (e) => { const d = dots.get(e.pointerId); if (!d) return; dots.delete(e.pointerId);
    d.style.opacity = '0'; d.style.transform = 'scale(1.5)'; setTimeout(() => d.remove(), 300); };
  addEventListener('pointerdown', show, { capture: true, passive: true });
  addEventListener('pointermove', (e) => { if (dots.has(e.pointerId)) show(e); }, { capture: true, passive: true });
  addEventListener('pointerup', hide, { capture: true, passive: true });
  addEventListener('pointercancel', hide, { capture: true, passive: true });
})();`;

export class Phone {
  /** Launches headless Chrome with a fresh (or reused) profile and opens a phone-sized page. */
  static async launch({ debugPort = 9333, profile, width = 412, height = 915, scale = 2, log = null } = {}) {
    mkdirSync(profile, { recursive: true });
    const proc = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${debugPort}`, `--user-data-dir=${profile}`,
      `--force-device-scale-factor=${scale}`, '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--hide-scrollbars', '--mute-audio',
      '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
      `--window-size=${width},${height}`, 'about:blank'], { stdio: 'ignore' });
    let targets = null;
    for (let i = 0; i < 60 && !targets; i++) {
      try { targets = await (await fetch(`http://127.0.0.1:${debugPort}/json`)).json(); } catch { await wait(250); }
    }
    if (!targets) throw new Error('Chrome did not open its debugging port');
    const page = targets.find((t) => t.type === 'page');
    const p = new Phone(proc, page.webSocketDebuggerUrl, log);
    await p._connect();
    p.width = width; p.height = height; p.scale = scale;
    await p.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile: true });
    await p.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await p.send('Emulation.setUserAgentOverride', { userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36' });
    await p.send('Page.enable');
    await p.send('Runtime.enable');
    await p.send('Page.addScriptToEvaluateOnNewDocument', { source: TOUCH_DOTS });
    return p;
  }
  constructor(proc, wsUrl, log) { this.proc = proc; this.wsUrl = wsUrl; this.log = log; this.id = 0; this.pending = new Map(); this.handlers = new Map(); }
  _connect() {
    return new Promise((res) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.id && this.pending.has(m.id)) { const p = this.pending.get(m.id); this.pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); }
        else if (m.method && this.handlers.has(m.method)) this.handlers.get(m.method)(m.params);
      };
      this.ws.onopen = res;
    });
  }
  send(method, params = {}) {
    return new Promise((res, rej) => { const i = ++this.id; this.pending.set(i, { res, rej }); this.ws.send(JSON.stringify({ id: i, method, params })); });
  }
  on(method, fn) { this.handlers.set(method, fn); }
  async goto(url, settle = 2500) { await this.send('Page.navigate', { url }); await wait(settle); }
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.exceptionDetails).slice(0, 300));
    return r.result.value;
  }
  async shot(file) { const r = await this.send('Page.captureScreenshot', { format: 'png' }); writeFileSync(file, Buffer.from(r.data, 'base64')); return file; }

  /* ---- screencast recording */
  async startRecording(dir) {
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    this.recDir = dir; this.frames = []; this.recording = true;
    this.on('Page.screencastFrame', (p) => {
      this.send('Page.screencastFrameAck', { sessionId: p.sessionId }).catch(() => {});
      if (!this.recording) return;
      const n = this.frames.length;
      const file = `f${String(n).padStart(6, '0')}.jpg`;
      writeFileSync(join(dir, file), Buffer.from(p.data, 'base64'));
      this.frames.push({ file, ts: p.metadata.timestamp, recv: now() });
    });
    await this.send('Page.startScreencast', { format: 'jpeg', quality: 92, maxWidth: this.width * this.scale, maxHeight: this.height * this.scale, everyNthFrame: 1 });
    for (let i = 0; i < 100 && !this.frames.length; i++) await wait(20);
    return this.frames.length ? this.frames[0].ts : now();
  }
  async stopRecording() {
    this.stopT = now();
    this.recording = false;
    await this.send('Page.stopScreencast');
    const meta = { stopT: this.stopT, frames: this.frames };
    writeFileSync(join(this.recDir, 'frames.json'), JSON.stringify(meta));
    return meta;
  }

  /* ---- touch input (CSS px in the phone viewport) */
  touch(type, pts) {
    return this.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map((p, i) => ({ x: p.x, y: p.y, id: p.id ?? i, radiusX: 8, radiusY: 8, force: 1 })) });
  }
  async tap(x, y, hold = 90) { await this.touch('touchStart', [{ x, y }]); await wait(hold); await this.touch('touchEnd', []); }
  async rect(selector) {
    return this.eval(`(() => { const e = ${selectorJs(selector)}; if (!e) return null; const r = e.getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height }; })()`);
  }
  /** Taps the center of an element (a CSS selector, or {text, within} to find a button by its text). */
  async tapEl(selector, { dx = 0, dy = 0, label } = {}) {
    let r = null;
    for (let i = 0; i < 30 && !r; i++) { r = await this.rect(selector); if (!r || r.w === 0) { r = null; await wait(200); } }
    if (!r) throw new Error('not found on the phone: ' + JSON.stringify(selector));
    const x = r.x + r.w / 2 + dx, y = r.y + r.h / 2 + dy;
    if (this.log) this.log.mark(label || `phone tap ${JSON.stringify(selector)}`);
    await this.tap(x, y);
    return { x, y };
  }
  /** Types like a person: one character at a time. */
  async type(text, { cps = 7 } = {}) {
    for (const ch of text) { await this.send('Input.insertText', { text: ch }); await wait(jitter(1000 / cps, 0.35)); }
  }
  /** One-finger drag through points [{x,y}] over `ms` milliseconds (touchpad or rail). */
  async drag(points, ms = 900) {
    // Time-based: the finger is where it should be at each moment, however long a CDP round trip takes.
    const seg = points.length - 1;
    const at = (p) => { const f = Math.min(1, p) * seg; const i = Math.min(seg - 1, Math.floor(f)); const u = f - i;
      return { x: points[i].x + (points[i + 1].x - points[i].x) * u, y: points[i].y + (points[i + 1].y - points[i].y) * u }; };
    await this.touch('touchStart', [points[0]]);
    const t0 = Date.now();
    for (;;) {
      const p = (Date.now() - t0) / ms;
      await this.touch('touchMove', [at(p)]);
      if (p >= 1) break;
      const spent = (Date.now() - t0) % 16;
      await wait(Math.max(1, 16 - spent));
    }
    await this.touch('touchEnd', []);
  }
  async close() {
    try { await this.send('Browser.close'); } catch { /* closing */ }
    await wait(500);
    try { this.proc.kill(); } catch { /* gone */ }
  }
}

function selectorJs(selector) {
  if (typeof selector === 'string') return `document.querySelector(${JSON.stringify(selector)})`;
  const within = JSON.stringify(selector.within || 'button');
  const re = selector.text;
  return `[...document.querySelectorAll(${within})].find(x => x.offsetParent !== null && new RegExp(${JSON.stringify(re)}, 'i').test(x.textContent))`;
}

/* ------------------------------------------------------------------ phone frames -> constant 30 fps clip */

/** Builds a constant-frame-rate H.264 clip from screencast frames (each shown until the next one arrived). */
export function framesToVideo(dir, out, { fps = 30, endT = null } = {}) {
  const meta = JSON.parse(readFileSync(join(dir, 'frames.json'), 'utf8'));
  const fr = meta.frames;
  if (!fr.length) throw new Error('no phone frames in ' + dir);
  const end = endT || meta.stopT || fr[fr.length - 1].ts + 0.5;
  let list = 'ffconcat version 1.0\n';
  for (let i = 0; i < fr.length; i++) {
    const next = i + 1 < fr.length ? fr[i + 1].ts : end;
    const d = Math.max(0.001, next - fr[i].ts);
    list += `file '${fr[i].file}'\nduration ${d.toFixed(6)}\n`;
  }
  list += `file '${fr[fr.length - 1].file}'\n`;
  writeFileSync(join(dir, 'list.ffconcat'), list);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', 'list.ffconcat',
    '-vf', `fps=${fps},scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p`, '-c:v', 'libx264', '-preset', 'medium', '-crf', '16', '-r', String(fps), resolve(out)],
  { cwd: dir, stdio: 'inherit' });
  return { out, startT: fr[0].ts, endT: end, frames: fr.length };
}

export function probeDuration(file) {
  return Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file], { encoding: 'utf8' }).trim());
}

export function readJson(file, dflt = null) { try { return JSON.parse(readFileSync(file, 'utf8')); } catch { return dflt; } }
export { existsSync };

/**
 * Best effort: raise the priority of our own headless Chrome (every process whose command line mentions
 * `match`, e.g. its profile folder). The machine this was recorded on is often near 100% CPU, and a starved
 * phone renderer drops frames and answers taps late.
 */
export function boostPriority(match) {
  if (process.platform !== 'win32') return;
  const ps = `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*${match.replace(/'/g, "''")}*' } | ForEach-Object { try { (Get-Process -Id $_.ProcessId).PriorityClass = 'AboveNormal' } catch {} }`;
  try { execFileSync('powershell', ['-NoProfile', '-Command', ps], { stdio: 'ignore', timeout: 20000 }); } catch { /* not critical */ }
}
