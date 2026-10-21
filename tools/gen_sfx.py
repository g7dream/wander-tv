#!/usr/bin/env python3
"""Synthesizes Wander TV's UI sounds into app/src/main/res/raw/ (Python stdlib only).

Every sound is original: soft marimba and glass tones with exponential decay, a little filtered
noise for the whoosh, all mixed quietly so nothing startles a sleepy living room.
Format: 22.05 kHz, mono, 16-bit PCM WAV, each under 0.7 s.

    python tools/gen_sfx.py            # writes app/src/main/res/raw/sfx_*.wav
    python tools/gen_sfx.py --out DIR  # somewhere else (for listening tests)
"""

import argparse
import math
import os
import random
import struct
import wave

RATE = 22050
MAX_SECONDS = 0.69


def note(name):
    """'C6' -> Hz (A4 = 440)."""
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    letter, rest = name[0], name[1:]
    shift = 0
    if rest.startswith("#"):
        shift, rest = 1, rest[1:]
    elif rest.startswith("b"):
        shift, rest = -1, rest[1:]
    octave = int(rest)
    semis = names[letter] + shift + (octave - 4) * 12
    return 440.0 * 2 ** (semis / 12.0)


def silence(seconds):
    return [0.0] * int(seconds * RATE)


def mix_into(buf, sig, at_seconds, gain=1.0):
    start = int(at_seconds * RATE)
    need = start + len(sig)
    if need > len(buf):
        buf.extend([0.0] * (need - len(buf)))
    for i, s in enumerate(sig):
        buf[start + i] += s * gain


def env(i, attack_s, decay_s):
    """Short linear attack (no click), then exponential decay with time constant decay_s."""
    t = i / RATE
    a = min(1.0, t / attack_s) if attack_s > 0 else 1.0
    return a * math.exp(-t / decay_s)


def marimba(freq, seconds, decay=0.12, bright=0.35, click=0.15, seed=1):
    """Wooden bar: fundamental, the 4x overtone that decays fast, and a tiny mallet click."""
    rnd = random.Random(seed)
    out = []
    n = int(seconds * RATE)
    click_n = int(0.004 * RATE)
    for i in range(n):
        t = i / RATE
        s = math.sin(2 * math.pi * freq * t) * env(i, 0.002, decay)
        s += bright * math.sin(2 * math.pi * freq * 3.93 * t) * env(i, 0.001, decay * 0.22)
        s += 0.12 * math.sin(2 * math.pi * freq * 2.0 * t) * env(i, 0.002, decay * 0.5)
        if i < click_n:
            s += click * (rnd.random() * 2 - 1) * (1 - i / click_n)
        out.append(s)
    return out


def glass(freq, seconds, decay=0.35, shimmer=0.3):
    """Struck glass: inharmonic partials (bell-like ratios) with a slow, soft decay and a gentle beat."""
    out = []
    n = int(seconds * RATE)
    for i in range(n):
        t = i / RATE
        s = math.sin(2 * math.pi * freq * t) * env(i, 0.003, decay)
        s += 0.5 * math.sin(2 * math.pi * freq * 1.003 * t) * env(i, 0.003, decay)  # slow beating
        s += shimmer * math.sin(2 * math.pi * freq * 2.76 * t) * env(i, 0.002, decay * 0.35)
        s += shimmer * 0.4 * math.sin(2 * math.pi * freq * 5.40 * t) * env(i, 0.001, decay * 0.15)
        out.append(s)
    return out


def lowpass(sig, cutoff):
    """One-pole low-pass to round off any harshness."""
    rc = 1.0 / (2 * math.pi * cutoff)
    dt = 1.0 / RATE
    a = dt / (rc + dt)
    out, y = [], 0.0
    for s in sig:
        y += a * (s - y)
        out.append(y)
    return out


def finish(sig, peak, fade_ms=12):
    """Trim to the length limit, fade both ends to true silence and normalize to [peak]."""
    sig = sig[: int(MAX_SECONDS * RATE)]
    lead = int(0.0015 * RATE)
    for i in range(min(lead, len(sig))):
        sig[i] *= i / lead
    fade = int(fade_ms / 1000 * RATE)
    for i in range(min(fade, len(sig))):
        sig[len(sig) - 1 - i] *= i / fade
    top = max(1e-9, max(abs(s) for s in sig))
    return [s / top * peak for s in sig]


# ---- the sounds ----------------------------------------------------------------------------------

def sfx_focus():
    # A very soft, high wooden tick: barely there, it confirms the D-pad moved.
    s = marimba(note("G6"), 0.07, decay=0.018, bright=0.15, click=0.05, seed=3)
    return finish(lowpass(s, 5000), 0.55, fade_ms=8)


def sfx_select():
    # A warm "tock" with a fifth above, like tapping a wooden bowl.
    buf = silence(0.0)
    mix_into(buf, marimba(note("C6"), 0.28, decay=0.07, bright=0.3, seed=4), 0.0)
    mix_into(buf, marimba(note("G6"), 0.22, decay=0.05, bright=0.2, click=0.0, seed=5), 0.012, 0.35)
    return finish(lowpass(buf, 6000), 0.7)


def sfx_back():
    # Two quick notes stepping down: "going back".
    buf = silence(0.0)
    mix_into(buf, marimba(note("A5"), 0.16, decay=0.05, bright=0.25, seed=6), 0.0)
    mix_into(buf, marimba(note("E5"), 0.22, decay=0.07, bright=0.25, seed=7), 0.07, 0.9)
    return finish(lowpass(buf, 5000), 0.65)


def sfx_join():
    # Someone sat down on the couch: a rising three-note glass chime.
    buf = silence(0.0)
    for k, (name, at) in enumerate((("C6", 0.0), ("E6", 0.09), ("G6", 0.18))):
        mix_into(buf, glass(note(name), 0.68 - at, decay=0.22 + k * 0.04, shimmer=0.25), at, 0.8 + k * 0.1)
    return finish(lowpass(buf, 7000), 0.75)


def sfx_success():
    # A sparkly arpeggio that lands an octave up, with a glassy shimmer on top.
    buf = silence(0.0)
    steps = (("C6", 0.0), ("E6", 0.055), ("G6", 0.11), ("C7", 0.165))
    for k, (name, at) in enumerate(steps):
        mix_into(buf, marimba(note(name), 0.3, decay=0.06, bright=0.3, click=0.08, seed=10 + k), at, 0.75)
    mix_into(buf, glass(note("E7"), 0.45, decay=0.16, shimmer=0.4), 0.2, 0.45)
    mix_into(buf, glass(note("C7"), 0.48, decay=0.2, shimmer=0.2), 0.18, 0.55)
    return finish(lowpass(buf, 8000), 0.8)


def sfx_fail():
    # A gentle, low "hmm-mm": two soft rounded notes, never a buzzer.
    buf = silence(0.0)
    for name, at, length in (("E4", 0.0, 0.2), ("C4", 0.17, 0.38)):
        f = note(name)
        n = int(length * RATE)
        tone = []
        for i in range(n):
            t = i / RATE
            vib = 1 + 0.004 * math.sin(2 * math.pi * 5.5 * t)
            s = math.sin(2 * math.pi * f * vib * t) + 0.25 * math.sin(2 * math.pi * 2 * f * t)
            a = min(1.0, t / 0.025)
            r = min(1.0, (length - t) / 0.08)
            tone.append(s * a * max(0.0, r) * math.exp(-t / 0.5))
        mix_into(buf, tone, at)
    return finish(lowpass(buf, 1800), 0.45)


def sfx_whoosh():
    # Breathy noise swept through a resonant band-pass, like a card sliding past.
    rnd = random.Random(42)
    seconds = 0.5
    n = int(seconds * RATE)
    out = []
    low = band = 0.0
    for i in range(n):
        x = i / n
        center = 350 + 2600 * math.sin(math.pi * min(1.0, x * 1.1)) ** 2
        f = 2 * math.sin(math.pi * center / RATE)
        q = 0.55
        noise = rnd.random() * 2 - 1
        high = noise - low - q * band
        band += f * high
        low += f * band
        amp = math.sin(math.pi * x) ** 1.6
        out.append(band * amp)
    return finish(lowpass(out, 6000), 0.55, fade_ms=30)


def sfx_pop():
    # A soft bubble pop: a quick falling sine with a tiny click.
    seconds = 0.12
    n = int(seconds * RATE)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        f = 320 + 780 * math.exp(-t / 0.018)
        phase += 2 * math.pi * f / RATE
        out.append(math.sin(phase) * env(i, 0.001, 0.035))
    return finish(lowpass(out, 5000), 0.65, fade_ms=10)


def sfx_chime():
    # Lumo's little bell: one struck glass note with a soft fifth.
    buf = silence(0.0)
    mix_into(buf, glass(note("E6"), 0.68, decay=0.3, shimmer=0.3), 0.0)
    mix_into(buf, glass(note("B6"), 0.6, decay=0.2, shimmer=0.2), 0.03, 0.35)
    return finish(lowpass(buf, 7000), 0.7)


SOUNDS = {
    "sfx_focus": sfx_focus,
    "sfx_select": sfx_select,
    "sfx_back": sfx_back,
    "sfx_join": sfx_join,
    "sfx_success": sfx_success,
    "sfx_fail": sfx_fail,
    "sfx_whoosh": sfx_whoosh,
    "sfx_pop": sfx_pop,
    "sfx_chime": sfx_chime,
}


def write_wav(path, samples):
    frames = bytearray()
    for s in samples:
        v = int(round(max(-1.0, min(1.0, s)) * 32767))
        frames += struct.pack("<h", v)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(frames))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.join(here, "..", "app", "src", "main", "res", "raw")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=default_out, help="output directory")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for name, make in SOUNDS.items():
        samples = make()
        assert len(samples) / RATE < 0.7, name
        path = os.path.join(args.out, name + ".wav")
        write_wav(path, samples)
        print("%-12s %.2fs  %6d bytes" % (name, len(samples) / RATE, os.path.getsize(path)))


if __name__ == "__main__":
    main()
