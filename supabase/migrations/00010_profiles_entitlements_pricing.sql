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
