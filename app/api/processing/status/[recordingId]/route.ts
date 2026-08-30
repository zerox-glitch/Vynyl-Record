import { NextRequest, NextResponse } from 'next/server';
import { getRecordingByIdForStatus } from '@/lib/db';
import { getRecordingStatus } from '@/lib/processing/queue';
import { getCustomerUser } from '@/lib/supabase/auth';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Lightweight polling endpoint for the studio: no audio bytes, no URLs. */
export async function GET(
  _req: NextRequest,
  { params }: { params: { recordingId: string } }
) {
  try {
    const recording = await getRecordingByIdForStatus(params.recordingId);
    if (!recording) return NextResponse.json({ error: 'Recording not found.' }, { status: 404 });
    const user = await getCustomerUser();
    if (recording.user_id && recording.user_id !== user?.id) {
      return NextResponse.json({ error: 'Recording not found.' }, { status: 404 });
    }
    const status = await getRecordingStatus(params.recordingId);
    return NextResponse.json({
      recording: {
        id: recording.id,
        slug: recording.slug,
        state: recording.processing_state ?? 'idle',
        progress: recording.processing_progress ?? 0,
        error: recording.processing_error ?? null,
      },
      jobs: status.jobs,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Status unavailable.' }, { status: 500 });
  }
}
