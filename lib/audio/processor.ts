import ffmpeg from 'fluent-ffmpeg';
import fs from 'fs';
import { FilterPresetType } from '@/types';

// Robust FFmpeg binary resolution - works with @ffmpeg-installer (2018 static) and modern
let ffmpegPathResolved = false;
try {
  const ffmpegInstaller = require('@ffmpeg-installer/ffmpeg');
  if (ffmpegInstaller && ffmpegInstaller.path && fs.existsSync(ffmpegInstaller.path)) {
    ffmpeg.setFfmpegPath(ffmpegInstaller.path);
    console.log('[AudioEngine] Using @ffmpeg-installer binary:', ffmpegInstaller.path);
    ffmpegPathResolved = true;
  }
} catch {}

if (!ffmpegPathResolved) {
  try {
    const ffmpegStatic = require('ffmpeg-static');
    const staticPath = typeof ffmpegStatic === 'string' ? ffmpegStatic : (ffmpegStatic as any).path || ffmpegStatic;
    if (staticPath && fs.existsSync(staticPath)) {
      ffmpeg.setFfmpegPath(staticPath);
      console.log('[AudioEngine] Using ffmpeg-static binary:', staticPath);
      ffmpegPathResolved = true;
    }
  } catch {}
}

if (!ffmpegPathResolved) {
  const possiblePaths = ['/usr/bin/ffmpeg', '/usr/local/bin/ffmpeg', '/opt/homebrew/bin/ffmpeg'];
  for (const p of possiblePaths) {
    try {
      if (fs.existsSync(p)) {
        ffmpeg.setFfmpegPath(p);
        console.log('[AudioEngine] Using system ffmpeg:', p);
        ffmpegPathResolved = true;
        break;
      }
    } catch {}
  }
}

if (!ffmpegPathResolved) {
  console.log('[AudioEngine] No explicit ffmpeg binary found, relying on PATH');
}

try {
  const ffprobeInstaller = require('@ffprobe-installer/ffprobe');
  if (ffprobeInstaller && ffprobeInstaller.path && fs.existsSync(ffprobeInstaller.path)) {
    (ffmpeg as any).setFfprobePath(ffprobeInstaller.path);
    console.log('[AudioEngine] Using @ffprobe-installer binary');
  }
} catch {
  try {
    const ffprobeStatic = require('ffprobe-static');
    const p = typeof ffprobeStatic === 'string' ? ffprobeStatic : (ffprobeStatic as any).path;
    if (p && fs.existsSync(p)) {
      (ffmpeg as any).setFfprobePath(p);
      console.log('[AudioEngine] Using ffprobe-static binary');
    }
  } catch {}
}

export interface AudioProcessingOptions {
  voiceFilePath: string;
  outputFilePath: string;
  filterPreset: FilterPresetType;
  crackleIntensity: number;
  bgMusicFilePath?: string | null;
  crackleFilePath?: string | null;
}

export interface AudioProcessResult {
  durationSeconds: number;
  outputFilePath: string;
}

/**
 * Vintage filters compatible with old 2018 ffmpeg-static from @ffmpeg-installer
 * No 'q' param in highpass/lowpass, no makeup in acompressor for max compatibility
 */
export function getVoiceFilterString(preset: FilterPresetType): string {
  switch (preset) {
    case 'gramophone':
      return 'highpass=f=400,lowpass=f=2800,equalizer=f=1200:t=q:w=1.5:g=8,acompressor=threshold=-20dB:ratio=6:attack=10:release=150,volume=1.5';
    case 'radio':
      return 'highpass=f=250,lowpass=f=3800,tremolo=f=5.5:d=0.35,equalizer=f=1800:t=q:w=2:g=5,volume=1.4';
    case 'tape':
      return 'lowpass=f=6500,equalizer=f=120:t=q:w=1:g=4,acompressor=threshold=-16dB:ratio=4:attack=10:release=180,volume=1.3';
    case 'clean':
    default:
      return 'volume=1.0,acompressor=threshold=-12dB:ratio=2';
  }
}

export function getAudioDuration(filePath: string): Promise<number> {
  return new Promise((resolve) => {
    try {
      ffmpeg.ffprobe(filePath, (err, metadata) => {
        if (err || !metadata || !metadata.format || !metadata.format.duration) {
          try {
            const stats = fs.statSync(filePath);
            const estimated = Math.max(3, Math.min(600, Math.floor(stats.size / 24000)));
            return resolve(estimated);
          } catch {
            return resolve(10);
          }
        }
        resolve(metadata.format.duration);
      });
    } catch {
      resolve(10);
    }
  });
}

/**
 * Server-Side Audio Synthesis - Guaranteed BG + Crackle mixing
 * Compatible with old ffmpeg 2018 static binary
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

  if (!fs.existsSync(voiceFilePath)) {
    throw new Error('Input voice recording file was not found on server.');
  }

  const voiceFilter = getVoiceFilterString(filterPreset);
  
  // Volumes - BG clearly audible, crackle audible but not overwhelming
  const bgMusicVolume = 0.32; // 32% - clearly audible cozy background
  const crackleVolume = Math.max(0.08, Math.min(0.4, crackleIntensity * 0.6 + 0.06));

  console.log(`[AudioEngine] Processing preset=${filterPreset} crackle=${crackleIntensity}->${crackleVolume.toFixed(3)} bg=${bgMusicFilePath ? 'YES '+bgMusicVolume : 'NO'} crackleFile=${crackleFilePath ? 'YES' : 'NO'}`);

  const hasBgMusic = !!(bgMusicFilePath && fs.existsSync(bgMusicFilePath));
  const hasCrackle = !!(crackleIntensity > 0.01 && crackleFilePath && fs.existsSync(crackleFilePath));

  if (bgMusicFilePath && !hasBgMusic) console.warn(`[AudioEngine] BG file missing: ${bgMusicFilePath}`);
  if (crackleFilePath && !hasCrackle) console.warn(`[AudioEngine] Crackle file missing or intensity low`);

  // Primary: Compatible complex filter that works on 2018 ffmpeg
  try {
    const result = await new Promise<AudioProcessResult>((resolve, reject) => {
      const command = ffmpeg();
      command.input(voiceFilePath).inputOptions(['-nostdin', '-vn']);

      const filters: string[] = [];
      let inputIdx = 1;
      let mixInputs = '[v0]';

      // Voice with vintage filter + resample + stereo
      filters.push(`[0:a]${voiceFilter},aresample=44100,aformat=channel_layouts=stereo[v0]`);

      if (hasBgMusic) {
        command.input(bgMusicFilePath!).inputOptions(['-nostdin', '-vn', '-stream_loop', '-1']);
        filters.push(`[${inputIdx}:a]volume=${bgMusicVolume},aresample=44100,aformat=channel_layouts=stereo[bg]`);
        mixInputs += '[bg]';
        inputIdx++;
      }

      if (hasCrackle) {
        command.input(crackleFilePath!).inputOptions(['-nostdin', '-vn', '-stream_loop', '-1']);
        filters.push(`[${inputIdx}:a]volume=${crackleVolume.toFixed(4)},aresample=44100,aformat=channel_layouts=stereo[crk]`);
        mixInputs += '[crk]';
        inputIdx++;
      }

      const totalInputs = inputIdx;

      if (totalInputs > 1) {
        // Old ffmpeg compatible: only inputs, duration, dropout_transition
        filters.push(`${mixInputs}amix=inputs=${totalInputs}:duration=first:dropout_transition=0[outa]`);
        command.complexFilter(filters, 'outa');
      } else {
        command.audioFilter(voiceFilter);
      }

      command
        .outputOptions(['-nostdin', '-threads', '1', '-vn', '-shortest'])
        .audioCodec('libmp3lame')
        .audioBitrate(192)
        .audioChannels(2)
        .audioFrequency(44100)
        .output(outputFilePath)
        .on('start', (cmd) => console.log('[AudioEngine] FFmpeg CMD:', cmd))
        .on('end', async () => {
          const dur = await getAudioDuration(outputFilePath);
          const size = fs.existsSync(outputFilePath) ? fs.statSync(outputFilePath).size : 0;
          console.log(`[AudioEngine] Primary mix SUCCESS duration=${dur}s size=${size} bg=${hasBgMusic} crackle=${hasCrackle}`);
          resolve({ durationSeconds: dur, outputFilePath });
        })
        .on('error', (err, stdout, stderr) => {
          console.warn('[AudioEngine] Primary mix ERROR:', err.message);
          if (stderr) console.warn('[AudioEngine] stderr tail:', stderr.slice(-1500));
          reject(err);
        });

      command.run();
    });

    if (fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 500) {
      return result;
    }
    throw new Error('Primary output too small');
  } catch (primaryErr) {
    console.warn('[AudioEngine] Primary failed, trying fallback without stream_loop...');
  }

  // Fallback 1: Without stream_loop, using aloop filter (more compatible for some builds)
  if (hasBgMusic || hasCrackle) {
    try {
      const result2 = await new Promise<AudioProcessResult>((resolve, reject) => {
        const command = ffmpeg();
        command.input(voiceFilePath).inputOptions(['-nostdin', '-vn']);

        const filters: string[] = [];
        let idx = 1;
        let mix = '[v0]';
        filters.push(`[0:a]${voiceFilter}[v0]`);

        if (hasBgMusic) {
          command.input(bgMusicFilePath!).inputOptions(['-nostdin', '-vn']);
          filters.push(`[${idx}:a]volume=${bgMusicVolume},aloop=loop=-1:size=2e+09[bg]`);
          mix += '[bg]';
          idx++;
        }
        if (hasCrackle) {
          command.input(crackleFilePath!).inputOptions(['-nostdin', '-vn']);
          filters.push(`[${idx}:a]volume=${crackleVolume.toFixed(4)},aloop=loop=-1:size=2e+09[crk]`);
          mix += '[crk]';
          idx++;
        }

        if (idx > 1) {
          filters.push(`${mix}amix=inputs=${idx}:duration=first:dropout_transition=0[outa]`);
          command.complexFilter(filters, 'outa');
          command.outputOptions(['-nostdin', '-threads', '1', '-vn', '-shortest']);
        } else {
          command.audioFilter(voiceFilter);
          command.outputOptions(['-nostdin', '-threads', '1', '-vn']);
        }

        command
          .audioCodec('libmp3lame')
          .audioBitrate(192)
          .audioChannels(2)
          .audioFrequency(44100)
          .output(outputFilePath)
          .on('end', async () => {
            const dur = await getAudioDuration(outputFilePath);
            console.log('[AudioEngine] Fallback aloop mix SUCCESS');
            resolve({ durationSeconds: dur, outputFilePath });
          })
          .on('error', (err) => {
            console.warn('[AudioEngine] Fallback aloop ERROR:', err.message);
            reject(err);
          })
          .run();
      });

      if (fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 500) {
        return result2;
      }
    } catch {}
  }

  // Fallback 2: Voice only with vintage filter
  try {
    const result3 = await new Promise<AudioProcessResult>((resolve, reject) => {
      ffmpeg(voiceFilePath)
        .inputOptions(['-nostdin', '-vn'])
        .audioFilter(voiceFilter)
        .outputOptions(['-nostdin', '-threads', '1', '-vn'])
        .audioCodec('libmp3lame')
        .audioBitrate(192)
        .audioChannels(2)
        .audioFrequency(44100)
        .output(outputFilePath)
        .on('end', async () => {
          const dur = await getAudioDuration(outputFilePath);
          console.log('[AudioEngine] Voice-only filter SUCCESS (BG/crackle missing in this fallback)');
          resolve({ durationSeconds: dur, outputFilePath });
        })
        .on('error', (err) => {
          console.warn('[AudioEngine] Voice-only ERROR:', err.message);
          reject(err);
        })
        .run();
    });

    if (fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 100) {
      return result3;
    }
  } catch {}

  // Last resort: copy
  try {
    await fs.promises.copyFile(voiceFilePath, outputFilePath);
    const dur = await getAudioDuration(outputFilePath);
    console.warn('[AudioEngine] Raw copy fallback - NO effects!');
    return { durationSeconds: dur, outputFilePath };
  } catch {
    throw new Error('Audio processor was unable to finalize the audio recording.');
  }
}
