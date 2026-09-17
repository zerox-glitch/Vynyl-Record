import { NextResponse } from 'next/server';
import { getRecordingBySlug } from '@/lib/db';
import { getStorage } from '@/lib/storage/r2';
import { getCustomerUser } from '@/lib/supabase/auth';
import { resolveUserEntitlement } from '@/lib/entitlements';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(
  request: Request,
  { params }: { params: { slug: string } }
) {
  try {
    const user = await getCustomerUser();
    const viewer = user ? { kind: 'user' as const, userId: user.id } : { kind: 'anonymous' as const };
    const recording = await getRecordingBySlug(params.slug, viewer);
    if (!recording) {
      return NextResponse.json({ error: 'Vinyl recording not found.' }, { status: 404 });
    }

    // Enforce download entitlement server-side
    const entitlement = await resolveUserEntitlement(user?.id || 'anonymous', recording.id);
    if (!entitlement.enabledFeatures.canDownload) {
      // For owner, check if recording itself has premium entitlement
      const isOwner = user && recording.user_id === user.id;
      if (!isOwner) {
        return NextResponse.json({ error: 'Download requires premium. Upgrade your plan or purchase this recording.' }, { status: 403 });
      }
      // Owner without download entitlement still needs premium? Free plan can_download false, so block
      if (!entitlement.isPremium && !recording.entitlement_plan_id) {
        return NextResponse.json({ error: 'Download requires premium entitlement for this recording.' }, { status: 403 });
      }
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
