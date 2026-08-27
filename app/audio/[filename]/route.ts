import { NextRequest, NextResponse } from 'next/server';
import { serveAudioFile } from '@/lib/audio/storage';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Bundled and uploaded assets under `/audio/<file>`. */
export async function GET(
  req: NextRequest,
  { params }: { params: { filename: string } }
) {
  try {
    return serveAudioFile(req, params.filename);
  } catch (error: any) {
    console.error('[AudioRoute] streaming error:', error);
    return new NextResponse('Internal Server Error', { status: 500 });
  }
}
