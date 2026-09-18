'use client';
import { useState } from 'react';
import { createSupabaseBrowserClient } from '@/lib/supabase/browser';

export function OAuthButtons({ next }: { next?: string }) {
  const [loading, setLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleGoogle() {
    setError(null);
    setLoading('google');
    const supabase = createSupabaseBrowserClient();
    if (!supabase) {
      setError('Auth not configured: Missing NEXT_PUBLIC_SUPABASE_URL or NEXT_PUBLIC_SUPABASE_ANON_KEY in Vercel env. Add them in Vercel Dashboard → Settings → Environment Variables and redeploy.');
      setLoading(null);
      return;
    }
    const origin = window.location.origin;
    const safeNext = next && next.startsWith('/') ? next : '/library';
    const redirectTo = `${origin}/auth/callback?next=${encodeURIComponent(safeNext)}`;
    const { error } = await supabase.auth.signInWithOAuth({
      provider: 'google',
      options: { redirectTo },
    });
    if (error) {
      setError(error.message);
      setLoading(null);
    }
  }

  async function handleMagicLink(email: string) {
    setError(null);
    setLoading('magic');
    const supabase = createSupabaseBrowserClient();
    if (!supabase) {
      setError('Auth not configured: Missing NEXT_PUBLIC_SUPABASE_URL or NEXT_PUBLIC_SUPABASE_ANON_KEY in Vercel env. Add them in Vercel → Settings → Environment Variables and redeploy.');
      setLoading(null);
      return { error: 'not configured' };
    }
    const origin = window.location.origin;
    const safeNext = next && next.startsWith('/') ? next : '/library';
    const redirectTo = `${origin}/auth/callback?next=${encodeURIComponent(safeNext)}`;
    const { error } = await supabase.auth.signInWithOtp({
      email,
      options: { emailRedirectTo: redirectTo },
    });
    setLoading(null);
    if (error) {
      setError(error.message);
      return { error: error.message };
    }
    return { success: true };
  }

  return { handleGoogle, handleMagicLink, loading, error, setError, OAuthUI: (
    <div className="space-y-3">
      <button
        onClick={handleGoogle}
        disabled={!!loading}
        className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 text-sm font-medium text-stone-200 hover:border-amber-600/50 hover:text-amber-100 disabled:opacity-60 flex items-center justify-center gap-2"
      >
        <svg width="16" height="16" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/></svg>
        {loading === 'google' ? 'Redirecting…' : 'Continue with Google'}
      </button>
      {error && <p className="text-xs text-red-400">{error}</p>}
    </div>
  ) };
}
