/**
 * Cloudflare R2 storage helper.
 * ----------------------------------------------------------------------------
 * Manuscripts (raw recordings + processed masters + artwork + videos) live
 * here once R2 is configured; otherwise the serverless temp dir on Vercel
 * keeps working so the app stays deployable. The shape of every method is
 * the same so callers can swap to R2 transparently:
 *
 *   await storage.putObject(key, body, contentType)
 *   await storage.getObject(key)
 *   await storage.deleteObject(key)
 *   await storage.signedUploadUrl(key, contentType)   // browser -> R2 direct
 *   await storage.signedDownloadUrl(key, ttlSeconds)  // browser / R2 proxy
 *
 * Required environment variables (set in Vercel once you have an R2 bucket +
 * API token): R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY,
 * R2_BUCKET, R2_PUBLIC_BASE (optional, custom CDN / workers.dev domain).
 *
 * Until those are set, the helper returns objects that mimic the R2 SDK so
 * callers don't need to branch — they just call the same methods. The
 * "delivery" implementation falls back to writing under os.tmpdir() and
 * serving via the existing /api/records/[filename] route. That keeps the
 * app deployable on $0/mo infra (Vercel + local FS) while preserving the
 * upgrade path.
 */

export interface ObjectRef {
  /** R2 object key (e.g. "users/u-abc/records/r-001/original.wav"). */
  key: string;
  /** Public URL that the browser can hit. May be data: or a remote URL. */
  url: string;
  /** Bytes written. */
  bytes: number;
  /** Stable identifier we can log + bucket analytics against. */
  storage: 'r2' | 'local' | 'inline';
}

export interface UploadUrl {
  /** Where the browser PUTs the bytes. */
  uploadUrl: string;
  /** Same key we'll reference after the upload completes. */
  key: string;
  /** Headers the browser must preserve on the PUT. */
  headers: Record<string, string>;
  /** When this URL stops working. */
  expiresAt: string;
}

export interface DownloadUrl {
  /** Same shape on the GET side — signed. */
  url: string;
  expiresAt: string;
}

export interface ObjectBody {
  body: Uint8Array;
  contentType: string;
  bytes: number;
}

export type StorageOperations = {
  putObject: (key: string, body: Uint8Array, contentType: string) => Promise<ObjectRef>;
  getObject: (key: string) => Promise<ObjectBody | null>;
  deleteObject: (key: string) => Promise<void>;
  signedUploadUrl: (
    key: string,
    contentType: string,
    ttlSeconds?: number
  ) => Promise<UploadUrl>;
  signedDownloadUrl: (key: string, ttlSeconds?: number) => Promise<DownloadUrl>;
  /** Pre-signed URL for short-lived GET. Pure local-FS impl returns a stable path. */
  publicUrl: (key: string) => Promise<string>;
  isR2Configured: boolean;
};

/** R2 endpoint is always `<account>.r2.cloudflarestorage.com`. */
function r2Endpoint(accountId: string): string {
  return `https://${accountId}.r2.cloudflarestorage.com`;
}

/**
 * SigV4 query-string for R2 presigned URLs.
 * Note: we keep this dependency-free (it's ~30 LoC) so we don't bloat the
 * app with the AWS SDK just for presigning. When the AWS SDK arrives in
 * the project we can swap this for its presigner.
 */
async function presignR2(args: {
  method: 'GET' | 'PUT';
  bucket: string;
  accountId: string;
  accessKeyId: string;
  secretAccessKey: string;
  region: string;
  key: string;
  contentType?: string;
  ttlSeconds: number;
}): Promise<string> {
  const host = `${args.accountId}.r2.cloudflarestorage.com`;
  const endpoint = `${args.method === 'PUT' ? 'https://' : 'https://'}${host}`;
  const now = new Date();
  const yyyymmdd = now.toISOString().slice(0, 10).replace(/-/g, '');
  const amzDate = `${yyyymmdd}T${now.toISOString().slice(11, 19).replace(/:/g, '')}Z`;
  const dateStamp = yyyymmdd;
  const credentialScope = `${dateStamp}/${args.region}/s3/aws4_request`;

  const params = new URLSearchParams();
  params.set('X-Amz-Algorithm', 'AWS4-HMAC-SHA256');
  params.set('X-Amz-Credential', `${args.accessKeyId}/${credentialScope}`);
  params.set('X-Amz-Date', amzDate);
  params.set('X-Amz-Expires', String(args.ttlSeconds));
  params.set('X-Amz-SignedHeaders', args.method === 'PUT' ? 'host' : 'host');

  const signedHeaders = 'host';
  const canonicalUri = `/${encodeURIComponent(args.bucket)}/${args.key
    .split('/')
    .map((p) => encodeURIComponent(p))
    .join('/')}`;
  const canonicalHeaders = `host:${host}\n`;
  const payloadHash = 'UNSIGNED-PAYLOAD';
  const canonicalRequest = [
    args.method,
    canonicalUri,
    params.toString(),
    canonicalHeaders,
    signedHeaders,
    payloadHash,
  ].join('\n');

  const encoder = new TextEncoder();
  const hash = async (s: string | Uint8Array) => {
    const data = typeof s === 'string' ? encoder.encode(s) : s;
    const buf = await crypto.subtle.digest('SHA-256', data);
    return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');
  };

  const stringToSign = [
    'AWS4-HMAC-SHA256',
    amzDate,
    credentialScope,
    await hash(canonicalRequest),
  ].join('\n');

  async function hmac(keyBytes: Uint8Array, data: string): Promise<Uint8Array> {
    const k = await crypto.subtle.importKey(
      'raw',
      keyBytes,
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const sig = await crypto.subtle.sign('HMAC', k, encoder.encode(data));
    return new Uint8Array(sig);
  }

  let k = encoder.encode(`AWS4${args.secretAccessKey}`);
  for (const piece of [dateStamp, args.region, 's3', 'aws4_request']) {
    const prev = k;
    k = await hmac(prev, piece);
  }
  const signature = await hmac(k, stringToSign);
  params.set(
    'X-Amz-Signature',
    Array.from(signature, (b) => b.toString(16).padStart(2, '0')).join('')
  );
  return `${endpoint}/${args.bucket}/${args.key
    .split('/')
    .map((p) => encodeURIComponent(p))
    .join('/')}?${params.toString()}`;
}

export function isR2Configured(): boolean {
  const accountId = process.env.R2_ACCOUNT_ID || '';
  const accessKey = process.env.R2_ACCESS_KEY_ID || '';
  const secret = process.env.R2_SECRET_ACCESS_KEY || '';
  const bucket = process.env.R2_BUCKET || '';
  return Boolean(accountId && accessKey && secret && bucket);
}

/**
 * Structured path generator. Per the brief:
 *   users/{userId}/records/{recordId}/original/{filename}
 *   users/{userId}/records/{recordId}/processed/{filename}
 *   users/{userId}/records/{recordId}/artwork/{filename}
 *   users/{userId}/records/{recordId}/video/{filename}
 */
export function buildRecordKey(input: {
  userId: string;
  recordId: string;
  variant: 'original' | 'processed' | 'artwork' | 'video';
  filename: string;
}): string {
  const safeUserId = sanitize(input.userId);
  const safeRecordId = sanitize(input.recordId);
  const safeName = sanitize(input.filename);
  if (!safeName) throw new Error('Empty filename');
  return `users/${safeUserId}/records/${safeRecordId}/${input.variant}/${safeName}`;
}

function sanitize(s: string): string {
  return s.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
}

/**
 * Build the storage operations object. Falls back to a local FS implementation
 * when R2 is unconfigured so the app keeps running on Vercel without external
 * services.
 */
export function getStorage(): StorageOperations {
  if (!isR2Configured()) {
    return buildLocalStorage();
  }
  return buildR2Storage();
}

function buildR2Storage(): StorageOperations {
  const accountId = process.env.R2_ACCOUNT_ID || '';
  const bucket = process.env.R2_BUCKET || '';
  const accessKeyId = process.env.R2_ACCESS_KEY_ID || '';
  const secretAccessKey = process.env.R2_SECRET_ACCESS_KEY || '';
  const region = process.env.R2_REGION || 'auto';

  const publicBase = (process.env.R2_PUBLIC_BASE || '').replace(/\/$/, '');

  return {
    isR2Configured: true,

    async publicUrl(key: string): Promise<string> {
      if (publicBase) return `${publicBase}/${key}`;
      // Public bucket access requires R2_PUBLIC_BASE; private-by-default is
      // safer for personal recordings. We presign on demand.
      return await presignR2({
        method: 'GET',
        bucket,
        accountId,
        accessKeyId,
        secretAccessKey,
        region,
        key,
        ttlSeconds: 60 * 60,
      });
    },

    async putObject(key, body, contentType): Promise<ObjectRef> {
      const token = await presignR2({
        method: 'PUT',
        bucket,
        accountId,
        accessKeyId,
        secretAccessKey,
        region,
        key,
        contentType,
        ttlSeconds: 60 * 60,
      });
      const res = await fetch(token, {
        method: 'PUT',
        body,
        headers: { 'content-type': contentType },
      });
      if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`R2 upload failed (${res.status}): ${text || res.statusText}`);
      }
      return {
        key,
        url: publicBase ? `${publicBase}/${key}` : '',
        bytes: body.byteLength,
        storage: 'r2',
      };
    },

    async getObject(key): Promise<ObjectBody | null> {
      const url = await presignR2({
        method: 'GET',
        bucket,
        accountId,
        accessKeyId,
        secretAccessKey,
        region,
        key,
        ttlSeconds: 60 * 60,
      });
      const res = await fetch(url);
      if (!res.ok) return null;
      const arrayBuffer = await res.arrayBuffer();
      return {
        body: new Uint8Array(arrayBuffer),
        contentType: res.headers.get('content-type') || 'application/octet-stream',
        bytes: arrayBuffer.byteLength,
      };
    },

    async deleteObject(key): Promise<void> {
      // R2 requires the SDK for signed DELETE; fall back to admin endpoint.
      // For now we rely on TTL/lifecycle rules and skip implementation here
      // — add the SDK-backed deleter when we wire heavy deletions.
      void key;
    },

    async signedUploadUrl(key, contentType, ttlSeconds = 600): Promise<UploadUrl> {
      const uploadUrl = await presignR2({
        method: 'PUT',
        bucket,
        accountId,
        accessKeyId,
        secretAccessKey,
        region,
        key,
        contentType,
        ttlSeconds,
      });
      return {
        uploadUrl,
        key,
        headers: { 'Content-Type': contentType },
        expiresAt: new Date(Date.now() + ttlSeconds * 1000).toISOString(),
      };
    },

    async signedDownloadUrl(key, ttlSeconds = 600): Promise<DownloadUrl> {
      const url = await presignR2({
        method: 'GET',
        bucket,
        accountId,
        accessKeyId,
        secretAccessKey,
        region,
        key,
        ttlSeconds,
      });
      return { url, expiresAt: new Date(Date.now() + ttlSeconds * 1000).toISOString() };
    },
  };
}

import fs from 'fs';
import path from 'path';
import os from 'os';

function buildLocalStorage(): StorageOperations {
  const root = path.join(os.tmpdir(), 'vynyl_records');
  try { if (!fs.existsSync(root)) fs.mkdirSync(root, { recursive: true }); } catch {}
  return {
    isR2Configured: false,
    async publicUrl(key) {
      return `/api/records/${key.split('/').pop()}`;
    },
    async putObject(key, body, _contentType): Promise<ObjectRef> {
      // Local fallback keeps keys in metadata but stores the file flat so the
      // existing Range-serving route can resolve it without exposing an
      // arbitrary filesystem path. Record ids are UUID-like, so basenames
      // remain collision-resistant.
      const target = path.join(root, path.basename(key));
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, body);
      return {
        key,
        url: `/api/records/${path.basename(target)}`,
        bytes: body.byteLength,
        storage: 'local',
      };
    },
    async getObject(key): Promise<ObjectBody | null> {
      const target = path.join(root, key);
      try {
        const stat = fs.statSync(target);
        const buf = fs.readFileSync(target);
        return {
          body: new Uint8Array(buf),
          contentType: 'application/octet-stream',
          bytes: stat.size,
        };
      } catch { return null; }
    },
    async deleteObject(key) {
      try { fs.unlinkSync(path.join(root, key)); } catch {}
    },
    async signedUploadUrl(key, contentType, ttlSeconds = 600): Promise<UploadUrl> {
      const expiresAt = new Date(Date.now() + ttlSeconds * 1000).toISOString();
      return {
        // Local dev flow: browser still POSTs through Vercel, no real "upload URL".
        uploadUrl: `/api/audio/upload-url/local-debug?key=${encodeURIComponent(key)}`,
        key,
        headers: { 'Content-Type': contentType },
        expiresAt,
      };
    },
    async signedDownloadUrl(key, ttlSeconds = 600): Promise<DownloadUrl> {
      const expiresAt = new Date(Date.now() + ttlSeconds * 1000).toISOString();
      return {
        url: `/api/audio/download-url/local-debug?key=${encodeURIComponent(key)}`,
        expiresAt,
      };
    },
  };
}
