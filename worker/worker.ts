import { createClient } from '@supabase/supabase-js';
import process from 'node:process';

const url = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY || '';
const pollMs = Math.max(500, Number(process.env.WORKER_POLL_MS || 2500));
if (!url || !serviceKey) throw new Error('NEXT_PUBLIC_SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required.');
const supabase = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

async function claim() {
  const { data } = await supabase
    .from('processing_jobs')
    .select('*')
    .eq('state', 'queued')
    .eq('job_type', 'audio_master')
    .order('created_at', { ascending: true })
    .limit(1)
    .maybeSingle();
  if (!data) return null;
  const now = new Date().toISOString();
  const { data: updated } = await supabase
    .from('processing_jobs')
    .update({ state: 'processing', attempts: (data.attempts || 0) + 1, started_at: now, last_heartbeat_at: now })
    .eq('id', data.id)
    .eq('state', 'queued')
    .select('*')
    .maybeSingle();
  return updated || null;
}

async function handle(job: any) {
  const appUrl = process.env.WORKER_APP_URL || '';
  const secret = process.env.PROCESSING_WORKER_SECRET || '';
  if (!appUrl || !secret) throw new Error('WORKER_APP_URL and PROCESSING_WORKER_SECRET are required.');
  const res = await fetch(`${appUrl.replace(/\/$/, '')}/api/processing/worker`, {
    method: 'POST', headers: { 'x-processing-worker-secret': secret },
  });
  if (!res.ok) throw new Error(`Worker bridge returned ${res.status}`);
  console.log(`[worker] ${job.id}: ${await res.text()}`);
}

async function loop() {
  console.log(`[worker] polling every ${pollMs}ms`);
  while (true) {
    try {
      const job = await claim();
      if (job) await handle(job);
    } catch (error) {
      console.error('[worker]', error);
    }
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
}
loop();
