import { NextRequest, NextResponse } from 'next/server';
import fs from 'node:fs/promises';
import path from 'node:path';
import { tmpAssetDir } from '@/lib/audio/storage';
import { verifyUploadIntent } from '@/lib/storage/upload-intent';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Local/dev fallback for direct-upload parity when R2 env vars are absent. */
export async function PUT(req: NextRequest) {
  const key = req.headers.get('x-upload-key') || '';
  const token = req.headers.get('x-upload-intent') || '';
  const contentType = req.headers.get('content-type') || 'application/octet-stream';
  const data = Buffer.from(await req.arrayBuffer());
  try {
    verifyUploadIntent(token, { key, contentType, size: data.byteLength });
    const filename = path.basename(key);
    const target = path.join(tmpAssetDir(), `upload-${filename}`);
    await fs.writeFile(target, data);
    return NextResponse.json({ success: true, key, url: `/api/records/${path.basename(target)}` });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Upload rejected.' }, { status: 403 });
  }
}
