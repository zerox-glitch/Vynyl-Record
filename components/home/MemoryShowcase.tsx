'use client';

import React from 'react';
import Link from 'next/link';
import { DEMO_RECORDINGS, VINYL_STYLES } from '@/lib/constants';
import { Disc3, Heart, ExternalLink, Sparkles, Play } from 'lucide-react';
import { Button } from '@/components/ui/Button';

export const MemoryShowcase: React.FC = () => {
  return (
    <section id="memories" className="w-full py-20 border-t border-stone-800/80 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-12">
        {/* Section Header */}
        <div className="flex flex-col md:flex-row md:items-end justify-between gap-6">
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-amber-500 font-mono text-xs uppercase tracking-widest">
              <Sparkles className="w-4 h-4" />
              <span>Master Archive Vault</span>
            </div>
            <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
              Heirlooms Pressed into Wax
            </h2>
            <p className="text-sm text-stone-400 max-w-xl leading-relaxed">
              Experience sample memories preserved by lovers, families, and storytellers. Tap any record to open its interactive 3D turntable experience.
            </p>
          </div>

          <Link href="/studio">
            <Button variant="outline" size="md" leftIcon={<Disc3 className="w-4 h-4 text-amber-400" />}>
              Record Your Own Memory
            </Button>
          </Link>
        </div>

        {/* 3 Sample Records Grid */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {DEMO_RECORDINGS.map((demo) => {
            const style = VINYL_STYLES.find((s) => s.id === demo.vinyl_style) || VINYL_STYLES[0];

            return (
              <div
                key={demo.id}
                className="group relative p-6 rounded-3xl bg-stone-900/80 border border-stone-800 hover:border-amber-600/40 backdrop-blur-md shadow-2xl transition-all duration-300 flex flex-col justify-between space-y-6 hover:-translate-y-1"
              >
                {/* Visual Disc Header */}
                <div className="flex items-start justify-between">
                  <div
                    className="w-16 h-16 rounded-full p-1 relative shadow-xl flex items-center justify-center border border-white/10 group-hover:rotate-90 transition-transform duration-700"
                    style={{ backgroundColor: style.baseColor }}
                  >
                    <div
                      className="w-7 h-7 rounded-full flex items-center justify-center border"
                      style={{ backgroundColor: style.labelColor, borderColor: style.brassAccent }}
                    >
                      <div className="w-2 h-2 rounded-full bg-black" />
                    </div>
                  </div>

                  <span className="px-2.5 py-0.5 rounded-full bg-stone-950 border border-amber-600/30 text-amber-300 text-[10px] font-mono uppercase">
                    {style.name}
                  </span>
                </div>

                {/* Content */}
                <div className="space-y-2">
                  <h3 className="text-xl font-serif font-bold text-stone-100 group-hover:text-amber-200 transition-colors">
                    {demo.title}
                  </h3>
                  <div className="flex items-center gap-2 text-xs text-stone-400">
                    <Heart className="w-3.5 h-3.5 text-red-500 fill-red-500" />
                    <span>To: {demo.recipient_name}</span>
                    <span className="text-stone-600">•</span>
                    <span>From: {demo.sender_name}</span>
                  </div>

                  <p className="text-xs text-stone-400 font-serif italic pt-2 line-clamp-2">
                    &ldquo;{demo.transcript_json.map((w) => w.word).join(' ')}&rdquo;
                  </p>
                </div>

                {/* Open Player Link */}
                <Link href={`/play/${demo.slug}`} className="block">
                  <Button
                    variant="secondary"
                    size="md"
                    className="w-full justify-between group-hover:border-amber-500/50"
                    leftIcon={<Play className="w-4 h-4 text-amber-400 fill-amber-400" />}
                    rightIcon={<ExternalLink className="w-4 h-4 text-stone-400 group-hover:text-amber-300" />}
                  >
                    Open 3D Gramophone
                  </Button>
                </Link>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
};
