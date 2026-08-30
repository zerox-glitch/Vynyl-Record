import { createServerClient } from '@supabase/ssr';
import { cookies } from 'next/headers';

/**
 * Customer auth server helper. Admin auth remains separate in lib/admin-auth.
 * This is deliberately tiny so customer sessions are standard Supabase
 * cookies and can be replaced by middleware refresh later without touching
 * record ownership code.
 */
export function getCustomerServerClient() {
  const cookieStore = cookies();
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
  if (!url || !key) return null;

  return createServerClient(url, key, {
    cookies: {
      get(name: string) { return cookieStore.get(name)?.value; },
      set(name: string, value: string, options: any) {
        try { cookieStore.set({ name, value, ...options }); } catch {}
      },
      remove(name: string, options: any) {
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
