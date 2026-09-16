'use client';

import React from 'react';
import { Mic, Sparkles, Disc3, Send } from 'lucide-react';

const STEPS = [
  {
    step: '01',
    title: 'Speak what is worth keeping.',
    body:
      'Open the studio in your browser. A few seconds is enough. Don\u2019t rehearse \u2014 the warmth is in the wobble.',
    icon: <Mic className="w-6 h-6 text-amber-400" />,
  },
  {
    step: '02',
    title: 'Choose the room it arrives in.',
    body:
      'A few moods to pick from. The crackle, the soft rain, the brass horn \u2014 whatever fits the moment you\u2019re sending.',
    icon: <Sparkles className="w-6 h-6 text-amber-400" />,
  },
  {
    step: '03',
    title: 'Press the record.',
    body:
      'Your voice, the mood, and a soft surface noise are baked into one master. A wax label with their name on it.',
    icon: <Disc3 className="w-6 h-6 text-amber-400" />,
  },
  {
    step: '04',
    title: 'Send the link.',
    body:
      'They open it in any browser. The needle drops, the wax turns, your words come through the room.',
    icon: <Send className="w-6 h-6 text-amber-400" />,
  },
];

export const HowItWorks: React.FC = () => {
  return (
    <section className="w-full py-24 border-t border-stone-800/80 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-16">
        <div className="text-center space-y-4 max-w-2xl mx-auto">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-amber-950/60 border border-amber-600/40 text-amber-300 font-mono text-xs uppercase tracking-widest">
            <span>From you to them, in a few quiet minutes</span>
          </div>
          <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
            How it lands on them.
          </h2>
          <p className="text-sm sm:text-base text-stone-400 leading-relaxed">
            From the moment you record to the moment they press play.
            No app to install, nothing for them to figure out.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {STEPS.map((s, idx) => (
            <div
              key={idx}
              className="p-7 rounded-3xl bg-stone-900/70 border border-stone-800 hover:border-amber-600/30 shadow-xl transition-all duration-300 flex flex-col justify-between space-y-7"
            >
              <div className="space-y-5">
                <div className="flex items-center justify-between">
                  <div className="w-12 h-12 rounded-2xl bg-stone-950 border border-amber-500/30 flex items-center justify-center shadow-inner">
                    {s.icon}
                  </div>
                  <span className="font-mono text-2xl font-bold text-stone-700">
                    {s.step}
                  </span>
                </div>

                <h3 className="text-lg font-serif font-bold text-amber-100 leading-snug">
                  {s.title}
                </h3>

                <p className="text-sm text-stone-300/90 leading-relaxed font-serif">
                  {s.body}
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
