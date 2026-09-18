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

/**
 * Local JSON is demo/dev only when Supabase is configured.
 * When Supabase is NOT configured (e.g. Vercel preview without env vars),
 * we allow fallback even in production to prevent hard crashes on homepage.
 * Production with Supabase configured must set ALLOW_LOCAL_DEMO_STORE=true to use local fallback.
 */
function localFallbackAllowed(): boolean {
  if (!isSupabaseServerConfigured()) return true;
  return process.env.NODE_ENV !== 'production' || process.env.ALLOW_LOCAL_DEMO_STORE === 'true';
}
function requireLocalFallbackAllowed(): void {
  if (!localFallbackAllowed()) {
    // Only throw if Supabase is configured but local fallback is not allowed
    if (isSupabaseServerConfigured()) {
      throw new Error('Persistent database is unavailable. Configure Supabase; local JSON fallback is disabled in production.');
    }
    // If Supabase not configured, allow fallback silently to prevent Vercel crash
  }
}

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
  requireLocalFallbackAllowed();
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
        .order('display_order', { ascending: true });
      if (data && data.length > 0) return data as PricingPlan[];
      // fallback order by price
      const { data: data2 } = await supabase.from('pricing_plans').select('*').eq('is_active', true).order('price_cents', { ascending: true });
      if (data2 && data2.length > 0) return data2 as PricingPlan[];
    } catch (err) {
      console.warn('Supabase pricing_plans fallback:', err);
    }
  }

  const store = readLocalStore();
  return store.pricingPlans.filter((p) => p.is_active).sort((a,b) => (a.display_order||0)-(b.display_order||0));
}

export async function getAllPricingPlans(): Promise<PricingPlan[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data, error } = await supabase.from('pricing_plans').select('*').order('display_order', { ascending: true });
      if (error) throw error;
      if (data) return data as PricingPlan[];
    } catch (err) {
      console.warn('Supabase all pricing_plans fallback:', err);
    }
  }
  const store = readLocalStore();
  return store.pricingPlans.sort((a,b) => (a.display_order||0)-(b.display_order||0));
}

export async function upsertPricingPlan(plan: PricingPlan): Promise<PricingPlan> {
  // sanitize
  const sanitized: PricingPlan = {
    id: plan.id,
    slug: (plan.slug || plan.name.toLowerCase().replace(/[^a-z0-9]+/g,'-')).slice(0,80),
    name: String(plan.name).slice(0,120),
    description: plan.description ? String(plan.description).slice(0,500) : null,
    billing_model: (['free','per_recording','monthly','lifetime'].includes(plan.billing_model) ? plan.billing_model : 'free') as any,
    price_cents: Math.max(0, Math.min(1000000, Math.floor(plan.price_cents))),
    currency: (plan.currency || 'usd').toLowerCase().slice(0,10),
    billing_interval: plan.billing_interval || (plan.billing_model === 'monthly' ? 'month' : plan.billing_model === 'free' ? null : 'one_time') as any,
    stripe_product_id: plan.stripe_product_id || null,
    stripe_price_id: plan.stripe_price_id || null,
    max_duration_seconds: Math.max(10, Math.min(3600, Math.floor(plan.max_duration_seconds))),
    included_recordings: plan.included_recordings != null ? Math.max(0, Math.floor(plan.included_recordings)) : null,
    allowed_filter_presets: Array.isArray(plan.allowed_filter_presets) ? plan.allowed_filter_presets : ['clean','gramophone'],
    allowed_vinyl_presets: Array.isArray(plan.allowed_vinyl_presets) ? plan.allowed_vinyl_presets : ['all'],
    allowed_bg_music_ids: Array.isArray(plan.allowed_bg_music_ids) ? plan.allowed_bg_music_ids : ['none'],
    allowed_vinyl_styles: Array.isArray(plan.allowed_vinyl_styles) ? plan.allowed_vinyl_styles : ['classic_red'],
    can_adjust_crackle: !!plan.can_adjust_crackle,
    can_download: plan.can_download ?? true,
    can_use_private_visibility: plan.can_use_private_visibility ?? false,
    can_use_advanced_mixer: plan.can_use_advanced_mixer ?? false,
    is_active: !!plan.is_active,
    display_order: plan.display_order != null ? Math.floor(plan.display_order) : 0,
    created_at: plan.created_at || new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };

  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { data, error } = await supabase.from('pricing_plans').upsert(sanitized).select().single();
    if (error) throw new Error(`Pricing plan could not be saved: ${error.message}`);
    return data as PricingPlan;
  }

  const store = readLocalStore();
  const idx = store.pricingPlans.findIndex((p) => p.id === plan.id);
  if (idx >= 0) {
    store.pricingPlans[idx] = sanitized;
  } else {
    store.pricingPlans.push(sanitized);
  }
  writeLocalStore(store);
  return sanitized;
}

export async function deletePricingPlan(id: string): Promise<void> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    // Prevent deletion if referenced
    const { data: userEnts } = await supabase.from('user_entitlements').select('id').eq('plan_id', id).limit(1);
    if (userEnts && userEnts.length > 0) throw new Error('Cannot delete plan currently referenced by entitlements. Archive it instead.');
    const { data: recEnts } = await supabase.from('recording_entitlements').select('id').eq('plan_id', id).limit(1);
    if (recEnts && recEnts.length > 0) throw new Error('Cannot delete plan currently referenced by recording purchases. Archive it instead.');
    const { error } = await supabase.from('pricing_plans').delete().eq('id', id);
    if (error) throw new Error(error.message);
    return;
  }
  const store = readLocalStore();
  store.pricingPlans = store.pricingPlans.filter(p => p.id !== id);
  writeLocalStore(store);
}


// 3. AUDIO ASSETS
export async function getAudioAssets(): Promise<AudioAsset[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data, error } = await supabase.from('audio_assets').select('*').order('created_at', { ascending: false });
      if (error) throw error;
      // An empty production table is still authoritative. Falling through to
      // bundled demo rows made an upload appear to vanish between requests.
      return (data || []) as AudioAsset[];
    } catch (err) {
      console.warn('Supabase audio_assets error:', err);
      // In production, if Supabase is configured but table missing, fallback to local with warning
      // to prevent hard crash on homepage. Only throw if explicitly required.
      if (process.env.NODE_ENV === 'production' && process.env.STRICT_DB === 'true') {
        throw new Error('The audio asset database is unavailable. Check the Supabase configuration and schema.');
      }
    }
  }

  const store = readLocalStore();
  return store.audioAssets;
}

export async function getAudioAssetById(id: string): Promise<AudioAsset | null> {
  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { data, error } = await supabase.from('audio_assets').select('*').eq('id', id).maybeSingle();
    if (error) throw new Error(`Audio asset could not be loaded: ${error.message}`);
    return (data as AudioAsset | null) || null;
  }

  const store = readLocalStore();
  return store.audioAssets.find((asset) => asset.id === id) || null;
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

export async function savePurchase(input: {
  stripe_session_id: string;
  stripe_event_id?: string | null;
  user_id?: string | null;
  customer_email?: string | null;
  plan_id?: string | null;
  status: 'pending' | 'paid' | 'failed' | 'refunded';
  amount_cents?: number | null;
  currency?: string | null;
  metadata?: Record<string, unknown>;
}): Promise<void> {
  if (!isSupabaseServerConfigured()) {
    if (!localFallbackAllowed()) throw new Error('Supabase is required for payment entitlements in production.');
    patchLocalStore('purchases', (rows) => ({ ...(rows || {}), [input.stripe_session_id]: input }));
    return;
  }
  const supabase = getServiceSupabase();
  const { error } = await supabase.from('purchases').upsert({
    ...input,
    updated_at: new Date().toISOString(),
  }, { onConflict: 'stripe_session_id' });
  if (error) throw new Error(`Purchase could not be saved: ${error.message}`);
}

export async function getPurchaseBySession(sessionId: string): Promise<{ status: string; plan_id?: string | null } | null> {
  if (!isSupabaseServerConfigured()) {
    if (!localFallbackAllowed()) throw new Error('Supabase is required for payment verification in production.');
    const rows = (readLocalStore() as any).purchases || {};
    return rows[sessionId] || null;
  }
  const supabase = getServiceSupabase();
  const { data } = await supabase.from('purchases').select('status, plan_id').eq('stripe_session_id', sessionId).maybeSingle();
  return data || null;
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

// 7. ADMIN AUDIT LOGS
export async function createAuditLog(input: {
  action: string;
  target_user_id?: string | null;
  target_plan_id?: string | null;
  target_recording_id?: string | null;
  before_data?: any;
  after_data?: any;
  metadata?: any;
  admin_session_id?: string | null;
  admin_user_id?: string | null;
}) {
  if (!isSupabaseServerConfigured()) {
    patchLocalStore('adminAuditLogs', (logs: any[] = []) => [...logs, { ...input, id: crypto.randomUUID(), created_at: new Date().toISOString() }]);
    return;
  }
  const supabase = getServiceSupabase();
  await supabase.from('admin_audit_logs').insert({
    action: input.action,
    target_user_id: input.target_user_id || null,
    target_plan_id: input.target_plan_id || null,
    target_recording_id: input.target_recording_id || null,
    before_data: input.before_data || null,
    after_data: input.after_data || null,
    metadata: input.metadata || {},
    admin_session_id: input.admin_session_id || null,
    admin_user_id: input.admin_user_id || null,
  });
}

export async function getAuditLogs(limit = 100) {
  if (!isSupabaseServerConfigured()) {
    const store = readLocalStore() as any;
    return (store.adminAuditLogs || []).slice(0, limit);
  }
  const supabase = getServiceSupabase();
  const { data } = await supabase.from('admin_audit_logs').select('*').order('created_at', { ascending: false }).limit(limit);
  return data || [];
}

