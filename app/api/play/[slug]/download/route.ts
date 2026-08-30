import { NextResponse } from 'next/server';
import { getRecordingBySlug } from '@/lib/db';
import { getStorage } from '@/lib/storage/r2';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(
  request: Request,
  { params }: { params: { slug: string } }
) {
  try {
    const recording = await getRecordingBySlug(params.slug, { kind: 'anonymous' });
    if (!recording) {
      return NextResponse.json({ error: 'Vinyl recording not found.' }, { status: 404 });
    }

    if (recording.processed_storage_key && getStorage().isR2Configured) {
      const signed = await getStorage().signedDownloadUrl(recording.processed_storage_key, 300);
      return NextResponse.redirect(signed.url, 303);
    }

    let audio: Uint8Array;
    if (recording.processed_audio_url.startsWith('data:audio/')) {
      const encoded = recording.processed_audio_url.split(',', 2)[1];
      if (!encoded) throw new Error('Stored audio data is invalid.');
      audio = Buffer.from(encoded, 'base64');
    } else {
      const sourceUrl = recording.processed_audio_url.startsWith('/')
        ? new URL(recording.processed_audio_url, request.url).toString()
        : recording.processed_audio_url;
      const response = await fetch(sourceUrl, { cache: 'no-store' });
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
