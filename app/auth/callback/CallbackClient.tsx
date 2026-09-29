'use client';
import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { createSupabaseBrowserClient } from '@/lib/supabase/browser';

export default function CallbackClient({ next }: { next: string }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState('Completing sign-in…');

  useEffect(() => {
    const supabase = createSupabaseBrowserClient();
    if (!supabase) {
      setError('Auth not configured: Missing NEXT_PUBLIC_SUPABASE_URL / NEXT_PUBLIC_SUPABASE_ANON_KEY.');
      setTimeout(() => router.push('/login'), 2000);
      return;
    }

    // Check for error params in URL (e.g., from Supabase or Google)
    const errorParam = searchParams.get('error_description') || searchParams.get('error');
    if (errorParam) {
      setError(errorParam);
      setTimeout(() => router.push(`/login?error=${encodeURIComponent(errorParam)}&next=${encodeURIComponent(next)}`), 2000);
      return;
    }

    // Check for code param - if present, try client-side exchange as fallback
    // (server should have handled it, but if cookies weren't set, try here)
    const code = searchParams.get('code');
    if (code) {
      setStatus('Exchanging code for session…');
      supabase.auth.exchangeCodeForSession(code).then(({ data, error }) => {
        if (error) {
          console.error('[CallbackClient] exchangeCodeForSession error', error);
          // If exchange fails due to PKCE verifier missing, the server may have already succeeded.
          // Check if we already have a session.
          supabase.auth.getSession().then(({ data: sessData }) => {
            if (sessData.session) {
              router.push(next);
              router.refresh();
            } else {
              setError(error.message);
              setTimeout(() => router.push(`/login?error=${encodeURIComponent(error.message)}&next=${encodeURIComponent(next)}`), 2000);
            }
          });
          return;
        }
        if (data.session) {
          router.push(next);
          router.refresh();
        } else {
          // Fallback to getSession
          supabase.auth.getSession().then(({ data: sessData }) => {
            if (sessData.session) {
              router.push(next);
              router.refresh();
            } else {
              router.push(next);
            }
          });
        }
      });
      return;
    }

    // No code - handle hash fragment (implicit flow, magic link, recovery)
    // Supabase will automatically parse hash fragments for access_token etc when getSession is called
    setStatus('Checking session…');
    supabase.auth.getSession().then(({ data, error }) => {
      if (error) {
        setError(error.message);
        setTimeout(() => router.push(`/login?error=${encodeURIComponent(error.message)}&next=${encodeURIComponent(next)}`), 1500);
        return;
      }
      if (data.session) {
        router.push(next);
        router.refresh();
      } else {
        // Try to handle hash explicitly - supabase-js v2 does this on auth state change
        // Wait for onAuthStateChange which fires when hash is parsed
        const { data: listener } = supabase.auth.onAuthStateChange((event, session) => {
          if (session) {
            setStatus('Session found, redirecting…');
            router.push(next);
            router.refresh();
          }
        });
        // Also check if hash contains access_token - if not, we may have no session
        const hasHash = window.location.hash.includes('access_token') || window.location.hash.includes('refresh_token');
        if (hasHash) {
          setStatus('Processing magic link…');
        }
        // Fallback after 2.5s
        const timeout = setTimeout(() => {
          listener.subscription.unsubscribe();
          // Re-check session after listener timeout
          supabase.auth.getSession().then(({ data: finalData }) => {
            if (finalData.session) {
              router.push(next);
              router.refresh();
            } else {
              // No session and no hash - if next is /library, we can still go there (it will show guest view)
              // But for protected routes like /account, middleware will redirect to login
              // For better UX, if no session, go to login with message
              const hasAnyToken = hasHash || window.location.search.includes('code');
              if (!hasAnyToken) {
                // No auth data at all - likely user visited /auth/callback directly
                setStatus('No session found');
                setTimeout(() => router.push(next), 1500);
              } else {
                setError('Could not establish session. Please try signing in again.');
                setTimeout(() => router.push(`/login?next=${encodeURIComponent(next)}`), 2000);
              }
            }
          });
        }, 2500);
        return () => {
          clearTimeout(timeout);
          listener.subscription.unsubscribe();
        };
      }
    });
  }, [next, router, searchParams]);

  return (
    <main className="min-h-screen grid place-items-center bg-[#0c0a09] text-stone-100 px-4">
      <div className="text-center space-y-3">
        <div className="h-10 w-10 animate-spin rounded-full border-2 border-amber-500 border-t-transparent mx-auto" />
        <p className="text-sm text-stone-300">{status}</p>
        {error ? <p className="text-xs text-red-400 max-w-md mx-auto">{error}</p> : <p className="text-xs text-stone-500">You will be redirected shortly.</p>}
      </div>
    </main>
  );
}
