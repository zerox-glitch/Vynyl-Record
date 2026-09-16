-- 00007: harden RLS policies around customer-owned recordings.
-- The original migration allowed any anon Supabase client to SELECT and INSERT
-- every recording. The application server uses service-role for public reads
-- and writes, so we can safely restrict the anon/authenticated REST surface.

-- Public and unlisted records are readable by anonymous visitors because the
-- share page is intentionally link-addressable. Private is owner-only.
DROP POLICY IF EXISTS "Public recordings read access" ON public.recordings;
DROP POLICY IF EXISTS "Recordings are visible by privacy" ON public.recordings;
CREATE POLICY "Recordings are visible by privacy" ON public.recordings
FOR SELECT USING (
  visibility IN ('public', 'unlisted')
  OR (auth.uid() IS NOT NULL AND user_id = auth.uid())
);

DROP POLICY IF EXISTS "Users can create recordings" ON public.recordings;
DROP POLICY IF EXISTS "Owners can create recordings" ON public.recordings;
CREATE POLICY "Owners can create recordings" ON public.recordings
FOR INSERT WITH CHECK (
  auth.uid() IS NOT NULL AND user_id = auth.uid()
);

DROP POLICY IF EXISTS "Owners can update recordings" ON public.recordings;
CREATE POLICY "Owners can update recordings" ON public.recordings
FOR UPDATE USING (auth.uid() IS NOT NULL AND user_id = auth.uid())
WITH CHECK (auth.uid() IS NOT NULL AND user_id = auth.uid());

DROP POLICY IF EXISTS "Owners can delete recordings" ON public.recordings;
CREATE POLICY "Owners can delete recordings" ON public.recordings
FOR DELETE USING (auth.uid() IS NOT NULL AND user_id = auth.uid());

-- Customers can read/update only their own profile. Admin service-role code
-- remains unaffected by RLS.
DROP POLICY IF EXISTS "Users can view their own profile" ON public.profiles;
CREATE POLICY "Users can view their own profile" ON public.profiles
FOR SELECT USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update their own profile" ON public.profiles;
CREATE POLICY "Users can update their own profile" ON public.profiles
FOR UPDATE USING (auth.uid() = id) WITH CHECK (auth.uid() = id);

-- Jobs, transcripts, and events are never anon-readable. Server-side
-- service-role worker/API code owns these tables.
ALTER TABLE public.processing_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.record_transcripts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.record_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Owners can view processing jobs" ON public.processing_jobs;
CREATE POLICY "Owners can view processing jobs" ON public.processing_jobs
FOR SELECT USING (auth.uid() IS NOT NULL AND user_id = auth.uid());

DROP POLICY IF EXISTS "Owners can view transcripts" ON public.record_transcripts;
CREATE POLICY "Owners can view transcripts" ON public.record_transcripts
FOR SELECT USING (
  auth.uid() IS NOT NULL AND EXISTS (
    SELECT 1 FROM public.recordings r
    WHERE r.id = recording_id AND r.user_id = auth.uid()
  )
);

-- Analytics events are write-only from the browser; reads stay service-role.
DROP POLICY IF EXISTS "No direct analytics reads" ON public.record_events;
