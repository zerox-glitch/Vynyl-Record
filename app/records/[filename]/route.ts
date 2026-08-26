import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import os from 'os';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(
  req: NextRequest,
  { params }: { params: { filename: string } }
) {
  try {
    const filename = params.filename;
    // Sanitize filename against path traversal
    const safeFilename = path.basename(filename);

    const candidatePaths = [
      path.join(process.cwd(), 'public', 'records', safeFilename),
      path.join(os.tmpdir(), 'vynyl_records', safeFilename),
      path.join(os.tmpdir(), safeFilename),
    ];

    let foundPath: string | null = null;
    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        foundPath = p;
        break;
      }
    }

    if (!foundPath) {
      return new NextResponse('Audio record file not found', { status: 404 });
    }

    const stat = fs.statSync(foundPath);
    const fileSize = stat.size;
    const rangeHeader = req.headers.get('range');

    const contentType = safeFilename.endsWith('.webm')
      ? 'audio/webm'
      : safeFilename.endsWith('.wav')
      ? 'audio/wav'
      : safeFilename.endsWith('.ogg')
      ? 'audio/ogg'
      : 'audio/mpeg';

    if (rangeHeader) {
      // Range: bytes=0-1024
      const parts = rangeHeader.replace(/bytes=/, '').split('-');
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;

      if (start >= fileSize || end >= fileSize) {
        return new NextResponse(null, {
          status: 416,
          headers: {
            'Content-Range': `bytes */${fileSize}`,
          },
        });
      }

      const chunkSize = end - start + 1;
      const fileStream = fs.createReadStream(foundPath, { start, end });

      // Convert Node.js readable stream to Web ReadableStream
      const stream = new ReadableStream({
        start(controller) {
          fileStream.on('data', (chunk) => controller.enqueue(chunk));
          fileStream.on('end', () => controller.close());
          fileStream.on('error', (err) => controller.error(err));
        },
        cancel() {
          fileStream.destroy();
        },
      });

      return new NextResponse(stream as any, {
        status: 206,
        headers: {
          'Content-Range': `bytes ${start}-${end}/${fileSize}`,
          'Accept-Ranges': 'bytes',
          'Content-Length': String(chunkSize),
          'Content-Type': contentType,
          'Cache-Control': 'public, max-age=31536000, immutable',
        },
      });
    }

    // Full audio file response
    const fileStream = fs.createReadStream(foundPath);
    const stream = new ReadableStream({
      start(controller) {
        fileStream.on('data', (chunk) => controller.enqueue(chunk));
        fileStream.on('end', () => controller.close());
        fileStream.on('error', (err) => controller.error(err));
      },
      cancel() {
        fileStream.destroy();
      },
    });

    return new NextResponse(stream as any, {
      status: 200,
      headers: {
        'Content-Length': String(fileSize),
        'Content-Type': contentType,
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'public, max-age=31536000, immutable',
      },
    });
  } catch (error: any) {
    console.error('[AudioRoute] Error streaming record:', error);
    return new NextResponse('Internal Server Error', { status: 500 });
  }
}
