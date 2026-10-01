#!/usr/bin/env python3
"""Generate the bundled background assets for the Android app.

Everything here is synthesised from scratch — oscillators, envelopes and filtered noise. No samples,
no sample libraries, no downloads, nothing under copyright: the output is original to this repository
and licensed with it (see LICENSES.md).

The six files mirror the web application's `public/audio/` names so a reader can line them up one for
one, but they are generated separately and are shorter, because they ship inside an APK that a person
installs on a phone.

    python3 tools/generate_bundled_assets.py

Requires `lameenc` and `numpy` (development-time only; neither is part of the app or its build).
"""
import math
import os
import struct
import wave

import lameenc
import numpy as np

SR = 44_100
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets")
DUR = 32.0
N = int(SR * DUR)


def env_ad(t, duration, attack, release):
    """A simple attack/decay envelope over a note of `duration` seconds starting at t=0."""
    if t < attack:
        return t / attack
    if t > duration - release:
        return max(0.0, (duration - t) / release)
    return 1.0


def plucked(freq, t, duration, decay=2.4, bright=0.35):
    """A nylon-string-ish pluck: a few decaying harmonics with a soft attack."""
    e = math.exp(-decay * t)
    if t < 0.004:
        e *= t / 0.004
    value = math.sin(2 * math.pi * freq * t)
    value += bright * math.sin(2 * math.pi * freq * 2.002 * t + 0.3) * math.exp(-decay * 1.4 * t)
    value += bright * 0.5 * math.sin(2 * math.pi * freq * 3.004 * t + 0.9) * math.exp(-decay * 2.1 * t)
    value += bright * 0.22 * math.sin(2 * math.pi * freq * 4.97 * t + 1.7) * math.exp(-decay * 2.9 * t)
    return value * e


def bowed(freq, t, level):
    """A cello-ish sustained tone: slow bow attack, slight vibrato, odd harmonics."""
    vib = 1.0 + 0.004 * math.sin(2 * math.pi * 4.6 * t)
    f = freq * vib
    value = math.sin(2 * math.pi * f * t)
    value += 0.42 * math.sin(2 * math.pi * 2 * f * t + 0.2)
    value += 0.20 * math.sin(2 * math.pi * 3 * f * t + 0.7)
    value += 0.09 * math.sin(2 * math.pi * 5 * f * t + 1.3)
    return value * level


def reed(freq, t, level):
    """An accordion-ish reed: detuned pair, hollow odd-harmonic emphasis."""
    f = freq * (1.0 + 0.0016 * math.sin(2 * math.pi * 5.4 * t))
    value = math.sin(2 * math.pi * f * t) + math.sin(2 * math.pi * f * 1.004 * t + 0.6)
    value += 0.35 * math.sin(2 * math.pi * 3 * f * t + 0.4)
    value += 0.12 * math.sin(2 * math.pi * 5 * f * t)
    return value * level


def one_pole_lowpass(signal, cutoff, sr=SR):
    a = math.exp(-2 * math.pi * cutoff / sr)
    out = np.empty_like(signal)
    acc = 0.0
    for i in range(signal.size):
        acc = a * acc + (1 - a) * signal[i]
        out[i] = acc
    return out


def one_pole_highpass(signal, cutoff, sr=SR):
    low = one_pole_lowpass(signal, cutoff, sr)
    return signal - low


def stereo(mono, width=1.0, decorrelate=0.0, rng=None):
    """Mono -> stereo. `decorrelate` adds independent noise to the sides for a wider image."""
    left = mono.copy()
    right = mono.copy()
    if decorrelate > 0 and rng is not None:
        jitter = rng.normal(0.0, decorrelate, mono.size)
        left = left + one_pole_highpass(jitter, 300)
        right = right - one_pole_highpass(jitter, 300)
    if width != 1.0:
        mid = (left + right) / 2
        side = (left - right) / 2 * width
        left, right = mid + side, mid - side
    return np.stack([left, right], axis=1)


def write_mp3(path, data, bitrate=128):
    pcm = np.clip(data, -1.0, 1.0)
    pcm16 = (pcm * 32767.0).astype("<i2")
    encoder = lameenc.Encoder()
    encoder.set_bit_rate(bitrate)
    encoder.set_in_sample_rate(SR)
    encoder.set_channels(2)
    encoder.set_quality(3)
    encoded = encoder.encode(pcm16.tobytes())
    encoded += encoder.flush()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(encoded)
    print(f"  {os.path.basename(path):24s} {len(encoded) / 1024:7.1f} KB  {DUR:.0f}s")


# ----------------------------------------------------------------------------- guitar
def make_guitar(rng, fade_seconds=8.0):
    """A slow, loopable nylon-guitar arpeggio in A minor."""
    chords = [
        [220.00, 261.63, 329.63, 440.00],   # Am
        [174.61, 220.00, 261.63, 349.23],   # F
        [130.81, 196.00, 246.94, 329.63],   # C
        [196.00, 246.94, 293.66, 392.00],   # G
    ]
    step = 0.5                       # one pluck every half second
    out = np.zeros(N)
    index = 0
    t = 0.0
    while t < DUR:
        chord = chords[(index // 8) % len(chords)]
        note = chord[index % len(chord)]
        octave = 0.5 if (index % 16) >= 12 else 1.0
        start = int(t * SR)
        length = int(min(3.2, DUR - t) * SR)
        for i in range(length):
            out[start + i] += plucked(note * octave, i / SR, 3.2, decay=1.5, bright=0.42) * 0.24
        index += 1
        t += step
    # A soft room so the loop has air around it.
    out += one_pole_lowpass(rng.normal(0.0, 0.02, N), 1800)
    out = np.concatenate([out, out[: int(fade_seconds * SR)]])
    out = one_pole_lowpass(out, 9_000)[:N]
    return stereo(out, width=1.05, decorrelate=0.004, rng=rng)


# ----------------------------------------------------------------------------- cello
def make_cello(rng, fade_seconds=8.0):
    """Two sustained low strings moving through a four-bar progression."""
    movement = [
        [110.00, 164.81],   # A2 E3
        [87.31, 130.81],    # F2 C3
        [98.00, 146.83],    # G2 D3
        [82.41, 123.47],    # E2 B2
    ]
    bar = 4.0
    out = np.zeros(N)
    for i in range(N):
        t = i / SR
        bar_index = int(t // bar) % len(movement)
        local = t - (t // bar) * bar
        swell = 0.5 - 0.5 * math.cos(2 * math.pi * local / bar)
        level = 0.16 * (0.35 + 0.65 * swell)
        value = bowled = bowed(movement[bar_index][0], t, level)
        value += bowed(movement[bar_index][1], t, level * 0.55)
        # A touch of bow noise keeps it from sounding like a synth pad.
        value += rng.normal(0.0, 0.0016)
        out[i] = value
    out += one_pole_lowpass(rng.normal(0.0, 0.012, N), 900)
    out = np.concatenate([out, out[: int(fade_seconds * SR)]])
    out = one_pole_lowpass(out, 6_000)[:N]
    return stereo(out, width=1.0, decorrelate=0.005, rng=rng)


# ----------------------------------------------------------------------------- accordion
def make_accordion(rng, fade_seconds=8.0):
    """A waltzing reed organ, slightly out of tune with itself."""
    tune = [
        [220.00, 261.63, 329.63],
        [196.00, 246.94, 293.66],
        [174.61, 220.00, 261.63],
        [164.81, 207.65, 246.94],
    ]
    bar = 2.6667               # a 3/4 waltz at this tempo
    out = np.zeros(N)
    for i in range(N):
        t = i / SR
        bar_index = int(t // bar) % len(tune)
        local = t - (t // bar) * bar
        # Bellows: in for two beats, out for one.
        breath = 0.55 + 0.45 * math.sin(2 * math.pi * local / bar - math.pi / 2)
        chord = tune[bar_index]
        value = 0.0
        for k, note in enumerate(chord):
            value += reed(note, t, 0.10 * breath * (1.0 - 0.12 * k))
        out[i] = value
    out = one_pole_lowpass(out, 7_500)
    out += one_pole_lowpass(rng.normal(0.0, 0.008, N), 2_500)
    out = np.concatenate([out, out[: int(fade_seconds * SR)]])
    return stereo(out, width=0.98, decorrelate=0.003, rng=rng)


# ----------------------------------------------------------------------------- rain
def make_rain(rng):
    """Rain on a window: filtered noise, sparse droplets, distant low rumble. Loops seamlessly."""
    base = rng.normal(0.0, 0.30, N)
    body = one_pole_lowpass(base, 4_200)
    # A sparse high layer reads as droplets hitting glass.
    droplets = np.zeros(N)
    count = int(DUR * 26)
    for _ in range(count):
        start = int(rng.random() * (N - 900))
        length = 200 + int(rng.random() * 700)
        amp = 0.05 + rng.random() * 0.16
        decay = 30.0 + rng.random() * 60.0
        for i in range(length):
            droplets[start + i] += rng.normal(0.0, amp) * math.exp(-decay * i / SR)
    high = one_pole_highpass(droplets, 2_500)
    rumble = one_pole_lowpass(rng.normal(0.0, 1.0, N), 90)
    out = one_pole_highpass(body, 120) * 0.55 + high + rumble * 0.05
    out = one_pole_lowpass(out, 11_000)
    out = np.concatenate([out, out[: int(8 * SR)]])
    return stereo(out, width=1.15, decorrelate=0.03, rng=rng)


# ----------------------------------------------------------------------------- tape room
def make_tape_room(rng):
    """A room tone: the hum of a quiet space with a machine in it. Loops seamlessly."""
    hum = np.zeros(N)
    for i in range(N):
        t = i / SR
        hum[i] = 0.030 * math.sin(2 * math.pi * 50.0 * t) + 0.014 * math.sin(2 * math.pi * 100.0 * t + 0.4)
    air = one_pole_lowpass(rng.normal(0.0, 0.06, N), 2_200)
    air = one_pole_highpass(air, 200)
    # A slow "breathing" of the room, at a period that divides the loop length exactly.
    period = DUR / 4.0
    breath = 0.7 + 0.3 * np.sin(2 * np.pi * np.arange(N) / (period * SR))
    out = (hum + air) * breath
    out = one_pole_lowpass(out, 7_000)
    return stereo(out, width=0.9, decorrelate=0.006, rng=rng)


# ----------------------------------------------------------------------------- needle drop
def make_needle_drop(rng):
    """A needle touching a still record, then a run-in groove. Three seconds, one shot."""
    total = int(3.0 * SR)
    out = np.zeros(total)
    # 1. The physical contact: a short broadband thump.
    for i in range(int(0.06 * SR)):
        t = i / SR
        out[i] += rng.normal(0.0, 1.0) * math.exp(-70 * t) * 0.55
    # 2. The arm settling: a tiny second bump 80 ms later.
    offset = int(0.08 * SR)
    for i in range(int(0.05 * SR)):
        t = i / SR
        out[offset + i] += rng.normal(0.0, 1.0) * math.exp(-90 * t) * 0.22
    # 3. The groove: surface noise that fades up and stays until the end.
    surface = one_pole_highpass(rng.normal(0.0, 0.05, total), 800)
    surface = one_pole_lowpass(surface, 9_000)
    for i in range(total):
        t = i / SR
        gate = min(1.0, max(0.0, (t - 0.10) / 0.35))
        out[i] += surface[i] * gate * 0.9
    # 4. A few ticks as the lead-in passes under the stylus.
    for _ in range(9):
        start = int((0.4 + rng.random() * 2.4) * SR)
        amp = 0.10 + rng.random() * 0.28
        for i in range(int(0.004 * SR)):
            out[start + i] += rng.normal(0.0, 1.0) * math.exp(-40 * i / SR) * amp
    out = one_pole_lowpass(out, 12_000)
    fade = int(0.25 * SR)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    return stereo(out, width=1.0, decorrelate=0.02, rng=rng)


def main():
    print(f"Writing bundled assets to {os.path.normpath(ROOT)}")
    audio = os.path.join(ROOT, "audio")
    jobs = [
        ("bg-guitar.mp3", lambda: make_guitar(np.random.default_rng(11))),
        ("bg-cello.mp3", lambda: make_cello(np.random.default_rng(22))),
        ("bg-accordion.mp3", lambda: make_accordion(np.random.default_rng(33))),
        ("bg-rain.mp3", lambda: make_rain(np.random.default_rng(44))),
        ("bg-tape-room.mp3", lambda: make_tape_room(np.random.default_rng(55))),
        ("needle-drop.mp3", lambda: make_needle_drop(np.random.default_rng(66))),
    ]
    for name, build in jobs:
        # Peak-normalise so every bed sits at a predictable level under the voice.
        data = build()
        peak = float(np.max(np.abs(data)))
        if peak > 0:
            data = data / peak * 0.82
        write_mp3(os.path.join(audio, name), data)
    print("done")


if __name__ == "__main__":
    main()
