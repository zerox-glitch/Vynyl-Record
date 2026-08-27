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

  // RIFF header
  buffer.write('RIFF', 0);
  buffer.writeUInt32LE(36 + dataSize, 4);
  buffer.write('WAVE', 8);

  // fmt subchunk
  buffer.write('fmt ', 12);
  buffer.writeUInt32LE(16, 16); // SubChunk1Size (16 for PCM)
  buffer.writeUInt16LE(1, 20); // AudioFormat (1 = PCM)
  buffer.writeUInt16LE(numChannels, 22); // NumChannels
  buffer.writeUInt32LE(sampleRate, 24); // SampleRate
  buffer.writeUInt32LE(byteRate, 28); // ByteRate
  buffer.writeUInt16LE(blockAlign, 32); // BlockAlign
  buffer.writeUInt16LE(16, 34); // BitsPerSample

  // data subchunk
  buffer.write('data', 36);
  buffer.writeUInt32LE(dataSize, 40);

  for (let i = 0; i < samples.length; i++) {
    // Clamp sample between -1.0 and 1.0
    let s = Math.max(-1, Math.min(1, samples[i]));
    let val = s < 0 ? s * 0x8000 : s * 0x7fff;
    buffer.writeInt16LE(Math.floor(val), 44 + i * 2);
  }

  const wavPath = path.join(TMP_DIR, `${filename}.wav`);
  fs.writeFileSync(wavPath, buffer);

  const mp3Path = path.join(OUTPUT_DIR, `${filename}.mp3`);
  try {
    execFileSync(resolveFfmpeg(), ['-y', '-loglevel', 'error', '-i', wavPath, '-codec:a', 'libmp3lame', '-b:a', '192k', mp3Path]);
    console.log(`Generated: ${mp3Path}`);
  } catch (err) {
    console.error(`FFmpeg error converting ${filename}:`, err);
  }
}

function masterComparisonSample(filename, filterGraph) {
  const ffmpegPath = resolveFfmpeg();
  const rawPath = path.join(OUTPUT_DIR, 'demo-raw-sample.mp3');
  const cracklePath = path.join(OUTPUT_DIR, 'crackle-vintage.mp3');
  const rainPath = path.join(OUTPUT_DIR, 'bg-rain.mp3');
  const outputPath = path.join(OUTPUT_DIR, `${filename}.mp3`);

  try {
    execFileSync(ffmpegPath, [
      '-y',
      '-loglevel', 'error',
      '-i', rawPath,
      '-stream_loop', '-1',
      '-i', filename === 'demo-gramophone-sample' ? cracklePath : rainPath,
      '-filter_complex', filterGraph,
      '-map', '[outa]',
      '-t', '6',
      '-codec:a', 'libmp3lame',
      '-b:a', '192k',
      '-ar', '44100',
      '-ac', '2',
      outputPath,
    ]);
    console.log(`Generated distinct mastered comparison: ${outputPath}`);
  } catch (err) {
    console.error(`FFmpeg comparison mastering error for ${filename}:`, err);
    throw err;
  }
}

const sampleRate = 44100;

// 1. NEEDLE DROP
console.log('Generating needle drop...');
const needleDuration = 1.35;
const needleSamples = new Float32Array(Math.floor(sampleRate * needleDuration));
let needleLp = 0;
for (let i = 0; i < needleSamples.length; i++) {
  const t = i / sampleRate;
  let s = 0;
  // Stylus contact: a soft low thud with a short muted tick, not a harsh click.
  if (t >= 0.12 && t <= 0.34) {
    const contactT = t - 0.12;
    const env = Math.exp(-contactT * 26);
    s += Math.sin(2 * Math.PI * 68 * contactT) * env * 0.55;
    s += Math.sin(2 * Math.PI * 150 * contactT) * env * 0.22;
    s += Math.sin(2 * Math.PI * 900 * contactT) * Math.exp(-contactT * 120) * 0.1;
    s += (Math.random() * 2 - 1) * env * 0.12;
  }
  // Stylus settling into the lead-in groove, warm and quiet.
  if (t >= 0.22 && t <= 0.95) {
    const env2 = Math.exp(-(t - 0.22) * 5);
    s += (Math.random() * 2 - 1) * 0.07 * env2;
    if (Math.random() < 0.012) s += (Math.random() > 0.5 ? 0.22 : -0.22) * env2;
  }
  // Gentle bass settle.
  if (t >= 0.35) {
    const fade = Math.max(0, 1 - (t - 0.35) / 1.0);
    s += Math.sin(2 * Math.PI * 55 * t) * 0.02 * fade;
  }
  // Warm the transient so it doesn't sound digital.
  needleLp += (s - needleLp) * 0.5;
  needleSamples[i] = needleLp;
}
writeWavFile('needle-drop', sampleRate, needleSamples);

// 2. VINYL CRACKLE LOOP (10 seconds seamless)
console.log('Generating vinyl crackle...');
const crackleDuration = 10.0;
const crackleSamples = new Float32Array(Math.floor(sampleRate * crackleDuration));
const revPeriod = 60 / 33.333; // ~1.8 seconds per revolution
let crackleLp = 0; // warm low-pass on the whole texture
let popTail = 0; // resonant decay of the last pop so clicks sound like dust, not noise
for (let i = 0; i < crackleSamples.length; i++) {
  const t = i / sampleRate;
  let s = 0;

  // Soft continuous surface hiss (gentle, filtered).
  const hiss = (Math.random() * 2 - 1) * 0.02;

  // Warm low rotational rumble.
  const rumble = Math.sin(2 * Math.PI * 33 * t) * 0.02 + Math.sin(2 * Math.PI * 66 * t) * 0.01;

  // Once-per-revolution gentle thump where the seam passes.
  const phase = (t % revPeriod) / revPeriod;
  if (phase < 0.015) {
    const popEnv = Math.exp(-phase * 200);
    s += Math.sin(2 * Math.PI * 120 * t) * popEnv * 0.18;
  }

  // Sparse dust ticks that ring briefly instead of sounding like static.
  if (Math.random() < 0.0022) {
    popTail = (Math.random() * 2 - 1) * (0.25 + Math.random() * 0.35);
  }
  popTail *= 0.86;
  s += popTail;

  // Warm the whole texture with a one-pole low-pass filter.
  const raw = s + hiss + rumble;
  crackleLp += (raw - crackleLp) * 0.35;
  crackleSamples[i] = crackleLp;
}
writeWavFile('crackle-vintage', sampleRate, crackleSamples);

// 2B. SOFT SHELLAC: warm, sparse defects and almost no bright hiss.
console.log('Generating soft shellac surface...');
const softSamples = new Float32Array(Math.floor(sampleRate * crackleDuration));
let softNoise = 0;
for (let i = 0; i < softSamples.length; i++) {
  const t = i / sampleRate;
  const white = Math.random() * 2 - 1;
  softNoise = softNoise * 0.975 + white * 0.025;
  let s = softNoise * 0.1 + Math.sin(2 * Math.PI * 42 * t) * 0.018;
  if (Math.random() < 0.00035) s += (Math.random() * 2 - 1) * 0.28;
  softSamples[i] = s;
}
writeWavFile('crackle-soft-shellac', sampleRate, softSamples);

// 2C. DUSTY ATTIC: bright clusters of scratches and frequent sharp dust clicks.
console.log('Generating dusty attic surface...');
const dustySamples = new Float32Array(Math.floor(sampleRate * crackleDuration));
let scratchBurst = 0;
for (let i = 0; i < dustySamples.length; i++) {
  const t = i / sampleRate;
  if (Math.random() < 0.00018) scratchBurst = 1;
  scratchBurst *= 0.99955;
  let s = (Math.random() * 2 - 1) * (0.035 + scratchBurst * 0.14);
  if (Math.random() < 0.0045) s += (Math.random() > 0.5 ? 1 : -1) * (0.2 + Math.random() * 0.55);
  s += Math.sin(2 * Math.PI * 92 * t) * 0.012;
  dustySamples[i] = s;
}
writeWavFile('crackle-dusty-attic', sampleRate, dustySamples);

// 2D. TAPE ROOM: smooth brown room tone with slow machine breathing.
console.log('Generating tape room ambience...');
const tapeRoomSamples = new Float32Array(Math.floor(sampleRate * 12));
let brown = 0;
for (let i = 0; i < tapeRoomSamples.length; i++) {
  const t = i / sampleRate;
  brown = Math.max(-1, Math.min(1, brown + (Math.random() * 2 - 1) * 0.018)) * 0.998;
  const breathing = 0.65 + 0.35 * Math.sin(2 * Math.PI * 0.12 * t);
  tapeRoomSamples[i] = brown * 0.09 * breathing + Math.sin(2 * Math.PI * 50 * t) * 0.012;
}
writeWavFile('bg-tape-room', sampleRate, tapeRoomSamples);

// 3. WARM LO-FI RAIN (12 seconds)
console.log('Generating rain background...');
const rainDuration = 12.0;
const rainSamples = new Float32Array(Math.floor(sampleRate * rainDuration));
let rainFilter = 0;
for (let i = 0; i < rainSamples.length; i++) {
  const t = i / sampleRate;
  // Pink noise / brown noise simulation for smooth warm rain
  const white = Math.random() * 2 - 1;
  rainFilter = (rainFilter * 0.92) + (white * 0.08);
  let s = rainFilter * 0.25;

  // Occasional soft water drops
  if (Math.random() < 0.001) {
    s += Math.sin(2 * Math.PI * (400 + Math.random() * 600) * t) * 0.15;
  }
  // Distant warm sub rumble
  s += Math.sin(2 * Math.PI * 45 * t + Math.sin(t * 0.5)) * 0.05;
  rainSamples[i] = s;
}
writeWavFile('bg-rain', sampleRate, rainSamples);

// 4. PARISIAN ACCORDION (12 seconds waltz)
console.log('Generating accordion track...');
const accDuration = 12.0;
const accSamples = new Float32Array(Math.floor(sampleRate * accDuration));
// Notes: A3 (220), C4 (261.6), E4 (329.6), D4 (293.7), F4 (349.2), G#3 (207.65), B3 (246.9)
const chordProgression = [
  { bass: 110, chord: [220, 261.63, 329.63] }, // Am
  { bass: 146.83, chord: [220, 293.66, 349.23] }, // Dm
  { bass: 164.81, chord: [207.65, 246.94, 329.63] }, // E7
  { bass: 110, chord: [220, 261.63, 329.63] }, // Am
];
const beatDuration = 0.75; // 3/4 waltz beat
for (let i = 0; i < accSamples.length; i++) {
  const t = i / sampleRate;
  const barIndex = Math.floor((t / (beatDuration * 3)) % chordProgression.length);
  const barTime = t % (beatDuration * 3);
  const beatIndex = Math.floor(barTime / beatDuration);
  const beatTime = barTime % beatDuration;
  const current = chordProgression[barIndex];

  let s = 0;
  // Reed oscillator helper with harmonics
  const reed = (freq, time) => {
    let osc = Math.sin(2 * Math.PI * freq * time);
    osc += 0.5 * Math.sin(2 * Math.PI * freq * 2 * time);
    osc += 0.25 * Math.sin(2 * Math.PI * freq * 3 * time);
    osc += 0.15 * Math.sin(2 * Math.PI * freq * 4 * time);
    // Vibrato
    const vib = 1 + 0.01 * Math.sin(2 * Math.PI * 5.5 * time);
    return osc * 0.2;
  };

  if (beatIndex === 0) {
    // Bass on beat 1
    const env = Math.exp(-beatTime * 3);
    s += reed(current.bass, t) * env * 0.8;
  } else {
    // Chords on beats 2 & 3
    const env = Math.exp(-beatTime * 4);
    for (const note of current.chord) {
      s += reed(note, t) * env * 0.4;
    }
  }

  // Melodic line on top
  const melodyNotes = [329.63, 349.23, 392.0, 329.63, 293.66, 261.63, 246.94, 220.0];
  const melNote = melodyNotes[Math.floor(t * 1.33) % melodyNotes.length];
  const melEnv = 0.5 + 0.5 * Math.sin(2 * Math.PI * 1.33 * t);
  s += reed(melNote * 1.5, t) * 0.2 * melEnv;

  accSamples[i] = s * 0.35;
}
writeWavFile('bg-accordion', sampleRate, accSamples);

// 5. ACOUSTIC FIREPLACE GUITAR (12 seconds)
console.log('Generating acoustic guitar...');
const guitarDuration = 12.0;
const guitarSamples = new Float32Array(Math.floor(sampleRate * guitarDuration));
// Arpeggio notes (C major -> Am -> F -> G)
const guitarChords = [
  [130.81, 196.0, 261.63, 329.63, 392.0, 523.25], // C
  [110.0, 164.81, 220.0, 261.63, 329.63, 440.0],  // Am
  [87.31, 130.81, 174.61, 220.0, 261.63, 349.23], // F
  [98.0, 146.83, 196.0, 246.94, 293.66, 392.0],   // G
];
for (let i = 0; i < guitarSamples.length; i++) {
  const t = i / sampleRate;
  const chordIdx = Math.floor((t / 3) % guitarChords.length);
  const chord = guitarChords[chordIdx];
  const noteIdx = Math.floor((t % 3) * 4) % chord.length;
  const noteTime = (t % (3 / 4));
  const freq = chord[noteIdx];

  const env = Math.exp(-noteTime * 4.5);
  let s = Math.sin(2 * Math.PI * freq * t) * 0.6;
  s += Math.sin(2 * Math.PI * freq * 2 * t) * 0.25;
  s += Math.sin(2 * Math.PI * freq * 3 * t) * 0.12;
  s += (Math.random() * 2 - 1) * 0.05 * Math.exp(-noteTime * 30); // string pluck pick attack

  guitarSamples[i] = s * env * 0.3;
}
writeWavFile('bg-guitar', sampleRate, guitarSamples);

// 6. CINEMATIC CELLO NOCTURNE (12 seconds)
console.log('Generating cello nocturne...');
const celloDuration = 12.0;
const celloSamples = new Float32Array(Math.floor(sampleRate * celloDuration));
const celloNotes = [110.0, 123.47, 130.81, 146.83, 164.81, 146.83, 130.81, 110.0];
for (let i = 0; i < celloSamples.length; i++) {
  const t = i / sampleRate;
  const noteIdx = Math.floor((t / 1.5) % celloNotes.length);
  const baseFreq = celloNotes[noteIdx];
  const vib = Math.sin(2 * Math.PI * 6 * t) * (baseFreq * 0.015);
  const freq = baseFreq + vib;

  let s = 0;
  // Rich bowed string harmonics
  for (let h = 1; h <= 6; h++) {
    const harmGain = 1 / (h * 0.85);
    s += Math.sin(2 * Math.PI * (freq * h) * t) * harmGain;
  }
  const noteT = t % 1.5;
  const bowEnv = Math.sin((noteT / 1.5) * Math.PI);
  celloSamples[i] = s * bowEnv * 0.12;
}
writeWavFile('bg-cello', sampleRate, celloSamples);

// 7. SAMPLE VOICE NOTES & DEMOS
console.log('Generating voice and demo tracks...');

function generateSpokenToneSample(filename, textWords, baseToneHz, durationSec) {
  const total = Math.floor(sampleRate * durationSec);
  const samples = new Float32Array(total);
  const wordDuration = durationSec / textWords.length;
  // One-pole low-pass state to keep the tone warm instead of buzzy/robotic.
  let lp = 0;

  for (let i = 0; i < total; i++) {
    const t = i / sampleRate;
    const wordIdx = Math.floor(t / wordDuration);
    const wordTime = t % wordDuration;
    const voiced = wordTime < wordDuration * 0.78;

    let s = 0;
    if (voiced) {
      // Natural pitch contour: gently rises then falls within each word.
      const prog = wordTime / (wordDuration * 0.78);
      const intonation = Math.sin(prog * Math.PI) * 10 - 4;
      const drift = Math.sin(2 * Math.PI * 4.5 * t) * 2.5;
      const f0 = baseToneHz + intonation + drift + (wordIdx % 3) * 3;

      // Warm glottal source: fundamental plus softly decaying harmonics only.
      s += Math.sin(2 * Math.PI * f0 * t) * 0.5;
      s += Math.sin(2 * Math.PI * f0 * 2 * t) * 0.22;
      s += Math.sin(2 * Math.PI * f0 * 3 * t) * 0.1;
      s += Math.sin(2 * Math.PI * f0 * 4 * t) * 0.05;

      // Single soft vowel resonance that slides between syllables.
      const formant = 480 + Math.sin(t * 6 + wordIdx) * 180;
      s += Math.sin(2 * Math.PI * formant * t) * 0.14;

      // Very light breath only at the onset of a word.
      if (wordTime < 0.03) s += (Math.random() * 2 - 1) * 0.08;

      // Smooth attack/release envelope so words don't click.
      const env = Math.sin(prog * Math.PI);
      s *= env;
    }

    // Warm low-pass filter removes the harsh digital buzz.
    lp += (s - lp) * 0.28;
    samples[i] = lp * 0.32;
  }

  writeWavFile(filename, sampleRate, samples);
}

// Demo recordings
generateSpokenToneSample(
  'demo-anniversary',
  ['My', 'dearest', 'Eleanor,', 'every', 'single', 'day', 'with', 'you', 'feels', 'like', 'a', 'melody', 'cut', 'into', 'gold.'],
  135,
  8.0
);

generateSpokenToneSample(
  'demo-grandmother',
  ['Remember', 'sweetheart,', 'always', 'add', 'a', 'pinch', 'of', 'cinnamon', 'and', 'whisk', 'with', 'love.', 'Grandma', 'is', 'always', 'with', 'you.'],
  180,
  9.5
);

generateSpokenToneSample(
  'demo-voice-ocean',
  ['Though', 'the', 'Pacific', 'lies', 'between', 'us', 'tonight,', 'listen', 'to', 'the', 'crackle', 'of', 'this', 'record', 'and', 'know', 'I', 'am', 'home.'],
  140,
  9.0
);

generateSpokenToneSample(
  'demo-raw-sample',
  ['Hello', 'my', 'love,', 'this', 'is', 'a', 'warm', 'voice', 'note', 'recorded', 'straight', 'from', 'my', 'heart.'],
  145,
  6.0
);

// Use the exact same raw source for both comparisons so the mastering difference
// is audible rather than comparing unrelated generated clips.
masterComparisonSample(
  'demo-gramophone-sample',
  '[0:a]highpass=f=420,lowpass=f=2700,equalizer=f=1150:t=q:w=1.2:g=10,tremolo=f=5.2:d=0.22,acompressor=threshold=-22dB:ratio=6:attack=8:release=120,volume=1.35[voice];' +
  '[1:a]highpass=f=700,lowpass=f=6500,volume=0.42[texture];' +
  '[voice][texture]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.92[outa]'
);

masterComparisonSample(
  'demo-lofi-sample',
  '[0:a]lowpass=f=5200,equalizer=f=180:t=q:w=1.1:g=5,equalizer=f=2800:t=q:w=1.5:g=-3,acompressor=threshold=-18dB:ratio=4:attack=18:release=240,vibrato=f=0.65:d=0.018,volume=1.1[voice];' +
  '[1:a]lowpass=f=4200,highpass=f=90,volume=0.48[rain];' +
  '[voice][rain]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.9[outa]'
);

console.log('All synthetic sound assets generated successfully.');
