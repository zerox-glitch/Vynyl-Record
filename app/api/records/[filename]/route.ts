import { NextRequest, NextResponse } from 'next/server';
import { serveAudioFile } from '@/lib/audio/storage';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/**
 * Streams a pressed master or its raw take.
 * GET /api/records/<file> — Range aware, so scrubbing works in every browser.
 */
export async function GET(
  req: NextRequest,
  { params }: { params: { filename: string } }
) {
  try {
    return serveAudioFile(req, params.filename);
  } catch (error: any) {
    console.error('[RecordsRoute] streaming error:', error);
    return new NextResponse('Internal Server Error', { status: 500 });
  }
}
