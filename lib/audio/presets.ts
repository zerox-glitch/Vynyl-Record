/**
 * Vinyl preset recipes.
 *
 * Each preset is a complete audio recipe: saturation drive + EQ + wow/flutter
 * + deterministic noise + crackle + pops + master gain. The Studio UI hands the
 * worker the preset id; the worker expands it into chained FFmpeg filters and
 * runs the offline DSP layer that generates the per-recording noise / crackle
 * bed before mixing.
 *
 * Adding a new preset is a one-line entry here. No other file in the codebase
 * needs to change.
 */
import type { FilterPresetType } from '@/types';

export type NoiseFilterShape = 'flat' | 'highpass-low' | 'highpass-flat' | 'lowpass-only';

/** Vinyl saturation: how the tape stage clips. */
export interface SaturationParams {
  /** 0…1 — drive into the soft clipper. Higher = warmer, then crushed. */
  drive: number;
  /** 0…1 — wet/dry mix. 0 = clean bypass, 1 = fully wet. */
  mix: number;
}

/** Tonal shaping applied across the audible band. */
export interface EqParams {
  /** High-pass cutoff in Hz. Removes low-end rumble. */
  highPassHz: number;
  /** Low-pass cutoff in Hz. The “softer highs” knob. */
  lowPassHz: number;
  /** Presence / mid-bell gain in dB; can be negative. */
  midGainDb: number;
  /** Mid-bell center frequency in Hz. */
  midFreqHz: number;
  /** Final master gain in linear. */
  masterGain: number;
}

/** Subtle slow pitch instability — motor wow. */
export interface WowParams {
  enabled: boolean;
  /** Peak depth in semitones-equivalent (centibels). Keep ≤ 12. */
  depthCents: number;
  /** Cycles per minute (~3.5 rpm motor). */
  ratePerMin: number;
}

/** Faster small flutter — bearing noise / slight rotational irregularity. */
export interface FlutterParams {
  enabled: boolean;
  /** Peak depth in cents. Keep ≤ 3. */
  depthCents: number;
  /** Cycles per minute. Real decks run ~4800 rpm, perceived as blur. */
  ratePerMin: number;
}

/** Vinyl surface noise layer. */
export interface SurfaceNoiseParams {
  enabled: boolean;
  /** Linear gain before the master limiter. -50 ≈ inaudible, -22 ≈ clearly audible. */
  levelDb: number;
  /** Spectral shape of the broadband noise bed. */
  filter: NoiseFilterShape;
}

/** Randomized crackle — short transients, stochastic timing. */
export interface CrackleParams {
  enabled: boolean;
  /** Average crackles per minute. Slow but irregular. */
  densityPerMin: number;
  /** Amplitude ceiling per crackle (0…1). Subtle by default. */
  intensity: number;
}

/** Occasional larger vinyl pops. */
export interface PopsParams {
  enabled: boolean;
  /** Average pops per minute. Real old records: 1-4 / min. */
  densityPerMin: number;
  /** Amplitude ceiling per pop. Sparse but visible. */
  intensity: number;
}

/** Stereo image width. */
export interface StereoParams {
  enabled: boolean;
  /** 0.5 = mid, 1 = normal, 1.05 = slight widening. Old records often narrow. */
  width: number;
}

/** Light dynamic range control over the entire mix. */
export interface DynamicsParams {
  /** Final alimiter target. 0.95 leaves peaks transparent while never clipping. */
  limiterCeil: number;
  /** Compander threshold in dBFS for the mid stage. -22 is gentle. */
  companderThresholdDb: number;
  /** Compander ratio. 2.5:1 stays musical. */
  companderRatio: number;
}

export interface VinylPreset {
  id: 'clean_vinyl' | 'warm_vintage' | 'dusty_record' | 'old_family_record' | 'rare_archival';
  name: string;
  description: string;
  /** Short sentence the UI can render under the preset name. */
  character: string;
  saturation: SaturationParams;
  eq: EqParams;
  wow: WowParams;
  flutter: FlutterParams;
  surface: SurfaceNoiseParams;
  crackle: CrackleParams;
  pops: PopsParams;
  stereo: StereoParams;
  dynamics: DynamicsParams;
  /** When seeds for noise/crackle/pops are derived from the recording id. */
  seedFromRecordingId: boolean;
}

export const VINYL_PRESETS: Record<VinylPreset['id'], VinylPreset> = {
  clean_vinyl: {
    id: 'clean_vinyl',
    name: 'Clean Vinyl',
    description: 'A fresh pressing: warm, present, almost-imperceptible crackle.',
    character: 'Warm tape • soft highs • barely-audible texture',
    saturation: { drive: 0.08, mix: 0.5 },
    eq: { highPassHz: 90, lowPassHz: 16500, midGainDb: -1, midFreqHz: 3200, masterGain: 1.0 },
    wow:     { enabled: true, depthCents: 3, ratePerMin: 3.4 },
    flutter: { enabled: true, depthCents: 0.6, ratePerMin: 2400 },
    surface: { enabled: true, levelDb: -42, filter: 'highpass-flat' },
    crackle: { enabled: true, densityPerMin: 10, intensity: 0.20 },
    pops:    { enabled: true, densityPerMin: 0.4, intensity: 0.55 },
    stereo:  { enabled: true, width: 1.00 },
    dynamics: { limiterCeil: 0.96, companderThresholdDb: -24, companderRatio: 1.6 },
    seedFromRecordingId: true,
  },

  warm_vintage: {
    id: 'warm_vintage',
    name: 'Warm Vintage',
    description: 'A well-loved record from the 70s. Visible breath, denser bed.',
    character: 'Warm tape • soft highs • medium crackle • gentle wow',
    saturation: { drive: 0.28, mix: 0.75 },
    eq: { highPassHz: 75, lowPassHz: 14200, midGainDb:  1.5, midFreqHz: 2800, masterGain: 0.92 },
    wow:     { enabled: true, depthCents: 6, ratePerMin: 3.3 },
    flutter: { enabled: true, depthCents: 1.2, ratePerMin: 2700 },
    surface: { enabled: true, levelDb: -34, filter: 'highpass-flat' },
    crackle: { enabled: true, densityPerMin: 22, intensity: 0.38 },
    pops:    { enabled: true, densityPerMin: 0.9, intensity: 0.65 },
    stereo:  { enabled: true, width: 0.95 },
    dynamics: { limiterCeil: 0.94, companderThresholdDb: -22, companderRatio: 2.2 },
    seedFromRecordingId: true,
  },

  dusty_record: {
    id: 'dusty_record',
    name: 'Dusty Record',
    description: 'A record pulled out of an attic sleeve. Audible grit throughout.',
    character: 'Tape grit • softer mids • clear crackle • dirt texture',
    saturation: { drive: 0.45, mix: 0.85 },
    eq: { highPassHz: 70, lowPassHz: 12600, midGainDb: 0.0, midFreqHz: 2400, masterGain: 0.90 },
    wow:     { enabled: true, depthCents: 8, ratePerMin: 3.2 },
    flutter: { enabled: true, depthCents: 1.8, ratePerMin: 2900 },
    surface: { enabled: true, levelDb: -28, filter: 'highpass-low' },
    crackle: { enabled: true, densityPerMin: 38, intensity: 0.55 },
    pops:    { enabled: true, densityPerMin: 1.6, intensity: 0.75 },
    stereo:  { enabled: true, width: 0.92 },
    dynamics: { limiterCeil: 0.94, companderThresholdDb: -22, companderRatio: 2.5 },
    seedFromRecordingId: true,
  },

  old_family_record: {
    id: 'old_family_record',
    name: 'Old Family Record',
    description: 'A mid-century voice memo rescued from a magnetic tape.',
    character: 'Warm tube • softer highs • narrower stereo • wandering wow',
    saturation: { drive: 0.55, mix: 0.95 },
    eq: { highPassHz: 65, lowPassHz: 11200, midGainDb: -2, midFreqHz: 1800, masterGain: 0.94 },
    wow:     { enabled: true, depthCents: 12, ratePerMin: 3.1 },
    flutter: { enabled: true, depthCents: 2.6, ratePerMin: 3300 },
    surface: { enabled: true, levelDb: -28, filter: 'flat' },
    crackle: { enabled: true, densityPerMin: 28, intensity: 0.45 },
    pops:    { enabled: true, densityPerMin: 1.0, intensity: 0.65 },
    stereo:  { enabled: true, width: 0.88 },
    dynamics: { limiterCeil: 0.93, companderThresholdDb: -21, companderRatio: 3.0 },
    seedFromRecordingId: true,
  },

  rare_archival: {
    id: 'rare_archival',
    name: 'Rare / Archival',
    description: 'A domestic tape transferred barely in time. Old, cherished, listenable.',
    character: 'Tube grit • darker EQ • sparse but obvious crackle • slight distortion',
    saturation: { drive: 0.65, mix: 1.0 },
    eq: { highPassHz: 60, lowPassHz: 9800, midGainDb: -3, midFreqHz: 1500, masterGain: 0.92 },
    wow:     { enabled: true, depthCents: 18, ratePerMin: 2.9 },
    flutter: { enabled: true, depthCents: 3.0, ratePerMin: 3600 },
    surface: { enabled: true, levelDb: -24, filter: 'flat' },
    crackle: { enabled: true, densityPerMin: 48, intensity: 0.65 },
    pops:    { enabled: true, densityPerMin: 2.0, intensity: 0.80 },
    stereo:  { enabled: true, width: 0.85 },
    dynamics: { limiterCeil: 0.92, companderThresholdDb: -20, companderRatio: 3.5 },
    seedFromRecordingId: true,
  },
};

/** Stable order for serialisation (also documents the gradient). */
export const VINYL_PRESET_ORDER: VinylPreset['id'][] = [
  'clean_vinyl',
  'warm_vintage',
  'dusty_record',
  'old_family_record',
  'rare_archival',
];

export const DEFAULT_VINYL_PRESET_ID: VinylPreset['id'] = 'warm_vintage';

/**
 * Backwards-compatible mapping from the original four FilterPresetType values
 * that the rest of the codebase still uses (Studio / API / DB).
 */
export const FILTER_TO_VINYL_PRESET: Record<FilterPresetType, VinylPreset['id']> = {
  clean:      'clean_vinyl',
  gramophone: 'warm_vintage',
  radio:      'old_family_record',
  tape:       'dusty_record',
};

/** Lookup helper that accepts either typing. */
export function resolveVinylPreset(key: string | undefined | null): VinylPreset {
  if (key && (key as VinylPreset['id']) in VINYL_PRESETS) return VINYL_PRESETS[key as VinylPreset['id']];
  return VINYL_PRESETS[DEFAULT_VINYL_PRESET_ID];
}

export function isVinylPresetId(value: unknown): value is VinylPreset['id'] {
  return typeof value === 'string' && value in VINYL_PRESETS;
}

/** Client-safe one-line character summary used by the Studio selector. */
export function presetFlavor(preset: VinylPreset): string {
  const parts: string[] = [];
  if (preset.saturation.drive >= 0.4) parts.push('tube warmth');
  else if (preset.saturation.drive >= 0.2) parts.push('gentle tape');
  else parts.push('clean press');
  if (preset.eq.lowPassHz <= 10500) parts.push('soft highs');
  if (preset.wow.depthCents >= 8) parts.push('wandering wow');
  if (preset.surface.levelDb >= -30) parts.push('audible surface');
  if (preset.crackle.intensity >= 0.45) parts.push('crackle');
  else if (preset.crackle.intensity >= 0.2) parts.push('light crackle');
  if (preset.pops.densityPerMin >= 1.4) parts.push('occasional pops');
  return parts.length ? parts.join(' • ') : 'clean';
}

