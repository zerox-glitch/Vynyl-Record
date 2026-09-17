-- =========================================================================
-- Vynyl Supabase setup
-- Executed verbatim into the Supabase SQL Editor.
-- This file consolidates migrations 00001..00008 and applies only the
-- idempotent subset needed for the current schema and security model.
-- Assumptions:
--   * uuid-ossp extension is enabled (Supabase enables pgcrypto/uuid-ossp).
--   * This script can run against a database where 00001..00008 has already
--     been applied OR against a database where none have been applied.
--   * In both cases it is safe to re-run.
-- My actual production outcomes are documented in SUPABASE_SETUP.md.
-- =========================================================================

-- =========================================================================
-- 0) Extension
-- =========================================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =========================================================================
-- 1) Profiles + User Roles
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.profiles (
  id UUID REFERENCES auth.users ON DELETE CASCADE PRIMARY KEY,
  email TEXT NOT NULL,
  full_name TEXT,
  role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
  stripe_customer_id TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- =========================================================================
-- 2) CMS Site Settings
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.site_settings (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO public.site_settings (key, value) VALUES
('hero_copy', '{"headline": "A voice they can return to.", "subheadline": "Press a few seconds of the people you love into a warm vinyl record. The needle drops, the wax turns, and your words come through the room.", "cta_text": "Press Your Voice"}'),
('branding_theme', '{"primary_color": "#d97706", "bg_color": "#0c0a09", "accent_color": "#78350f", "font_heading": "Playfair Display", "enable_grain_overlay": true}'),
('faqs', '[{"q": "How do they hear it?", "a": "You send them a single link. They open it in any browser. A 3D turntable appears, the needle lands on the wax, and your voice starts playing — no app, no account, nothing to download."}, {"q": "Can they keep it?", "a": "Yes. The recording has a download button beneath the turntable, so they can save the audio forever. The link itself never expires either."}, {"q": "How long should it be?", "a": "A few seconds is plenty. The best ones rarely are. A sentence you can''t quite say out loud is usually exactly enough."}, {"q": "What if I don''t like the sound of my voice?", "a": "You won''t. Nobody does. The vintage warmth takes the edge off, and what comes through is closer to how the people who love you already hear you."}]')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW();

-- =========================================================================
-- 3) Pricing Plans
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.pricing_plans (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  price_cents INT NOT NULL DEFAULT 0,
  stripe_price_id TEXT,
  max_duration_seconds INT NOT NULL DEFAULT 60,
  allowed_filter_presets JSONB NOT NULL DEFAULT '["clean", "gramophone"]',
  allowed_bg_music_ids JSONB NOT NULL DEFAULT '[]',
  allowed_vinyl_styles JSONB NOT NULL DEFAULT '["classic_red"]',
  can_adjust_crackle BOOLEAN DEFAULT FALSE,
  is_active BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO public.pricing_plans (id, name, price_cents, stripe_price_id, max_duration_seconds, allowed_filter_presets, allowed_bg_music_ids, allowed_vinyl_styles, can_adjust_crackle, is_active)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'Vintage Free',       0,    NULL,                   60,  '["clean", "gramophone"]',                                                       '["none", "rain"]',                                       '["classic_red"]',                                  FALSE, TRUE),
  ('22222222-2222-2222-2222-222222222222', 'Gold Master Vinyl',  900,  'price_gold_monthly',   180, '["clean", "gramophone", "radio", "tape"]',                                 '["none", "rain", "accordion", "guitar", "cello"]', '["classic_red", "midnight_blue", "gold_edition", "vintage_emerald", "smoked_obsidian"]', TRUE, TRUE),
  ('33333333-3333-3333-3333-333333333333', 'Heirloom Lifetime',  2900, 'price_heirloom_lifetime', 600, '["clean", "gramophone", "radio", "tape"]',                                 '["none", "rain", "accordion", "guitar", "cello"]', '["classic_red", "midnight_blue", "gold_edition", "vintage_emerald", "smoked_obsidian"]', TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

-- Align allowed_bg_music_ids with the current asset seed list.
UPDATE public.pricing_plans
SET allowed_bg_music_ids = '["none", "a2222222-2222-2222-2222-222222222222"]'::jsonb
WHERE id = '11111111-1111-1111-1111-111111111111';

UPDATE public.pricing_plans
SET allowed_bg_music_ids = '["all"]'::jsonb
WHERE id IN (
  '22222222-2222-2222-2222-222222222222',
  '33333333-3333-3333-3333-333333333333'
);

-- =========================================================================
-- 4) Audio Asset Library
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.audio_assets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN ('bg_music', 'crackle', 'sound_effect')),
  file_url TEXT NOT NULL,
  is_premium_only BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO public.audio_assets (id, title, category, file_url, is_premium_only)
VALUES
  ('a1111111-1111-1111-1111-111111111111', 'Vintage 1920s Vinyl Crackle Loop', 'crackle', '/audio/crackle-vintage.mp3', FALSE),
  ('a2222222-2222-2222-2222-222222222222', 'Warm Lo-Fi Rain & Hearth',         'bg_music', '/audio/bg-rain.mp3',         FALSE),
  ('a3333333-3333-3333-3333-333333333333', 'Parisian Cafe Accordion',           'bg_music', '/audio/bg-accordion.mp3',   TRUE),
  ('a4444444-4444-4444-4444-444444444444', 'Acoustic Fireplace Guitar',         'bg_music', '/audio/bg-guitar.mp3',       TRUE),
  ('a5555555-5555-5555-5555-555555555555', 'Cinematic Cello Nocturne',          'bg_music', '/audio/bg-cello.mp3',        TRUE),
  ('a6666666-6666-6666-6666-666666666666', 'Needle Drop on Wax',                'sound_effect', '/audio/needle-drop.mp3', FALSE)
ON CONFLICT (id) DO NOTHING;

ALTER TABLE public.audio_assets
  ADD COLUMN IF NOT EXISTS is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS default_volume REAL NOT NULL DEFAULT 0.25,
  ADD COLUMN IF NOT EXISTS storage_key TEXT,
  ADD COLUMN IF NOT EXISTS source_content_type TEXT,
  ADD COLUMN IF NOT EXISTS source_size_bytes BIGINT,
  ADD COLUMN IF NOT EXISTS duration_seconds REAL,
  ADD COLUMN IF NOT EXISTS trim_start_seconds REAL NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS trim_end_seconds REAL;

CREATE UNIQUE INDEX IF NOT EXISTS audio_assets_storage_key_unique
  ON public.audio_assets (storage_key)
  WHERE storage_key IS NOT NULL;

DO $$ BEGIN
  ALTER TABLE public.audio_assets
    DROP CONSTRAINT IF EXISTS audio_assets_default_volume_range;
EXCEPTION WHEN OTHERS THEN NULL; END $$;

ALTER TABLE public.audio_assets
  ADD CONSTRAINT audio_assets_default_volume_range
  CHECK (default_volume >= 0 AND default_volume <= 1);

DO $$ BEGIN
  ALTER TABLE public.audio_assets
    DROP CONSTRAINT IF EXISTS audio_assets_source_size_nonnegative,
    DROP CONSTRAINT IF EXISTS audio_assets_duration_nonnegative,
    DROP CONSTRAINT IF EXISTS audio_assets_trim_valid;
EXCEPTION WHEN OTHERS THEN NULL; END $$;

ALTER TABLE public.audio_assets
  ADD CONSTRAINT audio_assets_source_size_nonnegative
    CHECK (source_size_bytes IS NULL OR source_size_bytes > 0),
  ADD CONSTRAINT audio_assets_duration_nonnegative
    CHECK (duration_seconds IS NULL OR duration_seconds > 0),
  ADD CONSTRAINT audio_assets_trim_valid
    CHECK (
      trim_start_seconds >= 0
      AND (trim_end_seconds IS NULL OR trim_end_seconds > trim_start_seconds)
      AND (duration_seconds IS NULL OR trim_start_seconds < duration_seconds)
      AND (duration_seconds IS NULL OR trim_end_seconds IS NULL OR trim_end_seconds <= duration_seconds + 0.05)
    );

UPDATE public.audio_assets
SET default_volume = 0.30
WHERE id = 'a2222222-2222-2222-2222-222222222222';

-- Distinct ride-along surface textures + tape-room ambience.
INSERT INTO public.audio_assets
  (id, title, category, file_url, is_premium_only, is_enabled, default_volume)
VALUES
  ('a7777777-7777-7777-7777-777777777777', 'Soft Shellac Surface', 'crackle', '/audio/crackle-soft-shellac.mp3', FALSE, TRUE, 0.18),
  ('a8888888-8888-8888-8888-888888888888', 'Dusty Attic Scratches', 'crackle', '/audio/crackle-dusty-attic.mp3', TRUE, TRUE, 0.30),
  ('a9999999-9999-9999-9999-999999999999', 'Warm Tape Room Tone',  'bg_music', '/audio/bg-tape-room.mp3', FALSE, TRUE, 0.16)
ON CONFLICT (id) DO UPDATE SET
  title = EXCLUDED.title,
  category = EXCLUDED.category,
  file_url = EXCLUDED.file_url,
  is_premium_only = EXCLUDED.is_premium_only,
  is_enabled = EXCLUDED.is_enabled,
  default_volume = EXCLUDED.default_volume;

UPDATE public.audio_assets
SET title = 'Needle Drop Intro', is_enabled = TRUE, default_volume = 0.60
WHERE id = 'a6666666-6666-6666-6666-666666666666';

-- =========================================================================
-- 5) Recordings
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.recordings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug TEXT UNIQUE NOT NULL,
  user_id UUID,
  title TEXT DEFAULT 'Untitled Memory',
  recipient_name TEXT,
  processed_audio_url TEXT NOT NULL,
  raw_voice_url TEXT NOT NULL,
  transcript_json JSONB NOT NULL,
  vinyl_style TEXT NOT NULL DEFAULT 'classic_red',
  filter_preset TEXT NOT NULL DEFAULT 'gramophone',
  crackle_intensity NUMERIC DEFAULT 0.15,
  bg_music_id UUID,
  views INT DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS sender_name            TEXT,
  ADD COLUMN IF NOT EXISTS duration_seconds      REAL;

ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS is_premium         BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS recording_count     INT NOT NULL DEFAULT 0;

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS visibility              TEXT NOT NULL DEFAULT 'unlisted'
    CHECK (visibility IN ('public','unlisted','private')),
  ADD COLUMN IF NOT EXISTS occasion                TEXT;

UPDATE public.recordings
SET visibility = 'public'
WHERE slug IN (
  'anniversary-eleanor',
  'grandmas-secret-apple-pie',
  'distance-across-the-sea'
);

UPDATE public.recordings
SET visibility = 'unlisted'
WHERE visibility IS NULL;

CREATE INDEX IF NOT EXISTS recordings_visibility_idx ON public.recordings(visibility);
CREATE INDEX IF NOT EXISTS recordings_occasion_idx   ON public.recordings(occasion);

-- Customer auth + gift flow / async fields
ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS processing_state       TEXT NOT NULL DEFAULT 'idle'
    CHECK (processing_state IN ('idle','queued','processing','completed','failed')),
  ADD COLUMN IF NOT EXISTS processing_progress    INT NOT NULL DEFAULT 0
    CHECK (processing_progress BETWEEN 0 AND 100),
  ADD COLUMN IF NOT EXISTS processing_error        TEXT,
  ADD COLUMN IF NOT EXISTS processing_started_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS processing_completed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS recipient_email          TEXT,
  ADD COLUMN IF NOT EXISTS delivery_mode            TEXT NOT NULL DEFAULT 'link'
    CHECK (delivery_mode IN ('link','email','scheduled')),
  ADD COLUMN IF NOT EXISTS delivery_scheduled_for   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS delivery_sent_at         TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS dedication               TEXT,
  ADD COLUMN IF NOT EXISTS side_a_label             TEXT,
  ADD COLUMN IF NOT EXISTS side_b_label             TEXT,
  ADD COLUMN IF NOT EXISTS cover_image_url          TEXT,
  ADD COLUMN IF NOT EXISTS occasion_date            DATE,
  ADD COLUMN IF NOT EXISTS original_storage_key      TEXT,
  ADD COLUMN IF NOT EXISTS processed_storage_key     TEXT,
  ADD COLUMN IF NOT EXISTS cover_storage_key         TEXT;

-- Re-creating the user_id FK constraint idempotently. The original 00001
-- declared the column with no FK so values referenced auth.users(id) only
-- loosely; adding it now (and on every re-run) is safe because the DROP
-- before ADD removes any prior definition.
ALTER TABLE public.recordings
  DROP CONSTRAINT IF EXISTS recordings_user_id_fkey;
ALTER TABLE public.recordings
  ADD CONSTRAINT recordings_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS recordings_processing_state_idx ON public.recordings(processing_state);
CREATE INDEX IF NOT EXISTS recordings_user_created_idx     ON public.recordings(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS recordings_owner_idx            ON public.recordings(user_id, created_at DESC);

-- =========================================================================
-- 6) Integration Settings (single-row Stripe config)
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.integration_settings (
  id INT PRIMARY KEY DEFAULT 1,
  stripe_publishable_key TEXT,
  stripe_secret_key TEXT,
  stripe_webhook_secret TEXT,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- =========================================================================
-- 7) Processing Jobs
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.processing_jobs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recording_id UUID NOT NULL REFERENCES public.recordings(id) ON DELETE CASCADE,
  user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  job_type TEXT NOT NULL CHECK (job_type IN
    ('audio_master','transcription','qr_render','video_render','artwork_resize')),
  state TEXT NOT NULL DEFAULT 'queued'
    CHECK (state IN ('queued','processing','completed','failed')),
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  params JSONB NOT NULL DEFAULT '{}'::jsonb,
  result JSONB,
  error TEXT,
  last_heartbeat_at TIMESTAMPTZ DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  started_at   TIMESTAMPTZ,
  completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS processing_jobs_pending_idx
  ON public.processing_jobs(state, job_type, created_at)
  WHERE state IN ('queued','processing');

CREATE INDEX IF NOT EXISTS processing_jobs_recording_idx
  ON public.processing_jobs(recording_id, created_at DESC);

-- Auto-unblock stale processing rows (free tier applies; pg_cron scheduling
-- is NOT enabled by this script — see SUPABASE_SETUP.md for opt-in).
CREATE OR REPLACE FUNCTION public.requeue_stale_jobs(threshold_minutes INT DEFAULT 10)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE requeued_count INT;
BEGIN
  UPDATE public.processing_jobs
  SET state = 'queued',
      error = COALESCE(error,'') || ' [auto-requeued: last heartbeat exceeded threshold]',
      last_heartbeat_at = NOW()
  WHERE state = 'processing'
    AND last_heartbeat_at < NOW() - (threshold_minutes || ' minutes')::interval;
  GET DIAGNOSTICS requeued_count = ROW_COUNT;
  RETURN requeued_count;
END;
$$;

-- =========================================================================
-- 8) Analytics Events
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.record_events (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recording_id  UUID REFERENCES public.recordings(id) ON DELETE SET NULL,
  user_id       UUID REFERENCES auth.users(id)    ON DELETE SET NULL,
  event_type    TEXT NOT NULL,
  metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
  ip_hash       TEXT,
  user_agent_hash TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS record_events_type_time_idx
  ON public.record_events(event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS record_events_recording_time_idx
  ON public.record_events(recording_id, created_at DESC)
  WHERE recording_id IS NOT NULL;

-- =========================================================================
-- 9) Transcripts
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.record_transcripts (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recording_id         UUID NOT NULL UNIQUE REFERENCES public.recordings(id) ON DELETE CASCADE,
  words                JSONB NOT NULL,
  is_publicly_visible  BOOLEAN NOT NULL DEFAULT FALSE,
  provider             TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS record_transcripts_recording_idx
  ON public.record_transcripts(recording_id);

-- =========================================================================
-- 10) Purchases (Stripe idempotency)
-- =========================================================================
CREATE TABLE IF NOT EXISTS public.purchases (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  stripe_session_id TEXT UNIQUE NOT NULL,
  stripe_event_id   TEXT UNIQUE,
  user_id           UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  customer_email    TEXT,
  plan_id           UUID,
  status            TEXT NOT NULL DEFAULT 'paid'
    CHECK (status IN ('pending','paid','failed','refunded')),
  amount_cents      INT,
  currency          TEXT DEFAULT 'usd',
  metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS purchases_user_idx  ON public.purchases(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS purchases_email_idx ON public.purchases(customer_email, created_at DESC);

-- =========================================================================
-- 11) Storage buckets (NOT USED BY R2, but kept for legacy cleanup code
--     in lib/db.ts:deleteRecording that reads /storage/v1/object/public/...).
--     R2 is the active object store; these buckets are inert.
-- =========================================================================
INSERT INTO storage.buckets (id, name, public)
VALUES ('recordings', 'recordings', TRUE), ('audio-assets', 'audio-assets', TRUE)
ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public;

-- =========================================================================
-- 12) RLS - enable per-table and apply the reviewed policies.
-- =========================================================================
ALTER TABLE public.profiles              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recordings            ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.site_settings         ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pricing_plans         ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audio_assets          ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.processing_jobs        ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.record_transcripts    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.record_events         ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.purchases              ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Public site settings read access"   ON public.site_settings;
  CREATE POLICY "Public site settings read access"   ON public.site_settings   FOR SELECT USING (true);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Public pricing plans read access"   ON public.pricing_plans;
  CREATE POLICY "Public pricing plans read access"   ON public.pricing_plans   FOR SELECT USING (true);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Public audio assets read access"    ON public.audio_assets;
  CREATE POLICY "Public audio assets read access"    ON public.audio_assets    FOR SELECT USING (true);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Users can view their own profile"   ON public.profiles;
  CREATE POLICY "Users can view their own profile"   ON public.profiles  FOR SELECT USING (auth.uid() = id);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Users can update their own profile" ON public.profiles;
  CREATE POLICY "Users can update their own profile" ON public.profiles  FOR UPDATE USING (auth.uid() = id) WITH CHECK (auth.uid() = id);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

-- Recordings share page is link-addressable for public + unlisted; private is
-- owner-only. INSERT/UPDATE/DELETE are owner-only (server uses service-role).
DO $$ BEGIN
  DROP POLICY IF EXISTS "Recordings are visible by privacy" ON public.recordings;
  CREATE POLICY "Recordings are visible by privacy" ON public.recordings
    FOR SELECT USING (
      visibility IN ('public','unlisted')
      OR (auth.uid() IS NOT NULL AND user_id = auth.uid())
    );
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Owners can create recordings" ON public.recordings;
  CREATE POLICY "Owners can create recordings" ON public.recordings
    FOR INSERT WITH CHECK (auth.uid() IS NOT NULL AND user_id = auth.uid());
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Owners can update recordings" ON public.recordings;
  CREATE POLICY "Owners can update recordings" ON public.recordings
    FOR UPDATE USING (auth.uid() IS NOT NULL AND user_id = auth.uid())
    WITH CHECK (auth.uid() IS NOT NULL AND user_id = auth.uid());
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Owners can delete recordings" ON public.recordings;
  CREATE POLICY "Owners can delete recordings" ON public.recordings
    FOR DELETE USING (auth.uid() IS NOT NULL AND user_id = auth.uid());
EXCEPTION WHEN OTHERS THEN NULL; END $$;

-- Jobs, transcripts, events, purchases: anon/auth clients are read-restricted.
DO $$ BEGIN
  DROP POLICY IF EXISTS "Owners can view processing jobs" ON public.processing_jobs;
  CREATE POLICY "Owners can view processing jobs" ON public.processing_jobs
    FOR SELECT USING (auth.uid() IS NOT NULL AND user_id = auth.uid());
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Owners can view transcripts" ON public.record_transcripts;
  CREATE POLICY "Owners can view transcripts" ON public.record_transcripts
    FOR SELECT USING (
      auth.uid() IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.recordings r
        WHERE r.id = recording_id AND r.user_id = auth.uid()
      )
    );
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Owners can view purchases" ON public.purchases;
  CREATE POLICY "Owners can view purchases" ON public.purchases
    FOR SELECT USING (auth.uid() IS NOT NULL AND user_id = auth.uid());
EXCEPTION WHEN OTHERS THEN NULL; END $$;

-- analytics writes are server-side only; leave the table anon-write-blocked.
-- 00010: Production-grade auth, profiles, pricing plans, entitlements, audit logs
-- Additive only, safe to re-run.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA public;

-- =========================================================
-- Profiles enhancements
-- =========================================================
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS avatar_url TEXT,
  ADD COLUMN IF NOT EXISTS account_status TEXT NOT NULL DEFAULT 'active' CHECK (account_status IN ('active','suspended','disabled')),
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

-- Ensure is_premium and recording_count exist (from earlier migrations)
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS is_premium BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS recording_count INT NOT NULL DEFAULT 0;

-- Ensure full_name exists
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS full_name TEXT;

-- Trigger to auto-create profile on auth.users insert
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  INSERT INTO public.profiles (id, email, full_name, avatar_url, role, account_status, stripe_customer_id, is_premium, recording_count, created_at, updated_at)
  VALUES (
    NEW.id,
    COALESCE(NEW.email, ''),
    COALESCE(NEW.raw_user_meta_data->>'full_name', NEW.raw_user_meta_data->>'name', ''),
    COALESCE(NEW.raw_user_meta_data->>'avatar_url', NEW.raw_user_meta_data->>'picture', ''),
    'user',
    'active',
    NULL,
    FALSE,
    0,
    NOW(),
    NOW()
  )
  ON CONFLICT (id) DO UPDATE SET
    email = EXCLUDED.email,
    full_name = COALESCE(EXCLUDED.full_name, public.profiles.full_name),
    avatar_url = COALESCE(EXCLUDED.avatar_url, public.profiles.avatar_url),
    updated_at = NOW();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- =========================================================
-- Pricing plans extended model
-- =========================================================
ALTER TABLE public.pricing_plans
  ADD COLUMN IF NOT EXISTS slug TEXT,
  ADD COLUMN IF NOT EXISTS description TEXT,
  ADD COLUMN IF NOT EXISTS billing_model TEXT NOT NULL DEFAULT 'free' CHECK (billing_model IN ('free','per_recording','monthly','lifetime')),
  ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT 'usd',
  ADD COLUMN IF NOT EXISTS billing_interval TEXT CHECK (billing_interval IN ('month','year','one_time', NULL)),
  ADD COLUMN IF NOT EXISTS stripe_product_id TEXT,
  ADD COLUMN IF NOT EXISTS stripe_price_id TEXT,
  ADD COLUMN IF NOT EXISTS included_recordings INT,
  ADD COLUMN IF NOT EXISTS allowed_vinyl_presets JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS can_download BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS can_use_private_visibility BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS can_use_advanced_mixer BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS display_order INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

-- Backfill slug if null
UPDATE public.pricing_plans SET slug = LOWER(REPLACE(name, ' ', '-')) WHERE slug IS NULL;

-- Ensure unique slug
CREATE UNIQUE INDEX IF NOT EXISTS pricing_plans_slug_unique ON public.pricing_plans (slug);

-- Ensure stripe_product_id index
CREATE INDEX IF NOT EXISTS pricing_plans_stripe_product_idx ON public.pricing_plans (stripe_product_id);
CREATE INDEX IF NOT EXISTS pricing_plans_billing_model_idx ON public.pricing_plans (billing_model);

-- =========================================================
-- Seed four canonical plans (editable, idempotent)
-- =========================================================
-- Free
INSERT INTO public.pricing_plans (id, slug, name, description, billing_model, price_cents, currency, billing_interval, max_duration_seconds, included_recordings, allowed_filter_presets, allowed_vinyl_presets, allowed_bg_music_ids, allowed_vinyl_styles, can_adjust_crackle, can_download, can_use_private_visibility, can_use_advanced_mixer, is_active, display_order)
VALUES (
  '11111111-1111-1111-1111-111111111111',
  'free',
  'Free',
  'A beautiful first record for the words you want to keep. Limited records, 60-second maximum, standard features.',
  'free',
  0,
  'usd',
  NULL,
  60,
  3,
  '["clean","gramophone"]'::jsonb,
  '["warm_vintage","classic"]'::jsonb,
  '["none","a2222222-2222-2222-2222-222222222222"]'::jsonb,
  '["classic_red"]'::jsonb,
  FALSE,
  FALSE,
  FALSE,
  FALSE,
  TRUE,
  1
)
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  billing_model = EXCLUDED.billing_model,
  price_cents = CASE WHEN public.pricing_plans.price_cents = 0 THEN EXCLUDED.price_cents ELSE public.pricing_plans.price_cents END,
  currency = EXCLUDED.currency,
  max_duration_seconds = EXCLUDED.max_duration_seconds,
  included_recordings = COALESCE(public.pricing_plans.included_recordings, EXCLUDED.included_recordings),
  allowed_filter_presets = EXCLUDED.allowed_filter_presets,
  allowed_vinyl_presets = EXCLUDED.allowed_vinyl_presets,
  allowed_bg_music_ids = EXCLUDED.allowed_bg_music_ids,
  allowed_vinyl_styles = EXCLUDED.allowed_vinyl_styles,
  can_adjust_crackle = EXCLUDED.can_adjust_crackle,
  can_download = EXCLUDED.can_download,
  can_use_private_visibility = EXCLUDED.can_use_private_visibility,
  can_use_advanced_mixer = EXCLUDED.can_use_advanced_mixer,
  display_order = EXCLUDED.display_order,
  updated_at = NOW();

-- Premium Record (per recording)
INSERT INTO public.pricing_plans (id, slug, name, description, billing_model, price_cents, currency, billing_interval, max_duration_seconds, included_recordings, allowed_filter_presets, allowed_vinyl_presets, allowed_bg_music_ids, allowed_vinyl_styles, can_adjust_crackle, can_download, can_use_private_visibility, can_use_advanced_mixer, is_active, display_order)
VALUES (
  '22222222-2222-2222-2222-222222222222',
  'premium-record',
  'Premium Record',
  'Unlock every premium feature for one recording. One-time purchase, permanent access to that record.',
  'per_recording',
  900,
  'usd',
  'one_time',
  600,
  1,
  '["clean","gramophone","radio","tape"]'::jsonb,
  '["all"]'::jsonb,
  '["all"]'::jsonb,
  '["classic_red","midnight_blue","gold_edition","vintage_emerald","smoked_obsidian"]'::jsonb,
  TRUE,
  TRUE,
  TRUE,
  TRUE,
  TRUE,
  2
)
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  billing_model = EXCLUDED.billing_model,
  currency = EXCLUDED.currency,
  billing_interval = EXCLUDED.billing_interval,
  max_duration_seconds = EXCLUDED.max_duration_seconds,
  allowed_filter_presets = EXCLUDED.allowed_filter_presets,
  allowed_vinyl_presets = EXCLUDED.allowed_vinyl_presets,
  allowed_bg_music_ids = EXCLUDED.allowed_bg_music_ids,
  allowed_vinyl_styles = EXCLUDED.allowed_vinyl_styles,
  can_adjust_crackle = EXCLUDED.can_adjust_crackle,
  can_download = EXCLUDED.can_download,
  can_use_private_visibility = EXCLUDED.can_use_private_visibility,
  can_use_advanced_mixer = EXCLUDED.can_use_advanced_mixer,
  display_order = EXCLUDED.display_order,
  updated_at = NOW();

-- Collector Monthly
INSERT INTO public.pricing_plans (id, slug, name, description, billing_model, price_cents, currency, billing_interval, max_duration_seconds, included_recordings, allowed_filter_presets, allowed_vinyl_presets, allowed_bg_music_ids, allowed_vinyl_styles, can_adjust_crackle, can_download, can_use_private_visibility, can_use_advanced_mixer, is_active, display_order)
VALUES (
  '33333333-3333-3333-3333-333333333333',
  'collector-monthly',
  'Collector Monthly',
  'Monthly subscription with configurable included records and all premium features while active.',
  'monthly',
  1200,
  'usd',
  'month',
  600,
  20,
  '["clean","gramophone","radio","tape"]'::jsonb,
  '["all"]'::jsonb,
  '["all"]'::jsonb,
  '["classic_red","midnight_blue","gold_edition","vintage_emerald","smoked_obsidian"]'::jsonb,
  TRUE,
  TRUE,
  TRUE,
  TRUE,
  TRUE,
  3
)
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  billing_model = EXCLUDED.billing_model,
  currency = EXCLUDED.currency,
  billing_interval = EXCLUDED.billing_interval,
  max_duration_seconds = EXCLUDED.max_duration_seconds,
  included_recordings = COALESCE(public.pricing_plans.included_recordings, EXCLUDED.included_recordings),
  allowed_filter_presets = EXCLUDED.allowed_filter_presets,
  allowed_vinyl_presets = EXCLUDED.allowed_vinyl_presets,
  allowed_bg_music_ids = EXCLUDED.allowed_bg_music_ids,
  allowed_vinyl_styles = EXCLUDED.allowed_vinyl_styles,
  can_adjust_crackle = EXCLUDED.can_adjust_crackle,
  can_download = EXCLUDED.can_download,
  can_use_private_visibility = EXCLUDED.can_use_private_visibility,
  can_use_advanced_mixer = EXCLUDED.can_use_advanced_mixer,
  display_order = EXCLUDED.display_order,
  updated_at = NOW();

-- Lifetime Archive
INSERT INTO public.pricing_plans (id, slug, name, description, billing_model, price_cents, currency, billing_interval, max_duration_seconds, included_recordings, allowed_filter_presets, allowed_vinyl_presets, allowed_bg_music_ids, allowed_vinyl_styles, can_adjust_crackle, can_download, can_use_private_visibility, can_use_advanced_mixer, is_active, display_order)
VALUES (
  '44444444-4444-4444-4444-444444444444',
  'lifetime-archive',
  'Lifetime Archive',
  'One-time account-wide purchase. All premium features, fair-use limits, never expires unless refunded.',
  'lifetime',
  4900,
  'usd',
  'one_time',
  600,
  500,
  '["clean","gramophone","radio","tape"]'::jsonb,
  '["all"]'::jsonb,
  '["all"]'::jsonb,
  '["classic_red","midnight_blue","gold_edition","vintage_emerald","smoked_obsidian"]'::jsonb,
  TRUE,
  TRUE,
  TRUE,
  TRUE,
  TRUE,
  4
)
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  billing_model = EXCLUDED.billing_model,
  currency = EXCLUDED.currency,
  billing_interval = EXCLUDED.billing_interval,
  max_duration_seconds = EXCLUDED.max_duration_seconds,
  included_recordings = COALESCE(public.pricing_plans.included_recordings, EXCLUDED.included_recordings),
  allowed_filter_presets = EXCLUDED.allowed_filter_presets,
  allowed_vinyl_presets = EXCLUDED.allowed_vinyl_presets,
  allowed_bg_music_ids = EXCLUDED.allowed_bg_music_ids,
  allowed_vinyl_styles = EXCLUDED.allowed_vinyl_styles,
  can_adjust_crackle = EXCLUDED.can_adjust_crackle,
  can_download = EXCLUDED.can_download,
  can_use_private_visibility = EXCLUDED.can_use_private_visibility,
  can_use_advanced_mixer = EXCLUDED.can_use_advanced_mixer,
  display_order = EXCLUDED.display_order,
  updated_at = NOW();

-- =========================================================
-- User entitlements (account-wide)
-- =========================================================
CREATE TABLE IF NOT EXISTS public.user_entitlements (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  plan_id UUID NOT NULL REFERENCES public.pricing_plans(id) ON DELETE RESTRICT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','trialing','past_due','canceled','expired','revoked')),
  source TEXT NOT NULL DEFAULT 'signup' CHECK (source IN ('signup','stripe','admin','lifetime','migration')),
  starts_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ,
  remaining_recordings INT,
  stripe_customer_id TEXT,
  stripe_subscription_id TEXT,
  stripe_checkout_session_id TEXT,
  granted_by_admin UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS user_entitlements_user_idx ON public.user_entitlements (user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS user_entitlements_plan_idx ON public.user_entitlements (plan_id);
CREATE INDEX IF NOT EXISTS user_entitlements_stripe_customer_idx ON public.user_entitlements (stripe_customer_id);
CREATE INDEX IF NOT EXISTS user_entitlements_stripe_sub_idx ON public.user_entitlements (stripe_subscription_id);
CREATE UNIQUE INDEX IF NOT EXISTS user_entitlements_stripe_session_unique ON public.user_entitlements (stripe_checkout_session_id) WHERE stripe_checkout_session_id IS NOT NULL;

-- =========================================================
-- Recording entitlements (per-recording)
-- =========================================================
CREATE TABLE IF NOT EXISTS public.recording_entitlements (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recording_id UUID NOT NULL REFERENCES public.recordings(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  plan_id UUID NOT NULL REFERENCES public.pricing_plans(id) ON DELETE RESTRICT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','expired','revoked','refunded')),
  stripe_checkout_session_id TEXT,
  purchase_id UUID REFERENCES public.purchases(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS recording_entitlements_recording_idx ON public.recording_entitlements (recording_id);
CREATE INDEX IF NOT EXISTS recording_entitlements_user_idx ON public.recording_entitlements (user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS recording_entitlements_recording_user_unique ON public.recording_entitlements (recording_id, user_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS recording_entitlements_stripe_session_idx ON public.recording_entitlements (stripe_checkout_session_id);

-- For backward compatibility, keep purchases table but ensure recording_id column exists via metadata

-- =========================================================
-- Stripe webhook events idempotency
-- =========================================================
CREATE TABLE IF NOT EXISTS public.stripe_webhook_events (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  processed_at TIMESTAMPTZ,
  data JSONB
);

-- =========================================================
-- Admin audit log
-- =========================================================
CREATE TABLE IF NOT EXISTS public.admin_audit_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  action TEXT NOT NULL,
  target_user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  target_plan_id UUID REFERENCES public.pricing_plans(id) ON DELETE SET NULL,
  target_recording_id UUID REFERENCES public.recordings(id) ON DELETE SET NULL,
  before_data JSONB,
  after_data JSONB,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  admin_session_id TEXT,
  admin_user_id TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS admin_audit_logs_target_user_idx ON public.admin_audit_logs (target_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_action_idx ON public.admin_audit_logs (action, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_created_idx ON public.admin_audit_logs (created_at DESC);

-- =========================================================
-- RLS hardening for new tables
-- =========================================================
ALTER TABLE public.user_entitlements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recording_entitlements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.stripe_webhook_events ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Users can view own entitlements" ON public.user_entitlements;
  CREATE POLICY "Users can view own entitlements" ON public.user_entitlements FOR SELECT USING (auth.uid() = user_id);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

DO $$ BEGIN
  DROP POLICY IF EXISTS "Users can view own recording entitlements" ON public.recording_entitlements;
  CREATE POLICY "Users can view own recording entitlements" ON public.recording_entitlements FOR SELECT USING (auth.uid() = user_id);
EXCEPTION WHEN OTHERS THEN NULL; END $$;

-- Admin audit logs are server-side only (service role). No client policies.

-- =========================================================
-- Ensure recordings table has entitlement-related columns for reprocessing
-- =========================================================
ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS entitlement_plan_id UUID REFERENCES public.pricing_plans(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS entitlement_source TEXT CHECK (entitlement_source IN ('free','per_recording','monthly','lifetime','admin', NULL));

-- =========================================================
-- Function to auto-grant free entitlement on profile creation
-- =========================================================
CREATE OR REPLACE FUNCTION public.grant_free_entitlement_on_profile()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE free_plan_id UUID;
BEGIN
  SELECT id INTO free_plan_id FROM public.pricing_plans WHERE slug = 'free' AND is_active = true LIMIT 1;
  IF free_plan_id IS NULL THEN
    SELECT id INTO free_plan_id FROM public.pricing_plans WHERE billing_model = 'free' LIMIT 1;
  END IF;
  IF free_plan_id IS NOT NULL THEN
    INSERT INTO public.user_entitlements (user_id, plan_id, status, source, starts_at, remaining_recordings)
    VALUES (NEW.id, free_plan_id, 'active', 'signup', NOW(), NEW.recording_count)
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_profile_created_grant_free ON public.profiles;
CREATE TRIGGER on_profile_created_grant_free
  AFTER INSERT ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION public.grant_free_entitlement_on_profile();

-- Backfill free entitlements for existing profiles missing them
INSERT INTO public.user_entitlements (user_id, plan_id, status, source, starts_at)
SELECT p.id, pp.id, 'active', 'migration', NOW()
FROM public.profiles p
CROSS JOIN LATERAL (SELECT id FROM public.pricing_plans WHERE slug='free' LIMIT 1) pp
WHERE NOT EXISTS (SELECT 1 FROM public.user_entitlements ue WHERE ue.user_id = p.id)
ON CONFLICT DO NOTHING;
-- appended 00010
