import { createServerClient } from '@supabase/ssr';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import CallbackClient from './CallbackClient';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';

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
  searchParams: { code?: string; next?: string; error?: string; error_description?: string; error_code?: string };
}) {
  // Next 15 makes searchParams a Promise; handle both sync and async
  const resolvedParams: any =
    searchParams && typeof (searchParams as any).then === 'function'
      ? await (searchParams as any)
      : searchParams;

  const code = resolvedParams?.code as string | undefined;
  const nextParam = resolvedParams?.next as string | undefined;
  const errorDesc = (resolvedParams?.error_description || resolvedParams?.error) as string | undefined;
  const errorCode = resolvedParams?.error_code as string | undefined;

  const origin = process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:3000';
  const safeNext = getSafeNext(nextParam || null, origin);

  if (errorDesc || errorCode) {
    const msg = errorDesc || errorCode || 'Authentication failed';
    redirect(`/login?error=${encodeURIComponent(msg)}&next=${encodeURIComponent(safeNext)}`);
  }

  if (code) {
    const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
    const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
    if (!supabaseUrl || !supabaseAnonKey) {
      // Supabase not configured - cannot exchange, redirect with error guidance
      redirect(
        `/login?error=${encodeURIComponent(
          'Auth not configured: Missing NEXT_PUBLIC_SUPABASE_URL / NEXT_PUBLIC_SUPABASE_ANON_KEY in Vercel env. Add them in Vercel Dashboard → Settings → Environment Variables and redeploy.'
        )}&next=${encodeURIComponent(safeNext)}`
      );
    }

    // Use getAll/setAll for robust chunked cookie handling (PKCE verifier + auth token chunks)
    // cookies() is sync in Next 14, async in Next 15 - handle both
    const cookieStore: any = await Promise.resolve((cookies as any)());

    const supabase = createServerClient(supabaseUrl, supabaseAnonKey, {
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
              // Next 14 cookies().set signature: set(name, value, options) or set({name, value, ...options})
              // We use the 3-arg form for clarity and to ensure options (httpOnly, secure, sameSite, etc.) are preserved.
              cookieStore.set(name, value, options);
            });
          } catch (e) {
            // cookies() can throw in some edge contexts (e.g., during static generation) - log but don't crash
            console.warn('[auth/callback] cookie set failed', e);
          }
        },
      },
    });

    const { data, error } = await supabase.auth.exchangeCodeForSession(code);
    if (error) {
      console.error('[auth/callback] exchangeCodeForSession failed', error);
      // If the code has already been used or verifier missing, Supabase returns an error.
      // Try to see if a session already exists (user refreshed callback URL)
      try {
        const { data: existing } = await supabase.auth.getUser();
        if (existing?.user) {
          // Already signed in - ensure profile and redirect
          await ensureProfileForUser(existing.user);
          redirect(safeNext);
        }
      } catch {}
      redirect(`/login?error=${encodeURIComponent(error.message)}&next=${encodeURIComponent(safeNext)}`);
    }

    // Success - ensure profile + free entitlement exist (fallback if DB trigger not applied)
    if (data?.user) {
      await ensureProfileForUser(data.user);
    } else {
      // Edge case: exchange succeeded but no user in data (should not happen) - try getUser
      try {
        const { data: userData } = await supabase.auth.getUser();
        if (userData?.user) await ensureProfileForUser(userData.user);
      } catch {}
    }

    redirect(safeNext);
  }

  // No code - render client handler for hash fragment (magic link, recovery, implicit flow)
  return <CallbackClient next={safeNext} />;
}

async function ensureProfileForUser(user: { id: string; email?: string | null; user_metadata?: any }) {
  if (!isSupabaseServerConfigured()) return;
  try {
    const service = getServiceSupabase();
    const { data: existingProfile, error: selErr } = await service
      .from('profiles')
      .select('id')
      .eq('id', user.id)
      .maybeSingle();
    if (selErr) {
      console.warn('[auth/callback] profile select failed', selErr.message);
      return;
    }
    if (!existingProfile) {
      const fullName =
        (user.user_metadata?.full_name as string) ||
        (user.user_metadata?.name as string) ||
        (user.user_metadata?.user_name as string) ||
        '';
      const avatar =
        (user.user_metadata?.avatar_url as string) ||
        (user.user_metadata?.picture as string) ||
        '';
      const email = (user.email as string) || '';
      const { error: upsertErr } = await service.from('profiles').upsert(
        {
          id: user.id,
          email: email,
          full_name: fullName,
          avatar_url: avatar,
          role: 'user',
          account_status: 'active',
          is_premium: false,
          recording_count: 0,
          created_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
        },
        { onConflict: 'id' }
      );
      if (upsertErr) {
        console.warn('[auth/callback] profile upsert failed', upsertErr.message);
      } else {
        console.log('[auth/callback] profile created for', user.id);
      }
    }

    // Ensure free entitlement exists
    const { data: existingEnt } = await service
      .from('user_entitlements')
      .select('id')
      .eq('user_id', user.id)
      .limit(1)
      .maybeSingle();
    if (!existingEnt) {
      // Find free plan
      let freePlanId: string | null = null;
      const { data: freePlan } = await service
        .from('pricing_plans')
        .select('id')
        .eq('slug', 'free')
        .eq('is_active', true)
        .maybeSingle();
      freePlanId = freePlan?.id || null;
      if (!freePlanId) {
        const { data: fallback } = await service
          .from('pricing_plans')
          .select('id')
          .eq('billing_model', 'free')
          .limit(1)
          .maybeSingle();
        freePlanId = fallback?.id || null;
      }
      // Final fallback: try any free-priced plan
      if (!freePlanId) {
        const { data: anyFree } = await service
          .from('pricing_plans')
          .select('id')
          .eq('price_cents', 0)
          .limit(1)
          .maybeSingle();
        freePlanId = anyFree?.id || null;
      }
      if (freePlanId) {
        const { error: entErr } = await service.from('user_entitlements').insert({
          user_id: user.id,
          plan_id: freePlanId,
          status: 'active',
          source: 'signup',
          starts_at: new Date().toISOString(),
        });
        if (entErr) {
          // Handle race where trigger already created it
          if (!entErr.message.includes('duplicate') && !entErr.message.includes('unique')) {
            console.warn('[auth/callback] free entitlement insert failed', entErr.message);
          }
        } else {
          console.log('[auth/callback] free entitlement granted for', user.id);
        }
      } else {
        console.warn('[auth/callback] no free plan found to grant entitlement');
      }
    }
  } catch (e: any) {
    console.warn('[auth/callback] ensureProfileForUser failed', e?.message || e);
  }
}
