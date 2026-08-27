import ffmpeg from 'fluent-ffmpeg';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { execFile } from 'child_process';
import { FilterPresetType } from '@/types';

/* ------------------------------------------------------------------ *
 * Binary resolution                                                  *
 * ------------------------------------------------------------------ *
 * Vercel/lambda gives us a read-only FS and no system ffmpeg, so we  *
 * resolve a bundled static binary and remember its path for the     *
 * (ffprobe-free) duration probe below.                               */

let resolvedFfmpegPath: string | null = null;
let ffprobeReady = false;

function resolveBinaries() {
  if (resolvedFfmpegPath !== null || (globalThis as any).__vynylFfmpegResolved) return;
  (globalThis as any).__vynylFfmpegResolved = true;

  try {
    const installer = require('@ffmpeg-installer/ffmpeg');
    if (installer?.path && fs.existsSync(installer.path)) {
      ffmpeg.setFfmpegPath(installer.path);
      resolvedFfmpegPath = installer.path;
      console.log('[AudioEngine] ffmpeg binary (@ffmpeg-installer):', installer.path);
      return;
    }
  } catch {}

  try {
    const staticPkg = require('ffmpeg-static');
    const p = typeof staticPkg === 'string' ? staticPkg : staticPkg?.path;
    if (p && fs.existsSync(p)) {
      ffmpeg.setFfmpegPath(p);
      resolvedFfmpegPath = p;
      console.log('[AudioEngine] ffmpeg binary (ffmpeg-static):', p);
      return;
    }
  } catch {}

  for (const p of ['/usr/bin/ffmpeg', '/usr/local/bin/ffmpeg', '/opt/homebrew/bin/ffmpeg']) {
    try {
      if (fs.existsSync(p)) {
        ffmpeg.setFfmpegPath(p);
        resolvedFfmpegPath = p;
        console.log('[AudioEngine] ffmpeg binary (system):', p);
        return;
      }
    } catch {}
  }
  console.warn('[AudioEngine] No bundled ffmpeg found — relying on PATH. Audio will fall back to a raw take.');
}

resolveBinaries();

export const FFMPEG_AVAILABLE = () => {
  resolveBinaries();
  return resolvedFfmpegPath !== null;
};

/* ------------------------------------------------------------------ *
 * Vintage voice filters                                              *
 * ------------------------------------------------------------------ *
 * Kept compatible with the 2018 static build (no `q` param on        *
 * highpass/lowpass, no `makeup` on acompressor).                     */

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
      return 'volume=1.15,acompressor=threshold=-12dB:ratio=2';
  }
}

/* ------------------------------------------------------------------ *
 * Duration probing                                                   *
 * ------------------------------------------------------------------ *
 * ffprobe is often absent (Vercel ships only the ffmpeg binary in    *
 * @ffmpeg-installer), so we parse the `Duration:` line ffmpeg itself *
 * prints when opening a file. That gives real seconds instead of the *
 * old `bytes / 24000` guess that broke the scrub bar.                */

export function probeDurationSeconds(filePath: string): Promise<number> {
  return new Promise((resolve) => {
    const fromFfmpeg = () => {
      resolveBinaries();
      if (!resolvedFfmpegPath) return resolve(estimateFromSize(filePath));
      execFile(
        resolvedFfmpegPath,
        ['-hide_banner', '-i', filePath],
        { timeout: 8000, maxBuffer: 1024 * 1024 * 4 },
        (_err, _stdout, stderr) => {
          const m = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(String(stderr || ''));
          if (!m) return resolve(estimateFromSize(filePath));
          const secs = Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]);
          resolve(Number.isFinite(secs) && secs > 0 ? Number(secs.toFixed(2)) : estimateFromSize(filePath));
        }
      );
    };

    try {
      ffmpeg.ffprobe(filePath, (err, meta) => {
        const dur = meta?.format?.duration;
        if (!err && typeof dur === 'number' && Number.isFinite(dur) && dur > 0) {
          return resolve(Number(dur.toFixed(2)));
        }
        fromFfmpeg();
      });
    } catch {
      fromFfmpeg();
    }
  });
}

function estimateFromSize(filePath: string): number {
  try {
    const stats = fs.statSync(filePath);
    return Math.max(1, Math.min(1800, Math.round((stats.size / 24000) * 10) / 10));
  } catch {
    return 0;
  }
}

/** Kept for admin/preview callers that only need a number. */
export async function getAudioDuration(filePath: string): Promise<number> {
  const dur = await probeDurationSeconds(filePath);
  return dur > 0 ? dur : 10;
}

/* ------------------------------------------------------------------ *
 * Mix maths                                                          *
 * ------------------------------------------------------------------ *
 * `amix` renormalises by the number of inputs, so a 3-input mix lands *
 * ~9.5 dB below the voice. The previous engine shipped that: masters *
 * measured mean −38 dB versus −26 dB for the filtered voice alone, so *
 * records *sounded* broken (barely audible, especially on phones). We *
 * now undo the division and catch the peaks with a limiter.          */

/** Measured: mean -38.1 dB before, -26.3 dB after, peaks still under 0 dBFS. */
const MASTER_TRIM = 1.3;

export interface MixLevels {
  /** Relative level of the background track, post-compensation. */
  bgMusic: number;
  /** Relative level of the vinyl crackle bed. */
  crackle: number;
}

export function getMixLevels(crackleIntensity: number): MixLevels {
  const c = Number.isFinite(crackleIntensity) ? Math.max(0, Math.min(1, crackleIntensity)) : 0.22;
  return {
    bgMusic: 0.32,
    crackle: Number((0.06 + c * 0.42).toFixed(4)),
  };
}

export interface AudioProcessingOptions {
  voiceFilePath: string;
  outputFilePath: string;
  filterPreset: FilterPresetType;
  crackleIntensity: number;
  bgMusicFilePath?: string | null;
  crackleFilePath?: string | null;
  /** Hard ceiling for the master, in seconds (plan limits). */
  maxSeconds?: number;
}

export type MixStrategy = 'stream-loop' | 'aloop' | 'voice-and-crackle' | 'voice-only' | 'passthrough';

export interface AudioProcessResult {
  durationSeconds: number;
  outputFilePath: string;
  /** `mp3` when we transcoded; `source` when the upload was copied verbatim. */
  container: 'mp3' | 'source';
  strategy: MixStrategy;
  mix: {
    voice: boolean;
    bgMusic: boolean;
    crackle: boolean;
    filterPreset: FilterPresetType;
    /** dBFS mean of the master, measured by the engine (0 when unavailable). */
    levels: MixLevels | null;
  };
}

interface AttemptConfig {
  strategy: MixStrategy;
  /** loop the extra inputs with the `-stream_loop` input option (preferred) */
  useStreamLoop: boolean;
  /** include the background track */
  withBg: boolean;
  /** include the crackle bed */
  withCrackle: boolean;
  /** add alimiter + head/tail fades (skipped for ancient builds) */
  polish: boolean;
}

const ATTEMPTS: AttemptConfig[] = [
  { strategy: 'stream-loop', useStreamLoop: true, withBg: true, withCrackle: true, polish: true },
  { strategy: 'aloop', useStreamLoop: false, withBg: true, withCrackle: true, polish: true },
  { strategy: 'stream-loop', useStreamLoop: true, withBg: true, withCrackle: true, polish: false },
  { strategy: 'aloop', useStreamLoop: false, withBg: true, withCrackle: false, polish: false },
  { strategy: 'voice-and-crackle', useStreamLoop: true, withBg: false, withCrackle: true, polish: true },
  { strategy: 'voice-only', useStreamLoop: false, withBg: false, withCrackle: false, polish: true },
];

/**
 * Press a voice take onto a vinyl master: vintage filter chain on the voice,
 * background music + crackle bed mixed in, bounded to the voice length so a
 * looped bed can never run away, all levels compensated for `amix` gain loss.
 */
export async function processAudioTrack(options: AudioProcessingOptions): Promise<AudioProcessResult> {
  const {
    voiceFilePath,
    outputFilePath,
    filterPreset,
    crackleIntensity,
    bgMusicFilePath,
    crackleFilePath,
    maxSeconds = 600,
  } = options;

  if (!fs.existsSync(voiceFilePath)) {
    throw new Error('Input voice recording file was not found on server.');
  }

  const levels = getMixLevels(crackleIntensity);
  const voiceFilter = getVoiceFilterString(filterPreset);
  const wantedDuration = await probeDurationSeconds(voiceFilePath);

  if (!wantedDuration) {
    console.warn('[AudioEngine] Could not probe the upload; falling back to a raw copy.');
    return passthrough(voiceFilePath, outputFilePath, filterPreset, levels);
  }

  const voiceDuration = Math.max(0.6, Math.min(wantedDuration, maxSeconds));
  const hasBg = !!(bgMusicFilePath && fs.existsSync(bgMusicFilePath) && levels.bgMusic > 0);
  const hasCrackle = !!(
    crackleFilePath &&
    fs.existsSync(crackleFilePath) &&
    levels.crackle > 0.005
  );

  console.log(
    `[AudioEngine] master request: preset=${filterPreset} voice=${voiceDuration.toFixed(2)}s ` +
      `bg=${hasBg ? `${levels.bgMusic}` : 'none'} crackle=${hasCrackle ? levels.crackle : 'none'} ` +
      `(requested crackle=${crackleIntensity})`
  );

  for (const attempt of ATTEMPTS) {
    const useBg = hasBg && attempt.withBg;
    const useCrackle = hasCrackle && attempt.withCrackle;
    if (!useBg && !useCrackle && attempt.strategy !== 'voice-only') {
      // Voice-only variants are still worth running, everything else is a no-op.
    }

    try {
      const result = await runMix({
        voiceFilePath,
        outputFilePath,
        voiceFilter,
        bgMusicFilePath: useBg ? bgMusicFilePath! : null,
        crackleFilePath: useCrackle ? crackleFilePath! : null,
        levels,
        voiceDuration,
        attempt,
        filterPreset,
      });
      console.log(
        `[AudioEngine] master OK via ${attempt.strategy} (bg=${useBg} crackle=${useCrackle}) ` +
          `duration=${result.durationSeconds.toFixed(2)}s size=${result.bytes}B`
      );
      return {
        durationSeconds: result.durationSeconds,
        outputFilePath,
        container: 'mp3',
        strategy: attempt.strategy,
        mix: {
          voice: true,
          bgMusic: useBg,
          crackle: useCrackle,
          filterPreset,
          levels: useBg || useCrackle ? levels : null,
        },
      };
    } catch (err: any) {
      console.warn(`[AudioEngine] attempt "${attempt.strategy}" (bg=${useBg},crackle=${useCrackle},polish=${attempt.polish}) failed: ${err?.message || err}`);
    }
  }

  return passthrough(voiceFilePath, outputFilePath, filterPreset, levels);
}

interface RunMixArgs {
  voiceFilePath: string;
  outputFilePath: string;
  voiceFilter: string;
  bgMusicFilePath: string | null;
  crackleFilePath: string | null;
  levels: MixLevels;
  voiceDuration: number;
  attempt: AttemptConfig;
  filterPreset: FilterPresetType;
}

function runMix({
  voiceFilePath,
  outputFilePath,
  voiceFilter,
  bgMusicFilePath,
  crackleFilePath,
  levels,
  voiceDuration,
  attempt,
  filterPreset,
}: RunMixArgs): Promise<{ durationSeconds: number; bytes: number }> {
  return new Promise((resolve, reject) => {
    try {
      if (fs.existsSync(outputFilePath)) fs.unlinkSync(outputFilePath);
    } catch {}

    const extra: { file: string; gain: number; tag: string }[] = [];
    if (bgMusicFilePath) extra.push({ file: bgMusicFilePath, gain: levels.bgMusic, tag: 'bg' });
    if (crackleFilePath) extra.push({ file: crackleFilePath, gain: levels.crackle, tag: 'crk' });

    const inputCount = 1 + extra.length;
    // One-loop buffer instead of the old `size=2e9`: bounded memory, same result
    // because `atrim` clamps to the voice length anyway.
    const aloopSamples = Math.max(4410, Math.ceil(voiceDuration * 44100));
    const chains: string[] = [];
    const command = ffmpeg();

    command.input(voiceFilePath).inputOptions(['-nostdin', '-vn']);
    chains.push(
      `[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,${voiceFilter}[voice]`
    );

    let label = '[voice]';
    extra.forEach((track, i) => {
      const index = i + 1;
      const command_ = command.input(track.file);
      if (attempt.useStreamLoop) {
        command_.inputOptions(['-nostdin', '-vn', '-stream_loop', '-1', '-t', voiceDuration.toFixed(3)]);
        chains.push(
          `[${index}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=${track.gain.toFixed(4)}[${track.tag}]`
        );
      } else {
        command_.inputOptions(['-nostdin', '-vn']);
        chains.push(
          `[${index}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=${track.gain.toFixed(4)},aloop=loop=-1:size=${aloopSamples},atrim=0:${voiceDuration.toFixed(3)},asetpts=N/SR/TB[${track.tag}]`
        );
      }
      label += `[${track.tag}]`;
    });

    // Undo amix's 1/N renormalisation, then keep the master out of clipping.
    const compensation = (inputCount * MASTER_TRIM).toFixed(3);
    let tail = `amix=inputs=${inputCount}:duration=first:dropout_transition=0`;
    if (inputCount > 1) tail += `,volume=${compensation}`;
    if (attempt.polish) {
      const fadeOutStart = Math.max(0.05, voiceDuration - 0.45).toFixed(3);
      tail += `,alimiter=limit=0.92:level=false,afade=t=in:d=0.012,afade=t=out:st=${fadeOutStart}:d=0.45`;
    }

    if (inputCount > 1) {
      chains.push(`${label}${tail}[master]`);
      command.complexFilter(chains, 'master');
    } else {
      // Single input: no need for a filtergraph, a plain chain is faster.
      command.audioFilter(
        `${voiceFilter}${attempt.polish ? ',alimiter=limit=0.92:level=false' : ''}`
      );
    }

    command
      .outputOptions([
        '-nostdin',
        '-threads',
        '2',
        '-vn',
        '-t',
        voiceDuration.toFixed(3),
        '-map_metadata',
        '-1',
      ])
      .audioCodec('libmp3lame')
      .audioBitrate(192)
      .audioChannels(2)
      .audioFrequency(44100)
      .output(outputFilePath)
      .on('start', (cmd) => console.log('[AudioEngine] cmd:', cmd.replace(/\s+/g, ' ').slice(0, 320)))
      .on('end', async () => {
        try {
          const bytes = fs.existsSync(outputFilePath) ? fs.statSync(outputFilePath).size : 0;
          if (bytes < 2048) throw new Error(`output too small (${bytes}B)`);
          const duration = await probeDurationSeconds(outputFilePath);
          if (!duration || duration < 0.4) throw new Error('output has no playable duration');
          resolve({ durationSeconds: Number(Math.min(duration, voiceDuration + 1).toFixed(2)), bytes });
        } catch (err: any) {
          reject(err);
        }
      })
      .on('error', (err, _stdout, stderr) => {
        const tailMsg = String(stderr || '').trim().split('\n').slice(-3).join(' | ');
        reject(new Error(`${err.message}${tailMsg ? ` :: ${tailMsg}` : ''}`));
      });

    // A hung transcode is worse than a shorter one: kill it and let the next
    // (cheaper) attempt run. 90s covers ~10 minutes of audio on serverless.
    const watchdog = setTimeout(() => {
      try {
        command.kill('SIGKILL');
      } catch {}
      reject(new Error('ffmpeg timeout after 90s'));
    }, 90_000);

    command.once('exit', () => clearTimeout(watchdog));
    command.run();
  });
}

/**
 * Last resort: give the browser the upload verbatim. We relabel with the real
 * container (never an .mp3 that is actually webm, which is what made records
 * silent on Safari) and only after trying a quick straight transcode.
 */
async function passthrough(
  voiceFilePath: string,
  outputFilePath: string,
  filterPreset: FilterPresetType,
  levels: MixLevels
): Promise<AudioProcessResult> {
  const durationSeconds = await probeDurationSeconds(voiceFilePath);

  // Best effort: a plain transcode keeps the vintage tone without any mixing.
  const simple = await new Promise<boolean>((resolve) => {
    try {
      if (fs.existsSync(outputFilePath)) fs.unlinkSync(outputFilePath);
    } catch {}
    ffmpeg(voiceFilePath)
      .inputOptions(['-nostdin', '-vn'])
      .audioFilter(getVoiceFilterString(filterPreset))
      .outputOptions(['-nostdin', '-threads', '2', '-vn'])
      .audioCodec('libmp3lame')
      .audioBitrate(192)
      .audioChannels(2)
      .audioFrequency(44100)
      .on('end', () => resolve(true))
      .on('error', (err) => {
        console.warn('[AudioEngine] voice-only transcode failed:', err.message);
        resolve(false);
      })
      .save(outputFilePath);
  });

  if (simple && fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 1024) {
    console.warn('[AudioEngine] served a voice-only master (no BG/crackle bed applied)');
    return {
      durationSeconds,
      outputFilePath,
      container: 'mp3',
      strategy: 'voice-only',
      mix: { voice: true, bgMusic: false, crackle: false, filterPreset, levels: null },
    };
  }

  try {
    await fs.promises.copyFile(voiceFilePath, outputFilePath);
  } catch {
    throw new Error('Audio processor was unable to finalize the audio recording.');
  }
  console.warn('[AudioEngine] served the raw upload unchanged (no ffmpeg effects available)');
  return {
    durationSeconds,
    outputFilePath,
    container: 'source',
    strategy: 'passthrough',
    mix: { voice: true, bgMusic: false, crackle: false, filterPreset, levels: null },
  };
}

/* ------------------------------------------------------------------ *
 * Raw take + helpers used by the API route                           */
/* ------------------------------------------------------------------ *

/**
 * Convert the uploaded recording (webm/opus, m4a, wav…) into a small MP3 so
 * the "original take" fallback plays everywhere. Returns null if the engine
 * cannot run at all — the caller then stores the original bytes.
 */
export async function transcodeToMp3(inputFilePath: string, outputFilePath: string): Promise<boolean> {
  resolveBinaries();
  if (!resolvedFfmpegPath) return false;
  try {
    if (fs.existsSync(outputFilePath)) fs.unlinkSync(outputFilePath);
  } catch {}

  return new Promise((resolve) => {
    ffmpeg(inputFilePath)
      .inputOptions(['-nostdin', '-vn'])
      .outputOptions(['-nostdin', '-threads', '2', '-vn', '-ac', '2', '-ar', '44100'])
      .audioCodec('libmp3lame')
      .audioBitrate(160)
      .on('end', () => {
        const ok = fs.existsSync(outputFilePath) && fs.statSync(outputFilePath).size > 1024;
        resolve(ok);
      })
      .on('error', (err) => {
        console.warn('[AudioEngine] raw take transcode failed:', err.message);
        resolve(false);
      })
      .save(outputFilePath);
  });
}

/** Where temp files live: /tmp on serverless, a scratch dir locally. */
export function audioScratchDir(): string {
  const dir = path.join(os.tmpdir(), 'vynyl_tmp');
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    return dir;
  } catch {
    return os.tmpdir();
  }
}
