'use client';

import React, { useState, useRef } from 'react';
import { Play, Pause, Volume2, Sparkles, Radio, CloudRain, Mic } from 'lucide-react';
import { clsx } from 'clsx';

export const SoundExperienceBar: React.FC = () => {
  const [activeMode, setActiveMode] = useState<'raw' | 'gramophone' | 'lofi'>('gramophone');
  const [isPlaying, setIsPlaying] = useState<boolean>(false);

  const audioRef = useRef<HTMLAudioElement | null>(null);

  const modes = [
    {
      id: 'raw' as const,
      label: '1. Raw Voice Note',
      subtitle: 'Flat digital smartphone mic recording',
      src: '/audio/demo-raw-sample.mp3',
      icon: <Mic className="w-4 h-4 text-stone-400" />,
      tag: 'Raw Capture',
    },
    {
      id: 'gramophone' as const,
      label: '2. 1920s Gramophone',
      subtitle: 'Horn bandpass filter + 33.3 RPM wax crackle',
      src: '/audio/demo-gramophone-sample.mp3',
      icon: <Sparkles className="w-4 h-4 text-amber-400" />,
      tag: 'Analog Wax Master',
    },
    {
      id: 'lofi' as const,
      label: '3. Lo-Fi Rain Saturation',
      subtitle: 'Warm hearth rain + tape saturation + tube compression',
      src: '/audio/demo-lofi-sample.mp3',
      icon: <CloudRain className="w-4 h-4 text-sky-400" />,
      tag: 'Atmospheric Master',
    },
  ];

  const handleModeChange = (modeId: 'raw' | 'gramophone' | 'lofi') => {
    setActiveMode(modeId);
    const target = modes.find((m) => m.id === modeId);
    if (audioRef.current && target) {
      const wasPlaying = !audioRef.current.paused;
      const currentPos = audioRef.current.currentTime;
      audioRef.current.src = target.src;
      audioRef.current.currentTime = currentPos;
      if (wasPlaying || isPlaying) {
        audioRef.current.play().catch(() => {});
      }
    }
  };

  const togglePlay = () => {
    if (!audioRef.current) return;
    if (isPlaying) {
      audioRef.current.pause();
      setIsPlaying(false);
    } else {
      const target = modes.find((m) => m.id === activeMode);
      if (target) {
        audioRef.current.src = target.src;
        audioRef.current.play().catch(() => {});
        setIsPlaying(true);
      }
    }
  };

  return (
    <div id="experience" className="w-full py-16 scroll-mt-24">
      <div className="max-w-5xl mx-auto px-4 sm:px-6">
        <div className="p-8 sm:p-10 rounded-3xl bg-gradient-to-b from-stone-900/90 to-stone-950 border border-amber-600/30 backdrop-blur-xl shadow-2xl space-y-8 relative overflow-hidden">
          {/* Header */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div>
              <div className="flex items-center gap-2 text-amber-500 font-mono text-xs uppercase tracking-widest mb-1">
                <Volume2 className="w-4 h-4" />
                <span>Interactive Sound Lab</span>
              </div>
              <h3 className="text-2xl sm:text-3xl font-serif font-bold text-stone-100">
                Hear the Analog Transformation
              </h3>
            </div>
            <p className="text-xs text-stone-400 max-w-md">
              Tap each acoustic mode to hear how our server-side FFmpeg pipeline transforms an ordinary digital voice into nostalgic physical wax.
            </p>
          </div>

          {/* 3 Comparison Cards */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {modes.map((mode) => {
              const isSelected = activeMode === mode.id;

              return (
                <div
                  key={mode.id}
                  onClick={() => handleModeChange(mode.id)}
                  className={clsx(
                    'p-5 rounded-2xl border transition-all cursor-pointer flex flex-col justify-between space-y-4 select-none',
                    isSelected
                      ? 'bg-amber-950/40 border-amber-500 shadow-xl shadow-amber-950/60 scale-[1.02]'
                      : 'bg-stone-950/60 border-stone-800 hover:border-amber-700/50 hover:bg-stone-900/60'
                  )}
                >
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        {mode.icon}
                        <span className="font-serif font-bold text-stone-100">
                          {mode.label}
                        </span>
                      </div>
                      <span
                        className={`text-[9px] font-mono px-2 py-0.5 rounded-full border ${
                          isSelected
                            ? 'bg-amber-500 text-stone-950 font-bold border-amber-400'
                            : 'bg-stone-900 text-stone-400 border-stone-700'
                        }`}
                      >
                        {mode.tag}
                      </span>
                    </div>

                    <p className="text-xs text-stone-400 leading-relaxed">
                      {mode.subtitle}
                    </p>
                  </div>

                  {isSelected && (
                    <div className="flex items-center gap-2 text-xs font-mono text-amber-400">
                      <span className="w-2 h-2 rounded-full bg-amber-400 animate-ping" />
                      <span>{isPlaying ? 'Now Playing Mode' : 'Selected Mode'}</span>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {/* Master Trigger Button */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-4 border-t border-stone-800">
            <div className="flex items-center gap-3">
              <button
                onClick={togglePlay}
                className="w-12 h-12 rounded-2xl bg-gradient-to-br from-amber-500 to-amber-700 text-stone-950 flex items-center justify-center hover:scale-105 transition-transform shadow-lg shadow-amber-950/80"
              >
                {isPlaying ? (
                  <Pause className="w-5 h-5 fill-stone-950" />
                ) : (
                  <Play className="w-5 h-5 fill-stone-950 ml-0.5" />
                )}
              </button>

              <div>
                <span className="text-sm font-serif font-bold text-stone-200 block">
                  {isPlaying ? 'Playing Sample Master' : 'Tap to Listen & Compare'}
                </span>
                <span className="text-xs text-stone-400 font-mono">
                  Current Mode: {modes.find((m) => m.id === activeMode)?.label}
                </span>
              </div>
            </div>

            <span className="text-xs font-mono text-amber-500/80 uppercase tracking-wider">
              192kbps Stereo Master
            </span>
          </div>
        </div>
      </div>

      <audio
        ref={audioRef}
        src={modes[1].src}
        onEnded={() => setIsPlaying(false)}
        className="hidden"
      />
    </div>
  );
};
