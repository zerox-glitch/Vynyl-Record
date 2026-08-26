import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { v4 as uuidv4 } from 'uuid';
import { addAudioAsset, getAudioAssets, deleteAudioAsset } from '@/lib/db';
import { AudioAsset, AudioCategory } from '@/types';

export const runtime = 'nodejs';

export async function GET() {
  try {
    const assets = await getAudioAssets();
    return NextResponse.json({ assets });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  try {
    const formData = await req.formData();
    const file = formData.get('file') as File | null;
    const title = (formData.get('title') as string) || 'Custom Ambient Track';
    const category = ((formData.get('category') as string) || 'bg_music') as AudioCategory;
    const isPremiumOnly = formData.get('is_premium_only') === 'true';

    if (!file) {
      return NextResponse.json({ error: 'No audio file provided.' }, { status: 400 });
    }

    const ext = path.extname(file.name) || '.mp3';
    const assetId = uuidv4();
    const fileName = `custom-${assetId.slice(0, 8)}${ext}`;

    const tmpAudioDir = path.join(os.tmpdir(), 'vynyl_audio');
    if (!fs.existsSync(tmpAudioDir)) {
      try {
        fs.mkdirSync(tmpAudioDir, { recursive: true });
      } catch (e) {
        console.warn('[AssetUpload] Warning creating tmp audio directory:', e);
      }
    }

    const buffer = Buffer.from(await file.arrayBuffer());

    // 1. Write to tmp audio directory
    const tmpFilePath = path.join(tmpAudioDir, fileName);
    try {
      await fs.promises.writeFile(tmpFilePath, buffer);
    } catch (e) {
      console.warn('[AssetUpload] Error writing to tmp:', e);
    }

    // 2. Attempt to write to public/audio if writable (local dev)
    const publicAudioDir = path.join(process.cwd(), 'public', 'audio');
    try {
      if (!fs.existsSync(publicAudioDir)) {
        fs.mkdirSync(publicAudioDir, { recursive: true });
      }
      const publicFilePath = path.join(publicAudioDir, fileName);
      await fs.promises.writeFile(publicFilePath, buffer);
    } catch {
      // Ignored in read-only Vercel serverless environment; route handler serves from tmp
    }

    const newAsset: AudioAsset = {
      id: assetId,
      title,
      category,
      file_url: `/audio/${fileName}`,
      is_premium_only: isPremiumOnly,
      created_at: new Date().toISOString(),
    };

    await addAudioAsset(newAsset);

    return NextResponse.json({ success: true, asset: newAsset });
  } catch (error: any) {
    console.error('Asset upload error:', error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    if (!id) return NextResponse.json({ error: 'Asset ID required' }, { status: 400 });

    await deleteAudioAsset(id);
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
