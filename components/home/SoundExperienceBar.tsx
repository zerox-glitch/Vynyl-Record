'use client';

import React, { useState, useRef } from 'react';
import { Play, Pause, Volume2, Mic, Sparkles, CloudRain } from 'lucide-react';
import { clsx } from 'clsx';

export const SoundExperienceBar: React.FC = () => {
  const [activeMode, setActiveMode] = useState<'plain' | 'wax' | 'hearth'>('wax');
  const [isPlaying, setIsPlaying] = useState<boolean>(false);

  const audioRef = useRef<HTMLAudioElement | null>(null);

  const modes = [
    {
      id: 'plain' as const,
      label: 'Plain',
      subtitle: 'Your voice, the way you hear it in your head.',
      src: '/audio/demo-raw-sample.mp3?v=4',
      icon: <Mic className="w-4 h-4 text-stone-300" />,
    },
    {
      id: 'wax' as const,
      label: 'Vintage Wax',
      subtitle: 'The crackle your grandparents knew. Warm, lived-in, 1920s.',
      src: '/audio/demo-gramophone-sample.mp3?v=4',
      icon: <Sparkles className="w-4 h-4 text-amber-400" />,
    },
    {
      id: 'hearth' as const,
      label: 'Rainy Hearth',
      subtitle: 'Storm outside. Your voice, warm in the room.',
      src: '/audio/demo-lofi-sample.mp3?v=4',
      icon: <CloudRain className="w-4 h-4 text-sky-300" />,
    },
  ];

  const handleModeChange = (modeId: 'plain' | 'wax' | 'hearth') => {
    setActiveMode(modeId);
    const target = modes.find((m) => m.id === modeId);
    if (audioRef.current && target) {
      const wasPlaying = !audioRef.current.paused;
      const currentPos = audioRef.current.currentTime;
      audioRef.current.src = target.src;
      audioRef.current.currentTime = currentPos;
      if (wasPlaying || isPlaying) {
        audioRef.current.play().catch(() => setIsPlaying(false));
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
        audioRef.current.play()
          .then(() => setIsPlaying(true))
          .catch(() => setIsPlaying(false));
      }
    }
  };

  return (
    <section id="experience" className="w-full py-20 scroll-mt-24">
      <div className="max-w-5xl mx-auto px-4 sm:px-6">
        <div className="rounded-3xl bg-gradient-to-b from-stone-900/90 to-stone-950 border border-amber-600/25 shadow-2xl space-y-10 relative overflow-hidden p-8 sm:p-12">
          {/* Header */}
          <div className="flex flex-col md:flex-row md:items-end justify-between gap-6">
            <div className="space-y-3">
              <div className="flex items-center gap-2 text-amber-500 font-mono text-xs uppercase tracking-widest">
                <Volume2 className="w-4 h-4" />
                <span>Hear it before you press</span>
              </div>
              <h3 className="text-2xl sm:text-3xl font-serif font-bold text-stone-100 leading-tight">
                Three ways your voice can arrive.
              </h3>
              <p className="text-sm text-stone-400 max-w-md leading-relaxed">
                Same message, three moods. Pick the one that sounds like the moment you&apos;re sending.
              </p>
            </div>
          </div>

          {/* Mode cards */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {modes.map((mode) => {
              const isSelected = activeMode === mode.id;

              return (
                <button
                  type="button"
                  key={mode.id}
                  onClick={() => handleModeChange(mode.id)}
                  className={clsx(
                    'p-6 rounded-2xl border text-left transition-all flex flex-col justify-between space-y-5 select-none',
                    isSelected
                      ? 'bg-amber-950/40 border-amber-500 shadow-xl shadow-amber-950/60'
                      : 'bg-stone-950/60 border-stone-800 hover:border-amber-700/40 hover:bg-stone-900/60'
                  )}
                >
                  <div className="space-y-3">
                    <div className="flex items-center gap-2">
                      {mode.icon}
                      <span className="font-serif font-bold text-stone-100 text-lg">
                        {mode.label}
                      </span>
                    </div>
                    <p className="text-sm text-stone-300/90 leading-relaxed font-serif italic">
                      {mode.subtitle}
                    </p>
                  </div>

                  {isSelected && (
                    <div className="flex items-center gap-2 text-xs font-mono text-amber-300">
                      <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse" />
                      <span>Selected</span>
                    </div>
                  )}
                </button>
              );
            })}
          </div>

          {/* Master play */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-6 border-t border-stone-800">
            <div className="flex items-center gap-4">
              <button
                onClick={togglePlay}
                aria-label={isPlaying ? 'Pause preview' : 'Play preview'}
                className="w-14 h-14 rounded-2xl bg-gradient-to-br from-amber-400 to-amber-700 text-stone-950 flex items-center justify-center hover:scale-105 transition-transform shadow-lg shadow-amber-950/80"
              >
                {isPlaying ? (
                  <Pause className="w-5 h-5 fill-stone-950" />
                ) : (
                  <Play className="w-5 h-5 fill-stone-950 ml-0.5" />
                )}
              </button>

              <div>
                <span className="text-base font-serif font-bold text-stone-100 block">
                  {isPlaying
                    ? `Listening — ${modes.find((m) => m.id === activeMode)?.label}`
                    : 'Press play'}
                </span>
                <span className="text-xs text-stone-400 font-mono">
                  {modes.find((m) => m.id === activeMode)?.label} preview
                </span>
              </div>
            </div>

            <span className="text-xs font-mono text-stone-500 tracking-wider">
              Built from a single recording · all three moods
            </span>
          </div>
        </div>
      </div>

      <audio
        ref={audioRef}
        src={modes[1].src}
        onEnded={() => setIsPlaying(false)}
        onError={() => setIsPlaying(false)}
        className="hidden"
      />
    </section>
  );
};
