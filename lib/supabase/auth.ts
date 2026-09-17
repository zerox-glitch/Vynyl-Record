import { createServerClient, type CookieOptions } from '@supabase/ssr';
import { cookies } from 'next/headers';

export function getCustomerServerClient() {
  const cookieStore = cookies();
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
  if (!url || !key) return null;

  return createServerClient(url, key, {
    cookies: {
      get(name: string) { return cookieStore.get(name)?.value; },
      set(name: string, value: string, options: CookieOptions) {
        try { cookieStore.set({ name, value, ...options }); } catch {}
      },
      remove(name: string, options: CookieOptions) {
        try { cookieStore.set({ name, value: '', ...options }); } catch {}
      },
    },
  });
}

export async function getCustomerUser(): Promise<{ id: string; email?: string } | null> {
  const client = getCustomerServerClient();
  if (!client) return null;
  const { data } = await client.auth.getUser();
  return data.user ? { id: data.user.id, email: data.user.email } : null;
}

export async function getCustomerUserWithDetails() {
  const client = getCustomerServerClient();
  if (!client) return null;
  const { data } = await client.auth.getUser();
  if (!data.user) return null;
  return data.user;
}

// Safe redirect validation to prevent open-redirect
export function getSafeNextUrl(nextParam: string | null, origin: string): string {
  if (!nextParam) return '/library';
  try {
    // Only allow relative paths starting with /
    if (!nextParam.startsWith('/')) return '/library';
    // Disallow protocol-relative and double-slash
    if (nextParam.startsWith('//')) return '/library';
    // Disallow paths that attempt to escape
    const url = new URL(nextParam, origin);
    // Ensure same origin
    if (url.origin !== origin) return '/library';
    // Allow only specific safe prefixes
    const allowedPrefixes = ['/', '/library', '/studio', '/account', '/play'];
    // All relative paths starting with / are allowed if they don't contain suspicious patterns
    if (nextParam.includes('..')) return '/library';
    return nextParam;
  } catch {
    return '/library';
  }
}

export function getAuthCallbackRedirectUrl(requestOrigin: string, next?: string | null) {
  const safeNext = getSafeNextUrl(next || null, requestOrigin);
  return `${requestOrigin}/auth/callback?next=${encodeURIComponent(safeNext)}`;
}
