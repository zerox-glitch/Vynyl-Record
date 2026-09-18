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
 *
 * Cookie handling uses getAll/setAll (not deprecated get/set/remove) for robust
 * chunked cookie support. Supabase SSR splits large JWTs into multiple chunks
 * (e.g. sb-xxx-auth-token.0, .1) and PKCE verifiers into separate cookies.
 * Using getAll/setAll ensures all chunks are read/written atomically.
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
  // If Supabase not configured (e.g. preview without env), allow request through
  // without crashing — lib/db.ts will use local fallback.
  if (!supabaseUrl || !supabaseAnonKey) return NextResponse.next();

  // Use request as base for response so cookies, headers, etc. are preserved.
  // Supabase docs recommend: let response = NextResponse.next({ request })
  let response = NextResponse.next({ request: req });
  const supabase = createServerClient(supabaseUrl, supabaseAnonKey, {
    cookies: {
      getAll() {
        return req.cookies.getAll();
      },
      setAll(cookiesToSet) {
        // First, update the request cookies so the Supabase client sees the new values
        // within this same request (needed for PKCE verifier flow).
        cookiesToSet.forEach(({ name, value }) => req.cookies.set(name, value));
        // Recreate response with updated request so Set-Cookie headers are correct
        response = NextResponse.next({ request: req });
        // Then set cookies on the response (browser will receive them)
        cookiesToSet.forEach(({ name, value, options }) => response.cookies.set(name, value, options));
      },
    },
  });

  // Refresh session if needed. getUser validates with Supabase Auth server.
  // This also handles PKCE code verifier cleanup and token refresh rotation.
  // We use getUser (not getSession) for security - it validates JWT with server.
  const { data: { user } } = await supabase.auth.getUser();

  // Protected customer routes - redirect to login with safe next
  const protectedPaths = ['/account'];
  const isProtected = protectedPaths.some(p => req.nextUrl.pathname === p || req.nextUrl.pathname.startsWith(p + '/'));

  if (isProtected && !user) {
    const origin = req.nextUrl.origin;
    const next = getSafeNext(req.nextUrl.pathname + req.nextUrl.search, origin);
    const loginUrl = new URL('/login', req.url);
    loginUrl.searchParams.set('next', next);
    loginUrl.searchParams.set('expired', '1');
    // Return redirect response - note: we must use a new redirect response, not the `response` above,
    // because the Supabase client may have set cookies that need to be preserved.
    // However for unauthenticated users there are no new cookies, so simple redirect is fine.
    // If we had refreshed cookies, they are already in `response`, but redirect discards them.
    // In practice, unauthenticated users have no refresh, so no loss.
    // For safety, we copy any cookies from `response` to the redirect if needed.
    const redirectResponse = NextResponse.redirect(loginUrl);
    // Preserve any Set-Cookie from the Supabase refresh (rare for unauthenticated)
    response.cookies.getAll().forEach(cookie => {
      redirectResponse.cookies.set(cookie.name, cookie.value, cookie as any);
    });
    return redirectResponse;
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
      const redirectResponse = NextResponse.redirect(url);
      // Preserve cookies
      response.cookies.getAll().forEach(cookie => {
        redirectResponse.cookies.set(cookie.name, cookie.value, cookie as any);
      });
      return redirectResponse;
    }
  }

  return response;
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|mp3|mp4|webm|m4a)$).*)'],
};
