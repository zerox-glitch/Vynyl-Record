-- 00006: production data model for jobs, recipients, analytics, gift flow,
--   custom vinyl metadata, and transcription storage.
--
-- Additive only: every existing column / row stays intact. New tables are
-- only created if they don't exist, and new columns on `recordings` are
-- nullable with sensible defaults so older code that doesn't know about them
-- keeps working.
--
-- Lifetime invariants enforced here:
--   * recordings.processing_state = 'idle' by default. Workers set the
--     state; the studio and dashboard read it.
--   * processing_jobs are fully restart-safe: 'queued' / 'processing' /
--     'completed' / 'failed', with a heartbeat `last_heartbeat_at` so a
--     killed worker doesn't leave records stuck forever.
--   * record_events holds the privacy-conscious analytics funnel; never
--     stores raw audio.

-- =========================================================================
-- recordings: production fields
-- =========================================================================

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS processing_state       TEXT NOT NULL DEFAULT 'idle'
      CHECK (processing_state IN ('idle','queued','processing','completed','failed')),
  ADD COLUMN IF NOT EXISTS processing_progress    INT NOT NULL DEFAULT 0
      CHECK (processing_progress BETWEEN 0 AND 100),
  ADD COLUMN IF NOT EXISTS processing_error        TEXT,
  ADD COLUMN IF NOT EXISTS processing_started_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS processing_completed_at TIMESTAMPTZ;

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS recipient_email        TEXT,
  ADD COLUMN IF NOT EXISTS delivery_mode          TEXT NOT NULL DEFAULT 'link'
      CHECK (delivery_mode IN ('link','email','scheduled')),
  ADD COLUMN IF NOT EXISTS delivery_scheduled_for TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS delivery_sent_at       TIMESTAMPTZ;

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS dedication     TEXT,
  ADD COLUMN IF NOT EXISTS side_a_label   TEXT,
  ADD COLUMN IF NOT EXISTS side_b_label   TEXT,
  ADD COLUMN IF NOT EXISTS cover_image_url TEXT,
  ADD COLUMN IF NOT EXISTS occasion_date  DATE;

ALTER TABLE public.recordings
  ADD COLUMN IF NOT EXISTS original_storage_key    TEXT,  -- users/{uid}/records/{rid}/original/...
  ADD COLUMN IF NOT EXISTS processed_storage_key   TEXT,
  ADD COLUMN IF NOT EXISTS cover_storage_key       TEXT;

-- Visibility was added in 00005; ensure existing rows have a real value.
UPDATE public.recordings
SET visibility = 'unlisted'
WHERE visibility IS NULL;

CREATE INDEX IF NOT EXISTS recordings_processing_state_idx
  ON public.recordings(processing_state);
CREATE INDEX IF NOT EXISTS recordings_user_created_idx
  ON public.recordings(user_id, created_at DESC);

-- =========================================================================
-- processing_jobs: durable job queue rows
-- =========================================================================

CREATE TABLE IF NOT EXISTS public.processing_jobs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recording_id UUID NOT NULL REFERENCES public.recordings(id) ON DELETE CASCADE,
  user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  -- The job type controls which worker/converter runs. Wired today:
  --   'audio_master'   - vintage filter chain + crackle + bg music
  --   'transcription'  - Whisper word-level timestamps
  -- Future hooks documented for: 'qr_render', 'video_render', 'artwork_resize'
  job_type TEXT NOT NULL CHECK (job_type IN (
    'audio_master','transcription','qr_render','video_render','artwork_resize'
  )),
  state TEXT NOT NULL DEFAULT 'queued'
    CHECK (state IN ('queued','processing','completed','failed')),
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  -- Free-form worker hints (preset id, narration effect, etc).
  params JSONB NOT NULL DEFAULT '{}'::jsonb,
  result JSONB,
  error TEXT,
  last_heartbeat_at TIMESTAMPTZ DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  started_at  TIMESTAMPTZ,
  completed_at TIMESTAMPTZ
);

-- Index used by the worker pull: "give me the oldest queued audio_master".
CREATE INDEX IF NOT EXISTS processing_jobs_pending_idx
  ON public.processing_jobs(state, job_type, created_at)
  WHERE state IN ('queued','processing');

CREATE INDEX IF NOT EXISTS processing_jobs_recording_idx
  ON public.processing_jobs(recording_id, created_at DESC);

-- =========================================================================
-- record_events: privacy-conscious analytics
-- =========================================================================

CREATE TABLE IF NOT EXISTS public.record_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Most events belong to a recording, but funnel-start events don't
  -- (e.g. landing-page-view). recording_id is therefore nullable.
  recording_id UUID REFERENCES public.recordings(id) ON DELETE SET NULL,
  user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  -- event_type enumerates the funnel checkpoints the brief calls for:
  --   landing_view, signup, occasion_selected, recording_started,
  --   recording_completed, upload_started, upload_completed,
  --   processing_started, processing_completed, processing_failed,
  --   share_view, share_copied, download_requested, gift_purchased.
  event_type TEXT NOT NULL,
  -- Minimal metadata. Do NOT store audio bytes or full transcripts here.
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  -- IP / UA only for anti-abuse; never for any audio analytics.
  ip_hash TEXT,
  user_agent_hash TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS record_events_type_time_idx
  ON public.record_events(event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS record_events_recording_time_idx
  ON public.record_events(recording_id, created_at DESC)
  WHERE recording_id IS NOT NULL;

-- =========================================================================
-- record_transcripts: durable transcript storage (was previously inlined
-- in recordings.transcript_json). Keep the inline fallback for backward
-- compatibility, but treat this as the source of truth going forward.
-- =========================================================================

CREATE TABLE IF NOT EXISTS public.record_transcripts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recording_id UUID NOT NULL UNIQUE REFERENCES public.recordings(id) ON DELETE CASCADE,
  words JSONB NOT NULL,          -- [{ word, start, end }, ...]
  is_publicly_visible BOOLEAN NOT NULL DEFAULT FALSE,
  provider TEXT,                  -- 'openai-whisper' | null
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS record_transcripts_recording_idx
  ON public.record_transcripts(recording_id);

-- =========================================================================
-- pg_cron: auto-unstick stale processing jobs.
-- We can't always rely on a worker boot. After 10 minutes with no heartbeat
-- a 'processing' row is re-queued. This keeps the studio honest from the
-- inside of Supabase, free tier compatible.
-- =========================================================================

CREATE OR REPLACE FUNCTION public.requeue_stale_jobs(threshold_minutes INT DEFAULT 10)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
  requeued_count INT;
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

-- (Optional pg_cron scheduling; left disabled so it won't surprise prod.)
-- Default: enable at deploy-time via:
--   select cron.schedule('requeue-stale-jobs','*/5 * * * *',$$select public.requeue_stale_jobs(10);$$);
