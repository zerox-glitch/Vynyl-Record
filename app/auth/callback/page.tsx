import { createServerClient } from '@supabase/ssr';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import CallbackClient from './CallbackClient';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function getSafeNext(nextParam: string | null, origin: string): string {
  if (!nextParam) return '/library';
  try {
    if (!nextParam.startsWith('/')) return '/library';
    if (nextParam.startsWith('//')) return '/library';
    if (nextParam.includes('..')) return '/library';
    const url = new URL(nextParam, origin);
    if (url.origin !== origin) return '/library';
    return nextParam;
  } catch {
    return '/library';
  }
}

export default async function AuthCallbackPage({
  searchParams,
}: {
  searchParams: { code?: string; next?: string; error?: string; error_description?: string };
}) {
  const code = searchParams.code;
  const nextParam = searchParams.next;
  const errorDesc = searchParams.error_description || searchParams.error;

  const origin = process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:3000';
  const safeNext = getSafeNext(nextParam || null, origin);

  if (errorDesc) {
    redirect(`/login?error=${encodeURIComponent(errorDesc)}&next=${encodeURIComponent(safeNext)}`);
  }

  if (code) {
    const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
    const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
    if (supabaseUrl && supabaseAnonKey) {
      const cookieStore = cookies();
      const supabase = createServerClient(supabaseUrl, supabaseAnonKey, {
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
      const { error } = await supabase.auth.exchangeCodeForSession(code);
      if (error) {
        redirect(`/login?error=${encodeURIComponent(error.message)}&next=${encodeURIComponent(safeNext)}`);
      }
    }
    redirect(safeNext);
  }

  // No code - render client handler for hash fragment (magic link, recovery)
  return <CallbackClient next={safeNext} />;
}
