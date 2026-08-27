'use client';

import React from 'react';
import { AudioAsset } from '@/types';
import { SlidersHorizontal, Waves, Wind, Zap } from 'lucide-react';
import { Slider } from '@/components/ui/Slider';

interface AnalogMixerControlsProps {
  assets: AudioAsset[];
  bgMusicVolume: number;
  onBgMusicVolumeChange: (value: number) => void;
  crackleAssetId: string | null;
  onCrackleAssetChange: (value: string | null) => void;
  hissIntensity: number;
  onHissChange: (value: number) => void;
  rumbleIntensity: number;
  onRumbleChange: (value: number) => void;
  soundEffectId: string | null;
  onSoundEffectChange: (value: string | null) => void;
  soundEffectVolume: number;
  onSoundEffectVolumeChange: (value: number) => void;
  musicClarity: number;
  onMusicClarityChange: (value: number) => void;
  crackleBrightness: number;
  onCrackleBrightnessChange: (value: number) => void;
  voiceWarmth: number;
  onVoiceWarmthChange: (value: number) => void;
  voicePresence: number;
  onVoicePresenceChange: (value: number) => void;
  wowFlutter: number;
  onWowFlutterChange: (value: number) => void;
  introDelay: number;
  onIntroDelayChange: (value: number) => void;
  isPremium?: boolean;
}

export const AnalogMixerControls: React.FC<AnalogMixerControlsProps> = ({
  assets,
  bgMusicVolume,
  onBgMusicVolumeChange,
  crackleAssetId,
  onCrackleAssetChange,
  hissIntensity,
  onHissChange,
  rumbleIntensity,
  onRumbleChange,
  soundEffectId,
  onSoundEffectChange,
  soundEffectVolume,
  onSoundEffectVolumeChange,
  musicClarity,
  onMusicClarityChange,
  crackleBrightness,
  onCrackleBrightnessChange,
  voiceWarmth,
  onVoiceWarmthChange,
  voicePresence,
  onVoicePresenceChange,
  wowFlutter,
  onWowFlutterChange,
  introDelay,
  onIntroDelayChange,
  isPremium = false,
}) => {
  const isAvailable = (asset: AudioAsset) => !asset.is_premium_only || isPremium;
  const crackles = assets.filter((asset) => asset.category === 'crackle' && isAvailable(asset));
  const effects = assets.filter((asset) => asset.category === 'sound_effect' && isAvailable(asset));

  return (
    <div className="p-4 rounded-2xl bg-stone-900/70 border border-stone-800 space-y-4">
      <div className="flex items-center gap-2">
        <SlidersHorizontal className="w-4 h-4 text-amber-500" />
        <span className="text-sm font-serif font-bold text-amber-100">Advanced Analog Mixer</span>
      </div>
      <p className="text-xs text-stone-400">Balance music and tune each layer of grain rather than applying one fixed noise preset.</p>

      <Slider value={Math.round(bgMusicVolume * 100)} onChange={(value) => onBgMusicVolumeChange(value / 100)} min={0} max={60} step={2} label="Background music" displayValue={`${Math.round(bgMusicVolume * 100)}%`} />
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <Slider value={Math.round(musicClarity * 100)} onChange={(value) => onMusicClarityChange(value / 100)} min={0} max={100} step={5} label="Music clarity" displayValue={`${Math.round(musicClarity * 100)}%`} />
        <Slider value={Math.round(crackleBrightness * 100)} onChange={(value) => onCrackleBrightnessChange(value / 100)} min={0} max={100} step={5} label="Crackle brightness" displayValue={`${Math.round(crackleBrightness * 100)}%`} />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <label className="text-xs text-stone-300">
          <span className="mb-1.5 flex items-center gap-1"><Waves className="w-3 h-3 text-amber-500" /> Crackle texture</span>
          <select value={crackleAssetId || ''} onChange={(e) => onCrackleAssetChange(e.target.value || null)} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs">
            <option value="">Default vinyl surface</option>
            {crackles.map((asset) => <option key={asset.id} value={asset.id}>{asset.title}</option>)}
          </select>
        </label>
        <label className="text-xs text-stone-300">
          <span className="mb-1.5 flex items-center gap-1"><Zap className="w-3 h-3 text-amber-500" /> Intro sound effect</span>
          <select value={soundEffectId || ''} onChange={(e) => onSoundEffectChange(e.target.value || null)} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs">
            <option value="">No intro effect</option>
            {effects.map((asset) => <option key={asset.id} value={asset.id}>{asset.title}</option>)}
          </select>
        </label>
      </div>

      <Slider value={Math.round(soundEffectVolume * 100)} onChange={(value) => onSoundEffectVolumeChange(value / 100)} min={0} max={100} step={5} label="Intro effect volume" displayValue={`${Math.round(soundEffectVolume * 100)}%`} disabled={!soundEffectId} />
      <Slider value={Math.round(introDelay * 1000)} onChange={(value) => onIntroDelayChange(value / 1000)} min={250} max={2000} step={50} label="Voice starts after needle" displayValue={`${introDelay.toFixed(2)}s`} disabled={!soundEffectId} />
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <Slider value={Math.round(hissIntensity * 100)} onChange={(value) => onHissChange(value / 100)} min={0} max={100} step={5} label="Fine grain / hiss" displayValue={`${Math.round(hissIntensity * 100)}%`} />
        <Slider value={Math.round(rumbleIntensity * 100)} onChange={(value) => onRumbleChange(value / 100)} min={0} max={100} step={5} label="Low groove rumble" displayValue={`${Math.round(rumbleIntensity * 100)}%`} />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-2 border-t border-stone-800">
        <Slider value={Math.round(voiceWarmth * 100)} onChange={(value) => onVoiceWarmthChange(value / 100)} min={0} max={100} step={5} label="Voice warmth" displayValue={`${Math.round(voiceWarmth * 100)}%`} />
        <Slider value={Math.round(voicePresence * 100)} onChange={(value) => onVoicePresenceChange(value / 100)} min={0} max={100} step={5} label="Voice presence" displayValue={`${Math.round(voicePresence * 100)}%`} />
        <Slider value={Math.round(wowFlutter * 100)} onChange={(value) => onWowFlutterChange(value / 100)} min={0} max={100} step={5} label="Wow & flutter" displayValue={`${Math.round(wowFlutter * 100)}%`} />
      </div>
      <div className="flex items-center gap-1.5 text-[10px] text-stone-500"><Wind className="w-3 h-3" /> Noise layers are synthesized independently for a cleaner voice mix.</div>
    </div>
  );
};
