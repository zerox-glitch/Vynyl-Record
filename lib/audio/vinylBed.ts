/**
 * Vinyl-bed generator.
 *
 * A recording mastering chain needs three layered imperfections:
 *   - continuous surface noise ("hiss")        : broadband filtered noise
 *   - sparse crackle                            : short transients, irregular
 *   - occasional pops                            : louder transients, very rare
 *
 * A looping `crackle.mp3` is the worst way to add crackle: listeners will
 * eventually hear the period. We synthesize the bed deterministically
 * from a Mulberry32 stream keyed by the recording id, so:
 *   - The same recording + preset always produces the same bed
 *   - Different recordings sound distinct because the seed is different
 *   - The bed is exactly the duration of the voice master, so there is
 *     never a loop seam in the final render
 *
 * Output is a stereo 16-bit PCM WAV (the format FFmpeg reads by default).
 * We render a separate perception layer for surface vs crackle, then mix.
 */
import fs from 'node:fs';
import { childSeed, mulberry32 } from './random';
import { type VinylPreset } from './presets';

const SAMPLE_RATE = 44_100;
type Float = number;
type Stereo = [Float, Float];

interface GeneratedBed {
  filepath: string;
  durationSeconds: number;
  diagnostics: {
    noiseEnabled: boolean;
    crackleCount: number;
    popCount: number;
  };
}

function writeStereoWav(filepath: string, samples: Stereo[]): void {
  const numChannels = 2;
  const bitsPerSample = 16;
  const byteRate = (SAMPLE_RATE * numChannels * bitsPerSample) / 8;
  const dataSize = samples.length * numChannels * (bitsPerSample / 8);
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + dataSize, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(numChannels, 22);
  buf.writeUInt32LE(SAMPLE_RATE, 24);
  buf.writeUInt32LE(byteRate, 28);
  buf.writeUInt16LE((numChannels * bitsPerSample) / 8, 32);
  buf.writeUInt16LE(bitsPerSample, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(dataSize, 40);

  let o = 44;
  for (const [l, r] of samples) {
    const li = Math.min(32767, Math.max(-32768, Math.round(l * 32767)));
    const ri = Math.min(32767, Math.max(-32768, Math.round(r * 32767)));
    buf.writeInt16LE(li, o);
    buf.writeInt16LE(ri, o + 2);
    o += 4;
  }
  fs.writeFileSync(filepath, buf);
}

/** 1D pink noise, Mulberry32-driven (Paul Kellet economy).  Returns ~[-1, 1]. */
function pinkNoise(rand: () => number): () => number {
  let b0 = 0,
    b1 = 0,
    b2 = 0,
    b3 = 0,
    b4 = 0,
    b5 = 0,
    b6 = 0;
  return () => {
    const w = rand() * 2 - 1;
    b0 = 0.99886 * b0 + w * 0.0555179;
    b1 = 0.99332 * b1 + w * 0.0750759;
    b2 = 0.969 * b2 + w * 0.153852;
    b3 = 0.8665 * b3 + w * 0.3104856;
    b4 = 0.55 * b4 + w * 0.5329522;
    b5 = -0.7616 * b5 - w * 0.016898;
    return (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
  };
}

function dbToLin(db: number): number {
  return Math.pow(10, db / 20);
}

/** Plain one-pole high-pass on a stereo buffer (s in, s out — in place). */
function stereoHighPass(buf: Stereo[], cutoffHz: number, sampleRate = SAMPLE_RATE): void {
  if (cutoffHz <= 0) return;
  const dt = 1 / sampleRate;
  const rc = 1 / (2 * Math.PI * cutoffHz);
  const a = rc / (rc + dt);
  let yl = 0,
    yr = 0,
    pl = 0,
    pr = 0;
  for (let i = 0; i < buf.length; i++) {
    const xl = buf[i][0];
    const xr = buf[i][1];
    yl = a * (yl + xl - pl);
    yr = a * (yr + xr - pr);
    pl = xl;
    pr = xr;
    buf[i] = [yl, yr];
  }
}

/** Plain one-pole low-pass on a stereo buffer (in place). */
function stereoLowPass(buf: Stereo[], cutoffHz: number, sampleRate = SAMPLE_RATE): void {
  if (cutoffHz <= 0) return;
  const dt = 1 / sampleRate;
  const rc = 1 / (2 * Math.PI * cutoffHz);
  const a = dt / (rc + dt);
  let yl = 0,
    yr = 0;
  for (let i = 0; i < buf.length; i++) {
    yl += a * (buf[i][0] - yl);
    yr += a * (buf[i][1] - yr);
    buf[i] = [yl, yr];
  }
}

/** Add a short decaying noise click to the buffer. Mid / High / Stereo split. */
function addClick(
  buf: Stereo[],
  start: number,
  durationSec: number,
  amplitude: Float,
  lRand: () => Float,
  rRand: () => Float,
): void {
  const total = buf.length;
  const samples = Math.max(1, Math.floor(durationSec * SAMPLE_RATE));
  const startIdx = Math.max(0, Math.floor(start * SAMPLE_RATE));
  const endIdx = Math.min(total, startIdx + samples);
  // Tiny audible thump + sparse HF tick inside.
  for (let i = startIdx; i < endIdx; i++) {
    const t = (i - startIdx) / Math.max(1, samples - 1);
    // Dual envelope: fast out, no in (percussive)
    const env = Math.exp(-t * 24);
    const l = (lRand() * 2 - 1) * env * amplitude;
    const r = (rRand() * 2 - 1) * env * amplitude;
    buf[i] = [buf[i][0] + l, buf[i][1] + r];
  }
}

/** Add a longer decaying broadband click with mid-frequency body. */
function addPop(
  buf: Stereo[],
  start: number,
  durationSec: number,
  amplitude: Float,
  middleFreqHz: number,
): void {
  const total = buf.length;
  const samples = Math.max(1, Math.floor(durationSec * SAMPLE_RATE));
  const startIdx = Math.max(0, Math.floor(start * SAMPLE_RATE));
  const endIdx = Math.min(total, startIdx + samples);
  // One-pole lerp-like progression for sample-accurate envelope.
  let yl1 = 0,
    yr1 = 0,
    yl2 = 0,
    yr2 = 0;
  const a1 = Math.exp(-30 / SAMPLE_RATE);
  const a2 = Math.exp(-6 / SAMPLE_RATE);
  // Two-pole low-pass to emphasise midrange character (body)
  let last1 = 0,
    last2 = 0;
  const aPin = Math.exp(-1 / SAMPLE_RATE);
  // Simulate a mid body: 800 Hz centre with a couple of harmonics
  for (let i = startIdx; i < endIdx; i++) {
    const t = (i - startIdx) / Math.max(1, samples - 1);
    const env = Math.exp(-t * 12) * Math.sqrt(t + 0.05); // initial spike then decay
    const midHead = Math.sin(2 * Math.PI * middleFreqHz * (i / SAMPLE_RATE));
    const l = midHead * env * amplitude;
    const r = midHead * env * amplitude;
    // One-step low-pass smoothing
    last1 = aPin * last1 + (1 - aPin) * l;
    last2 = aPin * last2 + (1 - aPin) * r;
    buf[i] = [buf[i][0] + last1, buf[i][1] + last2];
    // keep linter quiet (unused signal variables)
    void [yl1, yr1, yl2, yr2];
    void [a1, a2];
  }
}

/** Posterior de-clicker. Hard-clip the bed so it can't dominate the master. */
function softClipSamples(buf: Stereo[], ceiling: number): void {
  const clip = (x: Float) => {
    const t = x / ceiling;
    return ceiling * Math.tanh(t);
  };
  for (let i = 0; i < buf.length; i++) {
    buf[i] = [clip(buf[i][0]), clip(buf[i][1])];
  }
}

/** Mute the bed for very-low-level snow that's inaudible. */
function normalizeBedFloor(_buf: Stereo[], _floor = 0.0001): void {
  // Skip \u2014 the limiter downstream does this. Kept as a named seam should a
  // future profile want a different noise topology.
  void [_buf, _floor];
}

/**
 * Render the full vinyl bed as a stereo WAV and return its path.
 *   1. Pink-noise surface, shaped with the preset's filter profile.
 *   2. Crankles: stochastic short transients, density per minute.
 *   3. Pops: rarer, longer broadband transients with mid-range body.
 *   4. Stereo decorrelation (left != right).
 *   5. Soft-clip guard so the bed itself cannot clip the master bus.
 *   6. Bed never loops \u2014 exact duration of the voice master.
 */
export function renderVinylBed(
  durationSeconds: number,
  preset: VinylPreset,
  seed: number,
  outputPath: string,
): GeneratedBed {
  const noiseRand = mulberry32(childSeed(seed, 'noise'));
  const crackleRand = mulberry32(childSeed(seed, 'crackle'));
  const popRand = mulberry32(childSeed(seed, 'pops'));
  const stereoRand = mulberry32(childSeed(seed, 'stereo-correlation'));

  const total = Math.max(1, Math.floor(durationSeconds * SAMPLE_RATE));
  const samples: Stereo[] = new Array(total);
  for (let i = 0; i < total; i++) samples[i] = [0, 0];

  let noiseEnabled = false;

  /* ----- 1. Surface noise layer (vinyl hiss) ----- */
  if (preset.surface.enabled) {
    noiseEnabled = true;
    const level = dbToLin(preset.surface.levelDb);
    const left = pinkNoise(noiseRand);
    const right = pinkNoise(noiseRand);
    for (let i = 0; i < total; i++) {
      const l = left();
      const r = right();
      const correlation = stereoRand() * 0.35 - 0.175; // +/- 17.5%
      samples[i][0] += l * level;
      samples[i][1] += (r + correlation) * level;
    }

    if (preset.surface.filter === 'highpass-low') {
      stereoHighPass(samples, 220);
    } else if (preset.surface.filter === 'highpass-flat') {
      stereoHighPass(samples, 80);
    } else if (preset.surface.filter === 'lowpass-only') {
      stereoLowPass(samples, 4500);
    } else {
      stereoHighPass(samples, 90);
      stereoLowPass(samples, 17500);
    }
  }

  /* ----- 2. Crackle layer (stochastic transients) ----- */
  let crackleCount = 0;
  if (preset.crackle.enabled && preset.crackle.densityPerMin > 0) {
    const expected = (durationSeconds / 60) * preset.crackle.densityPerMin;
    const jitter = 0.4;
    const finalCount = Math.max(0, Math.floor(expected * (1 - jitter + crackleRand() * jitter * 2)));

    const events: { when: number; amp: number; wideT: number }[] = [];
    for (let i = 0; i < finalCount; i++) {
      events.push({
        when: crackleRand() * durationSeconds,
        amp: 0.05 + crackleRand() * preset.crackle.intensity * 0.85,
        wideT: 0.002 + crackleRand() * 0.012,
      });
    }
    const clusterCount = Math.min(6, Math.max(1, Math.floor(preset.crackle.densityPerMin / 6)));
    for (let c = 0; c < clusterCount; c++) {
      const anchor = crackleRand() * durationSeconds;
      const spread = 0.06;
      const inside = 2 + Math.floor(crackleRand() * 4);
      for (let k = 0; k < inside; k++) {
        events.push({
          when: anchor + (crackleRand() - 0.5) * spread,
          amp: 0.10 + crackleRand() * preset.crackle.intensity * 0.7,
          wideT: 0.001 + crackleRand() * 0.006,
        });
      }
    }

    let placed = 0;
    for (const e of events) {
      if (e.when <= 0.02 || e.when >= durationSeconds - 0.02) continue;
      addClick(
        samples,
        e.when,
        e.wideT,
        e.amp,
        () => crackleRand() * 2 - 1,
        () => crackleRand() * 2 - 1,
      );
      placed += 1;
    }
    crackleCount = placed;
  }

  /* ----- 3. Pops layer (rare broadband bumps) ----- */
  let popCount = 0;
  if (preset.pops.enabled && preset.pops.densityPerMin > 0) {
    const expected = (durationSeconds / 60) * preset.pops.densityPerMin;
    const finalCount = Math.max(0, Math.floor(expected + (popRand() - 0.5)));
    for (let i = 0; i < finalCount; i++) {
      const t = popRand() * durationSeconds;
      if (t <= 0.05 || t >= durationSeconds - 0.05) continue;
      const amp = 0.15 + popRand() * preset.pops.intensity * 0.85;
      const middleHz = 380 + popRand() * 240;
      addPop(samples, t, 0.03, amp, middleHz);
      popCount += 1;
    }
  }

  /* ----- 4. Soft-clip safety ----- */
  // Bed peaks never exceed -1 dBFS. The master limiter compresses further.
  softClipSamples(samples, 0.894);

  writeStereoWav(outputPath, samples);

  return {
    filepath: outputPath,
    durationSeconds,
    diagnostics: { noiseEnabled, crackleCount, popCount },
  };
}

/** True when the preset actually mutates the audio (has any enabled layer). */
export function presetChangesAudio(preset: VinylPreset): boolean {
  return preset.surface.enabled ||
    preset.crackle.enabled ||
    preset.pops.enabled;
}

