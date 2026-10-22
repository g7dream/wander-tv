// read-aloud.mjs: records what the TV says when a card is read aloud, for scene 4.
//
//   node video/tools/read-aloud.mjs [card-number]     (default 3, the card Maya reads in s04)
//
// screenrecord (record.mjs) captures no audio, but the emulator's own recorder (console `screenrecord start`)
// records the emulator's display WITH its sound output. This opens the same Cheetah page in a clean on-device
// Wander, makes the cards, flips to the card, presses Read aloud over the phone protocol (the same {t:'read'} the
// phone's Read aloud button sends), and records the emulator for a few seconds. Writes video/raw/s04-read.webm
// (picture + sound), video/raw/s04-read.wav (the sound) and video/raw/s04-read.json: the card read, and
// `latency`, seconds from the read command to the first sound, so emulator-edl.mjs can lay the voice in at the
// moment Maya's tap triggered it in the s04 take.
import { execFileSync, spawnSync } from 'node:child_process';
import { writeFileSync, existsSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { RAW, adb, Leo, restartWander, emuConsole, ensureFront, wait, now } from './wanderlib.mjs';

const cardNo = Number(process.argv[2] || 3);
const webm = join(RAW, 's04-read.webm'), wav = join(RAW, 's04-read.wav'), meta = join(RAW, 's04-read.json');
const SLUG = 'Cheetah';
const r3 = (v) => Math.round(v * 1000) / 1000;

// The emulator's media volume (the stream the TV's voice plays on) sits at 3 of 15, which records at about
// -60 dB: turn it up for the capture, like the remote's volume key, and put it back afterwards.
const musicVolume = () => Number((adb(['shell', 'dumpsys', 'audio'], { allowFail: true }).match(/- STREAM_MUSIC:[\s\S]*?streamVolume:(\d+)/) || [])[1]);
const setMusicVolume = (v) => adb(['shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', String(v)], { allowFail: true });
const volBefore = musicVolume();

await restartWander({ demo: false });
const leo = await Leo.connect({ name: 'Leo', color: 0 });
try {
  setMusicVolume(12);
  leo.send({ t: 'go', text: `simple.wikipedia.org/wiki/${SLUG}` });
  const page = await leo.waitFor((m) => m.t === 'state' && m.mode === 'browse' && (m.url || '').includes(SLUG) && m.title, 25000);
  if (!page) throw new Error('the Cheetah page did not open');
  await wait(2500);
  leo.send({ t: 'cards' });
  const deck = await leo.waitFor((m) => m.t === 'state' && m.mode === 'cards' && m.cards && m.cards.count > 0, 25000);
  if (!deck) throw new Error('the cards did not appear');
  await wait(2500);
  for (let i = 1; i < cardNo; i++) { leo.send({ t: 'key', key: 'right' }); await wait(1600); }
  const at = await leo.waitFor((m) => m.t === 'state' && m.cards && m.cards.index === cardNo - 1, 5000);
  if (!at) throw new Error(`could not flip to card ${cardNo}`);
  await ensureFront();
  if (existsSync(webm)) unlinkSync(webm);
  await emuConsole(`screenrecord start --time-limit 16 "${webm.replace(/\\/g, '/')}"`);
  const recT = now();
  await wait(1500);
  const readT = now();
  leo.send({ t: 'read' });
  await wait(13000);
  await emuConsole('screenrecord stop');
  await wait(1500);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', webm, '-vn', '-ac', '1', '-ar', '48000', wav]);
  // the voice: the first sound after the read command, until the last sound
  const sd = spawnSync('ffmpeg', ['-hide_banner', '-nostats', '-i', wav, '-af', 'silencedetect=n=-45dB:d=0.3', '-f', 'null', '-'],
    { encoding: 'utf8' }).stderr;
  const readAt = r3(readT - recT);
  const ends = [...sd.matchAll(/silence_end: ([\d.]+)/g)].map((m) => Number(m[1])).filter((t) => t > readAt);
  const starts = [...sd.matchAll(/silence_start: ([\d.]+)/g)].map((m) => Number(m[1])).filter((t) => t > readAt);
  if (!ends.length) throw new Error('no sound after the read command: is the emulator muted, or the voice data missing?');
  const voiceIn = ends[0], voiceOut = starts.length ? starts[starts.length - 1] : null;
  const info = { card: cardNo, cardState: at.cards, page: page.title, readAt, voiceIn, voiceOut, latency: r3(voiceIn - readAt) };
  writeFileSync(meta, JSON.stringify(info, null, 2) + '\n');
  console.log(JSON.stringify(info, null, 2));
} finally {
  leo.close();
  if (Number.isFinite(volBefore)) setMusicVolume(volBefore);
}
