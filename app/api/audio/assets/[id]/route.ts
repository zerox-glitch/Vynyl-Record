import { NextRequest, NextResponse } from 'next/server';
import { getAudioAssetById } from '@/lib/db';
import { isAdminRequest } from '@/lib/admin-auth';
import { getStorage } from '@/lib/storage/r2';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Stable playback address for private R2-backed reusable audio assets. */
export async function GET(
  req: NextRequest,
  { params }: { params: { id: string } },
) {
  try {
    const asset = await getAudioAssetById(params.id);
    if (!asset || (asset.is_enabled === false && !(await isAdminRequest(req)))) {
      return new NextResponse('Audio asset not found', { status: 404 });
    }

    if (asset.storage_key) {
      const signed = await getStorage().signedDownloadUrl(asset.storage_key, 15 * 60);
      return new NextResponse(null, {
        status: 307,
        // Keep local fallback locations relative so preview/proxy hosts are
        // preserved; real R2 signed URLs are already absolute.
        headers: {
          Location: signed.url,
          'Cache-Control': 'private, no-store, max-age=0',
        },
      });
    }

    // Bundled/legacy assets already have stable app-relative or remote URLs.
    if (asset.file_url && asset.file_url !== req.nextUrl.pathname) {
      return new NextResponse(null, {
        status: 307,
        headers: { Location: asset.file_url, 'Cache-Control': 'public, max-age=300' },
      });
    }
    return new NextResponse('Audio asset source is unavailable', { status: 404 });
  } catch (error: any) {
    console.error('[AudioAssetPlayback]', error);
    return new NextResponse('Audio asset unavailable', { status: 500 });
  }
}
