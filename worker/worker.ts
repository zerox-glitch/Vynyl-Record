import process from 'node:process';
import { runOneJob } from '../lib/processing/workers';

const pollMs = Math.max(500, Number(process.env.WORKER_POLL_MS || 2500));
const required = [
  'NEXT_PUBLIC_SUPABASE_URL',
  'SUPABASE_SERVICE_ROLE_KEY',
  'R2_ACCOUNT_ID',
  'R2_ACCESS_KEY_ID',
  'R2_SECRET_ACCESS_KEY',
  'R2_BUCKET',
];
const missing = required.filter((name) => !process.env[name]);
if (missing.length > 0) {
  throw new Error(`Persistent worker is missing: ${missing.join(', ')}`);
}

let stopping = false;
process.on('SIGTERM', () => { stopping = true; });
process.on('SIGINT', () => { stopping = true; });

async function loop() {
  console.log(`[worker] persistent audio worker polling every ${pollMs}ms`);
  while (!stopping) {
    try {
      // Claim and execute on this host. FFmpeg never crosses back into the
      // Vercel orchestration route.
      const result = await runOneJob();
      if (result.ran) console.log(`[worker] job ${result.state}`);
    } catch (error) {
      console.error('[worker]', error);
    }
    if (!stopping) await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  console.log('[worker] stopped');
}

void loop();
