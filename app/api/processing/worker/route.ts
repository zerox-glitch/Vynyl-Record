import { NextRequest, NextResponse } from 'next/server';
import { runOneJob } from '@/lib/processing/workers';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/**
 * Drain exactly one job. This endpoint is intentionally internal:
 * PROCESSING_WORKER_SECRET is required, so an anonymous visitor cannot
 * spend server CPU claiming jobs. A Railway worker can call this endpoint
 * until it is moved to a direct queue consumer; Vercel Cron can call it for
 * small/dev installations.
 */
async function drain(req: NextRequest) {
  const expected = process.env.PROCESSING_WORKER_SECRET || process.env.CRON_SECRET || '';
  const headerSecret = req.headers.get('x-processing-worker-secret') || '';
  const bearer = req.headers.get('authorization')?.replace(/^Bearer\s+/i, '') || '';
  if (!expected || (headerSecret !== expected && bearer !== expected)) {
    return NextResponse.json({ error: 'Worker authentication required.' }, { status: 401 });
  }
  try {
    const result = await runOneJob();
    return NextResponse.json(result);
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Worker failed.' }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  return drain(req);
}

// Vercel Cron invokes GET with Authorization: Bearer $CRON_SECRET. A
// dedicated Railway worker can use POST + x-processing-worker-secret.
export async function GET(req: NextRequest) {
  return drain(req);
}
