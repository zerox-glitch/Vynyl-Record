'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { PricingPlan } from '@/types';
import { Button } from '@/components/ui/Button';
import { Check, Sparkles, Crown, Zap, ShieldCheck } from 'lucide-react';
import toast from 'react-hot-toast';

interface PricingSectionProps {
  plans: PricingPlan[];
}

export const PricingSection: React.FC<PricingSectionProps> = ({ plans }) => {
  const [loadingPlanId, setLoadingPlanId] = useState<string | null>(null);

  const handleCheckout = async (plan: PricingPlan) => {
    if (plan.price_cents === 0) {
      window.location.href = '/studio';
      return;
    }

    try {
      setLoadingPlanId(plan.id);
      const res = await fetch('/api/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ planId: plan.id }),
      });

      const data = await res.json();
      if (data.url) {
        if (data.sessionId?.startsWith('demo_session_')) {
          toast.success('✨ Plan unlocked in demo preview mode!');
          window.location.href = data.url;
        } else {
          window.location.href = data.url;
        }
      } else {
        throw new Error(data.error || 'Failed to start checkout');
      }
    } catch (err: any) {
      toast.error(err.message || 'Payment initiation failed');
    } finally {
      setLoadingPlanId(null);
    }
  };

  return (
    <section id="pricing" className="w-full py-24 border-t border-stone-800/80 relative">
      {/* Subtle Glow */}
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-3/4 h-72 bg-amber-600/5 blur-[160px] pointer-events-none" />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-16 relative">
        <div className="text-center space-y-4 max-w-2xl mx-auto">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-amber-950/60 border border-amber-600/40 text-amber-300 font-mono text-xs uppercase tracking-widest">
            <Crown className="w-3.5 h-3.5" />
            <span>Permanent Wax Preservation</span>
          </div>
          <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
            Transparent, Timeless Pricing
          </h2>
          <p className="text-sm text-stone-400 leading-relaxed">
            Choose the perfect edition for your anniversary, wedding vows, or heirloom archive.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 items-stretch">
          {plans.map((plan) => {
            const isFeatured = plan.price_cents === 900 || (plan.price_cents > 0 && plan.price_cents < 2000);
            const isFree = plan.price_cents === 0;
            const isLifetime = plan.price_cents >= 2000;

            return (
              <div
                key={plan.id}
                className={`relative rounded-3xl p-8 transition-all duration-300 flex flex-col justify-between ${
                  isFeatured
                    ? 'bg-gradient-to-b from-stone-900 to-stone-950 border-2 border-amber-500 shadow-2xl shadow-amber-950/80 scale-105 z-10'
                    : 'bg-stone-900/80 border border-stone-800 hover:border-amber-700/50 backdrop-blur-md shadow-xl'
                }`}
              >
                {isFeatured && (
                  <div className="absolute -top-3.5 left-1/2 -translate-x-1/2 bg-gradient-to-r from-amber-500 to-amber-700 text-stone-950 text-xs font-bold font-mono px-4 py-1 rounded-full shadow-lg shadow-amber-950/60 uppercase tracking-wider">
                    Most Popular
                  </div>
                )}

                {isLifetime && (
                  <div className="absolute -top-3.5 left-1/2 -translate-x-1/2 bg-stone-800 border border-amber-500/50 text-amber-300 text-xs font-bold font-mono px-4 py-1 rounded-full shadow-md uppercase tracking-wider">
                    Lifetime Heirloom
                  </div>
                )}

                <div className="space-y-6">
                  <div>
                    <h3 className="text-xl font-serif font-bold text-amber-100">
                      {plan.name}
                    </h3>
                    <div className="flex items-baseline gap-1 mt-3">
                      <span className="text-4xl font-mono font-bold text-stone-100">
                        ${(plan.price_cents / 100).toFixed(2)}
                      </span>
                      <span className="text-xs text-stone-400">
                        {isFree ? '/ forever' : isLifetime ? '/ one-time' : '/ month'}
                      </span>
                    </div>
                  </div>

                  <ul className="space-y-3 text-xs text-stone-300 border-t border-stone-800 pt-6">
                    <li className="flex items-center gap-2.5">
                      <Check className="w-4 h-4 text-amber-400 flex-shrink-0" />
                      <span>
                        <strong className="text-stone-100">
                          {Math.floor(plan.max_duration_seconds / 60)} minute
                        </strong>{' '}
                        recording limit
                      </span>
                    </li>
                    <li className="flex items-center gap-2.5">
                      <Check className="w-4 h-4 text-amber-400 flex-shrink-0" />
                      <span>
                        {plan.allowed_vinyl_styles.length} 3D Vinyl Wax Editions
                      </span>
                    </li>
                    <li className="flex items-center gap-2.5">
                      <Check className="w-4 h-4 text-amber-400 flex-shrink-0" />
                      <span>
                        {plan.allowed_filter_presets.length} Analog Tube & Horn Presets
                      </span>
                    </li>
                    <li className="flex items-center gap-2.5">
                      <Check className="w-4 h-4 text-amber-400 flex-shrink-0" />
                      <span>AI Whisper Word-by-Word Lyric Sync</span>
                    </li>
                    <li className="flex items-center gap-2.5">
                      <Check className="w-4 h-4 text-amber-400 flex-shrink-0" />
                      <span>
                        {plan.can_adjust_crackle
                          ? 'Full Vinyl Crackle & Static Mix Tuning'
                          : 'Preset 15% Vinyl Crackle'}
                      </span>
                    </li>
                    <li className="flex items-center gap-2.5">
                      <Check className="w-4 h-4 text-amber-400 flex-shrink-0" />
                      <span>Lossless 192kbps MP3 Download</span>
                    </li>
                  </ul>
                </div>

                <div className="pt-8">
                  <Button
                    variant={isFeatured ? 'primary' : 'secondary'}
                    size="lg"
                    onClick={() => handleCheckout(plan)}
                    isLoading={loadingPlanId === plan.id}
                    className="w-full"
                    leftIcon={
                      isFeatured ? (
                        <Zap className="w-4 h-4 text-stone-950 fill-stone-950" />
                      ) : undefined
                    }
                  >
                    {isFree ? 'Start Recording Free' : `Unlock ${plan.name}`}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>

        <div className="flex items-center justify-center gap-2 text-xs text-stone-500">
          <ShieldCheck className="w-4 h-4 text-emerald-500" />
          <span>All records preserved indefinitely on cloud wax storage.</span>
        </div>
      </div>
    </section>
  );
};
