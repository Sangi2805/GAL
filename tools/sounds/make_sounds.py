#!/usr/bin/env python3
"""
Writes the stage's sound effects to app/src/main/res/raw/scene_*.wav.

Every sound is made here from sine waves and filtered noise, so they are ours: no samples, no recordings, and
nothing modelled on any game's sounds. Plain Python, no packages needed. Re-run after changing a recipe:

    python3 tools/sounds/make_sounds.py

The output is 16-bit mono PCM at 22050 Hz, which SoundPool plays directly. Each file is a few kilobytes.
"""
import math
import random
import struct
import wave
from pathlib import Path

RATE = 22050
HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "app" / "src" / "main" / "res" / "raw"


def silence(seconds):
    return [0.0] * int(seconds * RATE)


def mix(*tracks):
    n = max(len(t) for t in tracks)
    out = [0.0] * n
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def env(i, n, attack=0.004, decay=None):
    """Quick attack, then an exponential decay with time constant `decay` (seconds), and a short fade at the end."""
    t = i / RATE
    a = min(1.0, t / attack) if attack > 0 else 1.0
    d = math.exp(-t / decay) if decay else 1.0
    tail = min(1.0, (n - i) / (0.004 * RATE))
    return a * d * tail


def sweep(f0, f1, seconds, decay, amp=1.0, curve=1.0):
    """Sine whose pitch glides from f0 to f1."""
    n = int(seconds * RATE)
    out, phase = [], 0.0
    for i in range(n):
        x = (i / n) ** curve
        f = f0 + (f1 - f0) * x
        phase += 2 * math.pi * f / RATE
        out.append(amp * math.sin(phase) * env(i, n, decay=decay))
    return out


def partials(freqs, seconds, decays, amps):
    """A struck object: a few inharmonic partials, each with its own decay."""
    n = int(seconds * RATE)
    out = [0.0] * n
    for f, d, a in zip(freqs, decays, amps):
        for i in range(n):
            out[i] += a * math.sin(2 * math.pi * f * i / RATE) * env(i, n, attack=0.0015, decay=d)
    return out


def noise(seconds, decay, amp=1.0, lowpass=0.3, seed=1, attack=0.002):
    """White noise through a one-pole low-pass. Smaller `lowpass` is darker."""
    rnd = random.Random(seed)
    n = int(seconds * RATE)
    out, y = [], 0.0
    for i in range(n):
        y += lowpass * (rnd.uniform(-1, 1) - y)
        out.append(amp * y * env(i, n, attack=attack, decay=decay))
    return out


def band_noise_sweep(f0, f1, seconds, decay, amp=1.0, seed=2):
    """Noise through a resonant band-pass whose centre glides from f0 to f1: an airy whoosh."""
    rnd = random.Random(seed)
    n = int(seconds * RATE)
    out = []
    low = band = 0.0
    q = 0.22
    for i in range(n):
        f = f0 + (f1 - f0) * (i / n)
        k = 2 * math.sin(math.pi * f / RATE)
        x = rnd.uniform(-1, 1)
        low += k * band
        high = x - low - q * band
        band += k * high
        out.append(amp * band * env(i, n, attack=0.02, decay=decay))
    return out


def normalise(samples, peak):
    top = max(abs(s) for s in samples) or 1.0
    return [s / top * peak for s in samples]


def write(name, samples, peak=0.8):
    OUT.mkdir(parents=True, exist_ok=True)
    data = normalise(samples, peak)
    path = OUT / f"scene_{name}.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in data))
    print(f"{path.relative_to(HERE.parents[1])}: {len(data) / RATE:.2f} s, {path.stat().st_size} bytes")


def crackle(seconds, count, seed):
    """Splinters: short dark noise bursts at random moments, getting sparser."""
    rnd = random.Random(seed)
    out = silence(seconds)
    for k in range(count):
        start = int(RATE * seconds * (rnd.random() ** 1.8) * 0.8)
        burst = noise(0.03 + rnd.random() * 0.03, 0.008 + rnd.random() * 0.01, amp=0.5 + rnd.random() * 0.5,
                      lowpass=0.25 + rnd.random() * 0.4, seed=seed * 100 + k)
        for i, v in enumerate(burst):
            if start + i < len(out):
                out[start + i] += v
    return out


def main():
    # Jump: an airy upward whoosh with a faint soft body under it. No chiptune square waves.
    write("jump", mix(band_noise_sweep(450, 1900, 0.16, decay=0.09, amp=1.0), sweep(260, 420, 0.12, decay=0.05, amp=0.25)), peak=0.55)

    # Landing: a soft, low puff.
    write("land", mix(sweep(120, 70, 0.12, decay=0.035, amp=1.0), noise(0.06, 0.012, amp=0.5, lowpass=0.12, seed=3)), peak=0.6)

    # The crate thuds onto the ground: heavier and woody.
    write("thud", mix(
        sweep(95, 62, 0.26, decay=0.07, amp=1.0),
        partials([228, 517], 0.2, [0.03, 0.018], [0.45, 0.25]),
        noise(0.05, 0.01, amp=0.5, lowpass=0.2, seed=4),
    ), peak=0.8)

    # Head meets crate from below: a hollow wooden tock.
    write("bump", mix(
        partials([540, 1290, 2210], 0.14, [0.035, 0.02, 0.01], [1.0, 0.5, 0.2]),
        noise(0.02, 0.004, amp=0.6, lowpass=0.5, seed=5),
    ), peak=0.7)

    # Hammer blow: a sharper knock with a click on top. Each later blow plays a little higher (SceneSounds).
    write("hit", mix(
        partials([410, 980, 1730, 2650], 0.18, [0.04, 0.025, 0.012, 0.006], [1.0, 0.6, 0.35, 0.15]),
        noise(0.015, 0.003, amp=0.9, lowpass=0.7, seed=6),
        sweep(150, 90, 0.1, decay=0.03, amp=0.5),
    ), peak=0.8)

    # The crate bursts: splinters over a low thump.
    write("break", mix(
        crackle(0.38, 14, seed=7),
        sweep(110, 60, 0.18, decay=0.05, amp=0.7),
        noise(0.3, 0.08, amp=0.35, lowpass=0.08, seed=8),
    ), peak=0.75)

    # The app pops out: a round, falling bloop.
    write("pop", mix(sweep(880, 330, 0.11, decay=0.05, amp=1.0, curve=0.6), sweep(1760, 660, 0.06, decay=0.02, amp=0.15, curve=0.6)), peak=0.6)


if __name__ == "__main__":
    main()
