import { NextRequest, NextResponse } from 'next/server';
import { getRecordingBySlug } from '@/lib/db';
import { getStorage } from '@/lib/storage/r2';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Short-lived signed playback redirect for R2-backed masters. */
export async function GET(
  req: NextRequest,
  { params }: { params: { slug: string } }
) {
  const recording = await getRecordingBySlug(params.slug, { kind: 'anonymous' });
  if (!recording) return new NextResponse('Not Found', { status: 404 });
  if (recording.processed_storage_key && getStorage().isR2Configured) {
    const signed = await getStorage().signedDownloadUrl(recording.processed_storage_key, 900);
    return NextResponse.redirect(signed.url, 307);
  }
  if (recording.processed_audio_url) return NextResponse.redirect(new URL(recording.processed_audio_url, req.nextUrl.origin), 307);
  return new NextResponse('Audio not ready', { status: 409 });
}
