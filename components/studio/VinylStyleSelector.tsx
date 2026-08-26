'use client';

import React from 'react';
import { VinylStyleType } from '@/types';
import { VINYL_STYLES } from '@/lib/constants';
import { Disc3, Check, Lock, Sparkles } from 'lucide-react';
import { clsx } from 'clsx';
import toast from 'react-hot-toast';

interface VinylStyleSelectorProps {
  selectedStyle: VinylStyleType;
  onChange: (style: VinylStyleType) => void;
  isPremium?: boolean;
  onTriggerUpgrade?: () => void;
}

export const VinylStyleSelector: React.FC<VinylStyleSelectorProps> = ({
  selectedStyle,
  onChange,
  isPremium = false,
  onTriggerUpgrade,
}) => {
  const handleSelect = (style: typeof VINYL_STYLES[0]) => {
    if (style.isPremium && !isPremium) {
      toast('Unlocked on Gold Master Tier', { icon: '✨' });
      if (onTriggerUpgrade) onTriggerUpgrade();
      return;
    }
    onChange(style.id);
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-sm font-serif font-bold text-amber-100 flex items-center gap-2">
          <Disc3 className="w-4 h-4 text-amber-500" />
          <span>Vinyl Disc & Wax Edition</span>
        </label>
        <span className="text-xs text-stone-400">Rendered in 3D WebGL</span>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-3">
        {VINYL_STYLES.map((style) => {
          const isSelected = selectedStyle === style.id;
          const isLocked = style.isPremium && !isPremium;

          return (
            <div
              key={style.id}
              onClick={() => handleSelect(style)}
              className={clsx(
                'group relative p-3 rounded-2xl border transition-all duration-200 cursor-pointer select-none flex flex-col items-center text-center gap-2',
                isSelected
                  ? 'bg-amber-950/40 border-amber-500 shadow-lg shadow-amber-950/50 scale-[1.02]'
                  : 'bg-stone-900/60 border-stone-800 hover:border-amber-700/60 hover:bg-stone-900'
              )}
            >
              {/* Mini Disc Preview Icon */}
              <div
                className="w-12 h-12 rounded-full p-1 relative shadow-md flex items-center justify-center border border-white/10 group-hover:rotate-45 transition-transform duration-500"
                style={{ backgroundColor: style.baseColor }}
              >
                {/* Center label */}
                <div
                  className="w-5 h-5 rounded-full flex items-center justify-center border"
                  style={{ backgroundColor: style.labelColor, borderColor: style.brassAccent }}
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-black" />
                </div>
              </div>

              <div className="space-y-0.5">
                <span className="text-xs font-serif font-bold text-stone-100 block">
                  {style.name}
                </span>
                <span className="text-[10px] text-stone-400 block line-clamp-1">
                  {style.subtitle}
                </span>
              </div>

              {/* Status Badge */}
              <div className="mt-1">
                {isLocked ? (
                  <span className="inline-flex items-center gap-1 text-[9px] font-mono px-2 py-0.5 rounded-full bg-stone-800 text-amber-500/80">
                    <Lock className="w-2.5 h-2.5" />
                    <span>Gold</span>
                  </span>
                ) : isSelected ? (
                  <span className="inline-flex items-center gap-1 text-[9px] font-mono px-2 py-0.5 rounded-full bg-amber-500 text-stone-950 font-bold">
                    <Check className="w-2.5 h-2.5" />
                    <span>Selected</span>
                  </span>
                ) : (
                  <span className="text-[9px] text-stone-500">Available</span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
