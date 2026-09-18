'use client';
import { FormEvent, useState } from 'react';
import Link from 'next/link';
import { Mail } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { AuthShell } from '@/components/auth/AuthShell';
import { createSupabaseBrowserClient } from '@/lib/supabase/browser';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true); setError(''); setMessage('');
    const supabase = createSupabaseBrowserClient();
    if (!supabase) {
      setError('Auth not configured.');
      setLoading(false);
      return;
    }
    const origin = window.location.origin;
    const redirectTo = `${origin}/auth/callback?next=${encodeURIComponent('/reset-password')}`;
    const { error } = await supabase.auth.resetPasswordForEmail(email.trim().toLowerCase(), { redirectTo });
    // Never expose whether email exists
    if (error) {
      // Still show generic success to avoid enumeration, but log for debugging
      console.warn('reset error', error.message);
    }
    setMessage('If an account exists for that email, you will receive a password reset link shortly.');
    setLoading(false);
  }

  return (
    <AuthShell title="Forgot your password?" subtitle="We will send a reset link if an account exists.">
      <form onSubmit={handleSubmit} className="space-y-4">
        <label className="block text-xs font-semibold text-stone-300">
          Email
          <div className="relative mt-2">
            <Mail className="absolute left-3 top-3 h-4 w-4 text-stone-500" />
            <input
              required
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-3 text-sm outline-none focus:border-amber-500"
              placeholder="you@example.com"
            />
          </div>
        </label>
        {error && <p className="text-sm text-red-400">{error}</p>}
        {message && <p className="text-sm text-emerald-400">{message}</p>}
        <Button type="submit" variant="primary" size="lg" className="w-full" isLoading={loading}>
          Send reset link
        </Button>
        <div className="text-center text-xs">
          <Link href="/login" className="text-stone-400 hover:text-amber-300">Back to sign in</Link>
        </div>
      </form>
    </AuthShell>
  );
}
