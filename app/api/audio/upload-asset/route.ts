import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { v4 as uuidv4 } from 'uuid';
import { persistAssetFile } from '@/lib/audio/storage';
import { addAudioAsset, getAudioAssets, deleteAudioAsset, updateAudioAsset } from '@/lib/db';
import { AudioAsset, AudioCategory } from '@/types';
import { isAdminRequest, requireAdmin } from '@/lib/admin-auth';

export const runtime = 'nodejs';

export async function GET(req: NextRequest) {
  try {
    const assets = await getAudioAssets();
    const admin = await isAdminRequest(req);
    return NextResponse.json({ assets: admin ? assets : assets.filter((asset) => asset.is_enabled !== false) });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const formData = await req.formData();
    const file = formData.get('file') as File | null;
    const title = (formData.get('title') as string) || 'Custom Ambient Track';
    const category = ((formData.get('category') as string) || 'bg_music') as AudioCategory;
    const isPremiumOnly = formData.get('is_premium_only') === 'true';

    if (!file) {
      return NextResponse.json({ error: 'No audio file provided.' }, { status: 400 });
    }
    const suppliedExtension = path.extname(file.name).toLowerCase();
    const hasAudioExtension = /^\.(mp3|wav|ogg|webm|m4a|mp4|flac|aac)$/.test(suppliedExtension);
    if ((!file.type.startsWith('audio/') && !hasAudioExtension) || file.size === 0 || file.size > 25 * 1024 * 1024) {
      return NextResponse.json({ error: 'Choose a valid audio file no larger than 25MB.' }, { status: 400 });
    }
    if (!['bg_music', 'crackle', 'sound_effect'].includes(category)) {
      return NextResponse.json({ error: 'Invalid audio category.' }, { status: 400 });
    }

    const extensionByType: Record<string, string> = {
      'audio/mpeg': '.mp3',
      'audio/mp3': '.mp3',
      'audio/wav': '.wav',
      'audio/x-wav': '.wav',
      'audio/ogg': '.ogg',
      'audio/webm': '.webm',
      'audio/mp4': '.m4a',
      'audio/x-m4a': '.m4a',
      'audio/flac': '.flac',
    };
    const ext = extensionByType[file.type] || (hasAudioExtension ? suppliedExtension : '.mp3');
    const assetId = uuidv4();
    const fileName = `custom-${assetId.slice(0, 8)}${ext}`;

    const buffer = Buffer.from(await file.arrayBuffer());
    const fileUrl = `/audio/${fileName}`;

    // Stage the bytes where the serving route can find them: public/audio when
    // the filesystem is writable (local dev), plus the serverless tmp dir.
    const { publicPath, tmpPath } = persistAssetFile(fileName, buffer);
    if (!publicPath) {
      console.warn('[AssetUpload] read-only filesystem — serving from', tmpPath);
    }

    if (!publicPath && !tmpPath) {
      return NextResponse.json({ error: 'The server could not store this audio file.' }, { status: 507 });
    }

    const newAsset: AudioAsset = {
      id: assetId,
      title,
      category,
      file_url: fileUrl,
      is_premium_only: isPremiumOnly,
      is_enabled: true,
      default_volume: category === 'bg_music' ? 0.18 : 0.25,
      created_at: new Date().toISOString(),
    };

    await addAudioAsset(newAsset);

    return NextResponse.json({ success: true, asset: newAsset });
  } catch (error: any) {
    console.error('Asset upload error:', error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function PATCH(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const { id, updates } = await req.json();
    if (!id || !updates) return NextResponse.json({ error: 'Asset ID and updates required.' }, { status: 400 });
    const allowedUpdates = {
      ...(typeof updates.title === 'string' ? { title: updates.title } : {}),
      ...(typeof updates.is_enabled === 'boolean' ? { is_enabled: updates.is_enabled } : {}),
      ...(typeof updates.is_premium_only === 'boolean' ? { is_premium_only: updates.is_premium_only } : {}),
      ...(typeof updates.default_volume === 'number' ? { default_volume: Math.max(0, Math.min(1, updates.default_volume)) } : {}),
    };
    const asset = await updateAudioAsset(id, allowedUpdates);
    if (!asset) return NextResponse.json({ error: 'Asset not found.' }, { status: 404 });
    return NextResponse.json({ success: true, asset });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    if (!id) return NextResponse.json({ error: 'Asset ID required' }, { status: 400 });

    const assets = await getAudioAssets();
    const asset = assets.find((item) => item.id === id);
    if (asset) {
      const fileName = path.basename(asset.file_url);
      if (fileName.startsWith('custom-')) {
        const uploadedPaths = [
          path.join(os.tmpdir(), 'vynyl_audio', fileName),
          path.join(process.cwd(), 'public', 'audio', fileName),
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
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
