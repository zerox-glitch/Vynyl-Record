'use client';

import React, { useEffect, useRef } from 'react';
import { TranscriptWord } from '@/types';
import { Sparkles, Heart, Feather, Calendar } from 'lucide-react';
import { clsx } from 'clsx';

interface ParchmentLyricCardProps {
  transcript: TranscriptWord[];
  currentTime: number;
  title: string;
  recipientName?: string | null;
  senderName?: string | null;
  createdAt?: string;
  onWordClick?: (startTime: number) => void;
}

export const ParchmentLyricCard: React.FC<ParchmentLyricCardProps> = ({
  transcript,
  currentTime,
  title,
  recipientName,
  senderName,
  createdAt,
  onWordClick,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const activeWordRef = useRef<HTMLSpanElement>(null);

  // Find index of current word
  const activeIndex = transcript.findIndex(
    (w) => currentTime >= w.start && currentTime <= w.end
  );

  // Auto-scroll parchment container to keep active word centered
  useEffect(() => {
    if (activeWordRef.current && containerRef.current) {
      const container = containerRef.current;
      const activeEl = activeWordRef.current;

      const containerHeight = container.clientHeight;
      const elementOffsetTop = activeEl.offsetTop;
      const elementHeight = activeEl.clientHeight;

      const targetScrollTop = elementOffsetTop - containerHeight / 2 + elementHeight / 2;

      container.scrollTo({
        top: Math.max(0, targetScrollTop),
        behavior: 'smooth',
      });
    }
  }, [activeIndex]);

  const formattedDate = createdAt
    ? new Date(createdAt).toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
      })
    : 'Preserved Forever';

  return (
    <div className="w-full h-full flex flex-col parchment-card rounded-3xl p-6 sm:p-8 overflow-hidden border border-amber-900/30 relative">
      {/* Decorative Aged Header */}
      <div className="border-b border-amber-900/20 pb-4 mb-4 flex items-center justify-between">
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-amber-900/80 font-serif text-xs uppercase tracking-widest">
            <Feather className="w-3.5 h-3.5" />
            <span>Analog Wax Transcript</span>
          </div>
          <h2 className="text-xl sm:text-2xl font-serif font-bold text-stone-900 tracking-tight">
            {title}
          </h2>
        </div>
        <div className="text-right">
          <span className="text-[11px] font-mono text-stone-700 block flex items-center gap-1 justify-end">
            <Calendar className="w-3 h-3" />
            {formattedDate}
          </span>
          {recipientName && (
            <span className="text-xs font-serif italic text-amber-950 font-semibold block mt-0.5">
              Dedication: {recipientName}
            </span>
          )}
        </div>
      </div>

      {/* Synchronized Scrolling Words Container */}
      <div
        ref={containerRef}
        className="flex-1 overflow-y-auto pr-3 space-y-4 font-serif text-base sm:text-lg text-stone-800 leading-relaxed scroll-smooth relative max-h-[380px]"
      >
        <div className="py-4 flex flex-wrap gap-x-1.5 gap-y-2.5 items-baseline">
          {transcript && transcript.length > 0 ? (
            transcript.map((item, idx) => {
              const isActive = idx === activeIndex;
              const isPast = currentTime > item.end;

              return (
                <span
                  key={idx}
                  ref={isActive ? activeWordRef : undefined}
                  onClick={() => onWordClick && onWordClick(item.start)}
                  className={clsx(
                    'inline-block rounded px-1.5 py-0.5 transition-all duration-150 cursor-pointer select-none',
                    isActive &&
                      'bg-amber-300 text-amber-950 font-bold shadow-md shadow-amber-900/20 scale-110 ring-2 ring-amber-500/50',
                    isPast && !isActive && 'text-stone-950 font-medium',
                    !isPast && !isActive && 'text-stone-700/80 hover:text-stone-950 hover:bg-amber-200/40'
                  )}
                  title={`Word: ${item.word} (${item.start}s - ${item.end}s)`}
                >
                  {item.word}
                </span>
              );
            })
          ) : (
            <div className="w-full text-center py-12 text-stone-500 italic">
              <Sparkles className="w-6 h-6 mx-auto mb-2 text-amber-600/60" />
              <p>Listening to digital wax grooves...</p>
            </div>
          )}
        </div>
      </div>

      {/* Decorative Aged Footer Dedication */}
      <div className="pt-4 mt-4 border-t border-amber-900/20 flex items-center justify-between text-xs text-stone-700">
        <div className="flex items-center gap-1.5 font-serif italic">
          <Heart className="w-3.5 h-3.5 text-amber-800 fill-amber-800" />
          <span>{senderName ? `With love from ${senderName}` : 'Handcrafted audio memory'}</span>
        </div>
        <div className="font-mono text-[10px] text-amber-900/70 tracking-widest uppercase">
          Whisper Synced
        </div>
      </div>
    </div>
  );
};
