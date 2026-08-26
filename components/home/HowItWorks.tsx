'use client';

import React from 'react';
import { Mic, Radio, Sparkles, Disc3 } from 'lucide-react';

const STEPS = [
  {
    step: '01',
    title: 'Speak Into the Studio Mic',
    description:
      'Record your voice note directly in your browser or upload an existing audio file. Live waveform feedback ensures optimal vocal levels.',
    icon: <Mic className="w-6 h-6 text-amber-400" />,
  },
  {
    step: '02',
    title: 'Analog Wax Mastering',
    description:
      'Our server-side FFmpeg pipeline applies authentic 1920s gramophone acoustic horn bandpasses, tube saturation, and 33⅓ RPM surface noise.',
    icon: <Radio className="w-6 h-6 text-amber-400" />,
  },
  {
    step: '03',
    title: 'AI Word-Level Timestamping',
    description:
      'OpenAI Whisper transcribes every single spoken syllable with millisecond accuracy for synchronized scrolling parchment lyrics.',
    icon: <Sparkles className="w-6 h-6 text-amber-400" />,
  },
  {
    step: '04',
    title: 'Share the 3D Turntable Link',
    description:
      'Loved ones receive your shareable URL. The brass needle lands on the rotating wax and your voice note plays in cinematic 3D.',
    icon: <Disc3 className="w-6 h-6 text-amber-400" />,
  },
];

export const HowItWorks: React.FC = () => {
  return (
    <section className="w-full py-20 border-t border-stone-800/80 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-16">
        <div className="text-center space-y-4 max-w-2xl mx-auto">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-amber-950/60 border border-amber-600/40 text-amber-300 font-mono text-xs uppercase tracking-widest">
            <span>Analog Craftsmanship Meets WebGL</span>
          </div>
          <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
            How Vinyl Voice Notes Works
          </h2>
          <p className="text-sm text-stone-400 leading-relaxed">
            From spoken thought to physical-feeling vintage record in four seamless steps.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {STEPS.map((s, idx) => (
            <div
              key={idx}
              className="p-6 rounded-3xl bg-stone-900/70 border border-stone-800 hover:border-amber-600/30 backdrop-blur-md shadow-xl transition-all duration-300 flex flex-col justify-between space-y-6"
            >
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <div className="w-12 h-12 rounded-2xl bg-stone-950 border border-amber-500/30 flex items-center justify-center shadow-inner">
                    {s.icon}
                  </div>
                  <span className="font-mono text-2xl font-bold text-stone-700">
                    {s.step}
                  </span>
                </div>

                <h3 className="text-lg font-serif font-bold text-amber-100">
                  {s.title}
                </h3>

                <p className="text-xs text-stone-400 leading-relaxed">
                  {s.description}
                </p>
              </div>

              <div className="h-1 w-12 bg-amber-600/40 rounded-full" />
            </div>
          ))}
        </div>
      </div>
    </section>
  );
};
