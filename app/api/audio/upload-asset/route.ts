import { NextRequest, NextResponse } from 'next/server';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { v4 as uuidv4 } from 'uuid';
import { tmpAssetDir } from '@/lib/audio/storage';
import { addAudioAsset, getAudioAssets, deleteAudioAsset, updateAudioAsset } from '@/lib/db';
import { AudioAsset, AudioCategory } from '@/types';
import { isAdminRequest, requireAdmin } from '@/lib/admin-auth';
import { buildAudioAssetKey, getStorage } from '@/lib/storage/r2';
import { createUploadIntent, verifyUploadIntent } from '@/lib/storage/upload-intent';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
const CATEGORIES = new Set<AudioCategory>(['bg_music', 'crackle', 'sound_effect']);
const EXTENSIONS = /^\.(mp3|wav|ogg|webm|m4a|mp4|flac|aac|opus)$/;
const AUDIO_TYPES = new Set([
  'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/ogg',
  'audio/webm', 'audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/flac',
  'audio/aac', 'audio/opus',
]);

function cleanContentType(input: unknown, filename: string): string {
  const supplied = typeof input === 'string' ? input.toLowerCase().split(';')[0].trim() : '';
  if (AUDIO_TYPES.has(supplied)) return supplied;
  const ext = path.extname(filename).toLowerCase();
  const byExtension: Record<string, string> = {
    '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg',
    '.opus': 'audio/opus', '.webm': 'audio/webm', '.m4a': 'audio/mp4',
    '.mp4': 'audio/mp4', '.flac': 'audio/flac', '.aac': 'audio/aac',
  };
  return byExtension[ext] || '';
}

function safeTitle(input: unknown, fallback: string): string {
  const value = typeof input === 'string' ? input.trim().slice(0, 120) : '';
  return value || fallback.slice(0, 120) || 'Custom Ambient Track';
}

function finiteNumber(input: unknown, fallback = 0): number {
  const value = typeof input === 'number' ? input : Number(input);
  return Number.isFinite(value) ? value : fallback;
}

export async function GET(req: NextRequest) {
  try {
    const assets = await getAudioAssets();
    const admin = await isAdminRequest(req);
    return NextResponse.json(
      { assets: admin ? assets : assets.filter((asset) => asset.is_enabled !== false) },
      { headers: { 'Cache-Control': 'no-store, max-age=0' } },
    );
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Audio assets could not be loaded.' }, { status: 500 });
  }
}

/**
 * Two lightweight metadata operations power direct browser -> R2 uploads:
 * create_upload returns a short-lived PUT URL; complete_upload writes only
 * the metadata row after the browser confirms that PUT succeeded. Audio bytes
 * never pass through a production Vercel function.
 */
export async function POST(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;

  try {
    if (!req.headers.get('content-type')?.includes('application/json')) {
      return NextResponse.json(
        { error: 'Use the current admin uploader. Legacy multipart uploads are no longer persisted on serverless deployments.' },
        { status: 415 },
      );
    }

    const body = await req.json();
    if (body?.action === 'create_upload') {
      const filename = typeof body.filename === 'string' ? path.basename(body.filename).slice(0, 120) : '';
      const size = Math.round(finiteNumber(body.size, 0));
      const contentType = cleanContentType(body.contentType, filename);
      if (!filename || !EXTENSIONS.test(path.extname(filename).toLowerCase()) || !contentType) {
        return NextResponse.json({ error: 'Choose a supported MP3, WAV, OGG, WebM, M4A, FLAC, AAC, or Opus file.' }, { status: 415 });
      }
      if (size < 200 || size > MAX_UPLOAD_BYTES) {
        return NextResponse.json({ error: `Audio must be between 200 bytes and ${MAX_UPLOAD_BYTES / 1024 / 1024} MB.` }, { status: 413 });
      }

      const storage = getStorage();
      if (!storage.isR2Configured && process.env.NODE_ENV === 'production') {
        return NextResponse.json({
          error: 'Persistent audio storage is not configured. Add the Cloudflare R2 variables in Vercel before uploading assets.',
          code: 'R2_NOT_CONFIGURED',
        }, { status: 503 });
      }

      const assetId = uuidv4();
      const key = buildAudioAssetKey({ assetId, filename });
      const intent = createUploadIntent({ key, contentType, maxBytes: size, ttlSeconds: 10 * 60 });
      if (storage.isR2Configured) {
        const upload = await storage.signedUploadUrl(key, contentType, 10 * 60);
        return NextResponse.json({
          direct: true,
          assetId,
          ...upload,
          intent: intent.token,
          maxBytes: size,
        });
      }

      return NextResponse.json({
        direct: false,
        assetId,
        uploadUrl: '/api/audio/upload-asset',
        key,
        headers: {
          'Content-Type': contentType,
          'X-Upload-Key': key,
          'X-Upload-Intent': intent.token,
        },
        intent: intent.token,
        expiresAt: intent.expiresAt,
        maxBytes: size,
      });
    }

    if (body?.action === 'complete_upload') {
      const assetId = typeof body.assetId === 'string' ? body.assetId : '';
      const key = typeof body.key === 'string' ? body.key : '';
      const intent = typeof body.intent === 'string' ? body.intent : '';
      const filename = typeof body.filename === 'string' ? path.basename(body.filename) : 'audio';
      const size = Math.round(finiteNumber(body.size, 0));
      const contentType = cleanContentType(body.contentType, filename);
      verifyUploadIntent(intent, { key, contentType, size });
      if (!assetId || !key.startsWith(`audio-assets/${assetId}/source/`)) {
        return NextResponse.json({ error: 'The uploaded object does not match this asset.' }, { status: 403 });
      }

      const category = body.category as AudioCategory;
      if (!CATEGORIES.has(category)) {
        return NextResponse.json({ error: 'Invalid audio category.' }, { status: 400 });
      }

      const duration = Math.max(0, finiteNumber(body.durationSeconds, 0));
      const requestedStart = Math.max(0, finiteNumber(body.trimStartSeconds, 0));
      const requestedEnd = Math.max(0, finiteNumber(body.trimEndSeconds, duration));
      const trimStart = duration > 0 ? Math.min(requestedStart, Math.max(0, duration - 0.1)) : 0;
      const trimEnd = duration > 0 ? Math.min(duration, Math.max(trimStart + 0.1, requestedEnd || duration)) : null;
      if (duration > 0 && trimEnd !== null && trimEnd - trimStart < 0.1) {
        return NextResponse.json({ error: 'The selected audio section must be at least 0.1 seconds long.' }, { status: 400 });
      }

      const asset: AudioAsset = {
        id: assetId,
        title: safeTitle(body.title, filename.replace(/\.[^/.]+$/, '')),
        category,
        file_url: `/api/audio/assets/${assetId}`,
        storage_key: key,
        source_content_type: contentType,
        source_size_bytes: size,
        duration_seconds: duration || null,
        trim_start_seconds: Number(trimStart.toFixed(3)),
        trim_end_seconds: trimEnd === null ? null : Number(trimEnd.toFixed(3)),
        is_premium_only: body.isPremiumOnly === true,
        is_enabled: true,
        default_volume: category === 'bg_music' ? 0.18 : 0.25,
        created_at: new Date().toISOString(),
      };

      const stored = await addAudioAsset(asset);
      return NextResponse.json({ success: true, asset: stored });
    }

    return NextResponse.json({ error: 'Unknown audio upload action.' }, { status: 400 });
  } catch (error: any) {
    console.error('[AssetUpload]', error);
    return NextResponse.json({ error: error?.message || 'Audio asset upload failed.' }, { status: 500 });
  }
}

/** Local-development parity for the direct R2 PUT. Never used in production. */
export async function PUT(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const storage = getStorage();
    if (process.env.NODE_ENV === 'production' || storage.isR2Configured) {
      return NextResponse.json({ error: 'Direct uploads must use the signed R2 URL.' }, { status: 400 });
    }
    const key = req.headers.get('x-upload-key') || '';
    const intent = req.headers.get('x-upload-intent') || '';
    const contentType = req.headers.get('content-type') || 'application/octet-stream';
    const bytes = new Uint8Array(await req.arrayBuffer());
    verifyUploadIntent(intent, { key, contentType, size: bytes.byteLength });
    await storage.putObject(key, bytes, contentType);
    return NextResponse.json({ success: true, key });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Upload rejected.' }, { status: 403 });
  }
}

export async function PATCH(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const { id, updates } = await req.json();
    if (!id || !updates) return NextResponse.json({ error: 'Asset ID and updates required.' }, { status: 400 });
    const allowedUpdates = {
      ...(typeof updates.title === 'string' ? { title: updates.title.trim().slice(0, 120) } : {}),
      ...(typeof updates.is_enabled === 'boolean' ? { is_enabled: updates.is_enabled } : {}),
      ...(typeof updates.is_premium_only === 'boolean' ? { is_premium_only: updates.is_premium_only } : {}),
      ...(typeof updates.default_volume === 'number' ? { default_volume: Math.max(0, Math.min(1, updates.default_volume)) } : {}),
    };
    const asset = await updateAudioAsset(id, allowedUpdates);
    if (!asset) return NextResponse.json({ error: 'Asset not found.' }, { status: 404 });
    return NextResponse.json({ success: true, asset });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Audio asset update failed.' }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const id = new URL(req.url).searchParams.get('id');
    if (!id) return NextResponse.json({ error: 'Asset ID required' }, { status: 400 });

    const assets = await getAudioAssets();
    const asset = assets.find((item) => item.id === id);
    if (asset?.storage_key) {
      await getStorage().deleteObject(asset.storage_key);
    } else if (asset) {
      const fileName = path.basename(asset.file_url);
      if (fileName.startsWith('custom-')) {
        const uploadedPaths = [
          path.join(os.tmpdir(), 'vynyl_audio', fileName),
          path.join(process.cwd(), 'public', 'audio', fileName),
          path.join(tmpAssetDir(), fileName),
        ];
        await Promise.all(uploadedPaths.map(async (filePath) => {
          if (!fs.existsSync(filePath)) return;
          try { await fs.promises.unlink(filePath); } catch {}
        }));
      }
    }
    await deleteAudioAsset(id);
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Audio asset deletion failed.' }, { status: 500 });
  }
}
