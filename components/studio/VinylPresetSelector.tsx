'use client';

import React from 'react';
import { Check, Sparkles, Waves } from 'lucide-react';
import { VINYL_PRESETS, VINYL_PRESET_ORDER, presetFlavor, type VinylPreset } from '@/lib/audio/presets';
import { clsx } from 'clsx';

export interface VinylPresetSelectorProps {
  selected: VinylPreset['id'];
  onChange: (id: VinylPreset['id']) => void;
}

/** Primary sound selection. Advanced knobs remain below in the existing UI. */
export function VinylPresetSelector({ selected, onChange }: VinylPresetSelectorProps) {
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="flex items-center gap-2 text-sm font-serif font-bold text-amber-100">
          <Waves className="h-4 w-4 text-amber-500" /> Vinyl character
        </label>
        <span className="text-[10px] uppercase tracking-wider text-stone-500">Complete sound recipe</span>
      </div>
      <div className="grid grid-cols-1 gap-2">
        {VINYL_PRESET_ORDER.map((id) => {
          const preset = VINYL_PRESETS[id];
          const isSelected = selected === id;
          return (
            <button
              type="button"
              key={id}
              onClick={() => onChange(id)}
              className={clsx(
                'rounded-2xl border p-3 text-left transition-all',
                isSelected
                  ? 'border-amber-500 bg-amber-950/50 shadow-lg shadow-amber-950/40'
                  : 'border-stone-800 bg-stone-950/60 hover:border-amber-800/60',
              )}
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-serif text-sm font-bold text-stone-100">{preset.name}</span>
                    {isSelected && <Check className="h-3.5 w-3.5 text-amber-400" />}
                  </div>
                  <p className="mt-1 text-[11px] leading-relaxed text-stone-400">{preset.description}</p>
                  <p className="mt-1 text-[10px] font-mono text-amber-300/80">{presetFlavor(preset)}</p>
                </div>
                <Sparkles className={clsx('h-4 w-4 shrink-0', isSelected ? 'text-amber-400' : 'text-stone-700')} />
              </div>
            </button>
          );
        })}
      </div>
      <p className="text-[10px] text-stone-500">Choosing a character sets the warmth, surface texture, crackle, pops, and motor movement together.</p>
    </div>
  );
}
