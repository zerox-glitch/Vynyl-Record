'use client';

import React, { useState, useEffect } from 'react';
import { AdminCmsTab } from '@/components/admin/AdminCmsTab';
import { AdminPricingTab } from '@/components/admin/AdminPricingTab';
import { AdminAudioStudioTab } from '@/components/admin/AdminAudioStudioTab';
import { AdminUsersTab } from '@/components/admin/AdminUsersTab';
import { AdminRecordingsTab } from '@/components/admin/AdminRecordingsTab';
import { AdminAuditTab } from '@/components/admin/AdminAuditTab';
import { 
  SiteSettings, 
  PricingPlan, 
  AudioAsset, 
  Profile, 
  Recording 
} from '@/types';
import { 
  DEFAULT_SITE_SETTINGS, 
  DEFAULT_PRICING_PLANS, 
  DEFAULT_AUDIO_ASSETS, 
  DEMO_RECORDINGS 
} from '@/lib/constants';
import { 
  Shield, 
  FileEdit, 
  DollarSign, 
  Volume2, 
  Users, 
  Disc3, 
  Unlock,
  LogOut
} from 'lucide-react';

type AdminTab = 'cms' | 'pricing' | 'audio' | 'users' | 'recordings' | 'audit';

export default function AdminPage() {
  const [activeTab, setActiveTab] = useState<AdminTab>('cms');

  // Master Data State
  const [siteSettings, setSiteSettings] = useState<SiteSettings>(DEFAULT_SITE_SETTINGS);
  const [pricingPlans, setPricingPlans] = useState<PricingPlan[]>(DEFAULT_PRICING_PLANS);
  const [audioAssets, setAudioAssets] = useState<AudioAsset[]>(DEFAULT_AUDIO_ASSETS);
  const [users, setUsers] = useState<any[]>([]);
  const [recordings, setRecordings] = useState<Recording[]>(DEMO_RECORDINGS);

  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const handleLogout = async () => {
    await fetch('/api/admin/session', { method: 'DELETE' });
    window.location.assign('/admin/login');
  };

  // Load all admin data
  useEffect(() => {
    async function loadData() {
      setIsLoading(true);
      setLoadError(null);
      try {
        const responses = await Promise.all([
          fetch('/api/cms'),
          fetch('/api/pricing'),
          fetch('/api/audio/upload-asset'),
          fetch('/api/admin/users?limit=100'),
          fetch('/api/recordings'),
        ]);
        if (responses.some((response) => response.status === 401)) {
          window.location.assign('/admin/login');
          return;
        }
        if (responses.some((response) => !response.ok)) {
          throw new Error('One or more administration services could not be loaded.');
        }
        const [cmsRes, plansRes, assetsRes, usersRes, recsRes] = await Promise.all(responses.map((response) => response.json()));

        if (cmsRes.settings) setSiteSettings(cmsRes.settings);
        if (plansRes.plans) setPricingPlans(plansRes.plans);
        if (assetsRes.assets) setAudioAssets(assetsRes.assets);
        if (usersRes.users) setUsers(usersRes.users);
        if (recsRes.recordings) setRecordings(recsRes.recordings);
      } catch (err) {
        setLoadError(err instanceof Error ? err.message : 'Administration data could not be loaded.');
      } finally {
        setIsLoading(false);
      }
    }

    loadData();
  }, []);

  // Handlers
  const handleSaveCms = async (updated: SiteSettings) => {
    const res = await fetch('/api/cms', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(updated),
    });
    if (!res.ok) throw new Error('Failed to update CMS');
    setSiteSettings(updated);
  };

  const loadUsers = async () => {
    const res = await fetch('/api/admin/users?limit=100');
    if (res.ok) {
      const data = await res.json();
      if (data.users) setUsers(data.users as any);
    }
  };

  const handleUpdateUser = async (id: string, updates: any) => {
    const res = await fetch('/api/admin/users', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, updates }),
    });
    if (!res.ok) throw new Error('Failed to update user');
    await loadUsers();
  };

  const handleDeleteUser = async (id: string, confirm: string = 'DELETE') => {
    const res = await fetch(`/api/admin/users?id=${id}&confirm=${encodeURIComponent(confirm)}`, { method: 'DELETE' });
    if (!res.ok) {
      const j = await res.json().catch(() => ({}));
      throw new Error(j.error || 'Failed to delete user');
    }
    setUsers((prev) => prev.filter((u) => u.id !== id));
  };

  const handleSavePlan = async (plan: PricingPlan) => {
    const res = await fetch('/api/pricing', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(plan),
    });
    if (!res.ok) {
      const j = await res.json().catch(() => ({}));
      throw new Error(j.error || 'Failed to save plan');
    }
    const data = await res.json();
    setPricingPlans((prev) => {
      const idx = prev.findIndex((p) => p.id === plan.id);
      if (idx >= 0) {
        const copy = [...prev];
        copy[idx] = data.plan;
        return copy;
      }
      return [...prev, data.plan];
    });
  };

  const handleAssetAdded = (asset: AudioAsset) => {
    setAudioAssets((prev) => [asset, ...prev]);
  };

  const handleAssetDeleted = (id: string) => {
    setAudioAssets((prev) => prev.filter((a) => a.id !== id));
  };

  const handleAssetUpdated = (asset: AudioAsset) => {
    setAudioAssets((prev) => prev.map((item) => (item.id === asset.id ? asset : item)));
  };

  const handleDeleteRecording = async (id: string) => {
    const res = await fetch(`/api/recordings?id=${id}`, { method: 'DELETE' });
    if (!res.ok) throw new Error('Failed to delete recording');
    setRecordings((prev) => prev.filter((r) => r.id !== id));
  };

  const tabConfigs = [
    { id: 'cms', label: 'Site CMS & Theme', icon: <FileEdit className="w-4 h-4" /> },
    { id: 'pricing', label: 'Pricing & Stripe Plans', icon: <DollarSign className="w-4 h-4" /> },
    { id: 'audio', label: 'Audio Assets & Mic Studio', icon: <Volume2 className="w-4 h-4" /> },
    { id: 'users', label: 'User Management', icon: <Users className="w-4 h-4" /> },
    { id: 'recordings', label: 'Recordings Moderation', icon: <Disc3 className="w-4 h-4" /> },
    { id: 'audit', label: 'Audit Log', icon: <Shield className="w-4 h-4" /> },
  ];

  return (
    <div className="min-h-screen bg-[#0c0a09] text-stone-100 flex flex-col selection:bg-amber-600 selection:text-white">
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-8">
        {/* Admin Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-stone-800 pb-6">
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-amber-500 font-mono text-xs uppercase tracking-widest">
              <Shield className="w-4 h-4" />
              <span>Restricted Operations Hub</span>
            </div>
            <h1 className="text-3xl font-serif font-bold text-stone-100">
              Master Admin Operations
            </h1>
          </div>

          <div className="flex items-center gap-2">
            <div className="flex items-center gap-2 bg-stone-900 border border-amber-600/30 px-3 py-1.5 rounded-xl text-xs font-mono text-amber-300">
              <Unlock className="w-3.5 h-3.5 text-amber-400" />
              <span>Admin Clearance Granted</span>
            </div>
            <button onClick={handleLogout} className="p-2 rounded-xl bg-stone-900 text-stone-400 hover:text-red-300" title="Sign out">
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="flex flex-wrap gap-2 border-b border-stone-800/80 pb-4">
          {tabConfigs.map((tab) => {
            const isActive = activeTab === tab.id;

            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as AdminTab)}
                className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs sm:text-sm font-medium transition-all ${
                  isActive
                    ? 'bg-amber-600 text-stone-950 font-bold shadow-lg shadow-amber-950/60'
                    : 'bg-stone-900 text-stone-300 hover:text-amber-200 hover:bg-stone-800'
                }`}
              >
                {tab.icon}
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>

        {/* Active Tab Panel */}
        <div className="pt-2">
          {isLoading ? (
            <div className="min-h-[320px] grid place-items-center rounded-3xl border border-stone-800 bg-stone-900/60">
              <div className="text-center space-y-3">
                <Disc3 className="w-10 h-10 mx-auto text-amber-500 animate-spin" />
                <p className="text-sm text-stone-400">Loading live administration data...</p>
              </div>
            </div>
          ) : loadError ? (
            <div className="min-h-[220px] grid place-items-center rounded-3xl border border-red-900/50 bg-red-950/20 p-8 text-center">
              <div>
                <h2 className="font-serif text-xl font-bold text-red-200">Admin data unavailable</h2>
                <p className="mt-2 text-sm text-stone-400">{loadError}</p>
                <button onClick={() => window.location.reload()} className="mt-5 rounded-xl bg-stone-800 px-4 py-2 text-sm hover:bg-stone-700">Try again</button>
              </div>
            </div>
          ) : activeTab === 'cms' && (
            <AdminCmsTab settings={siteSettings} onSave={handleSaveCms} />
          )}

          {!isLoading && !loadError && activeTab === 'pricing' && (
            <AdminPricingTab plans={pricingPlans} audioAssets={audioAssets} onSavePlan={handleSavePlan} onRefresh={async () => {
              const res = await fetch('/api/pricing');
              if (res.ok) {
                const d = await res.json();
                if (d.plans) setPricingPlans(d.plans);
              }
            }} />
          )}

          {!isLoading && !loadError && activeTab === 'audio' && (
            <AdminAudioStudioTab
              assets={audioAssets}
              onAssetAdded={handleAssetAdded}
              onAssetDeleted={handleAssetDeleted}
              onAssetUpdated={handleAssetUpdated}
            />
          )}

          {!isLoading && !loadError && activeTab === 'users' && (
            <AdminUsersTab
              users={users as any}
              onUpdateUser={handleUpdateUser}
              onDeleteUser={handleDeleteUser as any}
              onRefresh={loadUsers}
            />
          )}

          {!isLoading && !loadError && activeTab === 'recordings' && (
            <AdminRecordingsTab
              recordings={recordings}
              onDeleteRecording={handleDeleteRecording}
            />
          )}

          {!isLoading && !loadError && activeTab === 'audit' && (
            <AdminAuditTab />
          )}
        </div>
      </main>

    </div>
  );
}
