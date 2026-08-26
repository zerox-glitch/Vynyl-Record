'use client';

import React, { useState } from 'react';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { PricingPlan } from '@/types';
import { Check, Sparkles, Disc3, ShieldCheck, Zap } from 'lucide-react';
import toast from 'react-hot-toast';

interface StripeUpgradeModalProps {
  isOpen: boolean;
  onClose: () => void;
  plans: PricingPlan[];
  onSuccessUpgrade?: () => void;
}

export const StripeUpgradeModal: React.FC<StripeUpgradeModalProps> = ({
  isOpen,
  onClose,
  plans,
  onSuccessUpgrade,
}) => {
  const [selectedPlanId, setSelectedPlanId] = useState<string>(
    plans.find((p) => p.price_cents > 0)?.id || ''
  );
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const premiumPlans = plans.filter((p) => p.price_cents > 0);

  const handleCheckout = async () => {
    try {
      setIsLoading(true);
      const res = await fetch('/api/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ planId: selectedPlanId }),
      });

      const data = await res.json();
      if (data.url) {
        if (data.sessionId?.startsWith('demo_session_')) {
          toast.success('✨ Plan unlocked in demo preview mode!');
          if (onSuccessUpgrade) onSuccessUpgrade();
          onClose();
        } else {
          window.location.href = data.url;
        }
      } else {
        throw new Error(data.error || 'Failed to initiate checkout');
      }
    } catch (err: any) {
      toast.error(err.message || 'Payment processing error');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Unlock Gold Master Vinyl Studio"
      subtitle="Extend recording duration and access all vintage wax colors & filters"
      maxWidth="xl"
    >
      <div className="space-y-6 pt-2">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {premiumPlans.map((plan) => {
            const isSelected = selectedPlanId === plan.id;
            const isHeirloom = plan.price_cents > 1500;

            return (
              <div
                key={plan.id}
                onClick={() => setSelectedPlanId(plan.id)}
                className={`relative p-5 rounded-2xl border transition-all cursor-pointer flex flex-col justify-between ${
                  isSelected
                    ? 'bg-amber-950/40 border-amber-500 shadow-xl shadow-amber-950/50 scale-[1.02]'
                    : 'bg-stone-900/70 border-stone-800 hover:border-amber-700/60'
                }`}
              >
                {isHeirloom && (
                  <div className="absolute -top-3 right-4 bg-gradient-to-r from-amber-500 to-amber-700 text-stone-950 text-[10px] font-bold font-mono px-2.5 py-0.5 rounded-full shadow-md">
                    LIFETIME ACCESS
                  </div>
                )}

                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <h4 className="font-serif font-bold text-lg text-amber-100">
                      {plan.name}
                    </h4>
                    <div
                      className={`w-5 h-5 rounded-full border flex items-center justify-center ${
                        isSelected
                          ? 'bg-amber-500 border-amber-400 text-stone-950'
                          : 'border-stone-700'
                      }`}
                    >
                      {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                    </div>
                  </div>

                  <div className="flex items-baseline gap-1">
                    <span className="text-3xl font-bold font-mono text-stone-100">
                      ${(plan.price_cents / 100).toFixed(2)}
                    </span>
                    <span className="text-xs text-stone-400">
                      {isHeirloom ? '/ one-time' : '/ month'}
                    </span>
                  </div>

                  <ul className="space-y-2 text-xs text-stone-300 pt-2 border-t border-stone-800">
                    <li className="flex items-center gap-2">
                      <Check className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
                      <span>
                        Up to{' '}
                        <strong className="text-amber-200">
                          {Math.floor(plan.max_duration_seconds / 60)} minutes
                        </strong>{' '}
                        per recording
                      </span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
                      <span>All 5 luxury 3D vinyl wax colors</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
                      <span>1940s Radio & 1960s Tape Saturation</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
                      <span>All premium background melodies</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
                      <span>Custom vinyl crackle & pop mix control</span>
                    </li>
                  </ul>
                </div>
              </div>
            );
          })}
        </div>

        <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-4 border-t border-stone-800">
          <div className="flex items-center gap-2 text-xs text-stone-400">
            <ShieldCheck className="w-4 h-4 text-emerald-500" />
            <span>Secure 256-bit encrypted checkout</span>
          </div>

          <div className="flex items-center gap-3 w-full sm:w-auto">
            <Button variant="ghost" size="md" onClick={onClose}>
              Cancel
            </Button>
            <Button
              variant="primary"
              size="md"
              onClick={handleCheckout}
              isLoading={isLoading}
              leftIcon={<Zap className="w-4 h-4 text-stone-950 fill-stone-950" />}
              className="w-full sm:w-auto min-w-[160px]"
            >
              Unlock Now
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};
