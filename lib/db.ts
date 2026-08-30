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
  /** Optional. Added by lib/processing/queue.ts when the local fallback is alive. */
  processingJobs?: import('@/types').ProcessingJob[];
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

function readLocalStoreRaw(): LocalStore {
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

function writeLocalStoreRaw(store: LocalStore) {
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

/**
 * Typed in-file accessors. Caller modules still get `patchLocalStore`
 * (below) to extend the store with keys that aren't part of LocalStore.
 */
function readLocalStore(): LocalStore {
  return readLocalStoreRaw();
}
function writeLocalStore(store: LocalStore): void {
  writeLocalStoreRaw(store);
}

/**
 * Patch the local fallback blob in place. Sibling modules (notably the
 * processing queue) use this to extend the in-process store without having
 * to import every new field into the strict LocalStore type. Real
 * production calls go through Supabase.
 */
export function patchLocalStore<K extends string>(
  key: K,
  mutator: (current: any | undefined) => any
): void {
  const store = readLocalStoreRaw() as any;
  const next = mutator(store[key]);
  store[key] = next;
  writeLocalStoreRaw(store);
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
    const supabase = getServiceSupabase();
    if (updates.hero_copy) {
      const { error } = await supabase.from('site_settings').upsert({
          key: 'hero_copy',
          value: updates.hero_copy,
          updated_at: new Date().toISOString(),
      });
      if (error) throw new Error(`Hero settings could not be saved: ${error.message}`);
    }
    if (updates.branding_theme) {
      const { error } = await supabase.from('site_settings').upsert({
          key: 'branding_theme',
          value: updates.branding_theme,
          updated_at: new Date().toISOString(),
      });
      if (error) throw new Error(`Brand settings could not be saved: ${error.message}`);
    }
    if (updates.faqs) {
      const { error } = await supabase.from('site_settings').upsert({
          key: 'faqs',
          value: updates.faqs,
          updated_at: new Date().toISOString(),
      });
      if (error) throw new Error(`FAQ settings could not be saved: ${error.message}`);
    }
    return getSiteSettings();
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
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data, error } = await supabase.from('pricing_plans').select('*').order('price_cents', { ascending: true });
      if (error) throw error;
      if (data) return data as PricingPlan[];
    } catch (err) {
      console.warn('Supabase all pricing_plans fallback:', err);
    }
  }
  const store = readLocalStore();
  return store.pricingPlans;
}

export async function upsertPricingPlan(plan: PricingPlan): Promise<PricingPlan> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { data, error } = await supabase.from('pricing_plans').upsert(plan).select().single();
    if (error) throw new Error(`Pricing plan could not be saved: ${error.message}`);
    return data as PricingPlan;
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

export async function updateAudioAsset(id: string, updates: Partial<AudioAsset>): Promise<AudioAsset | null> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { data, error } = await supabase.from('audio_assets').update(updates).eq('id', id).select().single();
    if (error) throw new Error(`Audio asset could not be updated: ${error.message}`);
    return data as AudioAsset;
  }

  const store = readLocalStore();
  const index = store.audioAssets.findIndex((asset) => asset.id === id);
  if (index < 0) return null;
  store.audioAssets[index] = { ...store.audioAssets[index], ...updates };
  writeLocalStore(store);
  return store.audioAssets[index];
}

export async function addAudioAsset(asset: AudioAsset): Promise<AudioAsset> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { data, error } = await supabase.from('audio_assets').insert(asset).select().single();
    if (error) throw new Error(`Audio asset could not be created: ${error.message}`);
    return data as AudioAsset;
  }

  const store = readLocalStore();
  store.audioAssets.unshift(asset);
  writeLocalStore(store);
  return asset;
}

export async function deleteAudioAsset(id: string): Promise<boolean> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { error } = await supabase.from('audio_assets').delete().eq('id', id);
    if (error) throw new Error(`Audio asset could not be deleted: ${error.message}`);
    return true;
  }

  const store = readLocalStore();
  store.audioAssets = store.audioAssets.filter((a) => a.id !== id);
  writeLocalStore(store);
  return true;
}

// 4. RECORDINGS
//
// Visibility model:
//   'public'   — anyone (including anonymous) can find + view.
//   'unlisted' — only people with the slug URL can view (current
//                default; matches the old behaviour that everything
//                was shareable via link).
//   'private'  — owner OR admin can view; everyone else gets 404.
//
// `viewer` is passed in so the same helper powers both the public
// share API and the dashboard listing API.
export type Viewer =
  | { kind: 'anonymous' }
  | { kind: 'admin' }
  | { kind: 'user'; userId: string };

const VISIBLE_TO_VIEWER = (rec: Recording, viewer: Viewer): boolean => {
  const v = rec.visibility ?? 'unlisted';
  if (v === 'public' || v === 'unlisted') return true;
  if (v === 'private') {
    if (viewer.kind === 'admin') return true;
    if (viewer.kind === 'user' && rec.user_id && rec.user_id === viewer.userId) return true;
  }
  return false;
};

function applyVisibilityFilter(rows: Recording[], viewer: Viewer): Recording[] {
  // Listing is stricter than link-addressable playback: unlisted records
  // must never appear in a library/list endpoint for unrelated visitors.
  return rows.filter((r) => {
    const visibility = r.visibility ?? 'unlisted';
    if (visibility === 'public') return true;
    if (viewer.kind === 'admin') return true;
    if (viewer.kind === 'user' && r.user_id === viewer.userId) return true;
    return false;
  });
}

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
  // Admin-style call: returns everything, including private.
  return store.recordings;
}

export async function getRecordingsForViewer(viewer: Viewer): Promise<Recording[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('recordings').select('*').order('created_at', { ascending: false });
      if (data && data.length > 0) {
        return applyVisibilityFilter(data as Recording[], viewer);
      }
    } catch (err) {
      console.warn('Supabase recordings fallback:', err);
    }
  }
  const store = readLocalStore();
  return applyVisibilityFilter(store.recordings, viewer);
}

export async function getRecordingBySlug(slug: string, viewer: Viewer = { kind: 'anonymous' }): Promise<Recording | null> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('recordings').select('*').eq('slug', slug).single();
      if (data) {
        const rec = data as Recording;
        return VISIBLE_TO_VIEWER(rec, viewer) ? rec : null;
      }
    } catch (err) {
      console.warn('Supabase getRecordingBySlug fallback:', err);
    }
  }

  const store = readLocalStore();
  const rec = store.recordings.find((r) => r.slug.toLowerCase() === slug.toLowerCase());
  if (!rec) return null;
  return VISIBLE_TO_VIEWER(rec, viewer) ? rec : null;
}

export async function getRecordingForAudioFilename(filename: string): Promise<Pick<Recording, 'id' | 'visibility' | 'processed_audio_url' | 'raw_voice_url'> | null> {
  const needle = path.basename(filename.split('?')[0]).replace(/[^a-zA-Z0-9._-]/g, '');
  if (!needle) return null;
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const urls = [`/api/records/${needle}`, `/records/${needle}`, `/audio/${needle}`];
      const { data } = await supabase
        .from('recordings')
        .select('id, visibility, processed_audio_url, raw_voice_url')
        .or(urls.flatMap((url) => [`processed_audio_url.eq.${url}`, `raw_voice_url.eq.${url}`]).join(','))
        .limit(1)
        .maybeSingle();
      return (data as any) || null;
    } catch { return null; }
  }
  const store = readLocalStore();
  const rec = store.recordings.find((r) =>
    r.processed_audio_url.includes(needle) || r.raw_voice_url.includes(needle)
  );
  return rec ? {
    id: rec.id,
    visibility: rec.visibility,
    processed_audio_url: rec.processed_audio_url,
    raw_voice_url: rec.raw_voice_url,
  } : null;
}

export async function getRecordingByIdForStatus(id: string): Promise<Pick<Recording, 'id' | 'slug' | 'user_id' | 'processing_state' | 'processing_progress' | 'processing_error'> | null> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase
        .from('recordings')
        .select('id, slug, user_id, processing_state, processing_progress, processing_error')
        .eq('id', id)
        .maybeSingle();
      return (data as any) || null;
    } catch { return null; }
  }
  const store = readLocalStore();
  const rec = store.recordings.find((r) => r.id === id);
  return rec ? {
    id: rec.id,
    slug: rec.slug,
    user_id: rec.user_id,
    processing_state: rec.processing_state,
    processing_progress: rec.processing_progress,
    processing_error: rec.processing_error,
  } : null;
}

export async function updateRecordingProcessing(
  id: string,
  patch: Pick<Recording, 'processing_state' | 'processing_progress' | 'processing_error' | 'processing_started_at' | 'processing_completed_at' | 'processed_audio_url' | 'duration_seconds'> & Partial<Pick<Recording, 'processed_storage_key'>>
): Promise<void> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { error } = await supabase.from('recordings').update(patch).eq('id', id);
    if (error) throw new Error(`Recording processing state could not be updated: ${error.message}`);
    return;
  }
  const store = readLocalStore();
  const index = store.recordings.findIndex((r) => r.id === id);
  if (index >= 0) {
    store.recordings[index] = { ...store.recordings[index], ...patch };
    writeLocalStore(store);
  }
}

export async function saveTranscript(input: {
  recordingId: string;
  words: import('@/types').TranscriptWord[];
  isPubliclyVisible?: boolean;
  provider?: string | null;
}): Promise<void> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { error } = await supabase.from('record_transcripts').upsert({
      recording_id: input.recordingId,
      words: input.words,
      is_publicly_visible: input.isPubliclyVisible ?? false,
      provider: input.provider ?? null,
    }, { onConflict: 'recording_id' });
    if (error) throw new Error(`Transcript could not be saved: ${error.message}`);
    return;
  }
  patchLocalStore('recordTranscripts', (rows) => ({ ...(rows || {}), [input.recordingId]: input.words }));
}

export async function saveRecording(recording: Recording): Promise<Recording> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { error } = await supabase.from('recordings').insert(recording);
    if (error) throw new Error(`Recording could not be saved: ${error.message}`);
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
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data: current, error: readError } = await supabase.from('recordings').select('views').eq('slug', slug).single();
      if (readError) throw readError;
      const views = (current?.views || 0) + 1;
      const { error: updateError } = await supabase.from('recordings').update({ views }).eq('slug', slug);
      if (updateError) throw updateError;
      return views;
    } catch (err) {
      console.warn('Supabase increment views fallback:', err);
    }
  }

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
      const { data: recording, error: readError } = await supabase
        .from('recordings')
        .select('processed_audio_url')
        .eq('id', id)
        .single();
      if (readError) throw readError;
      const audioUrl = recording?.processed_audio_url || '';
      if (audioUrl.includes('/storage/v1/object/public/recordings/')) {
        const objectName = decodeURIComponent(audioUrl.split('/').pop() || '');
        if (objectName) {
          const { error: storageError } = await supabase.storage.from('recordings').remove([objectName]);
          if (storageError) throw storageError;
        }
      }
      const { error } = await supabase.from('recordings').delete().eq('id', id);
      if (error) throw error;
      return true;
    } catch (err) {
      console.warn('Supabase delete recording error:', err);
      return false;
    }
  }

  const store = readLocalStore();
  store.recordings = store.recordings.filter((r) => r.id !== id);
  writeLocalStore(store);
  return true;
}

// 5. PROFILES & USERS
export async function getProfiles(): Promise<Profile[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data, error } = await supabase.from('profiles').select('*').order('created_at', { ascending: false });
      if (error) throw error;
      if (data) return data as Profile[];
    } catch (err) {
      console.warn('Supabase profiles fallback:', err);
    }
  }
  const store = readLocalStore();
  return store.profiles;
}

export async function updateProfile(id: string, updates: Partial<Profile>): Promise<Profile | null> {
  const safeUpdates = { ...updates, updated_at: new Date().toISOString() };
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data, error } = await supabase.from('profiles').update(safeUpdates).eq('id', id).select().single();
      if (error) throw error;
      if (data) return data as Profile;
    } catch (err) {
      console.warn('Supabase update profile fallback:', err);
    }
  }
  const store = readLocalStore();
  const idx = store.profiles.findIndex((p) => p.id === id);
  if (idx >= 0) {
    store.profiles[idx] = { ...store.profiles[idx], ...safeUpdates };
    writeLocalStore(store);
    return store.profiles[idx];
  }
  return null;
}

export async function deleteProfile(id: string): Promise<boolean> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { error: authError } = await supabase.auth.admin.deleteUser(id);
      if (authError) throw authError;
      const { error } = await supabase.from('profiles').delete().eq('id', id);
      if (error) throw error;
      return true;
    } catch (err) {
      console.warn('Supabase delete profile error:', err);
      return false;
    }
  }
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
