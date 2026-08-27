import { NextRequest, NextResponse } from 'next/server';
import { ADMIN_COOKIE_NAME, isValidAdminToken } from '@/lib/admin-auth';

export async function middleware(req: NextRequest) {
  if (await isValidAdminToken(req.cookies.get(ADMIN_COOKIE_NAME)?.value)) {
    return NextResponse.next();
  }

  const loginUrl = new URL('/admin/login', req.url);
  return NextResponse.redirect(loginUrl);
}

export const config = {
  matcher: ['/admin', '/admin/((?!login).*)'],
};
