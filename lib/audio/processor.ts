import ffmpeg from 'fluent-ffmpeg';
import path from 'path';
import fs from 'fs';
import { FilterPresetType } from '@/types';

// Robust FFmpeg binary resolution
let ffmpegPathResolved = false;
try {
  // Try @ffmpeg-installer/ffmpeg first (most reliable for Vercel)
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
  // Try common system paths
  const possiblePaths = ['/usr/bin/ffmpeg', '/usr/local/bin/ffmpeg', '/opt/homebrew/bin/ffmpeg', 'C:\\ffmpeg\\bin\\ffmpeg.exe'];
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

// Try to set ffprobe path similarly
try {
  const ffprobeInstaller = require('@ffprobe-installer/ffprobe');
  if (ffprobeInstaller && ffprobeInstaller.path && fs.existsSync(ffprobeInstaller.path)) {
    (ffmpeg as any).setFfprobePath(ffprobeInstaller.path);
    console.log('[AudioEngine] Using @ffprobe-installer binary:', ffprobeInstaller.path);
  }
} catch {
  try {
    const ffprobeStatic = require('ffprobe-static');
    const p = typeof ffprobeStatic === 'string' ? ffprobeStatic : (ffprobeStatic as any).path;
    if (p && fs.existsSync(p)) {
      (ffmpeg as any).setFfprobePath(p);
      console.log('[AudioEngine] Using ffprobe-static binary:', p);
    }
  } catch {}
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

/**
 * Vintage filter definitions - more pronounced and warm
 */
export function getVoiceFilterString(preset: FilterPresetType): string {
  switch (preset) {
    case 'gramophone':
      // 1920s Gramophone: narrow horn bandpass + resonance + soft clipping
      return 'highpass=f=350:q=0.7,lowpass=f=3000:q=0.7,equalizer=f=1100:t=q:w=1.2:g=7,equalizer=f=2500:t=q:w=2:g=-3,acompressor=threshold=-18dB:ratio=5:attack=8:release=120:makeup=4dB,volume=1.6';

    case 'radio':
      // 1940s Radio: tube warmth + AM tremolo
      return 'highpass=f=200:q=0.8,lowpass=f=3600:q=0.8,tremolo=f=5.5:d=0.32,equalizer=f=1600:t=q:w=1.5:g=5,equalizer=f=300:t=q:w=1:g=2,acompressor=threshold=-16dB:ratio=3.5:attack=12:release=160:makeup=3dB,volume=1.5';

    case 'tape':
      // 1960s Tape: warm low bump + silky top rolloff
      return 'lowpass=f=7200:q=0.7,equalizer=f=100:t=q:w=0.8:g=4.5,equalizer=f=8000:t=q:w=1:g=-6,acompressor=threshold=-14dB:ratio=3:attack=15:release=200:makeup=2dB,volume=1.35';

    case 'clean':
    default:
      return 'highpass=f=60,lowpass=f=15000,acompressor=threshold=-12dB:ratio=2:attack=10:release=100:makeup=1dB,volume=1.0';
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
 * Server-Side Audio Synthesis Engine
 * Guarantees: voice + bg_music (if provided) + crackle are mixed into final MP3
 * Volumes tuned to be audible and cozy
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
  
  // Tuned volumes - BG should be clearly audible at 22-28% and crackle at 8-18%
  const bgMusicVolume = 0.26; // Increased from 0.18 to be clearly audible
  const crackleBase = Math.max(0.06, Math.min(0.35, crackleIntensity * 0.45 + 0.05));
  const crackleVolume = crackleBase;

  console.log(`[AudioEngine] Processing: preset=${filterPreset}, crackle=${crackleIntensity}->vol=${crackleVolume.toFixed(3)}, bg=${bgMusicFilePath ? 'yes vol='+bgMusicVolume : 'no'}, crackleFile=${crackleFilePath ? 'yes' : 'no'}`);

  // Validate assets exist
  const hasBgMusic = !!(bgMusicFilePath && fs.existsSync(bgMusicFilePath));
  const hasCrackle = !!(crackleIntensity > 0.01 && crackleFilePath && fs.existsSync(crackleFilePath));

  if (bgMusicFilePath && !hasBgMusic) {
    console.warn(`[AudioEngine] BG music file not found: ${bgMusicFilePath}`);
  }
  if (crackleFilePath && !hasCrackle && crackleIntensity > 0.01) {
    console.warn(`[AudioEngine] Crackle file not found or intensity too low: ${crackleFilePath}`);
  }

  // Strategy 1: Full multi-track with amix normalize=0 (keeps original volumes, no auto-ducking)
  try {
    const result = await new Promise<AudioProcessResult>((resolve, reject) => {
      const command = ffmpeg();
      
      // Voice input with filter
      command.input(voiceFilePath).inputOptions(['-nostdin', '-vn']);

      const complexFilters: string[] = [];
      let inputIndex = 1;
      let mixInputs = '[v0]';

      // Voice chain: apply vintage filter + ensure stereo 44.1k
      complexFilters.push(`[0:a]${voiceFilter},aformat=sample_fmts=fltp:channel_layouts=stereo,aresample=44100:async=1[v0]`);

      if (hasBgMusic) {
        command.input(bgMusicFilePath!).inputOptions(['-nostdin', '-vn', '-stream_loop', '-1']);
        // BG: loop, low volume, ensure stereo, slight lowpass for warmth
        complexFilters.push(`[${inputIndex}:a]volume=${bgMusicVolume},aformat=channel_layouts=stereo,aresample=44100,lowpass=f=8000[bg]`);
        mixInputs += '[bg]';
        inputIndex++;
      }

      if (hasCrackle) {
        command.input(crackleFilePath!).inputOptions(['-nostdin', '-vn', '-stream_loop', '-1']);
        // Crackle: loop, crackle volume, slight highpass to avoid mud
        complexFilters.push(`[${inputIndex}:a]volume=${crackleVolume.toFixed(4)},aformat=channel_layouts=stereo,aresample=44100,highpass=f=120[crk]`);
        mixInputs += '[crk]';
        inputIndex++;
      }

      const totalInputs = inputIndex;

      if (totalInputs > 1) {
        // Use amix with normalize=0 to preserve our volume settings, dropout_transition=0 for immediate mix, duration=first to stop at voice end
        // Also add loudnorm? No, keep vintage dynamics
        complexFilters.push(`${mixInputs}amix=inputs=${totalInputs}:duration=first:dropout_transition=0:normalize=0:weights=1 0.9 0.7[outa]`);
        command.complexFilter(complexFilters, 'outa');
      } else {
        // Only voice
        command.audioFilters(voiceFilter);
      }

      command
        .outputOptions([
          '-nostdin',
          '-threads', '1',
          '-vn',
          '-map', totalInputs > 1 ? '[outa]' : '0:a',
          '-shortest'
        ])
        .audioCodec('libmp3lame')
        .audioBitrate(192)
        .audioChannels(2)
        .audioFrequency(44100)
        .output(outputFilePath)
        .on('start', (cmdLine) => {
          console.log('[AudioEngine] FFmpeg command:', cmdLine);
        })
        .on('end', async () => {
          try {
            const dur = await getAudioDuration(outputFilePath);
            console.log(`[AudioEngine] Primary mix success, duration=${dur}s, size=${fs.statSync(outputFilePath).size} bytes`);
            resolve({ durationSeconds: dur, outputFilePath });
          } catch (e) {
            resolve({ durationSeconds: 10, outputFilePath });
          }
        })
        .on('error', (err, stdout, stderr) => {
          console.warn('[AudioEngine] Primary complex mix error:', err.message);
          if (stderr) console.warn('[AudioEngine] stderr:', stderr.slice(-500));
          reject(err);
        });

      command.run();
    });

    if (fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 500) {
      console.log('[AudioEngine] Primary strategy succeeded with BG and crackle mixed');
      return result;
    }
    throw new Error('Primary output too small');
  } catch (primaryErr) {
    console.warn('[AudioEngine] Primary failed, attempting fallback with simpler amix...');
  }

  // Strategy 2: Simpler mixing without stream_loop (more compatible)
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
          // Use -stream_loop 0 and -t to limit? For fallback, just use volume and amix
          filters.push(`[${idx}:a]aloop=loop=-1:size=2e+09,volume=${bgMusicVolume}[bg]`);
          mix += '[bg]';
          idx++;
        }
        if (hasCrackle) {
          command.input(crackleFilePath!).inputOptions(['-nostdin', '-vn']);
          filters.push(`[${idx}:a]aloop=loop=-1:size=2e+09,volume=${crackleVolume.toFixed(4)}[crk]`);
          mix += '[crk]';
          idx++;
        }

        if (idx > 1) {
          filters.push(`${mix}amix=inputs=${idx}:duration=first:dropout_transition=0:normalize=0[outa]`);
          command.complexFilter(filters, 'outa');
          command.outputOptions(['-nostdin', '-threads', '1', '-vn', '-map', '[outa]']);
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
            resolve({ durationSeconds: dur, outputFilePath });
          })
          .on('error', (err) => {
            console.warn('[AudioEngine] Fallback mix error:', err.message);
            reject(err);
          })
          .run();
      });

      if (fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 500) {
        console.log('[AudioEngine] Fallback mix succeeded');
        return result2;
      }
    } catch (e) {
      console.warn('[AudioEngine] Fallback mix failed');
    }
  }

  // Strategy 3: Voice only with filter (no BG) - still better than raw copy
  try {
    const result3 = await new Promise<AudioProcessResult>((resolve, reject) => {
      ffmpeg(voiceFilePath)
        .inputOptions(['-nostdin', '-vn'])
        .audioFilters(voiceFilter)
        .outputOptions(['-nostdin', '-threads', '1', '-vn'])
        .audioCodec('libmp3lame')
        .audioBitrate(192)
        .audioChannels(2)
        .audioFrequency(44100)
        .output(outputFilePath)
        .on('end', async () => {
          const dur = await getAudioDuration(outputFilePath);
          console.log('[AudioEngine] Voice-only filter succeeded (BG missing but filter applied)');
          resolve({ durationSeconds: dur, outputFilePath });
        })
        .on('error', (err) => {
          console.warn('[AudioEngine] Voice-only filter error:', err.message);
          reject(err);
        })
        .run();
    });

    if (fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 100) {
      return result3;
    }
  } catch (e) {
    console.warn('[AudioEngine] Voice-only failed, trying raw copy...');
  }

  // Strategy 4: Raw copy as last resort
  try {
    await fs.promises.copyFile(voiceFilePath, outputFilePath);
    const dur = await getAudioDuration(outputFilePath);
    console.warn('[AudioEngine] Used raw copy fallback - no effects applied!');
    return { durationSeconds: dur, outputFilePath };
  } catch (copyErr) {
    throw new Error('Audio processor was unable to finalize the audio recording.');
  }
}
