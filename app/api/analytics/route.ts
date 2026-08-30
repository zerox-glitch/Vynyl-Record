import { NextRequest, NextResponse } from 'next/server';
import { recordEvent, FunnelEvent } from '@/lib/analytics/events';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const ALLOWED: FunnelEvent[] = [
  'landing_view','signup','occasion_selected','recording_started','recording_completed',
  'upload_started','upload_completed','processing_started','processing_completed',
  'processing_failed','share_view','share_copied','download_requested','gift_purchased',
];

/** Client funnel beacon. Input is allowlisted and metadata-only. */
export async function POST(req: NextRequest) {
  const body = await req.json().catch(() => ({}));
  const eventType = body?.eventType as FunnelEvent;
  if (!ALLOWED.includes(eventType)) return NextResponse.json({ error: 'Invalid event.' }, { status: 400 });
  const metadata = body?.metadata && typeof body.metadata === 'object' ? body.metadata : {};
  // Never accept audio, transcript, email, or arbitrary unbounded payloads.
  const safeMetadata = Object.fromEntries(Object.entries(metadata).slice(0, 12).map(([key, value]) => [
    key.slice(0, 50), typeof value === 'string' ? value.slice(0, 120) : typeof value === 'number' || typeof value === 'boolean' ? value : null,
  ]));
  await recordEvent({
    eventType,
    recordingId: typeof body.recordingId === 'string' ? body.recordingId.slice(0, 100) : null,
    metadata: safeMetadata,
    ip: req.headers.get('x-forwarded-for')?.split(',')[0] || null,
    userAgent: req.headers.get('user-agent'),
  });
  return NextResponse.json({ success: true });
}
