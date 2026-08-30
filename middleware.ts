import { NextRequest, NextResponse } from 'next/server';
import { createServerClient } from '@supabase/ssr';
import { ADMIN_COOKIE_NAME, isValidAdminToken } from '@/lib/admin-auth';

/**
 * Two deliberately separate auth surfaces:
 * - /admin uses the existing signed HMAC cookie.
 * - customer pages use Supabase Auth cookies and are refreshed here.
 */
export async function middleware(req: NextRequest) {
  if (req.nextUrl.pathname.startsWith('/admin')) {
    if (req.nextUrl.pathname === '/admin/login') return NextResponse.next();
    if (await isValidAdminToken(req.cookies.get(ADMIN_COOKIE_NAME)?.value)) {
      return NextResponse.next();
    }
    return NextResponse.redirect(new URL('/admin/login', req.url));
  }

  const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
  const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
  if (!supabaseUrl || !supabaseAnonKey) return NextResponse.next();

  let response = NextResponse.next({ request: { headers: req.headers } });
  const supabase = createServerClient(supabaseUrl, supabaseAnonKey, {
    cookies: {
      getAll() { return req.cookies.getAll(); },
      setAll(cookiesToSet) {
        cookiesToSet.forEach(({ name, value, options }) => req.cookies.set(name, value));
        response = NextResponse.next({ request: { headers: req.headers } });
        cookiesToSet.forEach(({ name, value, options }) => response.cookies.set(name, value, options));
      },
    },
  });
  // getUser() refreshes an expired access token and causes setAll above to
  // attach the refreshed session cookie to the response.
  await supabase.auth.getUser();
  return response;
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
