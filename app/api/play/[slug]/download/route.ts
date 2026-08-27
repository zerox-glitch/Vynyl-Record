import { NextResponse } from 'next/server';
import { getRecordingBySlug } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(
  _request: Request,
  { params }: { params: { slug: string } }
) {
  try {
    const recording = await getRecordingBySlug(params.slug);
    if (!recording) {
      return NextResponse.json({ error: 'Vinyl recording not found.' }, { status: 404 });
    }

    let audio: Uint8Array;
    if (recording.processed_audio_url.startsWith('data:audio/')) {
      const encoded = recording.processed_audio_url.split(',', 2)[1];
      if (!encoded) throw new Error('Stored audio data is invalid.');
      audio = Buffer.from(encoded, 'base64');
    } else {
      const response = await fetch(recording.processed_audio_url, { cache: 'no-store' });
      if (!response.ok) throw new Error('Stored audio file is unavailable.');
      audio = new Uint8Array(await response.arrayBuffer());
    }

    const safeName = (recording.slug || 'vinyl-voice-note').replace(/[^a-z0-9-_]/gi, '-');
    return new NextResponse(audio, {
      status: 200,
      headers: {
        'Content-Type': 'audio/mpeg',
        'Content-Length': String(audio.byteLength),
        'Content-Disposition': `attachment; filename="${safeName}.mp3"`,
        'Cache-Control': 'private, no-store',
      },
    });
  } catch (error: any) {
    return NextResponse.json(
      { error: error.message || 'Audio download failed.' },
      { status: 500 }
    );
  }
}
