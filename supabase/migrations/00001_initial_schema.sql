-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. PROFILES & USER ROLES
CREATE TABLE IF NOT EXISTS public.profiles (
  id UUID REFERENCES auth.users ON DELETE CASCADE PRIMARY KEY,
  email TEXT NOT NULL,
  full_name TEXT,
  role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
  stripe_customer_id TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. DYNAMIC SITE CONTENT & BRANDING CMS
CREATE TABLE IF NOT EXISTS public.site_settings (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Default CMS Seed Data
INSERT INTO public.site_settings (key, value) VALUES
('hero_copy', '{"headline": "Preserve Your Voice in Digital Wax", "subheadline": "Send a timeless, crackling 3D vinyl message to someone you love. A gift they will listen to forever.", "cta_text": "Record Your Memory Now"}'),
('branding_theme', '{"primary_color": "#d97706", "bg_color": "#0c0a09", "accent_color": "#78350f", "font_heading": "Playfair Display", "enable_grain_overlay": true}'),
('faqs', '[{"q": "How does the recipient listen to my vinyl note?", "a": "They simply open your custom link on any browser. A gorgeous 3D vintage turntable appears, places the needle on the wax, and plays your crackling voice with synced scrolling parchment lyrics."}, {"q": "Can I download the mastered audio?", "a": "Yes! Every vinyl record includes a high-definition 192kbps MP3 download with authentic gramophone warmth and crackle mixed in."}, {"q": "How long do memories stay active?", "a": "Forever. Your digital wax records are permanently preserved on our cloud infrastructure."}]')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;

-- 3. DYNAMIC PRICING PLANS (Admin-Managed)
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

-- Seed Default Pricing Plans
INSERT INTO public.pricing_plans (id, name, price_cents, stripe_price_id, max_duration_seconds, allowed_filter_presets, allowed_bg_music_ids, allowed_vinyl_styles, can_adjust_crackle, is_active)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'Vintage Free', 0, NULL, 60, '["clean", "gramophone"]', '["none", "rain"]', '["classic_red"]', FALSE, TRUE),
  ('22222222-2222-2222-2222-222222222222', 'Gold Master Vinyl', 900, 'price_gold_monthly', 180, '["clean", "gramophone", "radio", "tape"]', '["none", "rain", "accordion", "guitar", "cello"]', '["classic_red", "midnight_blue", "gold_edition", "vintage_emerald", "smoked_obsidian"]', TRUE, TRUE),
  ('33333333-3333-3333-3333-333333333333', 'Heirloom Lifetime', 2900, 'price_heirloom_lifetime', 600, '["clean", "gramophone", "radio", "tape"]', '["none", "rain", "accordion", "guitar", "cello"]', '["classic_red", "midnight_blue", "gold_edition", "vintage_emerald", "smoked_obsidian"]', TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

-- 4. AUDIO ASSET LIBRARY (Music, Effects & Crackle Loops)
CREATE TABLE IF NOT EXISTS public.audio_assets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN ('bg_music', 'crackle', 'sound_effect')),
  file_url TEXT NOT NULL,
  is_premium_only BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Seed Default Audio Assets
INSERT INTO public.audio_assets (id, title, category, file_url, is_premium_only)
VALUES
  ('a1111111-1111-1111-1111-111111111111', 'Vintage 1920s Vinyl Crackle Loop', 'crackle', '/audio/crackle-vintage.mp3', FALSE),
  ('a2222222-2222-2222-2222-222222222222', 'Warm Lo-Fi Rain & Hearth', 'bg_music', '/audio/bg-rain.mp3', FALSE),
  ('a3333333-3333-3333-3333-333333333333', 'Parisian Cafe Accordion', 'bg_music', '/audio/bg-accordion.mp3', TRUE),
  ('a4444444-4444-4444-4444-444444444444', 'Acoustic Fireplace Guitar', 'bg_music', '/audio/bg-guitar.mp3', TRUE),
  ('a5555555-5555-5555-5555-555555555555', 'Cinematic Cello Nocturne', 'bg_music', '/audio/bg-cello.mp3', TRUE),
  ('a6666666-6666-6666-6666-666666666666', 'Needle Drop on Wax', 'sound_effect', '/audio/needle-drop.mp3', FALSE)
ON CONFLICT (id) DO NOTHING;

-- 5. USER VOICE RECORDINGS & TRANSCRIPTS
CREATE TABLE IF NOT EXISTS public.recordings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug TEXT UNIQUE NOT NULL,
  user_id UUID,
  title TEXT DEFAULT 'Untitled Memory',
  recipient_name TEXT,
  processed_audio_url TEXT NOT NULL,
  raw_voice_url TEXT NOT NULL,
  transcript_json JSONB NOT NULL, -- Format: [{ word: string, start: number, end: number }]
  vinyl_style TEXT NOT NULL DEFAULT 'classic_red',
  filter_preset TEXT NOT NULL DEFAULT 'gramophone',
  crackle_intensity NUMERIC DEFAULT 0.15,
  bg_music_id UUID,
  views INT DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6. SYSTEM INTEGRATION KEYS (Stripe Configuration)
CREATE TABLE IF NOT EXISTS public.integration_settings (
  id INT PRIMARY KEY DEFAULT 1,
  stripe_publishable_key TEXT,
  stripe_secret_key TEXT,
  stripe_webhook_secret TEXT,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Enable RLS Protocols
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recordings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.site_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pricing_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audio_assets ENABLE ROW LEVEL SECURITY;

-- RLS Policies
DROP POLICY IF EXISTS "Public recordings read access" ON public.recordings;
CREATE POLICY "Public recordings read access" ON public.recordings FOR SELECT USING (true);

DROP POLICY IF EXISTS "Users can create recordings" ON public.recordings;
CREATE POLICY "Users can create recordings" ON public.recordings FOR INSERT WITH CHECK (true);

DROP POLICY IF EXISTS "Public site settings read access" ON public.site_settings;
CREATE POLICY "Public site settings read access" ON public.site_settings FOR SELECT USING (true);

DROP POLICY IF EXISTS "Public pricing plans read access" ON public.pricing_plans;
CREATE POLICY "Public pricing plans read access" ON public.pricing_plans FOR SELECT USING (true);

DROP POLICY IF EXISTS "Public audio assets read access" ON public.audio_assets;
CREATE POLICY "Public audio assets read access" ON public.audio_assets FOR SELECT USING (true);
