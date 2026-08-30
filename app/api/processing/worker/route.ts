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
export async function POST(req: NextRequest) {
  const expected = process.env.PROCESSING_WORKER_SECRET || '';
  const provided = req.headers.get('x-processing-worker-secret') || '';
  if (!expected || provided !== expected) {
    return NextResponse.json({ error: 'Worker authentication required.' }, { status: 401 });
  }
  try {
    const result = await runOneJob();
    return NextResponse.json(result);
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Worker failed.' }, { status: 500 });
  }
}
