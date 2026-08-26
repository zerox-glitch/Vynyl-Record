'use client';

import React from 'react';
import { Volume2, VolumeX, Sparkles, Lock } from 'lucide-react';
import { Slider } from '@/components/ui/Slider';
import toast from 'react-hot-toast';

interface CrackleSliderProps {
  intensity: number; // 0 to 1
  onChange: (value: number) => void;
  canAdjust?: boolean;
  onTriggerUpgrade?: () => void;
}

export const CrackleSlider: React.FC<CrackleSliderProps> = ({
  intensity,
  onChange,
  canAdjust = true,
  onTriggerUpgrade,
}) => {
  const isEnabled = intensity > 0;

  const handleToggle = () => {
    if (!canAdjust && onTriggerUpgrade) {
      toast('Custom static tuning unlocked on Gold Master plan', { icon: '✨' });
      onTriggerUpgrade();
      return;
    }
    onChange(isEnabled ? 0 : 0.2);
  };

  const handleSliderChange = (val: number) => {
    if (!canAdjust && onTriggerUpgrade) {
      toast('Custom static tuning unlocked on Gold Master plan', { icon: '✨' });
      onTriggerUpgrade();
      return;
    }
    onChange(val / 100);
  };

  return (
    <div className="p-4 rounded-2xl bg-stone-900/70 border border-stone-800 space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-amber-500" />
          <span className="text-sm font-serif font-bold text-amber-100">
            Vinyl Surface Noise & Crackle
          </span>
        </div>

        <div className="flex items-center gap-2">
          {!canAdjust && (
            <span className="text-[10px] font-mono text-amber-500 flex items-center gap-1">
              <Lock className="w-3 h-3" />
              <span>Preset 15%</span>
            </span>
          )}

          <button
            type="button"
            onClick={handleToggle}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none ${
              isEnabled ? 'bg-amber-600' : 'bg-stone-700'
            }`}
          >
            <span
              className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                isEnabled ? 'translate-x-6' : 'translate-x-1'
              }`}
            />
          </button>
        </div>
      </div>

      <p className="text-xs text-stone-400">
        Injects authentic 1920s 33.3 RPM periodic pop, dust crackles, and mechanical turntable groove warmth.
      </p>

      <div className="pt-1">
        <Slider
          value={Math.round(intensity * 100)}
          onChange={handleSliderChange}
          min={0}
          max={100}
          step={5}
          label="Crackle Saturation Mix"
          displayValue={`${Math.round(intensity * 100)}%`}
          disabled={!isEnabled || !canAdjust}
        />
      </div>
    </div>
  );
};
