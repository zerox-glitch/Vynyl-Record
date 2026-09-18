'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { PricingPlan } from '@/types';
import { Button } from '@/components/ui/Button';
import { Check, Sparkles, Crown, Zap, ShieldCheck, Info } from 'lucide-react';
import toast from 'react-hot-toast';

interface PricingSectionProps {
  plans: PricingPlan[];
}

export const PricingSection: React.FC<PricingSectionProps> = ({ plans }) => {
  const [loadingPlanId, setLoadingPlanId] = useState<string | null>(null);

  const handleCheckout = async (plan: PricingPlan) => {
    if (plan.price_cents === 0 || plan.billing_model === 'free') {
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
      if (res.status === 401 && data.loginUrl) {
        toast('Please sign in to checkout — returning you to checkout after login', { icon: '🔒' });
        window.location.href = data.loginUrl;
        return;
      }
      if (data.url) {
        if (data.sessionId?.startsWith('demo_session_')) {
          toast.success('✨ Demo checkout — no real charge. Entitlement granted in demo mode.');
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

  const sorted = [...plans].sort((a,b) => (a.display_order||0)-(b.display_order||0));

  return (
    <section id="pricing" className="w-full py-24 border-t border-stone-800/80 relative">
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
            Prices loaded from database • Feature access from plan configuration • Per-recording unlock applies to one record • Monthly renews until canceled • Lifetime is one-time account-wide
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 items-stretch">
          {sorted.map((plan) => {
            const isFree = plan.billing_model === 'free' || plan.price_cents === 0;
            const isPerRec = plan.billing_model === 'per_recording';
            const isMonthly = plan.billing_model === 'monthly';
            const isLifetime = plan.billing_model === 'lifetime';
            const isFeatured = isMonthly; // recommended

            return (
              <div
                key={plan.id}
                className={`relative rounded-3xl p-6 transition-all duration-300 flex flex-col justify-between ${
                  isFeatured
                    ? 'bg-gradient-to-b from-stone-900 to-stone-950 border-2 border-amber-500 shadow-2xl shadow-amber-950/80 scale-105 z-10'
                    : 'bg-stone-900/80 border border-stone-800 hover:border-amber-700/50 backdrop-blur-md shadow-xl'
                }`}
              >
                {isFeatured && (
                  <div className="absolute -top-3.5 left-1/2 -translate-x-1/2 bg-gradient-to-r from-amber-500 to-amber-700 text-stone-950 text-xs font-bold font-mono px-4 py-1 rounded-full shadow-lg uppercase tracking-wider">
                    Recommended
                  </div>
                )}
                {isLifetime && !isFeatured && (
                  <div className="absolute -top-3.5 left-1/2 -translate-x-1/2 bg-stone-800 border border-amber-500/50 text-amber-300 text-xs font-bold font-mono px-4 py-1 rounded-full shadow-md uppercase tracking-wider">
                    Lifetime
                  </div>
                )}
                {isPerRec && (
                  <div className="absolute -top-3.5 left-1/2 -translate-x-1/2 bg-amber-900/60 border border-amber-600/30 text-amber-200 text-[10px] font-mono px-3 py-1 rounded-full uppercase tracking-wider">
                    Per Recording
                  </div>
                )}

                <div className="space-y-5">
                  <div>
                    <h3 className="text-lg font-serif font-bold text-amber-100">{plan.name}</h3>
                    <p className="text-[11px] text-stone-500 mt-1">{plan.description || ''}</p>
                    <div className="flex items-baseline gap-1 mt-3">
                      <span className="text-3xl font-mono font-bold text-stone-100">${(plan.price_cents / 100).toFixed(2)}</span>
                      <span className="text-xs text-stone-400">
                        {isFree ? '/ forever' : isMonthly ? '/ month' : isLifetime ? '/ one-time' : isPerRec ? '/ record' : ''}
                      </span>
                    </div>
                    <p className="text-[10px] text-stone-500 mt-1">Currency: {plan.currency?.toUpperCase()} • {plan.billing_interval || plan.billing_model}</p>
                  </div>

                  <ul className="space-y-2.5 text-xs text-stone-300 border-t border-stone-800 pt-4">
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span><strong>{Math.floor(plan.max_duration_seconds / 60)} min</strong> max duration</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.included_recordings ?? '∞'} included recordings</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.allowed_vinyl_styles.length} vinyl styles</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.allowed_filter_presets.length} filter presets</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.can_adjust_crackle ? 'Adjust crackle' : 'Fixed crackle'}</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.can_download ? 'MP3 download' : 'No download (free)'}</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.can_use_private_visibility ? 'Private visibility' : 'Public/unlisted only'}</span></li>
                    <li className="flex items-center gap-2"><Check className="w-4 h-4 text-amber-400" /><span>{plan.can_use_advanced_mixer ? 'Advanced mixer' : 'Standard mixer'}</span></li>
                  </ul>

                  {isPerRec && <p className="text-[11px] text-amber-200/70 flex gap-1"><Info className="w-3 h-3 mt-0.5" /> Unlocks premium for one recording only.</p>}
                  {isMonthly && <p className="text-[11px] text-amber-200/70 flex gap-1"><Info className="w-3 h-3 mt-0.5" /> Renews monthly until canceled.</p>}
                  {isLifetime && <p className="text-[11px] text-amber-200/70 flex gap-1"><Info className="w-3 h-3 mt-0.5" /> One-time, account-wide, fair-use limits.</p>}
                </div>

                <div className="pt-6">
                  <Button
                    variant={isFeatured ? 'primary' : 'secondary'}
                    size="lg"
                    onClick={() => handleCheckout(plan)}
                    isLoading={loadingPlanId === plan.id}
                    className="w-full"
                    leftIcon={isFeatured ? <Zap className="w-4 h-4 text-stone-950 fill-stone-950" /> : undefined}
                  >
                    {isFree ? 'Start Free' : `Get ${plan.name}`}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>

        <div className="flex items-center justify-center gap-2 text-xs text-stone-500">
          <ShieldCheck className="w-4 h-4 text-emerald-500" />
          <span>All plans editable by admin • Stripe Price sync status shown in admin • No browser price trusted</span>
        </div>
      </div>
    </section>
  );
};
