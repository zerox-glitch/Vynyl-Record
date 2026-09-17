import { NextRequest, NextResponse } from 'next/server';
import { createServerClient } from '@supabase/ssr';
import { ADMIN_COOKIE_NAME, isValidAdminToken } from '@/lib/admin-auth';

function getSafeNext(nextParam: string | null, origin: string): string {
  if (!nextParam) return '/library';
  if (!nextParam.startsWith('/')) return '/library';
  if (nextParam.startsWith('//')) return '/library';
  if (nextParam.includes('..')) return '/library';
  try {
    const url = new URL(nextParam, origin);
    if (url.origin !== origin) return '/library';
    return nextParam;
  } catch {
    return '/library';
  }
}

/**
 * Two deliberately separate auth surfaces:
 * - /admin uses the existing signed HMAC cookie.
 * - customer pages use Supabase Auth cookies and are refreshed here.
 * Also handles safe next-page redirect and session-expired for protected routes.
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
        cookiesToSet.forEach(({ name, value }) => req.cookies.set(name, value));
        response = NextResponse.next({ request: { headers: req.headers } });
        cookiesToSet.forEach(({ name, value, options }) => response.cookies.set(name, value, options));
      },
    },
  });

  const { data: { user } } = await supabase.auth.getUser();

  // Protected customer routes
  const protectedPaths = ['/account'];
  const isProtected = protectedPaths.some(p => req.nextUrl.pathname === p || req.nextUrl.pathname.startsWith(p + '/'));

  if (isProtected && !user) {
    const origin = req.nextUrl.origin;
    const next = getSafeNext(req.nextUrl.pathname + req.nextUrl.search, origin);
    const loginUrl = new URL('/login', req.url);
    loginUrl.searchParams.set('next', next);
    loginUrl.searchParams.set('expired', '1');
    return NextResponse.redirect(loginUrl);
  }

  // Validate next param on login/signup to prevent open redirect - if next is unsafe, rewrite to safe
  if ((req.nextUrl.pathname === '/login' || req.nextUrl.pathname === '/signup') && req.nextUrl.searchParams.has('next')) {
    const rawNext = req.nextUrl.searchParams.get('next');
    const safe = getSafeNext(rawNext, req.nextUrl.origin);
    if (rawNext !== safe) {
      const url = new URL(req.nextUrl.pathname, req.url);
      url.searchParams.set('next', safe);
      // Preserve other params
      req.nextUrl.searchParams.forEach((v, k) => {
        if (k !== 'next') url.searchParams.set(k, v);
      });
      return NextResponse.redirect(url);
    }
  }

  return response;
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|mp3|mp4|webm|m4a)$).*)'],
};
