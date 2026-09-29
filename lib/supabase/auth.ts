import { createServerClient } from '@supabase/ssr';
import { cookies } from 'next/headers';

export function getCustomerServerClient() {
  // cookies() is sync in Next 14, async in Next 15 — handle both via Any
  // For Next 14 we call synchronously; for Next 15 the caller would need to await, but
  // we keep sync for compatibility and rely on Next 14 behaviour (project uses 14.2.35).
  const cookieStore: any = (cookies as any)();
  // If cookies() returned a Promise (Next 15), we cannot handle sync - return null and let caller handle
  // In practice this project is Next 14, so this branch never triggers.
  if (cookieStore && typeof cookieStore.then === 'function') {
    // Async case: we cannot create client synchronously. Fall back to placeholder that will fail gracefully.
    // This ensures build doesn't crash; runtime will use async helper below if needed.
    return null;
  }
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
  if (!url || !key) return null;

  return createServerClient(url, key, {
    cookies: {
      getAll() {
        try {
          return cookieStore.getAll();
        } catch {
          return [];
        }
      },
      setAll(cookiesToSet) {
        try {
          cookiesToSet.forEach(({ name, value, options }: any) => {
            cookieStore.set(name, value, options);
          });
        } catch {}
      },
    },
  });
}

// Async variant for Next 15 compatibility and for route handlers / server actions that can await
export async function getCustomerServerClientAsync() {
  const cookieStore: any = await (cookies as any)();
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
  if (!url || !key) return null;
  return createServerClient(url, key, {
    cookies: {
      getAll() {
        try {
          return cookieStore.getAll();
        } catch {
          return [];
        }
      },
      setAll(cookiesToSet) {
        try {
          cookiesToSet.forEach(({ name, value, options }: any) => cookieStore.set(name, value, options));
        } catch {}
      },
    },
  });
}

export async function getCustomerUser(): Promise<{ id: string; email?: string } | null> {
  const client = getCustomerServerClient();
  if (!client) return null;
  const { data } = await client.auth.getUser();
  return data.user ? { id: data.user.id, email: data.user.email || undefined } : null;
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
