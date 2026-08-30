import { NextRequest, NextResponse } from 'next/server';
import { buildRecordKey, getStorage } from '@/lib/storage/r2';
import { createUploadIntent } from '@/lib/storage/upload-intent';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const MAX_UPLOAD_BYTES = 40 * 1024 * 1024;
const ALLOWED_AUDIO_TYPES = new Set([
  'audio/webm', 'audio/ogg', 'audio/opus', 'audio/mp4', 'audio/m4a',
  'audio/mpeg', 'audio/wav', 'audio/x-wav', 'audio/aac',
]);

function isSafeId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-zA-Z0-9_-]{3,100}$/.test(value);
}

/**
 * Create a direct browser upload URL. The request itself carries metadata
 * only; audio bytes never pass through this Vercel function.
 *
 * Current app has no customer login, so the record id is an unguessable
 * UUID created by the browser. When Supabase customer auth lands, this route
 * should additionally assert the record's user_id matches auth.uid().
 */
export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { recordId, filename, contentType, size } = body || {};

    if (!isSafeId(recordId)) return NextResponse.json({ error: 'A valid record id is required.' }, { status: 400 });
    if (typeof filename !== 'string' || filename.length < 1 || filename.length > 120) {
      return NextResponse.json({ error: 'A valid audio filename is required.' }, { status: 400 });
    }
    if (!ALLOWED_AUDIO_TYPES.has(String(contentType).toLowerCase())) {
      return NextResponse.json({ error: 'This audio type is not supported.' }, { status: 415 });
    }
    if (!Number.isInteger(size) || size < 200 || size > MAX_UPLOAD_BYTES) {
      return NextResponse.json({ error: `Audio must be between 200 bytes and ${MAX_UPLOAD_BYTES / 1024 / 1024} MB.` }, { status: 413 });
    }

    // In the auth-free demo path, use a stable anonymous namespace. This is
    // still safe because the upload token is signed and scoped to one key.
    const userId = 'anonymous';
    const key = buildRecordKey({
      userId,
      recordId,
      variant: 'original',
      filename,
    });
    const storage = getStorage();
    const intent = createUploadIntent({ key, contentType, maxBytes: MAX_UPLOAD_BYTES });

    if (storage.isR2Configured) {
      const upload = await storage.signedUploadUrl(key, contentType, 600);
      return NextResponse.json({
        direct: true,
        ...upload,
        intent: intent.token,
        maxBytes: MAX_UPLOAD_BYTES,
      });
    }

    // Keep a usable local fallback for dev/no-credentials mode. The browser
    // POSTs bytes to /api/audio/upload-url/confirm with the same intent.
    return NextResponse.json({
      direct: false,
      uploadUrl: '/api/audio/upload-url/confirm',
      key,
      intent: intent.token,
      headers: { 'Content-Type': contentType, 'X-Upload-Key': key, 'X-Upload-Intent': intent.token },
      expiresAt: intent.expiresAt,
      maxBytes: MAX_UPLOAD_BYTES,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Could not create an upload URL.' }, { status: 500 });
  }
}
