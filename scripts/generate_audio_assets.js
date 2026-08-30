/**
 * generate_audio_assets.js
 * ----------------------------------------------------------------------------
 * Synthesizes every bundled audio file (demos, filters, ambient beds, crackle,
 * needle drop). Output is 192kbps mono MP3 in public/audio/.
 *
 * The previous version used a "spoken tone" of pure sines + a single resonant
 * 480 Hz tone + a periodic 4.5 Hz pitch sine — that combination wobbled like a
 * space alien. This version models each source physically:
 *
 *   - Voice: sawtooth glottal source → 4 formant bandpass filters (F1–F4)
 *     → breath noise floor → small random pitch jitter (no periodic wobble)
 *     → per-word pitch contour (rising on stress, falling at phrase ends)
 *     → consonant noise burst at word starts.
 *   - Guitar: Karplus–Strong plucked-string loop (delay line + lowpass +
 *     comb feedback) so the string has a real pluck attack and decay.
 *   - Cello: sawtooth bowed-string with slow vibrato (~5 Hz, ±0.5%), bowed
 *     noise floor, and a stretched-partial (inharmonic) spectrum.
 *   - Accordion: two slightly detuned reeds (real tremolo/beating) driven
 *     through a bellows amplitude envelope with a sharp reed-click attack.
 *   - Rain: Paul Kellet's pink noise (proper 1/f spectrum) plus a low shelf
 *     that gives the gentle room rumble, and very rare drop clicks.
 *
 * All clips are loopable where they're used as beds (crackle, rain, accordion,
 * guitar, cello, tape room).
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const OUTPUT_DIR = path.join(__dirname, '../public/audio');
if (!fs.existsSync(OUTPUT_DIR)) {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
}
const TMP_DIR = path.join(__dirname, '../tmp');
if (!fs.existsSync(TMP_DIR)) fs.mkdirSync(TMP_DIR, { recursive: true });

function resolveFfmpeg() {
  try {
    return require('@ffmpeg-installer/ffmpeg').path;
  } catch {
    try { return require('ffmpeg-static'); } catch { return 'ffmpeg'; }
  }
}

function writeWavFile(filename, sampleRate, samples) {
  const numChannels = 1;
  const bytesPerSample = 2; // 16-bit PCM
  const blockAlign = numChannels * bytesPerSample;
  const byteRate = sampleRate * blockAlign;
  const dataSize = samples.length * bytesPerSample;
  const buffer = Buffer.alloc(44 + dataSize);

  buffer.write('RIFF', 0);
  buffer.writeUInt32LE(36 + dataSize, 4);
  buffer.write('WAVE', 8);

  buffer.write('fmt ', 12);
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(numChannels, 22);
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(byteRate, 28);
  buffer.writeUInt16LE(blockAlign, 32);
  buffer.writeUInt16LE(16, 34);

  buffer.write('data', 36);
  buffer.writeUInt32LE(dataSize, 40);

  for (let i = 0; i < samples.length; i++) {
    let s = Math.max(-1, Math.min(1, samples[i]));
    const val = s < 0 ? s * 0x8000 : s * 0x7fff;
    buffer.writeInt16LE(Math.floor(val), 44 + i * 2);
  }

  const wavPath = path.join(TMP_DIR, `${filename}.wav`);
  fs.writeFileSync(wavPath, buffer);

  const mp3Path = path.join(OUTPUT_DIR, `${filename}.mp3`);
  try {
    execFileSync(resolveFfmpeg(), [
      '-y', '-loglevel', 'error',
      '-i', wavPath,
      '-codec:a', 'libmp3lame',
      '-b:a', '192k',
      '-ar', '44100',
      '-ac', '1',
      mp3Path,
    ]);
    console.log(`Generated: ${mp3Path}`);
  } catch (err) {
    console.error(`FFmpeg error converting ${filename}:`, err.message);
  }
}

/**
 * Mix a voice sample with a crackle/rain bed using ffmpeg.
 *
 * The original code used a complex `amix=normalize=0` filtergraph that the
 * bundled 2018 ffmpeg doesn't accept. We now apply each side's pre-mastering
 * chain, then mix them and let `alimiter` catch the peaks.
 */
function masterComparisonSample(filename, voiceFilter, bedFilter) {
  const ffmpegPath = resolveFfmpeg();
  const rawPath = path.join(OUTPUT_DIR, 'demo-raw-sample.mp3');
  const cracklePath = path.join(OUTPUT_DIR, 'crackle-vintage.mp3');
  const rainPath = path.join(OUTPUT_DIR, 'bg-rain.mp3');
  const bedPath = filename === 'demo-gramophone-sample' ? cracklePath : rainPath;
  const outputPath = path.join(OUTPUT_DIR, `${filename}.mp3`);
  // Voice+bed weighted mix: bed is background (~0.42), voice is foreground (~0.85),
  // limiter catches anything > 0.92 to prevent clipping.
  const filterGraph =
    `[0:a]${voiceFilter}[voice];` +
    `[1:a]${bedFilter}[bed];` +
    `[voice]volume=0.85[v];` +
    `[bed]volume=0.42[b];` +
    `[v][b]amix=inputs=2:duration=first:dropout_transition=0,alimiter=limit=0.92[outa]`;

  try {
    execFileSync(ffmpegPath, [
      '-y', '-loglevel', 'error',
      '-i', rawPath,
      '-stream_loop', '-1',
      '-i', bedPath,
      '-filter_complex', filterGraph,
      '-map', '[outa]',
      '-t', '6',
      '-codec:a', 'libmp3lame',
      '-b:a', '192k',
      '-ar', '44100',
      '-ac', '2',
      outputPath,
    ]);
    console.log(`Generated mastered comparison: ${outputPath}`);
  } catch (err) {
    console.error(`FFmpeg comparison mastering error for ${filename}:`, err.message);
    throw err;
  }
}

const SR = 44100;

/* ------------------------------------------------------------------ *
 * DSP building blocks                                                 *
 * ------------------------------------------------------------------ */

/** One-pole lowpass (no clicks on parameter changes). */
function lp1(prev, input, cutoffHz, sampleRate) {
  const rc = 1 / (2 * Math.PI * Math.max(40, cutoffHz));
  const dt = 1 / sampleRate;
  const a = dt / (rc + dt);
  return prev + a * (input - prev);
}

/** One-pole highpass. */
function hp1(prevIn, prevOut, input, cutoffHz, sampleRate) {
  const rc = 1 / (2 * Math.PI * Math.max(20, cutoffHz));
  const dt = 1 / sampleRate;
  const a = rc / (rc + dt);
  const out = a * (prevOut + input - prevIn);
  return { prevIn: input, prevOut: out };
}

/** Second-order resonant bandpass (BiQuad). */
function biquad() {
  return { x1: 0, x2: 0, y1: 0, y2: 0 };
}
function biquadBandpass(state, input, freq, q, sampleRate) {
  const w0 = 2 * Math.PI * freq / sampleRate;
  const cw = Math.cos(w0);
  const sw = Math.sin(w0);
  const alpha = sw / (2 * Math.max(0.5, q));
  const b0 = alpha, b1 = 0, b2 = -alpha;
  const a0 = 1 + alpha, a1 = -2 * cw, a2 = 1 - alpha;
  const y = (b0 / a0) * input + (b1 / a0) * state.x1 + (b2 / a0) * state.x2
          - (a1 / a0) * state.y1 - (a2 / a0) * state.y2;
  state.x2 = state.x1; state.x1 = input;
  state.y2 = state.y1; state.y1 = y;
  return y;
}

/** Paul Kellet's pink-noise filter — gives a smooth 1/f spectrum. */
function makePinkNoise() {
  let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
  return () => {
    const white = Math.random() * 2 - 1;
    b0 = 0.99886 * b0 + white * 0.0555179;
    b1 = 0.99332 * b1 + white * 0.0750759;
    b2 = 0.96900 * b2 + white * 0.1538520;
    b3 = 0.86650 * b3 + white * 0.3104856;
    b4 = 0.55000 * b4 + white * 0.5329522;
    b5 = -0.7616 * b5 - white * 0.0168980;
    return (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11;
  };
}

/** Soft saturator that fattens without nasty digital clipping. */
function tanh(x) { return Math.tanh(x); }

/* ------------------------------------------------------------------ *
 * 1. NEEDLE DROP                                                      *
 * ------------------------------------------------------------------ */

console.log('Generating needle drop...');
{
  const dur = 1.35;
  const out = new Float32Array(Math.floor(SR * dur));
  let lp = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    let s = 0;
    // Soft contact thud with muted tick — wood-on-wax, not glass.
    if (t >= 0.10 && t <= 0.30) {
      const ct = t - 0.10;
      const env = Math.exp(-ct * 28);
      s += Math.sin(2 * Math.PI * 70 * ct) * env * 0.55;
      s += Math.sin(2 * Math.PI * 140 * ct) * env * 0.20;
      s += Math.sin(2 * Math.PI * 950 * ct) * Math.exp(-ct * 130) * 0.10;
      s += (Math.random() * 2 - 1) * env * 0.10;
    }
    // Settling hiss as the stylus finds its groove.
    if (t >= 0.20 && t <= 0.95) {
      const env2 = Math.exp(-(t - 0.20) * 4.5);
      s += (Math.random() * 2 - 1) * 0.06 * env2;
      if (Math.random() < 0.010) s += (Math.random() > 0.5 ? 0.18 : -0.18) * env2;
    }
    // Low bass settle.
    if (t >= 0.35) {
      const fade = Math.max(0, 1 - (t - 0.35) / 1.0);
      s += Math.sin(2 * Math.PI * 55 * t) * 0.02 * fade;
    }
    lp += (s - lp) * 0.5;
    out[i] = lp;
  }
  writeWavFile('needle-drop', SR, out);
}

/* ------------------------------------------------------------------ *
 * 2. VINYL CRACKLE (10-second seamless loop)                          *
 * ------------------------------------------------------------------ */

console.log('Generating vinyl crackle...');
{
  const dur = 10.0;
  const out = new Float32Array(Math.floor(SR * dur));
  const revPeriod = 60 / 33.333;
  let crackleLp = 0;
  let popTail = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    let s = 0;
    const hiss = (Math.random() * 2 - 1) * 0.02;
    const rumble = Math.sin(2 * Math.PI * 33 * t) * 0.02 + Math.sin(2 * Math.PI * 66 * t) * 0.01;
    const phase = (t % revPeriod) / revPeriod;
    if (phase < 0.015) {
      const popEnv = Math.exp(-phase * 200);
      s += Math.sin(2 * Math.PI * 120 * t) * popEnv * 0.18;
    }
    if (Math.random() < 0.0022) {
      popTail = (Math.random() * 2 - 1) * (0.25 + Math.random() * 0.35);
    }
    popTail *= 0.86;
    s += popTail;
    const raw = s + hiss + rumble;
    crackleLp += (raw - crackleLp) * 0.35;
    out[i] = crackleLp;
  }
  writeWavFile('crackle-vintage', SR, out);
}

/* 2B. Soft shellac */
console.log('Generating soft shellac surface...');
{
  const dur = 10.0;
  const out = new Float32Array(Math.floor(SR * dur));
  let softNoise = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    const white = Math.random() * 2 - 1;
    softNoise = softNoise * 0.975 + white * 0.025;
    let s = softNoise * 0.1 + Math.sin(2 * Math.PI * 42 * t) * 0.018;
    if (Math.random() < 0.00035) s += (Math.random() * 2 - 1) * 0.28;
    out[i] = s;
  }
  writeWavFile('crackle-soft-shellac', SR, out);
}

/* 2C. Dusty attic */
console.log('Generating dusty attic surface...');
{
  const dur = 10.0;
  const out = new Float32Array(Math.floor(SR * dur));
  let scratchBurst = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    if (Math.random() < 0.00018) scratchBurst = 1;
    scratchBurst *= 0.99955;
    let s = (Math.random() * 2 - 1) * (0.035 + scratchBurst * 0.14);
    if (Math.random() < 0.0045) s += (Math.random() > 0.5 ? 1 : -1) * (0.2 + Math.random() * 0.55);
    s += Math.sin(2 * Math.PI * 92 * t) * 0.012;
    out[i] = s;
  }
  writeWavFile('crackle-dusty-attic', SR, out);
}

/* 2D. Tape room */
console.log('Generating tape room ambience...');
{
  const dur = 12.0;
  const out = new Float32Array(Math.floor(SR * dur));
  let brown = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    brown = Math.max(-1, Math.min(1, brown + (Math.random() * 2 - 1) * 0.018)) * 0.998;
    const breathing = 0.65 + 0.35 * Math.sin(2 * Math.PI * 0.12 * t);
    out[i] = brown * 0.09 * breathing + Math.sin(2 * Math.PI * 50 * t) * 0.012;
  }
  writeWavFile('bg-tape-room', SR, out);
}

/* ------------------------------------------------------------------ *
 * 3. WARM LO-FI RAIN                                                  *
 *    Pink noise (proper 1/f) + a low shelf for distant rumble + the   *
 *    occasional drop click.                                           *
 * ------------------------------------------------------------------ */

console.log('Generating rain background...');
{
  const dur = 12.0;
  const out = new Float32Array(Math.floor(SR * dur));
  const pink = makePinkNoise();
  let lp = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    let s = pink() * 0.55;          // smooth 1/f rain body
    lp = lp1(lp, s, 220, SR);       // low shelf for room rumble
    let body = lp * 1.4 + (s - lp) * 0.55;
    // Distant warm sub rumble (moving very slowly).
    body += Math.sin(2 * Math.PI * 45 * t + Math.sin(t * 0.4) * 0.6) * 0.04;
    // Soft water drops.
    if (Math.random() < 0.0009) {
      const freq = 380 + Math.random() * 600;
      body += Math.sin(2 * Math.PI * freq * t) * 0.12 * Math.random();
    }
    out[i] = tanh(body * 0.85) * 0.85;
  }
  writeWavFile('bg-rain', SR, out);
}

/* ------------------------------------------------------------------ *
 * 4. PARISIAN ACCORDION                                               *
 *    Two slightly detuned reeds per note (real tremolo/beating),      *
 *    bellows amplitude modulation (slow swell), sharp reed-click      *
 *    attack on each note.                                             *
 * ------------------------------------------------------------------ */

console.log('Generating accordion track...');
{
  const dur = 12.0;
  const out = new Float32Array(Math.floor(SR * dur));
  const chordProgression = [
    { bass: 110, chord: [220, 261.63, 329.63] },   // Am
    { bass: 146.83, chord: [220, 293.66, 349.23] }, // Dm
    { bass: 164.81, chord: [207.65, 246.94, 329.63] }, // E7
    { bass: 110, chord: [220, 261.63, 329.63] },   // Am
  ];
  const beatDuration = 0.75; // 3/4 waltz
  const melodyNotes = [329.63, 349.23, 392.0, 329.63, 293.66, 261.63, 246.94, 220.0];

  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    const barIndex = Math.floor((t / (beatDuration * 3)) % chordProgression.length);
    const barTime = t % (beatDuration * 3);
    const beatIndex = Math.floor(barTime / beatDuration);
    const beatTime = barTime % beatDuration;
    const current = chordProgression[barIndex];

    // Bellows amplitude: slow swell (1 cycle per bar) plus per-beat emphasis.
    const bellows = 0.75 + 0.25 * Math.sin(2 * Math.PI * (1 / (beatDuration * 3)) * t);
    let s = 0;

    const reed = (freq) => {
      // Two reeds slightly out of tune (real accordion tremolo).
      const detune = 4.5; // Hz — gives ~9 Hz beating.
      let v = 0;
      for (const f of [freq, freq + detune]) {
        // Sawtooth is closer to a real reed than a sine.
        const phase = (f * t) % 1;
        let osc = phase * 2 - 1;                       // saw
        osc += 0.35 * Math.sin(2 * Math.PI * f * 2 * t); // 2nd harmonic
        osc += 0.18 * Math.sin(2 * Math.PI * f * 3 * t); // 3rd
        osc += 0.09 * Math.sin(2 * Math.PI * f * 4 * t);
        v += osc;
      }
      v *= 0.18; // combine two reeds
      return v;
    };

    if (beatIndex === 0) {
      const env = Math.exp(-beatTime * 3.2);
      s += reed(current.bass) * env * 0.95;
    } else {
      const env = Math.exp(-beatTime * 4.0);
      for (const note of current.chord) s += reed(note) * env * 0.45;
    }

    // Melodic line on top — slightly faster than the chord progression.
    const melNote = melodyNotes[Math.floor(t * 1.33) % melodyNotes.length];
    const melEnv = 0.45 + 0.35 * Math.sin(2 * Math.PI * 1.33 * t);
    s += reed(melNote * 1.5) * 0.22 * melEnv;

    // Soft reed-click attack every time a new beat starts.
    const beatHit = (barTime < 0.012) ? Math.exp(-barTime * 180) : 0;
    s += beatHit * (Math.random() * 2 - 1) * 0.05;

    out[i] = tanh(s * bellows * 0.9) * 0.55;
  }
  writeWavFile('bg-accordion', SR, out);
}

/* ------------------------------------------------------------------ *
 * 5. ACOUSTIC FIREPLACE GUITAR — KARPLUS–STRONG                        *
 *    A short noise burst at the pluck → delay line with one-pole      *
 *    lowpass + a slight comb in the feedback loop → 6 strings         *
 *    arpeggiating slowly through a warm chord progression.            *
 * ------------------------------------------------------------------ */

console.log('Generating acoustic guitar...');
{
  const dur = 12.0;
  const out = new Float32Array(Math.floor(SR * dur));
  const guitarChords = [
    [130.81, 196.0, 261.63, 329.63, 392.0, 523.25],   // C
    [110.0, 164.81, 220.0, 261.63, 329.63, 440.0],    // Am
    [87.31, 130.81, 174.61, 220.0, 261.63, 349.23],   // F
    [98.0, 146.83, 196.0, 246.94, 293.66, 392.0],     // G
  ];
  // 6 string voices — each keeps its own delay line + lowpass state.
  // Each voice also tracks its own pluck time so we don't have to search.
  const strings = guitarChords[0].map(() => ({ buffer: new Float32Array(SR), idx: 0, lp: 0, gate: 0, freq: 0, pluckedAt: -10 }));
  let lastArpIdx = -1;

  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    const chordIdx = Math.floor((t / 3) % guitarChords.length);
    const chord = guitarChords[chordIdx];
    const arpIdx = Math.floor((t * 2) % chord.length); // 2 notes/s
    // Trigger a pluck when the arpeggio index changes (1/0.5s = 2 notes/s).
    if (arpIdx !== lastArpIdx) {
      lastArpIdx = arpIdx;
      const s = strings[arpIdx];
      const f = chord[arpIdx];
      s.freq = f;
      const period = Math.max(2, Math.round(SR / f));
      // Resize ringbuffer to fit the period.
      if (s.buffer.length < period + 4) s.buffer = new Float32Array(period + 4);
      s.idx = 0;
      // Initial pluck = short noise burst with pluck envelope.
      for (let k = 0; k < period; k++) {
        s.buffer[k] = (Math.random() * 2 - 1) * Math.exp(-k / Math.max(1, period * 0.18));
      }
      s.lp = 0;
      s.gate = 1;
      s.pluckedAt = t;
    }
    let mix = 0;
    for (const s of strings) {
      if (s.gate === 0) continue;
      const period = Math.round(SR / s.freq);
      // Read the delayed sample.
      const readIdx = (s.idx + period) % s.buffer.length;
      const delayed = s.buffer[readIdx];
      // One-pole lowpass in the loop: high frequencies decay faster.
      s.lp += (delayed - s.lp) * 0.45;
      // Slight comb for warmth.
      const combIdx = (s.idx + Math.floor(period * 0.5)) % s.buffer.length;
      const combSample = s.buffer[combIdx] * 0.18;
      const next = (s.lp + combSample) * 0.995; // 0.5% per roundtrip loss
      s.buffer[s.idx] = next;
      s.idx = (s.idx + 1) % s.buffer.length;
      // Overall decay envelope so the loop doesn't sustain forever.
      const life = (t - s.pluckedAt) / (period / SR * 800);
      const decay = Math.exp(-life * 0.6);
      mix += next * decay * 0.4;
    }
    // Body resonance + faint pick attack.
    mix = tanh(mix * 1.4) * 0.55;
    // Subtle room rumble under the guitar.
    mix += Math.sin(2 * Math.PI * 65 * t) * 0.012;
    out[i] = mix;
  }
  writeWavFile('bg-guitar', SR, out);
}

/* ------------------------------------------------------------------ *
 * 6. CINEMATIC CELLO NOCTURNE — BOWED STRING                          *
 *    Sawtooth-rich bowed source with a stretched-partial spectrum     *
 *    (inharmonicity B ~ 0.6 for low cello notes), slow vibrato        *
 *    (~5 Hz, ±0.5%), bowed noise floor, smooth bow envelope.          *
 * ------------------------------------------------------------------ */

console.log('Generating cello nocturne...');
{
  const dur = 12.0;
  const out = new Float32Array(Math.floor(SR * dur));
  const celloNotes = [110.0, 123.47, 130.81, 146.83, 164.81, 146.83, 130.81, 110.0];

  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    const noteIdx = Math.floor((t / 1.5) % celloNotes.length);
    const baseFreq = celloNotes[noteIdx];
    // Real slow vibrato (~5 Hz, ±0.5%) — natural, not the alien wobble.
    const vib = Math.sin(2 * Math.PI * 5.2 * t) * (baseFreq * 0.005);
    const f0 = baseFreq + vib;

    let s = 0;
    // Bowed string has rich harmonics; partials are slightly stretched.
    const B = 0.6; // inharmonicity coefficient (small for cello).
    for (let h = 1; h <= 8; h++) {
      const fPart = h * f0 * Math.sqrt(1 + B * h * h);
      const gain = 1 / Math.pow(h, 1.4); // bowed spectrum rolls off gently
      // Sawtooth partial (rich, like a bowed string on the body).
      const phase = (fPart * t) % 1;
      s += (phase * 2 - 1) * gain;
    }
    s *= 0.16;
    // Bowed noise floor (rosin stick-slip texture).
    s += (Math.random() * 2 - 1) * 0.018;
    // Slow bow amplitude envelope: smooth attack on each new note.
    const noteT = t % 1.5;
    const bowEnv = Math.sin((noteT / 1.5) * Math.PI);
    out[i] = tanh(s * bowEnv * 1.3) * 0.55;
  }
  writeWavFile('bg-cello', SR, out);
}

/* ------------------------------------------------------------------ *
 * 7. VINTAGE VOICE NOTES & DEMOS — PROPER FORMANT SYNTHESIS            *
 *                                                                      *
 * Glottal source = sawtooth + soft jitter (rich in harmonics, like     *
 * a real vocal-fold buzz). Routed through FOUR formant bandpass        *
 * filters (F1–F4) tuned for adult male/female speech. The formants      *
 * slide during each word to suggest vowel movement, with brief         *
 * consonant-noise bursts at word starts and a breath noise floor.      *
 *                                                                      *
 * Pitch: a natural contour (rises on stressed words, falls at phrase  *
 * ends) + small random jitter. NO periodic wobble — that's what made  *
 * the old version sound alien.                                         *
 * ------------------------------------------------------------------ */

console.log('Generating voice and demo tracks...');

function generateVoiceSample(filename, words, baseF0Hz, durationSec, opts = {}) {
  const total = Math.floor(SR * durationSec);
  const samples = new Float32Array(total);

  // Pronunciation hint: words starting with [pbtkgsf] get a noise burst.
  function isConsonant(word) {
    return /^[pbtkgsfh]|^[sz]/.test(word.toLowerCase());
  }
  // Map a word's vowel to a target formant set.
  function vowelFormants(word) {
    const w = word.toLowerCase().replace(/[^a-z]/g, '');
    // Default neutral vowel ("uh").
    let f1 = 530, f2 = 1840, f3 = 2480, f4 = 3600;
    if (/a|aa|ah/.test(w)) { f1 = 730; f2 = 1090; f3 = 2440; f4 = 3500; }
    else if (/ee|ea|ie|y\b/i.test(w)) { f1 = 270; f2 = 2290; f3 = 3010; f4 = 3700; }
    else if (/i\b|ih|it\b|in\b|is\b/.test(w)) { f1 = 390; f2 = 1990; f3 = 2550; f4 = 3700; }
    else if (/o\b|oo|ow|oh/.test(w)) { f1 = 570; f2 = 840;  f3 = 2410; f4 = 3500; }
    else if (/u\b|uh|ou|ow/.test(w)) { f1 = 520; f2 = 1190; f3 = 2390; f4 = 3500; }
    else if (/er|or|ur|our/.test(w)) { f1 = 490; f2 = 1350; f3 = 1690; f4 = 3500; }
    return { f1, f2, f3, f4 };
  }

  // Precompute per-word data so the per-sample loop is allocation-free.
  const wordData = words.map((w) => ({
    isConsonant: isConsonant(w),
    isSibilant: /^[sz]|^sh/.test(w.toLowerCase()),
    formants: vowelFormants(w),
  }));

  // Hoist EVERYTHING used in the hot loop — V8 inlines but allocating a
  // fresh `{f1,f2,f3,f4}` object 350,000 times in a row pegs the GC.
  const breath = makePinkNoise();
  const F1 = biquad(), F2 = biquad(), F3 = biquad(), F4 = biquad();
  let body = 0;

  const wordDur = durationSec / words.length;
  const stress = opts.stress || words.map(() => 1);

  for (let i = 0; i < total; i++) {
    const t = i / SR;
    const wIdx = Math.min(words.length - 1, Math.floor(t / wordDur));
    const wFrac = (t - wIdx * wordDur) / wordDur;
    const wd = wordData[wIdx];
    const stressed = stress[wIdx] || 1;

    const voiced = wFrac < 0.78 && wFrac > 0.04;
    const stressBump = stressed > 1 ? 6 * Math.sin(wFrac * Math.PI) : 0;
    const phraseDrop = wFrac > 0.6 ? (wFrac - 0.6) * 35 * -0.18 : 0;
    const jitter = (Math.random() * 2 - 1) * 4;
    const f0 = baseF0Hz + stressBump + phraseDrop + jitter;

    // Glottal source: sawtooth + 2nd harmonic + micro-jitter.
    const glottPhase = (f0 * t) % 1;
    let glott = glottPhase * 2 - 1;
    glott += 0.15 * Math.sin(2 * Math.PI * f0 * 2 * t);
    glott -= 0.05 * (Math.random() * 2 - 1);
    glott *= 0.55;

    // Formants slide during the first 25% of each vowel.
    const target = wd.formants;
    const slideAmt = Math.min(1, wFrac * 4);
    const f1F = 500 + (target.f1 - 500) * slideAmt;
    const f2F = 1500 + (target.f2 - 1500) * slideAmt;
    const f3F = 2400 + (target.f3 - 2400) * slideAmt;
    const f4F = 3500 + (target.f4 - 3500) * slideAmt;

    const f1 = biquadBandpass(F1, glott, f1F, 8, SR);
    const f2 = biquadBandpass(F2, glott, f2F, 8, SR);
    const f3 = biquadBandpass(F3, glott, f3F, 8, SR);
    const f4 = biquadBandpass(F4, glott, f4F, 8, SR);
    let vowel = f1 * 0.42 + f2 * 0.35 + f3 * 0.16 + f4 * 0.10;

    if (wd.isConsonant && wFrac < 0.08) {
      const env = Math.exp(-wFrac * 50);
      const noise = Math.random() * 2 - 1;
      vowel += (wd.isSibilant ? noise * 0.5 : noise * 0.25) * env;
    }

    const attack = Math.min(1, wFrac / 0.08);
    const sustain = Math.sin(Math.PI * Math.min(1, wFrac / 0.85));
    const release = wFrac > 0.78 ? Math.exp(-(wFrac - 0.78) * 12) : 1;
    const env = attack * sustain * release * (voiced ? 1 : 0);

    body = lp1(body, breath() * (0.012 + (voiced ? 0 : 0.025)), 4500, SR);

    samples[i] = tanh((vowel * env + body) * 1.4) * 0.65;
  }

  writeWavFile(filename, SR, samples);
}

// --- demo recordings (timings are matched to the transcripts in lib/constants.ts) ---
generateVoiceSample(
  'demo-anniversary',
  ['My', 'dearest', 'Eleanor,', 'every', 'single', 'day', 'with', 'you', 'feels', 'like', 'a', 'melody', 'cut', 'into', 'gold.'],
  135, 8.0,
  { stress: [1, 1.4, 1, 1, 1.2, 1.4, 1, 1.2, 1.4, 1, 1, 1.3, 1.4, 1, 1.5] }
);

generateVoiceSample(
  'demo-grandmother',
  ['Remember', 'sweetheart,', 'always', 'add', 'a', 'pinch', 'of', 'cinnamon', 'and', 'whisk', 'with', 'love.', 'Grandma', 'is', 'always', 'with', 'you.'],
  185, 9.5,
  { stress: [1.3, 1, 1, 1.2, 1, 1.3, 1, 1.4, 1, 1.2, 1, 1.4, 1.3, 1, 1.2, 1, 1.4] }
);

generateVoiceSample(
  'demo-voice-ocean',
  ['Though', 'the', 'Pacific', 'lies', 'between', 'us', 'tonight,', 'listen', 'to', 'the', 'crackle', 'of', 'this', 'record', 'and', 'know', 'I', 'am', 'home.'],
  150, 9.0,
  { stress: [1.3, 1, 1.4, 1.2, 1, 1, 1.3, 1.4, 1, 1, 1.4, 1, 1, 1.4, 1, 1.4, 1, 1, 1.5] }
);

generateVoiceSample(
  'demo-raw-sample',
  ['Hello', 'my', 'love,', 'this', 'is', 'a', 'warm', 'voice', 'note', 'recorded', 'straight', 'from', 'my', 'heart.'],
  155, 6.0,
  { stress: [1.3, 1, 1, 1.2, 1, 1, 1.3, 1.3, 1.2, 1.3, 1.2, 1, 1, 1.4] }
);

// --- mastered comparisons (server-side ffmpeg so the difference is audible) ---
masterComparisonSample(
  'demo-gramophone-sample',
  // 1920s gramophone voice: horn bandpass + tube compression + 78rpm flutter.
  'highpass=f=420,lowpass=f=2700,equalizer=f=1150:t=q:w=1.2:g=10,tremolo=f=5.2:d=0.22,acompressor=threshold=-22dB:ratio=6:attack=8:release=120,volume=1.35',
  // Crackle bed for the gramophone side: keep the high content (it reads as
  // surface noise) but tame anything below 700Hz.
  'highpass=f=700,lowpass=f=6500'
);

masterComparisonSample(
  'demo-lofi-sample',
  // 1960s tape saturation: warm head bump, controlled top, optical levelling.
  'lowpass=f=5200,equalizer=f=180:t=q:w=1.1:g=5,equalizer=f=2800:t=q:w=1.5:g=-3,acompressor=threshold=-18dB:ratio=4:attack=18:release=240,vibrato=f=0.65:d=0.018,volume=1.1',
  // Rain bed for the lofi side: keep the low rumble of distant weather.
  'lowpass=f=4200,highpass=f=90'
);

console.log('All synthetic sound assets generated successfully.');
