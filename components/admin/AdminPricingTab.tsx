'use client';

import React, { useState, useEffect } from 'react';
import { AudioAsset, PricingPlan, FilterPresetType, VinylStyleType, BillingModel } from '@/types';
import { Button } from '@/components/ui/Button';
import { Plus, Edit2, Check, Save, DollarSign, Clock, Disc3, Archive, Trash2, Users, CreditCard, ShoppingCart } from 'lucide-react';
import { VINYL_STYLES, FILTER_PRESETS } from '@/lib/constants';
import toast from 'react-hot-toast';

interface AdminPricingTabProps {
  plans: PricingPlan[];
  onSavePlan: (plan: PricingPlan) => Promise<void>;
  audioAssets: AudioAsset[];
  onRefresh?: () => Promise<void>;
}

export const AdminPricingTab: React.FC<AdminPricingTabProps> = ({
  plans,
  onSavePlan,
  audioAssets,
  onRefresh,
}) => {
  const [editingPlan, setEditingPlan] = useState<PricingPlan | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [stats, setStats] = useState<Record<string, { users: number; subs: number; purchases: number }>>({});

  useEffect(() => {
    // Fetch stats per plan
    fetch('/api/admin/pricing-stats').then(r => r.json()).then(d => {
      if (d.stats) setStats(d.stats);
    }).catch(() => {});
  }, [plans]);

  const handleEdit = (plan: PricingPlan) => {
    setEditingPlan({ ...plan });
  };

  const handleCreateNew = () => {
    const newPlan: PricingPlan = {
      id: crypto.randomUUID(),
      slug: `custom-plan-${Date.now()}`,
      name: 'Custom Collector Plan',
      description: 'Custom plan description',
      billing_model: 'monthly' as BillingModel,
      price_cents: 1500,
      currency: 'usd',
      billing_interval: 'month',
      stripe_product_id: null,
      stripe_price_id: null,
      max_duration_seconds: 300,
      included_recordings: 10,
      allowed_filter_presets: ['clean', 'gramophone', 'radio'],
      allowed_vinyl_presets: ['all'],
      allowed_bg_music_ids: ['all'],
      allowed_vinyl_styles: ['classic_red', 'midnight_blue'],
      can_adjust_crackle: true,
      can_download: true,
      can_use_private_visibility: true,
      can_use_advanced_mixer: true,
      is_active: true,
      display_order: plans.length + 1,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
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
      if (onRefresh) await onRefresh();
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
      {/* Plans List */}
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-6">
        <div className="flex items-center justify-between border-b border-stone-800 pb-4">
          <div>
            <h3 className="font-serif font-bold text-lg text-amber-100">Pricing Plans & Entitlements</h3>
            <p className="text-xs text-stone-400">Manage billing models, Stripe sync, limits, features. Archive instead of delete if referenced.</p>
          </div>
          <Button variant="primary" size="sm" onClick={handleCreateNew} leftIcon={<Plus className="w-4 h-4 text-stone-950" />}>Create Plan</Button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {plans.sort((a,b) => (a.display_order||0)-(b.display_order||0)).map((plan) => {
            const s = stats[plan.id] || { users: 0, subs: 0, purchases: 0 };
            return (
              <div key={plan.id} className="p-5 rounded-2xl bg-stone-950 border border-stone-800 flex flex-col justify-between space-y-4 hover:border-amber-700/50 transition-all">
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <h4 className="font-serif font-bold text-stone-100 text-sm">{plan.name}</h4>
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-mono border ${plan.is_active ? 'bg-emerald-950 text-emerald-400 border-emerald-600/30' : 'bg-stone-800 text-stone-500 border-stone-700'}`}>{plan.is_active ? 'Active' : 'Archived'}</span>
                  </div>
                  <p className="text-[11px] text-stone-500">{plan.slug} • {plan.billing_model}</p>
                  <div className="flex items-baseline gap-1">
                    <span className="text-2xl font-mono font-bold text-amber-400">${(plan.price_cents / 100).toFixed(2)}</span>
                    <span className="text-xs text-stone-500">{plan.billing_model === 'monthly' ? '/mo' : plan.billing_model === 'lifetime' ? '/once' : plan.billing_model === 'per_recording' ? '/rec' : ''}</span>
                  </div>
                  <p className="text-[11px] text-stone-400 line-clamp-2">{plan.description}</p>
                  <div className="text-xs text-stone-400 space-y-1 pt-2 border-t border-stone-800">
                    <p className="flex items-center gap-1.5"><Clock className="w-3.5 h-3.5 text-amber-500" /> Max {Math.floor(plan.max_duration_seconds / 60)} min • {plan.included_recordings ?? '∞'} incl</p>
                    <p className="flex items-center gap-1.5"><Disc3 className="w-3.5 h-3.5 text-amber-500" /> {plan.allowed_vinyl_styles?.length || 1} styles • {plan.allowed_filter_presets?.length} filters</p>
                    <p className="flex items-center gap-1.5"><Users className="w-3 h-3 text-stone-500" /> {s.users} users • {s.subs} subs • {s.purchases} per-rec</p>
                    <p className="flex items-center gap-1.5 font-mono text-[10px]"><CreditCard className="w-3 h-3" /> {plan.stripe_product_id ? `prod ${plan.stripe_product_id.slice(0,8)}…` : 'no prod'} • {plan.stripe_price_id ? `price ${plan.stripe_price_id.slice(0,8)}…` : 'no price'}</p>
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={() => handleEdit(plan)} leftIcon={<Edit2 className="w-3.5 h-3.5" />} className="flex-1">Edit</Button>
                  {!plan.is_active ? (
                    <Button variant="outline" size="sm" onClick={() => { const upd = { ...plan, is_active: true }; onSavePlan(upd).then(() => toast.success('Reactivated')); }}>Re-activate</Button>
                  ) : (
                    <Button variant="outline" size="sm" onClick={() => { const upd = { ...plan, is_active: false }; onSavePlan(upd).then(() => toast.success('Archived')); }} leftIcon={<Archive className="w-3 h-3" />}>Archive</Button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Edit */}
      {editingPlan && (
        <form onSubmit={handleSave} className="p-6 rounded-3xl bg-stone-900 border border-amber-600/40 space-y-6 shadow-2xl">
          <div className="flex items-center justify-between border-b border-stone-800 pb-3">
            <h3 className="font-serif font-bold text-lg text-amber-100">Edit Plan: {editingPlan.name}</h3>
            <button type="button" onClick={() => setEditingPlan(null)} className="text-xs text-stone-400 hover:text-stone-200">Cancel</button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Name</label>
              <input type="text" value={editingPlan.name} onChange={(e) => setEditingPlan({ ...editingPlan, name: e.target.value })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm text-stone-100" required />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Slug</label>
              <input type="text" value={editingPlan.slug} onChange={(e) => setEditingPlan({ ...editingPlan, slug: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g,'-') })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm font-mono" />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Billing Model</label>
              <select value={editingPlan.billing_model} onChange={(e) => setEditingPlan({ ...editingPlan, billing_model: e.target.value as any, billing_interval: e.target.value === 'monthly' ? 'month' : e.target.value === 'free' ? null : 'one_time' as any })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm">
                <option value="free">Free</option>
                <option value="per_recording">Per Recording</option>
                <option value="monthly">Monthly</option>
                <option value="lifetime">Lifetime</option>
              </select>
            </div>
            <div className="lg:col-span-3">
              <label className="block text-xs font-mono text-stone-300 mb-1">Description</label>
              <textarea value={editingPlan.description || ''} onChange={(e) => setEditingPlan({ ...editingPlan, description: e.target.value })} rows={2} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Price cents</label>
              <input type="number" value={editingPlan.price_cents} onChange={(e) => setEditingPlan({ ...editingPlan, price_cents: parseInt(e.target.value) || 0 })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm font-mono" required />
              <p className="text-[10px] text-stone-500 mt-1">Changing amount will create new Stripe Price (old archived). Existing subs unchanged.</p>
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Currency</label>
              <input type="text" value={editingPlan.currency} onChange={(e) => setEditingPlan({ ...editingPlan, currency: e.target.value.toLowerCase() })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Billing Interval</label>
              <select value={editingPlan.billing_interval || ''} onChange={(e) => setEditingPlan({ ...editingPlan, billing_interval: (e.target.value || null) as any })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm">
                <option value="">None (free)</option>
                <option value="month">Month</option>
                <option value="year">Year</option>
                <option value="one_time">One-time</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Max Duration (sec)</label>
              <input type="number" value={editingPlan.max_duration_seconds} onChange={(e) => setEditingPlan({ ...editingPlan, max_duration_seconds: parseInt(e.target.value) || 60 })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm font-mono" />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Included recordings</label>
              <input type="number" value={editingPlan.included_recordings ?? ''} onChange={(e) => setEditingPlan({ ...editingPlan, included_recordings: e.target.value ? parseInt(e.target.value) : null })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm" placeholder="null = unlimited" />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Display order</label>
              <input type="number" value={editingPlan.display_order} onChange={(e) => setEditingPlan({ ...editingPlan, display_order: parseInt(e.target.value) || 0 })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Stripe Product ID</label>
              <input type="text" value={editingPlan.stripe_product_id || ''} onChange={(e) => setEditingPlan({ ...editingPlan, stripe_product_id: e.target.value || null })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs font-mono" placeholder="prod_..." />
            </div>
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">Stripe Price ID</label>
              <input type="text" value={editingPlan.stripe_price_id || ''} onChange={(e) => setEditingPlan({ ...editingPlan, stripe_price_id: e.target.value || null })} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs font-mono" placeholder="price_..." />
              <p className="text-[10px] text-amber-400 mt-1">If you edit price, system will auto-create new Stripe Price and archive old.</p>
            </div>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-mono text-stone-300">Allowed Filter Presets</label>
            <div className="flex flex-wrap gap-2">
              {FILTER_PRESETS.map((filter) => {
                const isSelected = editingPlan.allowed_filter_presets?.includes(filter.id);
                return (
                  <button key={filter.id} type="button" onClick={() => toggleFilter(filter.id)} className={`px-3 py-1.5 rounded-xl text-xs font-medium border flex items-center gap-1.5 ${isSelected ? 'bg-amber-600/20 border-amber-500 text-amber-200' : 'bg-stone-950 border-stone-800 text-stone-400'}`}>
                    {isSelected && <Check className="w-3.5 h-3.5 text-amber-400" />}<span>{filter.name}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-mono text-stone-300">Allowed Vinyl Styles</label>
            <div className="flex flex-wrap gap-2">
              {VINYL_STYLES.map((style) => {
                const isSelected = editingPlan.allowed_vinyl_styles?.includes(style.id);
                return (
                  <button key={style.id} type="button" onClick={() => toggleVinylStyle(style.id)} className={`px-3 py-1.5 rounded-xl text-xs font-medium border flex items-center gap-1.5 ${isSelected ? 'bg-amber-600/20 border-amber-500 text-amber-200' : 'bg-stone-950 border-stone-800 text-stone-400'}`}>
                    {isSelected && <Check className="w-3.5 h-3.5 text-amber-400" />}<span>{style.name}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-mono text-stone-300">Allowed Background Music</label>
            <div className="flex flex-wrap gap-2">
              {audioAssets.filter((asset) => asset.category === 'bg_music').map((asset) => {
                const isSelected = editingPlan.allowed_bg_music_ids.includes('all') || editingPlan.allowed_bg_music_ids.includes(asset.id);
                return (
                  <button key={asset.id} type="button" onClick={() => toggleBackgroundMusic(asset.id)} className={`px-3 py-1.5 rounded-xl text-xs font-medium border flex items-center gap-1.5 ${isSelected ? 'bg-amber-600/20 border-amber-500 text-amber-200' : 'bg-stone-950 border-stone-800 text-stone-400'}`}>
                    {isSelected && <Check className="w-3.5 h-3.5 text-amber-400" />}<span>{asset.title}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-4 border-t border-stone-800">
            <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer"><input type="checkbox" checked={editingPlan.can_adjust_crackle} onChange={(e) => setEditingPlan({ ...editingPlan, can_adjust_crackle: e.target.checked })} className="rounded accent-amber-500" />Can Adjust Crackle</label>
            <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer"><input type="checkbox" checked={editingPlan.can_download ?? false} onChange={(e) => setEditingPlan({ ...editingPlan, can_download: e.target.checked })} className="rounded accent-amber-500" />Can Download</label>
            <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer"><input type="checkbox" checked={editingPlan.can_use_private_visibility ?? false} onChange={(e) => setEditingPlan({ ...editingPlan, can_use_private_visibility: e.target.checked })} className="rounded accent-amber-500" />Can Use Private Visibility</label>
            <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer"><input type="checkbox" checked={editingPlan.can_use_advanced_mixer ?? false} onChange={(e) => setEditingPlan({ ...editingPlan, can_use_advanced_mixer: e.target.checked })} className="rounded accent-amber-500" />Can Use Advanced Mixer</label>
            <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer"><input type="checkbox" checked={editingPlan.is_active} onChange={(e) => setEditingPlan({ ...editingPlan, is_active: e.target.checked })} className="rounded accent-emerald-500" />Active</label>
          </div>

          <div className="flex justify-end pt-2">
            <Button type="submit" variant="primary" size="md" isLoading={isSaving} leftIcon={<Save className="w-4 h-4 text-stone-950" />}>Save Plan (creates Stripe Price if amount changed)</Button>
          </div>
        </form>
      )}
    </div>
  );
};
