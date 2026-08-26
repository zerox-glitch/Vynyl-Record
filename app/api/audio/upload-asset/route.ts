import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
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

    const audioDir = path.join(process.cwd(), 'public', 'audio');
    if (!fs.existsSync(audioDir)) {
      fs.mkdirSync(audioDir, { recursive: true });
    }

    const ext = path.extname(file.name) || '.mp3';
    const assetId = uuidv4();
    const fileName = `custom-${assetId.slice(0, 8)}${ext}`;
    const filePath = path.join(audioDir, fileName);

    const buffer = Buffer.from(await file.arrayBuffer());
    await fs.promises.writeFile(filePath, buffer);

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
