/**
 * Per-preset FFmpeg filter-graph compiler.
 *
 * Turns a VinylPreset into a single `complexFilter` string the worker
 * feeds into fluent-ffmpeg. The chain reads top-to-bottom as the perception
 * order:
 *
 *   [0:a] voice
 *       high-pass    (cartridge rumble cut, set by preset.eq.highPassHz)
 *       low-pass     (high-end roll-off, set by preset.eq.lowPassHz)
 *       saturation   (harmonic warmth via acompressor; depth varies by preset)
 *       mid bell     (presence bump / dip, set by preset.eq.midGainDb)
 *       compander    (gentle dynamic control before wow/flutter)
 *       wow vibrato  (FFmpeg vibrato; depth in cents, sub-perceptual)
 *       flutter vib  (FFmpeg vibrato; depth cents, faster than wow)
 *     -> [voice_mod]
 *
 *   [1:a] bg         (bg music with -stream_loop -1 and -t voiceDur; volume set by preset)
 *     -> [bg]
 *
 *   [2:a] bed        (vinyl bed; high-pass shape set by preset.eq)
 *     -> [bed_q]
 *
 *   [voice_mod][bg][bed_q] amix=inputs=N:duration=longest:dropout_transition=0
 *     volume=N (undoes amix's 1/N renormalisation)
 *     stereotools=mlev=1:slev=W (width)
 *     afade=t=in:d=0.010,afade=t=out:st=t-0.4:d=0.30  (no seams at edges)
 *     alimiter=limit=L:level=false:attack=5:release=80:asc=true  (master ceiling)
 *     volume=masterGain   (final level trim)
 *     -> [vynyl_mix]
 *
 * The terminal link is always `[vynyl_mix]` so fluent-ffmpeg's
 * `.complexFilter(graph, 'vynyl_mix')` has an explicit output to consume.
 */
import { type VinylPreset } from './presets';

export interface ChainInputs {
  voice: string;
  bg: string | null;
  bed: string | null;
}

export interface ChainOptions {
  voiceDuration: number;
  /** User/admin mix level. Falls back to the preset recipe when omitted. */
  bgMusicGain?: number;
}

const TERMINAL = '[vynyl_mix]';

export function buildMasterFilterGraph(
  preset: VinylPreset,
  inputs: ChainInputs,
  opts: ChainOptions,
): string {
  const duration = Math.max(0.6, Number(opts.voiceDuration) || 0);

  /* ---------- 0: voice DSP ----------------------------------------------------*/
  const voiceDsp = [
    `highpass=f=${preset.eq.highPassHz}:t=q:w=0.5`,
    `lowpass=f=${preset.eq.lowPassHz}:t=q:w=0.5`,
    // The bundled static FFmpeg is an older build: acompressor threshold
    // and makeup are linear, not dB. Convert here rather than relying on
    // a newer system FFmpeg that is absent on Vercel.
    `acompressor=threshold=${Math.pow(10, (-18 * (1 - preset.saturation.drive) - 8) / 20).toFixed(5)}:ratio=4:attack=4:release=120:makeup=${(1 + preset.saturation.drive * 0.8).toFixed(3)}:level_sc=1`,
    `equalizer=f=${preset.eq.midFreqHz}:t=q:w=2:g=${preset.eq.midGainDb.toFixed(2)}`,
    `acompressor=threshold=${Math.pow(10, preset.dynamics.companderThresholdDb / 20).toFixed(5)}:ratio=${preset.dynamics.companderRatio.toFixed(2)}:attack=15:release=200:makeup=1`,
  ].join(',');

  // wow + flutter — kept well below 1 cent perceptible threshold.
  const vibs: string[] = [];
  if (preset.wow.enabled && preset.wow.depthCents > 0) {
    // The bundled FFmpeg vibrato filter bottoms out at 0.1 Hz. Vinyl wow
    // recipes below six cycles/minute use that safe floor.
    vibs.push(`vibrato=f=${Math.max(0.1, preset.wow.ratePerMin / 60).toFixed(4)}:d=${(preset.wow.depthCents / 100).toFixed(4)}`);
  }
  if (preset.flutter.enabled && preset.flutter.depthCents > 0) {
    vibs.push(`vibrato=f=${Math.max(0.001, preset.flutter.ratePerMin / 60).toFixed(4)}:d=${(preset.flutter.depthCents / 100).toFixed(4)}`);
  }
  const voiceChain = vibs.length
    ? `${voiceDsp},${vibs.join(',')}`
    : voiceDsp;
  const voiceGraph = `[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,${voiceChain}[voice_mod]`;

  /* ---------- 1: bg music -----------------------------------------------------*/
  const recipeBgGain = 0.18 + 0.05 * preset.saturation.drive;
  const bgGain = Math.max(0, Math.min(0.8, opts.bgMusicGain ?? recipeBgGain));
  const bgChain = inputs.bg && bgGain > 0
    ? `[1:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=${bgGain.toFixed(3)}[bg]`
    : '';

  /* ---------- 2: vinyl bed -----------------------------------------------------*/
  let bedChain = '';
  if (inputs.bed) {
    // Command input order is voice, optional bg, optional bed.
    const bedInputIndex = inputs.bg ? 2 : 1;
    const bedFilter = [
      `highpass=f=${Math.max(40, preset.eq.highPassHz - 30)}:t=q:w=0.5`,
      `lowpass=f=${Math.min(20000, preset.eq.lowPassHz + 200)}:t=q:w=0.5`,
    ].join(',');
    bedChain = `[${bedInputIndex}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,${bedFilter}[bed_q]`;
  }

  /* ---------- downstream mixer + limiter -------------------------------*/
  const inputCount =
    1 +
    (inputs.bg && bgGain > 0 ? 1 : 0) +
    (inputs.bed ? 1 : 0);

  // Inputs to amix follow the mapping [voice_mod, bg?, bed_q?].
  const mixInputs: string =
    inputs.bg && bgGain > 0
      ? inputs.bed
        ? '[voice_mod][bg][bed_q]'
        : '[voice_mod][bg]'
      : inputs.bed
        ? '[voice_mod][bed_q]'
        : '[voice_mod]';

  const fadeOutStart = Math.max(0.05, duration - 0.4).toFixed(3);
  const stereoWidth = Math.max(0.5, Math.min(1.25, preset.stereo.width));
  const widthFilter = preset.stereo.enabled
    ? `stereotools=mlev=1:slev=${stereoWidth.toFixed(3)}`
    : '';
  const tails =
    [
      // FFmpeg amix normalizes by 1/N. Restore unity here; the limiter catches
      // combined peaks instead of leaving voice and music buried by 6–10 dB.
      `volume=${Math.max(1, inputCount).toFixed(3)}`,
      widthFilter,
      // This bundled FFmpeg exposes `level` as a boolean, not level=before.
      `alimiter=limit=${preset.dynamics.limiterCeil.toFixed(3)}:level=false:attack=5:release=80:asc=true`,
      // Head/tail fades so the final mp3 never starts on a click.
      `afade=t=in:d=0.010,afade=t=out:st=${fadeOutStart}:d=0.30`,
      // Final master gain trimming.
      `volume=${preset.eq.masterGain.toFixed(4)}`,
    ].filter(Boolean).join(',');

  /* ---------- wires the chain -------------------------------------- */
  if (inputCount === 1) {
    return `${voiceGraph};[voice_mod]${tails}${TERMINAL}`;
  }
  const subGraphs = [voiceGraph, bgChain, bedChain].filter(Boolean).join(';');
  // Bound the output with `-t voiceDuration` in pressVinylMaster. Using
  // `longest` here prevents a short curated music cut from ending the record
  // early on older FFmpeg builds where amix `first` pad ordering is unstable.
  return `${subGraphs};${mixInputs}amix=inputs=${inputCount}:duration=longest:dropout_transition=0,${tails}${TERMINAL}`;
}
