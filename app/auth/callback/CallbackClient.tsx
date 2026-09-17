'use client';
import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { createSupabaseBrowserClient } from '@/lib/supabase/browser';

export default function CallbackClient({ next }: { next: string }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const supabase = createSupabaseBrowserClient();
    if (!supabase) {
      router.push('/login');
      return;
    }
    // Supabase will automatically parse hash fragments for access_token etc when getSession is called
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
        const { data: listener } = supabase.auth.onAuthStateChange((event, session) => {
          if (session) {
            router.push(next);
            router.refresh();
          }
        });
        // Fallback after 2s
        setTimeout(() => {
          listener.subscription.unsubscribe();
          // If still no session, check if we have code in URL handled by server - else redirect to login
          const hasHash = window.location.hash.includes('access_token');
          if (!hasHash) {
            router.push(next);
          }
        }, 2000);
      }
    });
  }, [next, router]);

  return (
    <main className="min-h-screen grid place-items-center bg-[#0c0a09] text-stone-100 px-4">
      <div className="text-center space-y-3">
        <div className="h-10 w-10 animate-spin rounded-full border-2 border-amber-500 border-t-transparent mx-auto" />
        <p className="text-sm text-stone-300">Completing sign-in…</p>
        {error ? <p className="text-xs text-red-400">{error}</p> : <p className="text-xs text-stone-500">You will be redirected shortly.</p>}
      </div>
    </main>
  );
}
