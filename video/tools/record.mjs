// record.mjs: records the demo scenes of video/SCRIPT.md on the Android TV emulator, as real interactions
// with the real app (Wander in on-device / offline mode), and writes everything to video/raw/ (git-ignored).
//
//   node video/tools/record.mjs s01-home s02-remote          TV-only scenes
//   node video/tools/record.mjs s03-join s04-cards s05-quest  TV + Maya's phone, one continuous evening
//   node video/tools/record.mjs s06-drift                     TV only, demo mode
//
// Scenes run in the order given, in one process, so s03 -> s04 -> s05 continue the same couch (same Maya, same
// Leo). A phone scene started on its own first sets the couch up off camera (clean restart, joins, the page).
// A take that stalls (this machine is often CPU-starved) is retried from a clean start, up to 4 attempts.
//
// Per scene it writes video/raw/<scene>.mp4 (TV, screenrecord), and for phone scenes
// video/raw/<scene>-phone.mp4 (Maya's phone at 30 fps) plus video/raw/<scene>.events.json (host times of
// both recordings and every action). Then run composite.mjs <scene> for the side-by-side clip.
//
// Environment: CAPTURE_TMP = folder for Chrome profiles (default: the OS temp folder).
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  RAW, PORT, wait, now, jitter, ensureFront, restartWander, forwardPhonePort, boostPriority, adb, TvRecorder, TvKeys, Leo, Phone, EventLog, framesToVideo, probeDuration,
} from './wanderlib.mjs';

const TMP = process.env.CAPTURE_TMP || tmpdir();
const ctx = { maya: null, leo: null, couch: false };
const W = 'simple.wikipedia.org/wiki/';

/** A take that went wrong in a way a fresh attempt fixes (this machine is often CPU-starved). */
class Retry extends Error {}
const must = (ok, what) => { if (!ok) throw new Retry(what); };

/* ------------------------------------------------------------------ take helper */

async function take(scene, { phone = false, limit = 180 }, body) {
  await ensureFront();
  const log = new EventLog(scene);
  if (ctx.maya) ctx.maya.log = log;
  if (ctx.leo) ctx.leo.log = log;
  const tv = new TvRecorder(scene, { limit });
  log.meta.tvStart = await tv.start();
  log.mark('tv recording');
  if (phone) {
    log.meta.phoneStart = await ctx.maya.startRecording(join(RAW, `${scene}-phone-frames`));
    log.mark('phone recording');
    await wait(1200);
    const n = ctx.maya.frames.length;
    if (n < 20) { await ctx.maya.stopRecording(); await tv.stop(); throw new Retry(`phone renders only ${n} frames in 1.2 s`); }
  }
  let err = null;
  try { await body(log); } catch (e) { err = e; console.error('scene error:', e); }
  // Health: is the phone server still reachable, are the phones still connected?
  const health = { phoneServer: await fetch(`http://localhost:${PORT}/`).then((r) => r.ok).catch(() => false) };
  if (ctx.leo) health.leoConnected = !ctx.leo.closed;
  if (phone) health.phoneReconnectOverlay = await ctx.maya.eval(`!document.getElementById('reconnect').hidden`).catch(() => 'unknown');
  log.meta.health = health;
  console.log('health', JSON.stringify(health));
  if (phone) {
    const m = await ctx.maya.stopRecording();
    log.meta.phoneStop = m.stopT;
  }
  await tv.stop();
  log.meta.tvStop = tv.stopT;
  log.meta.tvLines = tv.lines;
  log.meta.tvClock = tv.clock;
  log.meta.focusAfter = (adb(['shell', 'dumpsys', 'window'], { allowFail: true }).match(/mCurrentFocus=.*/) || [''])[0];
  if (phone) {
    const v = framesToVideo(join(RAW, `${scene}-phone-frames`), join(RAW, `${scene}-phone.mp4`));
    log.meta.phoneFirstFrame = v.startT;
    log.meta.phoneFrames = v.frames;
  }
  log.meta.tvDuration = probeDuration(join(RAW, `${scene}.mp4`));
  const f = log.save();
  console.log(`saved ${scene}: TV ${log.meta.tvDuration.toFixed(1)} s, events ${f}`);
  if (err) throw (err instanceof Retry ? err : new Retry(err.message)); // a stalled take: try again
  return log;
}

const imeShown = () => /mInputShown=true/.test(adb(['shell', 'dumpsys', 'input_method'], { allowFail: true }));

/* ------------------------------------------------------------------ the couch */

async function openMaya() {
  if (ctx.maya) return ctx.maya;
  const profile = join(TMP, `wander-maya-${Date.now()}`);
  ctx.maya = await Phone.launch({ debugPort: 9351, profile });
  for (let i = 0; i < 6; i++) {
    if (i === 0) await ctx.maya.goto(`http://localhost:${PORT}/`, 3000);
    else { await ctx.maya.send('Page.reload', { ignoreCache: true }); await wait(3500); }
    // Requests through the emulator's port redirection sometimes stall when the machine is busy. Check that the
    // page really booted: stylesheet applied, phone.js ran (it draws the moth, drops "booting"), fonts loaded.
    const ok = await ctx.maya.eval(`(() => {
      const css = [...document.styleSheets].some((s) => { try { return s.cssRules.length > 50; } catch { return false; } });
      const js = document.querySelector('.hero-lumo').innerHTML.length > 0 && !document.getElementById('app').classList.contains('booting');
      const styled = getComputedStyle(document.querySelector('.join-card')).borderRadius !== '0px';
      return css && js && styled && document.fonts.status === 'loaded' && document.readyState === 'complete';
    })()`).catch(() => false);
    if (ok) { boostPriority(profile); return ctx.maya; }
    console.log('[maya] phone page did not boot, reloading');
  }
  throw new Error('phone page does not boot');
}

async function mayaJoin(log) {
  const m = ctx.maya;
  log.mark('maya taps name field');
  await m.tapEl('#join-name', { label: 'maya: tap name field' });
  await wait(450);
  await m.type('Maya', { cps: 4.5 });
  await wait(600);
  await m.tapEl('.swatch[data-color="1"]', { label: 'maya: picks Berry' });
  await wait(900);
  await m.tapEl('#join-btn', { label: 'maya: Join the couch' });
}

async function leoJoin(log) {
  ctx.leo = await Leo.connect({ name: 'Leo', color: 0, log });
  log.mark('leo joined (script)');
}

/** Maya types an address in the phone's search field and presses Go. */
async function mayaGo(log, address, cps = 8) {
  const m = ctx.maya;
  await m.tapEl('#omni-input', { label: 'maya: tap search field' });
  await wait(500);
  await m.type(address, { cps });
  await wait(450);
  await m.tapEl('#omni-send', { label: `maya: Go ${address}` });
}

async function waitPage(log, who, slug, timeout = 15000) {
  const s = await ctx.leo.waitFor((m) => m.t === 'state' && m.mode === 'browse' && (m.url || '').includes(slug) && m.title, timeout);
  const last = ctx.leo.state || {};
  log.mark(s ? `TV page open: ${s.title}` : `TV page NOT open (${slug}); last state ${last.mode} "${last.title}" ${last.url}`);
  return s;
}

/** Maya scrolls the page with the phone's scroll wheel (the rail on the touchpad's right edge). Unused for now. */
async function mayaRail(log, dy = 120, ms = 700) {
  const m = ctx.maya;
  const r = await m.rect('#rail');
  if (!r) return;
  const x = r.x + r.w / 2, y0 = r.y + r.h * 0.35;
  log.mark('maya: scroll wheel');
  await m.drag([{ x, y: y0 }, { x, y: y0 + dy }], ms);
}

/** A slow, straight stroke from a point of the pad (fractions) by (dx, dy) phone px: slow strokes keep the
 *  TV's pointer acceleration at its floor, so where the cursor lands is predictable. */
async function mayaPadRel(log, fx, fy, dx, dy, ms) {
  const r = await ctx.maya.rect('#pad');
  const x = r.x + r.w * fx, y = r.y + r.h * fy;
  log.mark('maya: touchpad drag');
  await ctx.maya.drag([{ x, y }, { x: x + dx, y: y + dy }], ms);
}

async function mayaTapPad(log, label = 'maya: tap touchpad') {
  const r = await ctx.maya.rect('#pad');
  log.mark(label);
  await ctx.maya.tap(r.x + r.w * 0.45, r.y + r.h * 0.5);
}

/** Leo's cursor move (turns the cursor amber), as touchpad deltas in phone px. */
async function leoMove(dx, dy, steps = 40) {
  for (let i = 0; i < steps; i++) { ctx.leo.send({ t: 'move', dx: dx / steps, dy: dy / steps }); await wait(17); }
}

/** Off-camera setup for phone scenes recorded on their own. */
async function setupCouch(withPage = true) {
  if (ctx.couch) return;
  const log = new EventLog('setup');
  await restartWander({ demo: false });
  await openMaya();
  await mayaJoin(log);
  await wait(2500);
  await leoJoin(log);
  await wait(2500);
  if (withPage) {
    ctx.leo.send({ t: 'go', text: W + 'Cheetah' });
    await waitPage(log, 'leo', 'Cheetah');
    await wait(2500);
    await leoMove(-60, 40);
    await ctx.leo.scroll(260, 8, 60);
    await wait(2000);
  }
  ctx.couch = true;
}

/* ------------------------------------------------------------------ scenes */

const SCENES = {
  // Home right after a clean start: Lumo greets in the footer bubble, empty seats, nobody joined.
  async 's01-home'() {
    adb(['shell', 'am', 'force-stop', 'tv.wander']);
    adb(['shell', 'am', 'start', '-n', 'tv.wander/.MainActivity', '--es', 'api_url', '""', '--ez', 'demo', 'false']);
    await wait(3000);
    adb(['shell', 'am', 'force-stop', 'tv.wander']);
    adb(['shell', 'am', 'start', '-W', '-n', 'tv.wander/.MainActivity']);
    await forwardPhonePort();
    await take('s01-home', { limit: 30 }, async (log) => {
      await wait(16000);
      log.mark('end');
    });
  },

  // The pain: Search tile with the remote, then "cheetah" letter by letter on the on-screen keyboard.
  async 's02-remote'() {
    if (ctx.last !== 's01-home') {
      // Same starting point as right after s01: a clean start, Home, the greeting gone, Search tile focused.
      await restartWander({ demo: false });
      await wait(9000);
    }
    const keys = await TvKeys.open();
    try {
      await take('s02-remote', { limit: 60 }, async (log) => {
        const press = async (k, label) => { log.mark(label || `remote ${k}`); await keys.press(k); };
        // Starts on Home with the Search tile focused (as s01 leaves it). The first OK opens the search field
        // and the on-screen keyboard; if focus was hidden, that OK only reveals it, so press once more.
        await wait(1500);
        await press('ok', 'remote OK (open Search)');
        await wait(1300);
        if (!imeShown()) {
          log.mark('keyboard not up yet');
          await press('ok', 'remote OK (open Search, 2nd)');
          await wait(1300);
        }
        log.mark(imeShown() ? 'keyboard up' : 'keyboard NOT up');
        await wait(600);
        // Gboard TV layout, focus starts on q. Rows: qwertyuiop / asdfghjkl, / shift z x c v b n m . del
        const plan = [
          ['c', ['down', 'down', 'right', 'right', 'right']],
          ['h', ['up', 'right', 'right']],
          ['e', ['up', 'left', 'left', 'left']],
          ['e', []],
          ['t', ['right', 'right']],
          ['a', ['down', 'left', 'left', 'left', 'left']],
          ['h', ['right', 'right', 'right', 'right', 'right']],
        ];
        for (const [letter, moves] of plan) {
          for (const mv of moves) { await press(mv); await wait(jitter(520, 0.2)); }
          await wait(300);
          await press('ok', `remote OK -> "${letter}"`);
          await wait(jitter(800, 0.15));
        }
        await wait(2500);
        log.mark('end');
      });
    } finally {
      await keys.press('back').catch(() => {});
      await keys.close();
    }
  },

  // The fix: Maya joins on her phone, Leo joins, Maya opens the Cheetah article, Leo scrolls, Maya drives.
  async 's03-join'() {
    await restartWander({ demo: false });
    ctx.leo?.close(); ctx.leo = null;
    if (ctx.maya) { await ctx.maya.close(); ctx.maya = null; }
    await openMaya();
    await wait(1500);
    await take('s03-join', { phone: true, limit: 90 }, async (log) => {
      await wait(1800);
      await mayaJoin(log);
      await wait(4200);
      await leoJoin(log);
      await wait(3000);
      await mayaGo(log, W + 'Cheetah', 9);
      await waitPage(log, 'maya', 'Cheetah');
      await wait(2200);
      // Cursor math (CursorMotion.kt): a new page puts the cursor at (960, 497) TV px; below 120 phone px/s
      // the touchpad gain is a steady 1.3 dp = 2.6 TV px per phone px. Leo's stroke ends near (698, 654);
      // Maya's slow stroke (+97, -148 phone px) lands near (950, 270): on an unscrolled page the blank band right
      // of the article title. After Leo's scroll her tap sends the page back to the top (README, known issues), so
      // the cut ends before the tap.
      log.mark('leo: touchpad move + scroll');
      await leoMove(-100, 60);
      await wait(250);
      await ctx.leo.scroll(330, 12, 70); // page scroll: about 1.5 screens in 0.85 s (needs the 1137733 scroll fix)
      await wait(2400);
      await mayaPadRel(log, 0.30, 0.72, 97, -148, 1900);
      await wait(800);
      await mayaTapPad(log, 'maya: tap (click on the page)');
      await wait(3000);
      log.mark('end');
    });
    ctx.couch = true;
  },

  // Make it cards from Maya's phone, Next twice, Read aloud.
  async 's04-cards'() {
    await setupCouch(true);
    await take('s04-cards', { phone: true, limit: 90 }, async (log) => {
      await wait(2000);
      await ctx.maya.tapEl('#dock-cards', { label: 'maya: Make it cards' });
      const s = await ctx.leo.waitFor((m) => m.t === 'state' && m.mode === 'cards' && m.cards && m.cards.count > 0, 20000);
      log.mark(s ? `cards shown (${s.cards.count})` : 'cards NOT shown');
      must(s, 'cards did not appear');
      await wait(4500);
      await ctx.maya.tapEl('#cards-next', { label: 'maya: Next (card 2)' });
      await wait(3200);
      await ctx.maya.tapEl('#cards-next', { label: 'maya: Next (card 3)' });
      await wait(3200);
      await ctx.maya.tapEl('#cards-read', { label: 'maya: Read aloud' });
      await wait(9000);
      log.mark('end');
    });
  },

  // Speed Safari in one take: Maya starts it from the phone, then four submissions.
  async 's05-quest'() {
    await setupCouch(true);
    await take('s05-quest', { phone: true, limit: 180 }, async (log) => {
      const leo = ctx.leo;
      await wait(1500);
      await ctx.maya.tapEl('.dock-btn[data-key="home"]', { label: 'maya: Home' });
      must(await leo.waitFor((m) => m.t === 'state' && m.mode === 'home', 3000), 'TV did not go Home within 3 s');
      log.mark('TV home');
      await wait(2600);
      await ctx.maya.tapEl({ text: 'Speed Safari', within: '.qtile' }, { label: 'maya: quest row -> Speed Safari' });
      const intro = await leo.waitFor((m) => m.t === 'state' && m.mode === 'quest_intro', 8000);
      log.mark(intro ? 'quest intro shown' : 'quest intro NOT shown');
      must(intro, 'quest intro did not show');
      await wait(2200);
      // 1) Maya: Peregrine falcon (+100 with evidence). She types while the intro is still up; her Go closes
      // it and opens her page. (Closing the intro with OK or Back first makes the TV open the quest's starter
      // web search, a search engine's results page we keep off camera.)
      await mayaGo(log, W + 'Peregrine_falcon', 9);
      await leo.waitFor((m) => m.t === 'state' && m.mode !== 'quest_intro', 6000);
      log.mark('intro closed by her Go');
      await waitPage(log, 'maya', 'Peregrine');
      await wait(5600);
      await ctx.maya.tapEl('#quest-submit', { label: 'maya: submit Peregrine falcon' });
      await leo.waitFor((m) => m.t === 'state' && m.quest && m.quest.found.length >= 1, 15000);
      log.mark('accepted: Peregrine falcon (state)');
      await wait(6500);

      // 2) Leo: Cat (gentle "not quite")
      log.mark('leo: go Cat');
      leo.send({ t: 'go', text: W + 'Cat' });
      await waitPage(log, 'leo', '/Cat');
      await wait(1500);
      await leoMove(40, 30);
      await wait(4300);
      log.mark('leo: submit Cat');
      leo.send({ t: 'quest_submit' });
      await wait(7500);

      // 3) Leo: Cheetah (+100)
      log.mark('leo: go Cheetah');
      leo.send({ t: 'go', text: W + 'Cheetah' });
      await waitPage(log, 'leo', 'Cheetah');
      await wait(1500);
      await leoMove(-30, 20);
      await wait(4300);
      log.mark('leo: submit Cheetah');
      leo.send({ t: 'quest_submit' });
      await leo.waitFor((m) => m.t === 'state' && m.quest && m.quest.found.length >= 2, 15000);
      log.mark('accepted: Cheetah (state)');
      await wait(6500);

      // 4) Maya: Golden eagle (+100, finale)
      await mayaGo(log, W + 'Golden_eagle', 10);
      await waitPage(log, 'maya', 'Golden');
      await wait(5600);
      await ctx.maya.tapEl('#quest-submit', { label: 'maya: submit Golden eagle' });
      const fin = await leo.waitFor((m) => m.t === 'state' && m.quest && m.quest.complete, 15000);
      log.mark(fin ? 'quest complete (state)' : 'quest NOT complete');
      await wait(14000);
      log.mark('end');
    });
  },

  // Drift: demo mode, aimless hopping until Lumo's rescue, then Back on the remote ("we're fine").
  async 's06-drift'() {
    await restartWander({ demo: true });
    ctx.leo?.close();
    const log0 = new EventLog('setup');
    ctx.leo = await Leo.connect({ name: 'Leo', color: 0, log: log0 });
    await wait(7000); // let the welcome line settle before the take
    const keys = await TvKeys.open();
    try {
      await take('s06-drift', { limit: 120 }, async (log) => {
        const leo = ctx.leo;
        const pages = ['Lion', 'Zebra', 'Giraffe', 'Hippopotamus', 'Rhinoceros', 'Elephant', 'Gorilla', 'Kangaroo'];
        let rescue = null;
        const watch = leo.waitFor((m) => m.t === 'state' && m.mode === 'rescue' && m.rescue, 110000).then((m) => { rescue = m; });
        await wait(1500);
        for (const p of pages) {
          if (rescue) break;
          log.mark(`leo: go ${p}`);
          leo.send({ t: 'go', text: W + p });
          await waitPage(log, 'leo', p, 8000);
          if (rescue) break;
          await wait(900);
          await leoMove(20 - Math.random() * 40, 30);
          await leo.scroll(220, 6, 50);
          for (let i = 0; i < 25 && !rescue; i++) await wait(100);
        }
        await Promise.race([watch, wait(20000)]);
        log.mark(rescue ? `rescue shown: ${rescue.rescue.headline} | ${rescue.rescue.detail}` : 'rescue NOT shown');
        await wait(6000);
        log.mark('remote Back ("we\'re fine")');
        await keys.press('back');
        await wait(9000);
        log.mark('end');
      });
    } finally {
      await keys.close();
    }
  },
};

/* ------------------------------------------------------------------ main */

const list = process.argv.slice(2);
if (!list.length || list.some((s) => !SCENES[s])) {
  console.log('usage: node video/tools/record.mjs <scene...>   scenes: ' + Object.keys(SCENES).join(' '));
  process.exit(1);
}
let failed = false;
try {
  for (const s of list) {
    for (let attempt = 1; ; attempt++) {
      console.log(`\n=== ${s}${attempt > 1 ? ` (attempt ${attempt})` : ''}`);
      try { await SCENES[s](); ctx.last = s; break; } catch (e) {
        if (!(e instanceof Retry) || attempt >= 4) throw e;
        console.log(`[retry] ${e.message}`);
        ctx.leo?.close(); ctx.leo = null;
        if (ctx.maya) { await ctx.maya.close(); ctx.maya = null; }
        ctx.couch = false;
        await wait(3000);
      }
    }
    await wait(1500);
  }
} catch (e) { failed = true; console.error(e); }
ctx.leo?.close();
if (ctx.maya) await ctx.maya.close();
process.exit(failed ? 1 : 0);
