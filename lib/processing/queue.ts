/**
 * processing/queue.ts
 * ----------------------------------------------------------------------------
 * Durable job queue for audio / transcription / QR / video / artwork jobs.
 *
 * When Supabase is configured, all reads/writes go to `public.processing_jobs`.
 * Otherwise it falls back to the same in-process JSON-on-tmp store the rest
 * of `lib/db.ts` uses, so the app keeps working in $0/mo dev mode and demos.
 *
 * The shape of every public method here is stable: a Cloudflare Worker,
 * a Railway container, or a Vercel cron-driven dog can call `claimNext()`
 * and `completeJob()` / `failJob()` to advance jobs without knowing what's
 * actually running FFmpeg or Whisper today.
 */
import { ProcessingJob } from '@/types';
import { isSupabaseServerConfigured, getServiceSupabase } from '@/lib/supabase/server';
import { patchLocalStore } from '@/lib/db';

function jobsStore(): { processingJobs: ProcessingJob[] } {
  let cached: ProcessingJob[] | undefined;
  patchLocalStore('processingJobs', (current) => {
    cached = current ?? [];
    return cached;
  });
  return { processingJobs: cached! };
}

/** CREATE — enqueue a new job. Server-side callers only; never expose this to the browser. */
export async function enqueueJob(input: {
  recording_id: string;
  user_id?: string | null;
  job_type: ProcessingJob['job_type'];
  params?: Record<string, unknown>;
  max_attempts?: number;
}): Promise<ProcessingJob> {
  const job: ProcessingJob = {
    id: crypto.randomUUID(),
    recording_id: input.recording_id,
    user_id: input.user_id ?? null,
    job_type: input.job_type,
    state: 'queued',
    attempts: 0,
    max_attempts: input.max_attempts ?? 3,
    params: input.params ?? {},
    last_heartbeat_at: new Date().toISOString(),
    created_at: new Date().toISOString(),
  };

  if (isSupabaseServerConfigured()) {
    const supabase = getServiceSupabase();
    const { error } = await supabase.from('processing_jobs').insert(job);
    if (error) throw new Error(`Could not enqueue job: ${error.message}`);
    return job;
  }
  const s = jobsStore();
  s.processingJobs.unshift(job);
  patchLocalStore('processingJobs', () => s.processingJobs);
  return job;
}

/** READ — get the latest job for a recording + job_type (e.g. the dashboard polling). */
export async function getJobForRecording(
  recordingId: string,
  jobType: ProcessingJob['job_type']
): Promise<ProcessingJob | null> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase
        .from('processing_jobs')
        .select('*')
        .eq('recording_id', recordingId)
        .eq('job_type', jobType)
        .order('created_at', { ascending: false })
        .limit(1)
        .single();
      if (data) return data as ProcessingJob;
    } catch { /* fallthrough */ }
  }
  const s = jobsStore();
  return (
    s.processingJobs.find((j) => j.recording_id === recordingId && j.job_type === jobType) ?? null
  );
}

/** CLAIM — worker-callable: atomically marks a queued job as 'processing'. */
export async function claimNext(input: {
  jobType: ProcessingJob['job_type'];
  /** Visibility filter; used so admin-only / user-scoped workers don't steal each other's jobs. */
  userId?: string | null;
}): Promise<ProcessingJob | null> {
  const now = new Date().toISOString();
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      let query = supabase
        .from('processing_jobs')
        .select('*')
        .eq('state', 'queued')
        .eq('job_type', input.jobType)
        .order('created_at', { ascending: true })
        .limit(1);
      if (input.userId !== undefined) query = query.eq('user_id', input.userId);
      const { data } = await query.maybeSingle();
      if (!data) return null;
      const claimed = { ...(data as ProcessingJob), state: 'processing' as const, started_at: now, attempts: (data as any).attempts + 1, last_heartbeat_at: now };
      const { error } = await supabase
        .from('processing_jobs')
        .update({
          state: claimed.state,
          started_at: claimed.started_at,
          attempts: claimed.attempts,
          last_heartbeat_at: claimed.last_heartbeat_at,
        })
        .eq('id', claimed.id);
      if (error) return null;
      return claimed;
    } catch { return null; }
  }
  const s = jobsStore();
  const job = s.processingJobs.find(
    (j) => j.state === 'queued' && j.job_type === input.jobType
      && (input.userId === undefined || j.user_id === input.userId)
  );
  if (!job) return null;
  job.state = 'processing';
  job.started_at = now;
  job.attempts += 1;
  job.last_heartbeat_at = now;
  patchLocalStore('processingJobs', () => s.processingJobs);
  return job;
}

/** HEARTBEAT — keep the row from being auto-requeued. */
export async function heartbeat(jobId: string, patch?: Partial<ProcessingJob>): Promise<void> {
  const now = new Date().toISOString();
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('processing_jobs').update({ ...patch, last_heartbeat_at: now }).eq('id', jobId);
      return;
    } catch { return; }
  }
  const s = jobsStore();
  const job = s.processingJobs.find((j) => j.id === jobId);
  if (job) {
    Object.assign(job, patch, { last_heartbeat_at: now });
    patchLocalStore('processingJobs', () => s.processingJobs);
  }
}

/** COMPLETE — mark a job done and persist whatever the worker returned. */
export async function completeJob(jobId: string, result: ProcessingJob['result']): Promise<void> {
  const now = new Date().toISOString();
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('processing_jobs').update({
        state: 'completed',
        completed_at: now,
        result,
        last_heartbeat_at: now,
      }).eq('id', jobId);
      return;
    } catch { return; }
  }
  const s = jobsStore();
  const job = s.processingJobs.find((j) => j.id === jobId);
  if (job) {
    job.state = 'completed';
    job.completed_at = now;
    job.result = result;
    job.last_heartbeat_at = now;
    patchLocalStore('processingJobs', () => s.processingJobs);
  }
}

/** FAIL — record the error. Worker re-runs unless attempts >= max_attempts. */
export async function failJob(
  jobId: string,
  error: string,
  opts: { requeue?: boolean } = {}
): Promise<void> {
  const now = new Date().toISOString();
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('processing_jobs').update({
        state: opts.requeue ? 'queued' : 'failed',
        error,
        last_heartbeat_at: now,
      }).eq('id', jobId);
      return;
    } catch { return; }
  }
  const s = jobsStore();
  const job = s.processingJobs.find((j) => j.id === jobId);
  if (job) {
    job.state = opts.requeue ? 'queued' : 'failed';
    job.error = error;
    job.last_heartbeat_at = now;
    patchLocalStore('processingJobs', () => s.processingJobs);
  }
}

/** Bulk re-stamp: stuck 'processing' rows older than threshold get re-queued. */
export async function requeueStaleJobs(thresholdMinutes = 10): Promise<number> {
  if (!isSupabaseServerConfigured()) {
    const s = jobsStore();
    const cutoff = Date.now() - thresholdMinutes * 60 * 1000;
    let count = 0;
    for (const j of s.processingJobs) {
      if (j.state === 'processing' && Date.parse(j.last_heartbeat_at) < cutoff) {
        j.state = 'queued';
        j.error = (j.error || '') + ' [requeue: heartbeat expired]';
        j.last_heartbeat_at = new Date().toISOString();
        count += 1;
      }
    }
    if (count) patchLocalStore('processingJobs', () => s.processingJobs);
    return count;
  }
  const supabase = getServiceSupabase();
  const cutoff = new Date(Date.now() - thresholdMinutes * 60 * 1000).toISOString();
  const { data } = await supabase
    .from('processing_jobs')
    .update({ state: 'queued', last_heartbeat_at: new Date().toISOString() })
    .eq('state', 'processing')
    .lt('last_heartbeat_at', cutoff)
    .select('id');
  return data?.length ?? 0;
}

/** Aggregate view of "what's happening to this recording right now" for the studio dashboard poll. */
export async function getRecordingStatus(
  recordingId: string
): Promise<{
  recording_id: string;
  any_processing: boolean;
  any_failed: boolean;
  jobs: ProcessingJob[];
}> {
  const { getJobForRecording } = await import('./queue');
  void getJobForRecording;
  // Pull all jobs for this recording (up to 8 most recent).
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase
        .from('processing_jobs')
        .select('*')
        .eq('recording_id', recordingId)
        .order('created_at', { ascending: false })
        .limit(8);
      const jobs = (data || []) as ProcessingJob[];
      return {
        recording_id: recordingId,
        any_processing: jobs.some((j) => j.state === 'queued' || j.state === 'processing'),
        any_failed: jobs.some((j) => j.state === 'failed'),
        jobs,
      };
    } catch { /* fallthrough */ }
  }
  const s = jobsStore();
  const jobs = s.processingJobs
    .filter((j) => j.recording_id === recordingId)
    .slice(0, 8);
  return {
    recording_id: recordingId,
    any_processing: jobs.some((j) => j.state === 'queued' || j.state === 'processing'),
    any_failed: jobs.some((j) => j.state === 'failed'),
    jobs,
  };
}
