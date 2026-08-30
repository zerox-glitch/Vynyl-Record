import fs from 'fs';
import path from 'path';
import os from 'os';
import { NextRequest, NextResponse } from 'next/server';

/**
 * Where pressed masters live.
 * ---------------------------------------------------------------------------
 * The previous engine returned the finished MP3 as a base64 `data:` URI and
 * stored ~1.5 MB of text per row. That is what made playback flaky: no Range
 * support (so seeking and `duration` were unreliable), huge payloads through
 * the DB, and Safari refusing to decode some of them. We now write a real file
 * and serve it from a route handler that supports Range requests, keeping the
 * data URI only as a last resort for read-only filesystems.
 */

const PUBLIC_RECORDS_DIR = path.join(process.cwd(), 'public', 'records');
const PUBLIC_AUDIO_DIR = path.join(process.cwd(), 'public', 'audio');
const TMP_RECORDS_DIR = path.join(os.tmpdir(), 'vynyl_records');
const TMP_AUDIO_DIR = path.join(os.tmpdir(), 'vynyl_audio');

/** Keep at most this many generated files per directory (dev disk hygiene). */
const KEEP_RECENT = 80;

export type StoredAudioLocation = 'public' | 'tmp' | 'inline';

export interface StoredAudio {
  /** URL to put in the record (`/api/records/<file>`), or a data: URI. */
  url: string;
  location: StoredAudioLocation;
  bytes: number;
}

const MIME_BY_EXT: Record<string, string> = {
  '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4',
  '.mp4': 'audio/mp4',
  '.aac': 'audio/aac',
  '.weba': 'audio/webm',
  '.webm': 'audio/webm',
  '.ogg': 'audio/ogg',
  '.oga': 'audio/ogg',
  '.opus': 'audio/ogg',
  '.wav': 'audio/wav',
  '.flac': 'audio/flac',
  '.aiff': 'audio/aiff',
};

export function contentTypeFor(filename: string): string {
  return MIME_BY_EXT[path.extname(filename).toLowerCase()] || 'application/octet-stream';
}

export function extensionFor(mime: string | null | undefined, fallback = '.webm'): string {
  const m = String(mime || '').toLowerCase();
  if (m.includes('mpeg') || m.includes('mp3')) return '.mp3';
  if (m.includes('mp4') || m.includes('aac') || m.includes('m4a')) return '.m4a';
  if (m.includes('ogg') || m.includes('opus')) return '.ogg';
  if (m.includes('wav')) return '.wav';
  if (m.includes('webm')) return '.webm';
  return fallback;
}

function ensureDir(dir: string): boolean {
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    return true;
  } catch {
    return false;
  }
}

/**
 * Persist a generated audio file. Prefers `public/records` (survives dev-server
 * restarts and works with the static file server) then falls back to the
 * serverless `/tmp` scratch dir, and finally to an inline data URI.
 */
export function persistRecordAudio(fileName: string, data: Buffer): StoredAudio {
  const safe = path.basename(fileName);

  if (ensureDir(PUBLIC_RECORDS_DIR)) {
    try {
      const target = path.join(PUBLIC_RECORDS_DIR, safe);
      fs.writeFileSync(target, data);
      pruneGeneratedFiles(PUBLIC_RECORDS_DIR);
      return { url: `/api/records/${safe}`, location: 'public', bytes: data.length };
    } catch (err: any) {
      // Read-only filesystem (Vercel) — expected, move on to /tmp.
      console.warn('[AudioStore] public/records not writable, using tmp:', err?.code || err?.message);
    }
  }

  if (ensureDir(TMP_RECORDS_DIR)) {
    try {
      const target = path.join(TMP_RECORDS_DIR, safe);
      fs.writeFileSync(target, data);
      pruneGeneratedFiles(TMP_RECORDS_DIR);
      return { url: `/api/records/${safe}`, location: 'tmp', bytes: data.length };
    } catch (err: any) {
      console.warn('[AudioStore] tmp not writable:', err?.code || err?.message);
    }
  }

  const mime = contentTypeFor(safe);
  return {
    url: `data:${mime};base64,${data.toString('base64')}`,
    location: 'inline',
    bytes: data.length,
  };
}

/**
 * Resolve a generated/uploaded audio file from disk. Used by the `/records/*`
 * and `/audio/*` route handlers so custom assets and pressed masters are found
 * no matter which directory they were written to.
 */
export function resolveRecordFile(filename: string): string | null {
  const safe = path.basename(String(filename || ''));
  if (!safe || safe.includes('\0') || safe.startsWith('.')) return null;

  const candidates = [
    path.join(PUBLIC_RECORDS_DIR, safe),
    path.join(TMP_RECORDS_DIR, safe),
    path.join(PUBLIC_AUDIO_DIR, safe),
    path.join(TMP_AUDIO_DIR, safe),
  ];

  for (const candidate of candidates) {
    try {
      if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
    } catch {}
  }
  return null;
}

/** Directory used to hand uploaded assets to the serving route. */
export function tmpAssetDir(): string {
  return ensureDir(TMP_AUDIO_DIR) ? TMP_AUDIO_DIR : os.tmpdir();
}

/** Best-effort write of a custom asset; returns whether public/ got it. */
export function persistAssetFile(fileName: string, data: Buffer): { publicPath: string | null; tmpPath: string } {
  const safe = path.basename(fileName);
  let publicPath: string | null = null;
  try {
    if (ensureDir(PUBLIC_AUDIO_DIR)) {
      const target = path.join(PUBLIC_AUDIO_DIR, safe);
      fs.writeFileSync(target, data);
      publicPath = target;
    }
  } catch {
    publicPath = null;
  }
  const tmpPath = path.join(tmpAssetDir(), safe);
  try {
    fs.writeFileSync(tmpPath, data);
  } catch (err: any) {
    console.warn('[AudioStore] could not stage asset in tmp:', err?.code || err?.message);
  }
  return { publicPath, tmpPath };
}

/** Delete old generated masters so dev/tmp storage can't grow forever. */
function pruneGeneratedFiles(dir: string) {
  try {
    const entries = fs
      .readdirSync(dir)
      .filter((f) => /^(record_|raw_|custom_)/.test(f))
      .map((f) => {
        const full = path.join(dir, f);
        try {
          return { full, mtime: fs.statSync(full).mtimeMs };
        } catch {
          return null;
        }
      })
      .filter(Boolean) as { full: string; mtime: number }[];

    entries.sort((a, b) => b.mtime - a.mtime);
    const stale = entries.slice(KEEP_RECENT);
    for (const entry of stale) {
      try {
        fs.unlinkSync(entry.full);
      } catch {}
    }
  } catch {}
}

/** Remove the scratch files produced for one request. */
export function cleanupFiles(files: (string | null | undefined)[]) {
  for (const file of files) {
    if (!file) continue;
    try {
      if (fs.existsSync(file)) fs.unlinkSync(file);
    } catch {}
  }
}

/**
 * Stream an audio file with correct MIME + Range support.
 *
 * This lives in a route handler rather than relying on `public/`: Next serves
 * `public/**` itself and that path may answer without `Accept-Ranges`, which
 * turns seeking (and Safari's duration probe) into "the record always starts
 * from the top". Generated audio is therefore addressed as
 * `/api/records/<file>` so we own the response.
 */
export function serveAudioFile(req: NextRequest, filename: string): NextResponse {
  const foundPath = resolveRecordFile(filename);
  if (!foundPath) {
    return new NextResponse('Audio file not found', {
      status: 404,
      headers: { 'Cache-Control': 'no-store' },
    });
  }

  let stat: fs.Stats;
  try {
    stat = fs.statSync(foundPath);
  } catch {
    return new NextResponse('Audio file not readable', { status: 500 });
  }

  const fileSize = stat.size;
  const headers: Record<string, string> = {
    'Content-Type': contentTypeFor(path.basename(foundPath)),
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'public, max-age=31536000, immutable',
  };

  const rangeHeader = req.headers.get('range');
  if (!rangeHeader) {
    const stream = nodeStreamToWeb(fs.createReadStream(foundPath));
    return new NextResponse(stream as any, {
      status: 200,
      headers: { ...headers, 'Content-Length': String(fileSize) },
    });
  }

  const [rawStart, rawEnd] = rangeHeader.replace(/bytes=/, '').split('-');
  const start = parseInt(rawStart, 10);
  const end = rawEnd ? parseInt(rawEnd, 10) : fileSize - 1;

  if (!Number.isFinite(start) || start >= fileSize || (Number.isFinite(end) && end < start)) {
    return new NextResponse(null, {
      status: 416,
      headers: { ...headers, 'Content-Range': `bytes */${fileSize}` },
    });
  }

  const safeEnd = Math.min(Number.isFinite(end) ? end : fileSize - 1, fileSize - 1);
  const chunkSize = safeEnd - start + 1;
  const stream = nodeStreamToWeb(fs.createReadStream(foundPath, { start, end: safeEnd }));

  return new NextResponse(stream as any, {
    status: 206,
    headers: {
      ...headers,
      'Content-Range': `bytes ${start}-${safeEnd}/${fileSize}`,
      'Content-Length': String(chunkSize),
    },
  });
}

function nodeStreamToWeb(source: fs.ReadStream) {
  return new ReadableStream({
    start(controller) {
      source.on('data', (chunk: Buffer) => controller.enqueue(chunk));
      source.on('end', () => controller.close());
      source.on('error', (err) => controller.error(err));
    },
    cancel() {
      source.destroy();
    },
  });
}

/**
 * Fetch any URL (local route handler, data: URI, or R2 presigned URL) and
 * drop it on disk under os.tmpdir() so FFmpeg can stream-read it without
 * pulling the whole file into memory. Returns the local path.
 */
export async function downloadToTmp(url: string, prefix: string): Promise<string> {
  if (!url) throw new Error('downloadToTmp: empty url');
  const dir = path.join(os.tmpdir(), 'vynyl_wf_in');
  try { fs.mkdirSync(dir, { recursive: true }); } catch {}
  const extMatch = url.match(/^\s*data:[^;]+;base64,/) && !url.startsWith('http');
  let ext = '.bin';
  if (url.toLowerCase().includes('.mp3')) ext = '.mp3';
  else if (url.toLowerCase().includes('.m4a')) ext = '.m4a';
  else if (url.toLowerCase().includes('.webm')) ext = '.webm';
  else if (url.toLowerCase().includes('.wav')) ext = '.wav';
  else if (extMatch) ext = '.bin';
  const target = path.join(dir, `${prefix}-${Date.now()}${ext}`);
  if (url.startsWith('data:')) {
    const b64 = url.split(',', 2)[1] || '';
    const buf = Buffer.from(b64, 'base64');
    fs.writeFileSync(target, buf);
    return target;
  }
  if (url.startsWith('/api/records/') || url.startsWith('/audio/')) {
    // Local path — let resolveRecordFile find it.
    const filename = path.basename(url);
    const resolved = resolveRecordFile(filename);
    if (resolved) {
      fs.copyFileSync(resolved, target);
      return target;
    }
  }
  // Otherwise treat as a remote (R2 presign or any pre-signed URL) URL.
  const res = await fetch(url);
  if (!res.ok) throw new Error(`downloadToTmp: ${res.status} ${res.statusText}`);
  const buf = Buffer.from(await res.arrayBuffer());
  fs.writeFileSync(target, buf);
  return target;
}
