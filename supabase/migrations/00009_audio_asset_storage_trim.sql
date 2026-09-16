-- Durable admin audio assets and non-destructive segment selection.
-- Source bytes live in the existing Cloudflare R2 bucket; this table stores
-- only their object address and the section the mastering worker should use.
ALTER TABLE public.audio_assets
  ADD COLUMN IF NOT EXISTS storage_key TEXT,
  ADD COLUMN IF NOT EXISTS source_content_type TEXT,
  ADD COLUMN IF NOT EXISTS source_size_bytes BIGINT,
  ADD COLUMN IF NOT EXISTS duration_seconds REAL,
  ADD COLUMN IF NOT EXISTS trim_start_seconds REAL NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS trim_end_seconds REAL;

CREATE UNIQUE INDEX IF NOT EXISTS audio_assets_storage_key_unique
  ON public.audio_assets (storage_key)
  WHERE storage_key IS NOT NULL;

ALTER TABLE public.audio_assets
  DROP CONSTRAINT IF EXISTS audio_assets_source_size_nonnegative,
  DROP CONSTRAINT IF EXISTS audio_assets_duration_nonnegative,
  DROP CONSTRAINT IF EXISTS audio_assets_trim_valid;

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
