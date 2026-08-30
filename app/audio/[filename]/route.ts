import { NextRequest, NextResponse } from 'next/server';
import { serveAudioFile } from '@/lib/audio/storage';
import { getRecordingForAudioFilename } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Bundled and uploaded assets under `/audio/<file>`. */
export async function GET(
  req: NextRequest,
  { params }: { params: { filename: string } }
) {
  try {
    const recording = await getRecordingForAudioFilename(params.filename);
    if (recording?.visibility === 'private') return new NextResponse('Audio file not found', { status: 404 });
    return serveAudioFile(req, params.filename);
  } catch (error: any) {
    console.error('[AudioRoute] streaming error:', error);
    return new NextResponse('Internal Server Error', { status: 500 });
  }
}
