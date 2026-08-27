-- Administrative availability and mix defaults for every audio asset type.
ALTER TABLE public.audio_assets
  ADD COLUMN IF NOT EXISTS is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS default_volume REAL NOT NULL DEFAULT 0.25;

UPDATE public.audio_assets
SET default_volume = 0.18
WHERE category = 'bg_music' AND default_volume = 0.25;

ALTER TABLE public.audio_assets
  DROP CONSTRAINT IF EXISTS audio_assets_default_volume_range;

ALTER TABLE public.audio_assets
  ADD CONSTRAINT audio_assets_default_volume_range
  CHECK (default_volume >= 0 AND default_volume <= 1);
