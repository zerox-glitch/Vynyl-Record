const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const OUTPUT_DIR = path.join(__dirname, '../public/audio');
if (!fs.existsSync(OUTPUT_DIR)) {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
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

  const wavPath = path.join(__dirname, `../tmp/${filename}.wav`);
  fs.writeFileSync(wavPath, buffer);

  const mp3Path = path.join(OUTPUT_DIR, `${filename}.mp3`);
  try {
    execSync(`ffmpeg -y -i "${wavPath}" -codec:a libmp3lame -b:a 192k "${mp3Path}" 2>/dev/null`);
    console.log(`Generated: ${mp3Path}`);
  } catch (err) {
    console.error(`FFmpeg error converting ${filename}:`, err);
  }
}

const sampleRate = 44100;

// 1. NEEDLE DROP
console.log('Generating needle drop...');
const needleDuration = 2.5;
const needleSamples = new Float32Array(Math.floor(sampleRate * needleDuration));
for (let i = 0; i < needleSamples.length; i++) {
  const t = i / sampleRate;
  let s = 0;
  // Tone drop thud at 0.5s
  if (t >= 0.48 && t <= 0.65) {
    const env = Math.exp(-(t - 0.48) * 40);
    s += Math.sin(2 * Math.PI * 80 * t) * env * 0.9;
    s += (Math.random() * 2 - 1) * env * 0.5;
  }
  // Scratch and groove entry 0.55s - 1.2s
  if (t >= 0.52 && t <= 1.2) {
    const env2 = Math.exp(-(t - 0.52) * 8);
    s += (Math.random() * 2 - 1) * 0.3 * env2;
    if (Math.random() < 0.08) s += (Math.random() > 0.5 ? 0.7 : -0.7) * env2;
  }
  // Background groove hum 60Hz and 120Hz
  if (t >= 0.55) {
    const fadeIn = Math.min(1, (t - 0.55) * 4);
    s += Math.sin(2 * Math.PI * 60 * t) * 0.04 * fadeIn;
    s += Math.sin(2 * Math.PI * 120 * t) * 0.02 * fadeIn;
    // light surface noise
    s += (Math.random() * 2 - 1) * 0.05 * fadeIn;
  }
  needleSamples[i] = s;
}
writeWavFile('needle-drop', sampleRate, needleSamples);

// 2. VINYL CRACKLE LOOP (10 seconds seamless)
console.log('Generating vinyl crackle...');
const crackleDuration = 10.0;
const crackleSamples = new Float32Array(Math.floor(sampleRate * crackleDuration));
const revPeriod = 60 / 33.333; // ~1.8 seconds per revolution
for (let i = 0; i < crackleSamples.length; i++) {
  const t = i / sampleRate;
  let s = 0;
  // Subtle rumble & hiss
  const hiss = (Math.random() * 2 - 1) * 0.04;
  const rumble = Math.sin(2 * Math.PI * 55 * t) * 0.03 + Math.sin(2 * Math.PI * 110 * t) * 0.015;
  
  // Periodic vinyl rotational thump
  const phase = (t % revPeriod) / revPeriod;
  if (phase < 0.02) {
    const popEnv = Math.exp(-phase * 150);
    s += Math.sin(2 * Math.PI * 140 * t) * popEnv * 0.25;
  }

  // Random dust clicks and crackles
  if (Math.random() < 0.003) {
    const pop = (Math.random() * 2 - 1) * (0.2 + Math.random() * 0.4);
    s += pop;
  }

  crackleSamples[i] = s + hiss + rumble;
}
writeWavFile('crackle-vintage', sampleRate, crackleSamples);

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

  for (let i = 0; i < total; i++) {
    const t = i / sampleRate;
    const wordIdx = Math.floor(t / wordDuration);
    const wordTime = t % wordDuration;

    // Formant voice synthesis (formants F1, F2, F3)
    let s = 0;
    if (wordTime < wordDuration * 0.85) {
      // Modulated vocal cords fundamental
      const pitchMod = Math.sin(2 * Math.PI * 1.5 * t) * 6;
      const f0 = baseToneHz + pitchMod + (wordIdx % 3) * 8;
      
      const v1 = Math.sin(2 * Math.PI * f0 * t);
      const f1 = 500 + Math.sin(t * 8) * 200; // Vowel formant 1
      const f2 = 1500 + Math.cos(t * 12) * 400; // Vowel formant 2
      const f3 = 2500;
      
      s += v1 * 0.3;
      s += Math.sin(2 * Math.PI * f1 * t) * 0.25;
      s += Math.sin(2 * Math.PI * f2 * t) * 0.15;
      s += Math.sin(2 * Math.PI * f3 * t) * 0.08;
      
      // Sibilants / consonants at word start
      if (wordTime < 0.05) {
        s += (Math.random() * 2 - 1) * 0.2;
      }

      // Smooth attack & decay per syllable
      const sylEnv = Math.sin((wordTime / (wordDuration * 0.85)) * Math.PI);
      s *= sylEnv;
    }
    samples[i] = s * 0.3;
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

// Gramophone sample
generateSpokenToneSample(
  'demo-gramophone-sample',
  ['Hello', 'my', 'love,', 'this', 'is', 'a', 'warm', 'vintage', 'gramophone', 'masterpiece.'],
  145,
  6.0
);

// Lo-Fi sample
generateSpokenToneSample(
  'demo-lofi-sample',
  ['Hello', 'my', 'love,', 'immersed', 'in', 'warm', 'rain', 'and', 'analog', 'tape', 'saturation.'],
  145,
  6.0
);

console.log('All synthetic sound assets generated successfully.');
