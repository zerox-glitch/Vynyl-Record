import { createHmac, timingSafeEqual } from 'node:crypto';

/**
 * Short-lived signed capability for a single browser -> R2 upload.
 *
 * There is no customer auth in the existing app yet, so the capability is
 * scoped to one generated record id, one object key, one MIME type and one
 * maximum byte count. It prevents key/path tampering and makes the upload
 * intent unforgeable without exposing any R2 credential. Once customer
 * accounts are added, the caller also binds the intent to `user_id`.
 */

interface UploadIntentPayload {
  key: string;
  contentType: string;
  maxBytes: number;
  expiresAt: number;
}

function secret(): string {
  const value = process.env.R2_UPLOAD_SECRET || process.env.ADMIN_SESSION_SECRET || '';
  if (!value) throw new Error('R2_UPLOAD_SECRET or ADMIN_SESSION_SECRET must be configured for upload intents.');
  return value;
}

function encode(value: string): string {
  return Buffer.from(value, 'utf8').toString('base64url');
}
function decode(value: string): string {
  return Buffer.from(value, 'base64url').toString('utf8');
}
function signature(payload: string): string {
  return createHmac('sha256', secret()).update(payload).digest('base64url');
}

export function createUploadIntent(input: {
  key: string;
  contentType: string;
  maxBytes: number;
  ttlSeconds?: number;
}): { token: string; expiresAt: string } {
  const expiresAt = Date.now() + (input.ttlSeconds ?? 10 * 60) * 1000;
  const body = encode(JSON.stringify({
    key: input.key,
    contentType: input.contentType,
    maxBytes: input.maxBytes,
    expiresAt,
  } satisfies UploadIntentPayload));
  return {
    token: `${body}.${signature(body)}`,
    expiresAt: new Date(expiresAt).toISOString(),
  };
}

export function verifyUploadIntent(
  token: string,
  expected: { key: string; contentType: string; size: number }
): UploadIntentPayload {
  const [encoded, provided] = String(token || '').split('.');
  if (!encoded || !provided) throw new Error('Upload intent is invalid.');
  const actual = signature(encoded);
  const a = Buffer.from(actual);
  const b = Buffer.from(provided);
  if (a.length !== b.length || !timingSafeEqual(a, b)) throw new Error('Upload intent signature is invalid.');

  let payload: UploadIntentPayload;
  try {
    payload = JSON.parse(decode(encoded)) as UploadIntentPayload;
  } catch {
    throw new Error('Upload intent payload is invalid.');
  }
  if (!payload.key || payload.key !== expected.key) throw new Error('Upload key does not match the upload intent.');
  if (payload.contentType !== expected.contentType) throw new Error('Upload type does not match the upload intent.');
  if (!Number.isFinite(payload.maxBytes) || expected.size > payload.maxBytes) throw new Error('Upload exceeds its authorized size.');
  if (payload.expiresAt <= Date.now()) throw new Error('Upload intent has expired.');
  return payload;
}
