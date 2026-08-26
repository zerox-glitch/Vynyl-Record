import fs from 'fs';
import path from 'path';
import os from 'os';
import { 
  Recording, 
  SiteSettings, 
  PricingPlan, 
  AudioAsset, 
  Profile, 
  IntegrationSettings 
} from '@/types';
import { 
  DEFAULT_SITE_SETTINGS, 
  DEFAULT_PRICING_PLANS, 
  DEFAULT_AUDIO_ASSETS, 
  DEMO_RECORDINGS 
} from '@/lib/constants';
import { getServiceSupabase, isSupabaseServerConfigured } from './supabase/server';

interface LocalStore {
  recordings: Recording[];
  siteSettings: SiteSettings;
  pricingPlans: PricingPlan[];
  audioAssets: AudioAsset[];
  profiles: Profile[];
  integrationSettings: IntegrationSettings;
}

// Serverless writable directory: use os.tmpdir() to prevent EROFS errors on Vercel
const DATA_FILE = path.join(os.tmpdir(), 'vynyl_local_database.json');

const DEFAULT_PROFILES: Profile[] = [
  {
    id: 'user-0001',
    email: 'creator@vinylvoicenotes.com',
    full_name: 'Arthur Vance',
    role: 'admin',
    stripe_customer_id: 'cus_sample_01',
    is_premium: true,
    recording_count: 5,
    created_at: new Date(Date.now() - 86400000 * 30).toISOString(),
    updated_at: new Date().toISOString(),
  },
  {
    id: 'user-0002',
    email: 'maya.sound@example.com',
    full_name: 'Maya Lin',
    role: 'user',
    stripe_customer_id: 'cus_sample_02',
    is_premium: true,
    recording_count: 12,
    created_at: new Date(Date.now() - 86400000 * 15).toISOString(),
    updated_at: new Date().toISOString(),
  },
  {
    id: 'user-0003',
    email: 'nostalgia.collector@vintage.fm',
    full_name: 'Julian Hayes',
    role: 'user',
    stripe_customer_id: null,
    is_premium: false,
    recording_count: 1,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
    updated_at: new Date().toISOString(),
  },
];

const DEFAULT_INTEGRATION_SETTINGS: IntegrationSettings = {
  id: 1,
  stripe_publishable_key: process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY || null,
  stripe_secret_key: process.env.STRIPE_SECRET_KEY || null,
  stripe_webhook_secret: process.env.STRIPE_WEBHOOK_SECRET || null,
  updated_at: new Date().toISOString(),
};

function getInitialStore(): LocalStore {
  return {
    recordings: [...DEMO_RECORDINGS],
    siteSettings: { ...DEFAULT_SITE_SETTINGS },
    pricingPlans: [...DEFAULT_PRICING_PLANS],
    audioAssets: [...DEFAULT_AUDIO_ASSETS],
    profiles: [...DEFAULT_PROFILES],
    integrationSettings: { ...DEFAULT_INTEGRATION_SETTINGS },
  };
}

// In-memory runtime cache for serverless resiliency against read-only or ephemeral filesystems
function getMemoryStore(): LocalStore {
  const globalObj = globalThis as unknown as { __vynylLocalStore?: LocalStore };
  if (!globalObj.__vynylLocalStore) {
    globalObj.__vynylLocalStore = getInitialStore();
  }
  return globalObj.__vynylLocalStore;
}

function setMemoryStore(store: LocalStore) {
  const globalObj = globalThis as unknown as { __vynylLocalStore?: LocalStore };
  globalObj.__vynylLocalStore = store;
}

function readLocalStore(): LocalStore {
  const memStore = getMemoryStore();

  try {
    if (fs.existsSync(DATA_FILE)) {
      const raw = fs.readFileSync(DATA_FILE, 'utf-8');
      const parsed = JSON.parse(raw);
      if (parsed && Array.isArray(parsed.recordings)) {
        setMemoryStore(parsed);
        return parsed;
      }
    }
  } catch (err) {
    console.warn('[DB] Local DB file read warning (using in-memory store):', err);
  }

  // Attempt to write initial store to tmp
  try {
    const dir = path.dirname(DATA_FILE);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(DATA_FILE, JSON.stringify(memStore, null, 2), 'utf-8');
  } catch (err) {
    console.warn('[DB] Local DB tmp file write note (in-memory mode active):', err);
  }

  return memStore;
}

function writeLocalStore(store: LocalStore) {
  // Always update in-memory store first
  setMemoryStore(store);

  try {
    const dir = path.dirname(DATA_FILE);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(DATA_FILE, JSON.stringify(store, null, 2), 'utf-8');
  } catch (err) {
    console.warn('[DB] Local store tmp write note:', err);
  }
}

// 1. SITE SETTINGS
export async function getSiteSettings(): Promise<SiteSettings> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('site_settings').select('*');
      if (data && data.length > 0) {
        const settings: any = {};
        for (const row of data) {
          settings[row.key] = row.value;
        }
        return {
          hero_copy: settings.hero_copy || DEFAULT_SITE_SETTINGS.hero_copy,
          branding_theme: settings.branding_theme || DEFAULT_SITE_SETTINGS.branding_theme,
          faqs: settings.faqs || DEFAULT_SITE_SETTINGS.faqs,
        };
      }
    } catch (err) {
      console.warn('Supabase site_settings fallback:', err);
    }
  }

  const store = readLocalStore();
  return store.siteSettings;
}

export async function updateSiteSettings(updates: Partial<SiteSettings>): Promise<SiteSettings> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      if (updates.hero_copy) {
        await supabase.from('site_settings').upsert({
          key: 'hero_copy',
          value: updates.hero_copy,
          updated_at: new Date().toISOString(),
        });
      }
      if (updates.branding_theme) {
        await supabase.from('site_settings').upsert({
          key: 'branding_theme',
          value: updates.branding_theme,
          updated_at: new Date().toISOString(),
        });
      }
      if (updates.faqs) {
        await supabase.from('site_settings').upsert({
          key: 'faqs',
          value: updates.faqs,
          updated_at: new Date().toISOString(),
        });
      }
    } catch (err) {
      console.warn('Supabase site_settings update error:', err);
    }
  }

  const store = readLocalStore();
  store.siteSettings = {
    ...store.siteSettings,
    ...updates,
  };
  writeLocalStore(store);
  return store.siteSettings;
}

// 2. PRICING PLANS
export async function getPricingPlans(): Promise<PricingPlan[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase
        .from('pricing_plans')
        .select('*')
        .eq('is_active', true)
        .order('price_cents', { ascending: true });
      if (data && data.length > 0) return data as PricingPlan[];
    } catch (err) {
      console.warn('Supabase pricing_plans fallback:', err);
    }
  }

  const store = readLocalStore();
  return store.pricingPlans.filter((p) => p.is_active);
}

export async function getAllPricingPlans(): Promise<PricingPlan[]> {
  const store = readLocalStore();
  return store.pricingPlans;
}

export async function upsertPricingPlan(plan: PricingPlan): Promise<PricingPlan> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('pricing_plans').upsert(plan);
    } catch (err) {
      console.warn('Supabase upsert pricing error:', err);
    }
  }

  const store = readLocalStore();
  const idx = store.pricingPlans.findIndex((p) => p.id === plan.id);
  if (idx >= 0) {
    store.pricingPlans[idx] = plan;
  } else {
    store.pricingPlans.push(plan);
  }
  writeLocalStore(store);
  return plan;
}

// 3. AUDIO ASSETS
export async function getAudioAssets(): Promise<AudioAsset[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('audio_assets').select('*').order('created_at', { ascending: false });
      if (data && data.length > 0) return data as AudioAsset[];
    } catch (err) {
      console.warn('Supabase audio_assets fallback:', err);
    }
  }

  const store = readLocalStore();
  return store.audioAssets;
}

export async function addAudioAsset(asset: AudioAsset): Promise<AudioAsset> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('audio_assets').insert(asset);
    } catch (err) {
      console.warn('Supabase add audio_asset error:', err);
    }
  }

  const store = readLocalStore();
  store.audioAssets.unshift(asset);
  writeLocalStore(store);
  return asset;
}

export async function deleteAudioAsset(id: string): Promise<boolean> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('audio_assets').delete().eq('id', id);
    } catch (err) {
      console.warn('Supabase delete audio_asset error:', err);
    }
  }

  const store = readLocalStore();
  store.audioAssets = store.audioAssets.filter((a) => a.id !== id);
  writeLocalStore(store);
  return true;
}

// 4. RECORDINGS
export async function getRecordings(): Promise<Recording[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('recordings').select('*').order('created_at', { ascending: false });
      if (data && data.length > 0) return data as Recording[];
    } catch (err) {
      console.warn('Supabase recordings fallback:', err);
    }
  }

  const store = readLocalStore();
  return store.recordings;
}

export async function getRecordingBySlug(slug: string): Promise<Recording | null> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('recordings').select('*').eq('slug', slug).single();
      if (data) return data as Recording;
    } catch (err) {
      console.warn('Supabase getRecordingBySlug fallback:', err);
    }
  }

  const store = readLocalStore();
  const rec = store.recordings.find((r) => r.slug.toLowerCase() === slug.toLowerCase());
  return rec || null;
}

export async function saveRecording(recording: Recording): Promise<Recording> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('recordings').insert(recording);
    } catch (err) {
      console.warn('Supabase save recording error:', err);
    }
  }

  const store = readLocalStore();
  const idx = store.recordings.findIndex((r) => r.id === recording.id || r.slug === recording.slug);
  if (idx >= 0) {
    store.recordings[idx] = recording;
  } else {
    store.recordings.unshift(recording);
  }
  writeLocalStore(store);
  return recording;
}

export async function incrementRecordingViews(slug: string): Promise<number> {
  const store = readLocalStore();
  const rec = store.recordings.find((r) => r.slug.toLowerCase() === slug.toLowerCase());
  if (rec) {
    rec.views = (rec.views || 0) + 1;
    writeLocalStore(store);
    return rec.views;
  }
  return 0;
}

export async function deleteRecording(id: string): Promise<boolean> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('recordings').delete().eq('id', id);
    } catch (err) {
      console.warn('Supabase delete recording error:', err);
    }
  }

  const store = readLocalStore();
  store.recordings = store.recordings.filter((r) => r.id !== id);
  writeLocalStore(store);
  return true;
}

// 5. PROFILES & USERS
export async function getProfiles(): Promise<Profile[]> {
  const store = readLocalStore();
  return store.profiles;
}

export async function updateProfile(id: string, updates: Partial<Profile>): Promise<Profile | null> {
  const store = readLocalStore();
  const idx = store.profiles.findIndex((p) => p.id === id);
  if (idx >= 0) {
    store.profiles[idx] = { ...store.profiles[idx], ...updates, updated_at: new Date().toISOString() };
    writeLocalStore(store);
    return store.profiles[idx];
  }
  return null;
}

export async function deleteProfile(id: string): Promise<boolean> {
  const store = readLocalStore();
  store.profiles = store.profiles.filter((p) => p.id !== id);
  writeLocalStore(store);
  return true;
}

// 6. INTEGRATION SETTINGS
export async function getIntegrationSettings(): Promise<IntegrationSettings> {
  const store = readLocalStore();
  return store.integrationSettings;
}

export async function updateIntegrationSettings(settings: Partial<IntegrationSettings>): Promise<IntegrationSettings> {
  const store = readLocalStore();
  store.integrationSettings = {
    ...store.integrationSettings,
    ...settings,
    updated_at: new Date().toISOString(),
  };
  writeLocalStore(store);
  return store.integrationSettings;
}
