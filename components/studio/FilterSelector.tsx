'use client';

import React, { useState } from 'react';
import { FilterPresetType } from '@/types';
import { FILTER_PRESETS } from '@/lib/constants';
import { Volume2, Sparkles, Check, Lock, Play, Pause } from 'lucide-react';
import { clsx } from 'clsx';
import toast from 'react-hot-toast';

interface FilterSelectorProps {
  selectedFilter: FilterPresetType;
  onChange: (filter: FilterPresetType) => void;
  isPremium?: boolean;
  onTriggerUpgrade?: () => void;
  allowedPresets?: FilterPresetType[];
}

export const FilterSelector: React.FC<FilterSelectorProps> = ({
  selectedFilter,
  onChange,
  isPremium = false,
  onTriggerUpgrade,
  allowedPresets,
}) => {
  const [playingPreview, setPlayingPreview] = useState<FilterPresetType | null>(null);
  const audioPreviewRef = React.useRef<HTMLAudioElement | null>(null);

  const previewAudioMap: Record<FilterPresetType, string> = {
    clean: '/audio/demo-raw-sample.mp3?v=3',
    gramophone: '/audio/demo-gramophone-sample.mp3?v=3',
    radio: '/audio/demo-voice-ocean.mp3?v=3',
    tape: '/audio/demo-lofi-sample.mp3?v=3',
  };

  const handleTogglePreview = (presetId: FilterPresetType, e: React.MouseEvent) => {
    e.stopPropagation();

    if (playingPreview === presetId) {
      if (audioPreviewRef.current) audioPreviewRef.current.pause();
      setPlayingPreview(null);
    } else {
      if (audioPreviewRef.current) {
        audioPreviewRef.current.src = previewAudioMap[presetId];
        audioPreviewRef.current.play()
          .then(() => setPlayingPreview(presetId))
          .catch(() => {
            setPlayingPreview(null);
            toast.error('This filter preview is unavailable.');
          });
      }
    }
  };

  const handleSelect = (preset: typeof FILTER_PRESETS[0]) => {
    if ((allowedPresets && !allowedPresets.includes(preset.id)) || (preset.isPremium && !isPremium)) {
      toast('Unlocked on Gold Master Tier', { icon: '✨' });
      if (onTriggerUpgrade) onTriggerUpgrade();
      return;
    }
    onChange(preset.id);
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-sm font-serif font-bold text-amber-100 flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-amber-500" />
          <span>Analog Filter Preset</span>
        </label>
        <span className="text-xs text-stone-400">Server-Side FFmpeg Mastering</span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {FILTER_PRESETS.map((preset) => {
          const isSelected = selectedFilter === preset.id;
          const isLocked = (allowedPresets && !allowedPresets.includes(preset.id)) || (preset.isPremium && !isPremium);
          const isAudioActive = playingPreview === preset.id;

          return (
            <div
              key={preset.id}
              onClick={() => handleSelect(preset)}
              className={clsx(
                'group relative p-4 rounded-2xl border transition-all duration-200 cursor-pointer select-none overflow-hidden',
                isSelected
                  ? 'bg-amber-950/40 border-amber-500 shadow-lg shadow-amber-950/50'
                  : 'bg-stone-900/70 border-stone-800 hover:border-amber-700/60 hover:bg-stone-900'
              )}
            >
              {/* Gold Selection Indicator */}
              <div className="flex items-start justify-between">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-serif font-bold text-stone-100 group-hover:text-amber-200">
                      {preset.name}
                    </span>
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-stone-800 text-amber-400 border border-amber-500/20">
                      {preset.year}
                    </span>
                  </div>
                  <p className="text-xs text-stone-400 line-clamp-2 leading-relaxed">
                    {preset.description}
                  </p>
                </div>

                <div className="flex items-center gap-2 ml-2">
                  {/* Audio Preview Button */}
                  <button
                    type="button"
                    onClick={(e) => handleTogglePreview(preset.id, e)}
                    className={clsx(
                      'p-2 rounded-xl transition-all',
                      isAudioActive
                        ? 'bg-amber-500 text-stone-950 animate-pulse'
                        : 'bg-stone-800 text-stone-300 hover:text-amber-300 hover:bg-stone-700'
                    )}
                    title="Listen to filter sample"
                  >
                    {isAudioActive ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
                  </button>

                  {/* Lock or Check */}
                  {isLocked ? (
                    <div className="p-1 rounded bg-stone-800 text-amber-500/70" title="Premium filter">
                      <Lock className="w-4 h-4" />
                    </div>
                  ) : (
                    <div
                      className={clsx(
                        'w-5 h-5 rounded-full border flex items-center justify-center transition-all',
                        isSelected
                          ? 'bg-amber-500 border-amber-400 text-stone-950'
                          : 'border-stone-700 group-hover:border-amber-500/50'
                      )}
                    >
                      {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                    </div>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <audio
        ref={audioPreviewRef}
        onEnded={() => setPlayingPreview(null)}
        className="hidden"
      />
    </div>
  );
};
