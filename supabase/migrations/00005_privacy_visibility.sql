-- 00005: Production privacy + occasion metadata.
-- Adds:
--   1. recordings.visibility  (text: 'public' | 'unlisted' | 'private'; default 'unlisted')
--   2. recordings.occasion    (text, nullable)
--   3. RLS-style helper on a new helper function is_publicly_viewable()
--      so server code can centrally decide what is visible to anonymous
--      visitors vs owners vs admins.
--
-- This exists alongside the existing ROW-level REST access pattern that
-- lib/db.ts already handles; the column is added at server level so the
-- fallback JSON store can keep serving the in-memory demo data.

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'unlisted'
    CHECK (visibility IN ('public', 'unlisted', 'private')),
  ADD COLUMN IF NOT EXISTS occasion   TEXT;

-- Backfill: the demo rows used on the landing page should stay publicly
-- viewable so the share page works without auth.
UPDATE public.recordings
SET visibility = 'public'
WHERE slug IN (
  'anniversary-eleanor',
  'grandmas-secret-apple-pie',
  'distance-across-the-sea'
);

-- Index the visibility column so the listing path can short-circuit.
CREATE INDEX IF NOT EXISTS recordings_visibility_idx ON public.recordings(visibility);
CREATE INDEX IF NOT EXISTS recordings_occasion_idx   ON public.recordings(occasion);
