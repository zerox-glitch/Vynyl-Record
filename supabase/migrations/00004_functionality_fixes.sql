-- Align production data with the current admin and studio functionality.
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS is_premium BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS recording_count INT NOT NULL DEFAULT 0;

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS sender_name TEXT,
  ADD COLUMN IF NOT EXISTS duration_seconds REAL;

UPDATE public.pricing_plans
SET allowed_bg_music_ids = '["none", "a2222222-2222-2222-2222-222222222222"]'::jsonb
WHERE id = '11111111-1111-1111-1111-111111111111';

UPDATE public.pricing_plans
SET allowed_bg_music_ids = '["all"]'::jsonb
WHERE id IN (
  '22222222-2222-2222-2222-222222222222',
  '33333333-3333-3333-3333-333333333333'
);

UPDATE public.audio_assets
SET default_volume = 0.30
WHERE id = 'a2222222-2222-2222-2222-222222222222';

-- Public playback objects are written only by the server-side service-role client.
INSERT INTO storage.buckets (id, name, public)
VALUES
  ('recordings', 'recordings', TRUE),
  ('audio-assets', 'audio-assets', TRUE)
ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public;
