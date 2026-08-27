'use client';

import React, { useState } from 'react';
import { AudioAsset, PricingPlan, FilterPresetType, VinylStyleType } from '@/types';
import { Button } from '@/components/ui/Button';
import { Plus, Edit2, Check, Lock, Save, DollarSign, Clock, Disc3 } from 'lucide-react';
import { VINYL_STYLES, FILTER_PRESETS } from '@/lib/constants';
import toast from 'react-hot-toast';

interface AdminPricingTabProps {
  plans: PricingPlan[];
  onSavePlan: (plan: PricingPlan) => Promise<void>;
  audioAssets: AudioAsset[];
}

export const AdminPricingTab: React.FC<AdminPricingTabProps> = ({
  plans,
  onSavePlan,
  audioAssets,
}) => {
  const [editingPlan, setEditingPlan] = useState<PricingPlan | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  const handleEdit = (plan: PricingPlan) => {
    setEditingPlan({ ...plan });
  };

  const handleCreateNew = () => {
    const newPlan: PricingPlan = {
      id: crypto.randomUUID(),
      name: 'Custom Collector Plan',
      price_cents: 1500,
      stripe_price_id: 'price_custom',
      max_duration_seconds: 300,
      allowed_filter_presets: ['clean', 'gramophone', 'radio'],
      allowed_bg_music_ids: ['all'],
      allowed_vinyl_styles: ['classic_red', 'midnight_blue'],
      can_adjust_crackle: true,
      is_active: true,
      created_at: new Date().toISOString(),
    };
    setEditingPlan(newPlan);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingPlan) return;

    try {
      setIsSaving(true);
      await onSavePlan(editingPlan);
      toast.success(`Plan "${editingPlan.name}" saved!`);
      setEditingPlan(null);
    } catch (err: any) {
      toast.error('Failed to save plan: ' + err.message);
    } finally {
      setIsSaving(false);
    }
  };

  const toggleFilter = (presetId: FilterPresetType) => {
    if (!editingPlan) return;
    const current = editingPlan.allowed_filter_presets || [];
    const next = current.includes(presetId)
      ? current.filter((p) => p !== presetId)
      : [...current, presetId];
    setEditingPlan({ ...editingPlan, allowed_filter_presets: next });
  };

  const toggleVinylStyle = (styleId: VinylStyleType) => {
    if (!editingPlan) return;
    const current = editingPlan.allowed_vinyl_styles || [];
    const next = current.includes(styleId)
      ? current.filter((s) => s !== styleId)
      : [...current, styleId];
    setEditingPlan({ ...editingPlan, allowed_vinyl_styles: next });
  };

  const toggleBackgroundMusic = (assetId: string) => {
    if (!editingPlan) return;
    const backgroundIds = audioAssets.filter((asset) => asset.category === 'bg_music').map((asset) => asset.id);
    const current = editingPlan.allowed_bg_music_ids.includes('all')
      ? ['none', ...backgroundIds]
      : editingPlan.allowed_bg_music_ids;
    const next = current.includes(assetId)
      ? current.filter((id) => id !== assetId)
      : [...current, assetId];
    setEditingPlan({ ...editingPlan, allowed_bg_music_ids: next });
  };

  return (
    <div className="space-y-8">
      {/* Plans List Table */}
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-6">
        <div className="flex items-center justify-between border-b border-stone-800 pb-4">
          <div>
            <h3 className="font-serif font-bold text-lg text-amber-100">
              Active Pricing Tiers & Duration Limits
            </h3>
            <p className="text-xs text-stone-400">
              Manage plan availability, duration caps, and allowed studio features
            </p>
          </div>
          <Button
            variant="primary"
            size="sm"
            onClick={handleCreateNew}
            leftIcon={<Plus className="w-4 h-4 text-stone-950" />}
          >
            Create Plan
          </Button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {plans.map((plan) => (
            <div
              key={plan.id}
              className="p-5 rounded-2xl bg-stone-950 border border-stone-800 flex flex-col justify-between space-y-4 hover:border-amber-700/50 transition-all"
            >
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <h4 className="font-serif font-bold text-stone-100">{plan.name}</h4>
                  <span
                    className={`px-2 py-0.5 rounded-full text-[10px] font-mono ${
                      plan.is_active
                        ? 'bg-emerald-950 text-emerald-400 border border-emerald-600/30'
                        : 'bg-stone-800 text-stone-500'
                    }`}
                  >
                    {plan.is_active ? 'Active' : 'Archived'}
                  </span>
                </div>

                <div className="flex items-baseline gap-1">
                  <span className="text-2xl font-mono font-bold text-amber-400">
                    ${(plan.price_cents / 100).toFixed(2)}
                  </span>
                  <span className="text-xs text-stone-500">/ unlock</span>
                </div>

                <div className="text-xs text-stone-400 space-y-1 pt-2 border-t border-stone-800">
                  <p className="flex items-center gap-1.5">
                    <Clock className="w-3.5 h-3.5 text-amber-500" />
                    <span>Max {Math.floor(plan.max_duration_seconds / 60)} min recording</span>
                  </p>
                  <p className="flex items-center gap-1.5">
                    <Disc3 className="w-3.5 h-3.5 text-amber-500" />
                    <span>{plan.allowed_vinyl_styles?.length || 1} Wax Styles</span>
                  </p>
                </div>
              </div>

              <Button
                variant="outline"
                size="sm"
                onClick={() => handleEdit(plan)}
                leftIcon={<Edit2 className="w-3.5 h-3.5" />}
                className="w-full"
              >
                Configure Tier
              </Button>
            </div>
          ))}
        </div>
      </div>

      {/* Edit / Create Plan Modal / Card */}
      {editingPlan && (
        <form onSubmit={handleSave} className="p-6 rounded-3xl bg-stone-900 border border-amber-600/40 space-y-6 shadow-2xl">
          <div className="flex items-center justify-between border-b border-stone-800 pb-3">
            <h3 className="font-serif font-bold text-lg text-amber-100">
              Edit Plan: {editingPlan.name}
            </h3>
            <button
              type="button"
              onClick={() => setEditingPlan(null)}
              className="text-xs text-stone-400 hover:text-stone-200"
            >
              Cancel
            </button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">
                Plan Name
              </label>
              <input
                type="text"
                value={editingPlan.name}
                onChange={(e) => setEditingPlan({ ...editingPlan, name: e.target.value })}
                className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm text-stone-100"
                required
              />
            </div>

            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">
                Price in Cents (e.g. 900 = $9.00)
              </label>
              <input
                type="number"
                value={editingPlan.price_cents}
                onChange={(e) => setEditingPlan({ ...editingPlan, price_cents: parseInt(e.target.value) || 0 })}
                className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm text-stone-100 font-mono"
                required
              />
            </div>

            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">
                Max Duration (Seconds)
              </label>
              <input
                type="number"
                value={editingPlan.max_duration_seconds}
                onChange={(e) =>
                  setEditingPlan({ ...editingPlan, max_duration_seconds: parseInt(e.target.value) || 60 })
                }
                className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm text-stone-100 font-mono"
                required
              />
            </div>
          </div>

          {/* Allowed Filter Presets */}
          <div className="space-y-2">
            <label className="block text-xs font-mono text-stone-300">
              Allowed Filter Presets
            </label>
            <div className="flex flex-wrap gap-2">
              {FILTER_PRESETS.map((filter) => {
                const isSelected = editingPlan.allowed_filter_presets?.includes(filter.id);
                return (
                  <button
                    key={filter.id}
                    type="button"
                    onClick={() => toggleFilter(filter.id)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-medium border flex items-center gap-1.5 ${
                      isSelected
                        ? 'bg-amber-600/20 border-amber-500 text-amber-200'
                        : 'bg-stone-950 border-stone-800 text-stone-400'
                    }`}
                  >
                    {isSelected && <Check className="w-3.5 h-3.5 text-amber-400" />}
                    <span>{filter.name}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Allowed Vinyl Styles */}
          <div className="space-y-2">
            <label className="block text-xs font-mono text-stone-300">
              Allowed Vinyl Styles
            </label>
            <div className="flex flex-wrap gap-2">
              {VINYL_STYLES.map((style) => {
                const isSelected = editingPlan.allowed_vinyl_styles?.includes(style.id);
                return (
                  <button
                    key={style.id}
                    type="button"
                    onClick={() => toggleVinylStyle(style.id)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-medium border flex items-center gap-1.5 ${
                      isSelected
                        ? 'bg-amber-600/20 border-amber-500 text-amber-200'
                        : 'bg-stone-950 border-stone-800 text-stone-400'
                    }`}
                  >
                    {isSelected && <Check className="w-3.5 h-3.5 text-amber-400" />}
                    <span>{style.name}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-mono text-stone-300">
              Allowed Background Music
            </label>
            <div className="flex flex-wrap gap-2">
              {audioAssets.filter((asset) => asset.category === 'bg_music').map((asset) => {
                const isSelected = editingPlan.allowed_bg_music_ids.includes('all') || editingPlan.allowed_bg_music_ids.includes(asset.id);
                return (
                  <button
                    key={asset.id}
                    type="button"
                    onClick={() => toggleBackgroundMusic(asset.id)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-medium border flex items-center gap-1.5 ${
                      isSelected
                        ? 'bg-amber-600/20 border-amber-500 text-amber-200'
                        : 'bg-stone-950 border-stone-800 text-stone-400'
                    }`}
                  >
                    {isSelected && <Check className="w-3.5 h-3.5 text-amber-400" />}
                    <span>{asset.title}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="flex items-center justify-between pt-4 border-t border-stone-800">
            <div className="flex flex-wrap items-center gap-5">
              <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editingPlan.can_adjust_crackle}
                  onChange={(e) => setEditingPlan({ ...editingPlan, can_adjust_crackle: e.target.checked })}
                  className="rounded accent-amber-500"
                />
                <span>Can Adjust Vinyl Crackle Intensity</span>
              </label>
              <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editingPlan.is_active}
                  onChange={(e) => setEditingPlan({ ...editingPlan, is_active: e.target.checked })}
                  className="rounded accent-emerald-500"
                />
                <span>Plan Available to Customers</span>
              </label>
            </div>

            <Button
              type="submit"
              variant="primary"
              size="md"
              isLoading={isSaving}
              leftIcon={<Save className="w-4 h-4 text-stone-950" />}
            >
              Save Plan
            </Button>
          </div>
        </form>
      )}
    </div>
  );
};
