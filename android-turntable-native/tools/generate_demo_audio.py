"""Synthesise the bundled demo record: a warm, slow, loopable ambient chord progression plus
vinyl surface noise. Everything is generated here - no samples, no downloads."""
import math, struct, random, wave, io, os
import lameenc

SR = 44100
DUR = 32.0          # seconds
N = int(SR * DUR)

def adsr(t, dur, a=0.9, r=1.6):
    if t < a: return t / a
    if t > dur - r: return max(0.0, (dur - t) / r)
    return 1.0

# Chord progression (Am9 - Fmaj7 - Cmaj9 - G6), one bar every 4 seconds, two rounds.
CHORDS = [
    [220.00, 261.63, 329.63, 493.88],   # A3 C4 E4 B4  (Am9 without the 7th)
    [174.61, 220.00, 261.63, 349.23],   # F3 A3 C4 F4
    [130.81, 196.00, 246.94, 329.63],   # C3 G3 B3 E4
    [196.00, 246.94, 293.66, 392.00],   # G3 B3 D4 G4
]
BAR = 4.0

def note(freq, t, dur):
    """A soft, slightly detuned electric-piano-ish voice with two harmonics."""
    env = adsr(t, dur)
    if env <= 0: return 0.0
    vib = 1.0 + 0.0022 * math.sin(2 * math.pi * 5.1 * t)
    f = freq * vib
    s = math.sin(2 * math.pi * f * t)
    s += 0.34 * math.sin(2 * math.pi * 2.002 * f * t + 0.4)
    s += 0.12 * math.sin(2 * math.pi * 3.001 * f * t + 1.1)
    # gentle tremolo so the pad breathes
    s *= 1.0 + 0.06 * math.sin(2 * math.pi * 0.31 * t)
    return 0.11 * env * s

rng = random.Random(20260930)
samples = []
# pre-compute crackle positions
crackle = set()
for _ in range(int(DUR * 26)):
    crackle.add(int(rng.uniform(0, N - 1)))

lp = 0.0
for i in range(N):
    t = i / SR
    bar = int(t // BAR) % len(CHORDS)
    bar_t = t % BAR
    value = 0.0
    for idx, freq in enumerate(CHORDS[bar]):
        value += note(freq, bar_t + 0.02 * idx, BAR * 0.98)
    # sustained sub for weight
    value += 0.05 * math.sin(2 * math.pi * (CHORDS[bar][0] / 2.0) * t) * adsr(bar_t, BAR, 1.1, 1.8)

    # vinyl surface noise: filtered white noise plus sparse crackle
    white = rng.uniform(-1.0, 1.0)
    lp += (white - lp) * 0.22
    value += 0.013 * lp
    if i in crackle:
        value += 0.16 * rng.choice([-1.0, 1.0]) * rng.uniform(0.3, 1.0)

    # head/tail fade so looping and replay are click-free
    fade = 1.0
    if t < 0.35: fade = t / 0.35
    if t > DUR - 0.9: fade = max(0.0, (DUR - t) / 0.9)
    value *= fade

    samples.append(max(-1.0, min(1.0, value)))

# normalise to -3 dBFS peak
peak = max(abs(s) for s in samples) or 1.0
gain = 0.70 / peak
pcm = bytearray()
for s in samples:
    v = int(max(-32768, min(32767, s * gain * 32767)))
    # duplicate to stereo with a hint of width
    pcm += struct.pack('<hh', v, max(-32768, min(32767, int(v * 0.985))))

encoder = lameenc.Encoder()
encoder.set_bit_rate(112)
encoder.set_in_sample_rate(SR)
encoder.set_channels(2)
encoder.set_quality(2)
mp3 = encoder.encode(bytes(pcm))
mp3 += encoder.flush()

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "demo-side-a.mp3")
with open(out, "wb") as fh:
    fh.write(mp3)
print("wrote", out, len(mp3), "bytes,", DUR, "seconds")
