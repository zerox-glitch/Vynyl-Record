import { NextRequest, NextResponse } from 'next/server';
import { ADMIN_COOKIE_NAME, createAdminToken } from '@/lib/admin-auth';

export async function POST(req: NextRequest) {
  const configuredPassword = process.env.ADMIN_PASSWORD;
  if (!configuredPassword) {
    return NextResponse.json(
      { error: 'Admin access is not configured. Set ADMIN_PASSWORD on the server.' },
      { status: 503 }
    );
  }

  const { password } = await req.json();
  if (typeof password !== 'string' || password !== configuredPassword) {
    return NextResponse.json({ error: 'Invalid administrator password.' }, { status: 401 });
  }

  const response = NextResponse.json({ success: true });
  response.cookies.set(ADMIN_COOKIE_NAME, await createAdminToken(), {
    httpOnly: true,
    sameSite: 'strict',
    secure: process.env.NODE_ENV === 'production',
    maxAge: 8 * 60 * 60,
    path: '/',
  });
  return response;
}

export async function DELETE() {
  const response = NextResponse.json({ success: true });
  response.cookies.set(ADMIN_COOKIE_NAME, '', { httpOnly: true, maxAge: 0, path: '/' });
  return response;
}
