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

// Universal robust audio filter strings compatible with all FFmpeg builds
function getVoiceFilterString(preset: FilterPresetType): string {
  switch (preset) {
    case 'gramophone':
      // Gramophone acoustic horn curve: 300Hz highpass, 3500Hz lowpass bandpass + vintage compressor
      return 'highpass=f=300,lowpass=f=3500,acompressor=threshold=-18dB:ratio=4:1:attack=20:release=250,volume=1.3';
    case 'radio':
      // 1940s Vintage Vacuum Radio: 200Hz - 4500Hz bandpass + gentle tremolo wave
      return 'highpass=f=200,lowpass=f=4500,tremolo=f=5:d=0.1,volume=1.2';
    case 'tape':
      // 1960s Tape Saturation: 8000Hz lowpass + warm tape compression
      return 'lowpass=f=8000,acompressor=threshold=-12dB:ratio=3:1:attack=15:release=200,volume=1.2';
    case 'clean':
    default:
      return 'volume=1.0,acompressor=threshold=-12dB:ratio=2:1';
  }
}

/**
 * Probes duration of an audio file in seconds.
 */
export function getAudioDuration(filePath: string): Promise<number> {
  return new Promise((resolve) => {
    ffmpeg.ffprobe(filePath, (err, metadata) => {
      if (err || !metadata || !metadata.format || !metadata.format.duration) {
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

  return new Promise((resolve, reject) => {
    try {
      const voiceFilter = getVoiceFilterString(filterPreset);
      const crackleVolume = Math.max(0.01, Math.min(1.0, crackleIntensity * 0.20));
      const bgMusicVolume = 0.18;

      const command = ffmpeg();
      // Input 0: Raw Voice
      command.input(voiceFilePath);

      const complexFilters: string[] = [];
      let inputCount = 1;
      let mixInputs = '[v0]';

      // 1. Process voice track
      complexFilters.push(`[0:a]${voiceFilter}[v0]`);

      // 2. Background Music (Input 1 if present)
      const hasBgMusic = bgMusicFilePath && fs.existsSync(bgMusicFilePath);
      if (hasBgMusic) {
        command.input(bgMusicFilePath).inputOptions(['-stream_loop', '-1']);
        complexFilters.push(`[${inputCount}:a]volume=${bgMusicVolume}[bg]`);
        mixInputs += '[bg]';
        inputCount++;
      }

      // 3. Vinyl Crackle (Input 2 if crackleIntensity > 0 and file exists)
      const hasCrackle = crackleIntensity > 0 && crackleFilePath && fs.existsSync(crackleFilePath);
      if (hasCrackle) {
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
          console.log('[FFmpeg] Synthesis command:', cmdline);
        })
        .on('error', (err, stdout, stderr) => {
          console.warn('[FFmpeg] Primary complex filter error, applying standard synthesis:', err.message);
          try {
            ffmpeg(voiceFilePath)
              .audioFilter(voiceFilter)
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
          console.log('[FFmpeg] Processing finished successfully:', outputFilePath);
          const dur = await getAudioDuration(outputFilePath);
          resolve({ durationSeconds: dur, outputFilePath });
        });

      command.run();
    } catch (err) {
      reject(err);
    }
  });
}
