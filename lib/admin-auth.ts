import { NextRequest, NextResponse } from 'next/server';

export const ADMIN_COOKIE_NAME = 'vynyl_admin_session';

function getSecret(): string | null {
  return process.env.ADMIN_SESSION_SECRET || process.env.ADMIN_PASSWORD || null;
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function sign(value: string): Promise<string> {
  const secret = getSecret();
  if (!secret) return '';
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  return bytesToHex(new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value))));
}

export async function createAdminToken(): Promise<string> {
  const expiresAt = (Date.now() + 8 * 60 * 60 * 1000).toString();
  return `${expiresAt}.${await sign(expiresAt)}`;
}

export async function isValidAdminToken(token?: string): Promise<boolean> {
  if (!token || !getSecret()) return false;
  const [expiresAt, signature] = token.split('.');
  if (!expiresAt || !signature || Number(expiresAt) <= Date.now()) return false;
  const expected = await sign(expiresAt);
  if (signature.length !== expected.length) return false;
  let mismatch = 0;
  for (let index = 0; index < signature.length; index++) {
    mismatch |= signature.charCodeAt(index) ^ expected.charCodeAt(index);
  }
  return mismatch === 0;
}

export async function isAdminRequest(req: NextRequest): Promise<boolean> {
  return isValidAdminToken(req.cookies.get(ADMIN_COOKIE_NAME)?.value);
}

export async function requireAdmin(req: NextRequest): Promise<NextResponse | null> {
  if (await isAdminRequest(req)) return null;
  return NextResponse.json({ error: 'Admin authentication required.' }, { status: 401 });
}
