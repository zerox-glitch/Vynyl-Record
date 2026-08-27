'use client';

import React, { useState, useRef } from 'react';
import { AudioAsset } from '@/types';
import { Music, Play, Pause, Lock, Check } from 'lucide-react';
import { clsx } from 'clsx';
import toast from 'react-hot-toast';

interface BackgroundMusicSelectorProps {
  assets: AudioAsset[];
  selectedBgMusicId: string | null;
  onChange: (id: string | null) => void;
  isPremium?: boolean;
  onTriggerUpgrade?: () => void;
  allowedAssetIds?: string[];
}

export const BackgroundMusicSelector: React.FC<BackgroundMusicSelectorProps> = ({
  assets,
  selectedBgMusicId,
  onChange,
  isPremium = false,
  onTriggerUpgrade,
  allowedAssetIds,
}) => {
  const [playingPreview, setPlayingPreview] = useState<string | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const bgAssets = assets.filter((a) => a.category === 'bg_music');

  const handleTogglePreview = (asset: AudioAsset, e: React.MouseEvent) => {
    e.stopPropagation();

    if (playingPreview === asset.id) {
      if (audioRef.current) audioRef.current.pause();
      setPlayingPreview(null);
    } else {
      if (audioRef.current) {
        audioRef.current.src = asset.file_url;
        audioRef.current.play()
          .then(() => setPlayingPreview(asset.id))
          .catch(() => {
            setPlayingPreview(null);
            toast.error('This background preview is unavailable.');
          });
      }
    }
  };

  const handleSelect = (assetId: string | null, isLocked: boolean = false) => {
    if (isLocked) {
      toast('This track is not included in the active plan', { icon: '👑' });
      if (onTriggerUpgrade) onTriggerUpgrade();
      return;
    }
    onChange(assetId);
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-sm font-serif font-bold text-amber-100 flex items-center gap-2">
          <Music className="w-4 h-4 text-amber-500" />
          <span>Ambient Background Atmosphere</span>
        </label>
        <span className="text-xs text-stone-400">Baked into MP3 • Always audible</span>
      </div>

      <div className="space-y-2">
        {/* Option: None / Silent Background */}
        <div
          onClick={() => handleSelect(null, false)}
          className={clsx(
            'flex items-center justify-between p-3 rounded-xl border transition-all cursor-pointer select-none',
            selectedBgMusicId === null
              ? 'bg-amber-950/40 border-amber-500 text-amber-200'
              : 'bg-stone-900/60 border-stone-800 text-stone-300 hover:border-amber-700/50 hover:bg-stone-900'
          )}
        >
          <div className="flex items-center gap-3">
            <span className="text-sm font-medium">No Background Atmosphere (Voice & Wax Only)</span>
          </div>
          <div
            className={clsx(
              'w-5 h-5 rounded-full border flex items-center justify-center transition-all',
              selectedBgMusicId === null
                ? 'bg-amber-500 border-amber-400 text-stone-950'
                : 'border-stone-700'
            )}
          >
            {selectedBgMusicId === null && <Check className="w-3 h-3 stroke-[3]" />}
          </div>
        </div>

        {/* Dynamic List from DB */}
        {bgAssets.map((asset) => {
          const isSelected = selectedBgMusicId === asset.id;
          const isAllowedByPlan = !allowedAssetIds || allowedAssetIds.includes(asset.id) || allowedAssetIds.includes('all');
          const isLocked = !isAllowedByPlan || (asset.is_premium_only && !isPremium);
          const isPlaying = playingPreview === asset.id;

          return (
            <div
              key={asset.id}
              onClick={() => handleSelect(asset.id, isLocked)}
              className={clsx(
                'flex items-center justify-between p-3 rounded-xl border transition-all cursor-pointer select-none',
                isSelected
                  ? 'bg-amber-950/40 border-amber-500 text-amber-100'
                  : 'bg-stone-900/60 border-stone-800 text-stone-300 hover:border-amber-700/50 hover:bg-stone-900'
              )}
            >
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={(e) => handleTogglePreview(asset, e)}
                  className={clsx(
                    'p-2 rounded-lg transition-colors',
                    isPlaying
                      ? 'bg-amber-500 text-stone-950 animate-pulse'
                      : 'bg-stone-800 text-stone-300 hover:text-amber-300 hover:bg-stone-700'
                  )}
                  title="Preview audio sample"
                >
                  {isPlaying ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
                </button>
                <div className="flex flex-col">
                  <span className="text-sm font-medium">{asset.title}</span>
                  {asset.is_premium_only && (
                    <span className="text-[10px] text-amber-500/90 uppercase font-mono">
                      Master Wax Exclusive
                    </span>
                  )}
                </div>
              </div>

              <div className="flex items-center gap-2">
                {isLocked ? (
                  <div className="p-1 rounded bg-stone-800 text-amber-500/70" title="Premium track">
                    <Lock className="w-4 h-4" />
                  </div>
                ) : (
                  <div
                    className={clsx(
                      'w-5 h-5 rounded-full border flex items-center justify-center transition-all',
                      isSelected
                        ? 'bg-amber-500 border-amber-400 text-stone-950'
                        : 'border-stone-700'
                    )}
                  >
                    {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      <audio ref={audioRef} onEnded={() => setPlayingPreview(null)} className="hidden" />
    </div>
  );
};
