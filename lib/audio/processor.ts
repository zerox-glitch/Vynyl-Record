import ffmpeg from 'fluent-ffmpeg';
import path from 'path';
import fs from 'fs';
import { FilterPresetType } from '@/types';

// FFmpeg Binary Configuration Directive
try {
  const ffmpegInstaller = require('@ffmpeg-installer/ffmpeg');
  if (ffmpegInstaller && ffmpegInstaller.path && fs.existsSync(ffmpegInstaller.path)) {
    ffmpeg.setFfmpegPath(ffmpegInstaller.path);
  } else {
    const ffmpegStatic = require('ffmpeg-static');
    if (ffmpegStatic && fs.existsSync(ffmpegStatic)) {
      ffmpeg.setFfmpegPath(ffmpegStatic);
    }
  }
} catch (e) {
  // If static module not resolved, rely on system PATH ffmpeg
  console.log('Using system ffmpeg fallback');
}

export interface AudioProcessingOptions {
  voiceFilePath: string;
  outputFilePath: string;
  filterPreset: FilterPresetType;
  crackleIntensity: number; // 0.0 to 1.0
  bgMusicFilePath?: string | null;
  crackleFilePath?: string | null;
}

export interface AudioProcessResult {
  durationSeconds: number;
  outputFilePath: string;
}

// Complex Filter strings according to technical specifications
function getVoiceFilterString(preset: FilterPresetType): string {
  switch (preset) {
    case 'gramophone':
      // Gramophone: highpass=f=300, lowpass=f=3500, acompressor=threshold=-18dB:ratio=4:1, overdrive=gain=3
      return 'highpass=f=300,lowpass=f=3500,acompressor=threshold=-18dB:ratio=4:1,overdrive=gain=3,volume=1.2';
    case 'radio':
      // Vintage Radio: highpass=f=200, lowpass=f=4500, tremolo=f=5:d=0.1
      return 'highpass=f=200,lowpass=f=4500,tremolo=f=5:d=0.1,volume=1.1';
    case 'tape':
      // Tape Saturation: lowpass=f=8000, acrossover=200, volume=1.2
      return 'lowpass=f=8000,volume=1.2';
    case 'clean':
    default:
      return 'volume=1.0,acompressor=threshold=-12dB:ratio=2:1';
  }
}

/**
 * Probes the duration of an audio file in seconds.
 */
export function getAudioDuration(filePath: string): Promise<number> {
  return new Promise((resolve) => {
    ffmpeg.ffprobe(filePath, (err, metadata) => {
      if (err || !metadata || !metadata.format || !metadata.format.duration) {
        // Fallback estimated duration based on file size if probe fails
        try {
          const stats = fs.statSync(filePath);
          const estimated = Math.max(3, Math.min(600, Math.floor(stats.size / 16000)));
          return resolve(estimated);
        } catch {
          return resolve(10);
        }
      }
      resolve(metadata.format.duration);
    });
  });
}

/**
 * Server-Side Audio Synthesis Engine using fluent-ffmpeg.
 * Mixes raw voice + filter chain + background music + vinyl crackle into stereo 192kbps MP3.
 */
export async function processAudioTrack(options: AudioProcessingOptions): Promise<AudioProcessResult> {
  const {
    voiceFilePath,
    outputFilePath,
    filterPreset,
    crackleIntensity,
    bgMusicFilePath,
    crackleFilePath,
  } = options;

  return new Promise(async (resolve, reject) => {
    try {
      const voiceFilter = getVoiceFilterString(filterPreset);
      const crackleVolume = Math.max(0.01, Math.min(1.0, crackleIntensity * 0.20));
      const bgMusicVolume = 0.18;

      const command = ffmpeg();
      // Input 0: Voice
      command.input(voiceFilePath);

      const complexFilters: string[] = [];
      let inputCount = 1;
      let mixInputs = '[v0]';

      // 1. Process voice track
      complexFilters.push(`[0:a]${voiceFilter}[v0]`);

      // 2. Background Music (Input 1 if present)
      const hasBgMusic = bgMusicFilePath && fs.existsSync(bgMusicFilePath);
      if (hasBgMusic) {
        // Loop background music so it covers long recordings
        command.input(bgMusicFilePath).inputOptions(['-stream_loop', '-1']);
        complexFilters.push(`[${inputCount}:a]volume=${bgMusicVolume}[bg]`);
        mixInputs += '[bg]';
        inputCount++;
      }

      // 3. Vinyl Crackle (Input 2 if crackleIntensity > 0 and file exists)
      const hasCrackle = crackleIntensity > 0 && crackleFilePath && fs.existsSync(crackleFilePath);
      if (hasCrackle) {
        // Loop crackle seamlessly
        command.input(crackleFilePath).inputOptions(['-stream_loop', '-1']);
        complexFilters.push(`[${inputCount}:a]volume=${crackleVolume.toFixed(3)}[crk]`);
        mixInputs += '[crk]';
        inputCount++;
      }

      // 4. Combine all inputs with amix=inputs=X:duration=first (matches voice length)
      if (inputCount > 1) {
        complexFilters.push(`${mixInputs}amix=inputs=${inputCount}:duration=first:dropout_transition=2[outa]`);
        command.complexFilter(complexFilters, 'outa');
      } else {
        command.audioFilter(voiceFilter);
      }

      // Export format & bitrate: stereo 192kbps MP3
      command
        .audioCodec('libmp3lame')
        .audioBitrate(192)
        .audioChannels(2)
        .audioFrequency(44100)
        .output(outputFilePath)
        .on('start', (cmdline) => {
          console.log('FFmpeg processing started:', cmdline);
        })
        .on('error', (err, stdout, stderr) => {
          console.error('FFmpeg processing error:', err.message, stderr);
          // Fallback if complex amix has stream mismatch: simple re-encode
          try {
            ffmpeg(voiceFilePath)
              .audioCodec('libmp3lame')
              .audioBitrate(192)
              .output(outputFilePath)
              .on('end', async () => {
                const dur = await getAudioDuration(outputFilePath);
                resolve({ durationSeconds: dur, outputFilePath });
              })
              .on('error', (err2) => {
                reject(err2);
              })
              .run();
          } catch (e2) {
            reject(err);
          }
        })
        .on('end', async () => {
          console.log('FFmpeg processing finished:', outputFilePath);
          const dur = await getAudioDuration(outputFilePath);
          resolve({ durationSeconds: dur, outputFilePath });
        });

      command.run();
    } catch (err) {
      reject(err);
    }
  });
}
