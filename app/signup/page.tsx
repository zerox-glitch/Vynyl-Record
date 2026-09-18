'use client';
import { FormEvent, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { Mail, LockKeyhole, Eye, EyeOff, User } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { AuthShell } from '@/components/auth/AuthShell';
import { createSupabaseBrowserClient } from '@/lib/supabase/browser';

function passwordStrength(pw: string): { score: number; label: string; color: string } {
  let score = 0;
  if (pw.length >= 8) score++;
  if (pw.length >= 12) score++;
  if (/[A-Z]/.test(pw) && /[a-z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  if (score <= 2) return { score, label: 'Weak', color: 'bg-red-500' };
  if (score === 3) return { score, label: 'Fair', color: 'bg-amber-500' };
  if (score === 4) return { score, label: 'Good', color: 'bg-emerald-500' };
  return { score, label: 'Strong', color: 'bg-emerald-400' };
}

export default function SignupPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = searchParams.get('next');
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);

  const strength = passwordStrength(password);

  async function handleSignup(e: FormEvent) {
    e.preventDefault();
    setError(''); setMessage('');
    if (password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    setLoading(true);
    const supabase = createSupabaseBrowserClient();
    if (!supabase) {
      setError('Authentication not configured.');
      setLoading(false);
      return;
    }
    const origin = window.location.origin;
    const safeNext = next && next.startsWith('/') ? next : '/library';
    const emailRedirectTo = `${origin}/auth/callback?next=${encodeURIComponent(safeNext)}`;

    const { data, error } = await supabase.auth.signUp({
      email: email.trim().toLowerCase(),
      password,
      options: {
        data: { full_name: fullName.trim() },
        emailRedirectTo,
      },
    });
    if (error) {
      setError(error.message);
      setLoading(false);
      return;
    }
    if (data.user && !data.session) {
      setMessage('Check your email to confirm your account, then sign in.');
    } else if (data.session) {
      router.push(safeNext);
      router.refresh();
    }
    setLoading(false);
  }

  async function handleGoogle() {
    setError('');
    const supabase = createSupabaseBrowserClient();
    if (!supabase) { setError('Auth not configured'); return; }
    const origin = window.location.origin;
    const safeNext = next && next.startsWith('/') ? next : '/library';
    const redirectTo = `${origin}/auth/callback?next=${encodeURIComponent(safeNext)}`;
    const { error } = await supabase.auth.signInWithOAuth({ provider: 'google', options: { redirectTo } });
    if (error) setError(error.message);
  }

  return (
    <AuthShell title="Keep your records." subtitle="Create a private room for the things worth keeping.">
      <div className="space-y-6">
        <form onSubmit={handleSignup} className="space-y-4">
          <label className="block text-xs font-semibold text-stone-300">
            Full name
            <div className="relative mt-2">
              <User className="absolute left-3 top-3 h-4 w-4 text-stone-500" />
              <input
                type="text"
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-3 text-sm outline-none focus:border-amber-500"
                placeholder="Arthur Vance"
                autoComplete="name"
              />
            </div>
          </label>

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
                autoComplete="email"
              />
            </div>
          </label>

          <label className="block text-xs font-semibold text-stone-300">
            Password
            <div className="relative mt-2">
              <LockKeyhole className="absolute left-3 top-3 h-4 w-4 text-stone-500" />
              <input
                required
                minLength={8}
                type={showPw ? 'text' : 'password'}
                value={password}
                onChange={e => setPassword(e.target.value)}
                className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-10 text-sm outline-none focus:border-amber-500"
                placeholder="At least 8 characters"
                autoComplete="new-password"
              />
              <button type="button" onClick={() => setShowPw(!showPw)} className="absolute right-3 top-2.5 text-stone-500 hover:text-stone-300">
                {showPw ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            {password && (
              <div className="mt-2 space-y-1">
                <div className="h-1 w-full rounded-full bg-stone-800">
                  <div className={`h-1 rounded-full ${strength.color} transition-all`} style={{ width: `${(strength.score / 5) * 100}%` }} />
                </div>
                <p className="text-[11px] text-stone-400">Strength: {strength.label} — use 12+ chars, mixed case, numbers, symbols.</p>
              </div>
            )}
          </label>

          {error && <p className="text-sm text-red-400" role="alert">{error}</p>}
          {message && <p className="text-sm text-emerald-400">{message}</p>}

          <Button type="submit" variant="primary" size="lg" className="w-full" isLoading={loading}>
            Create my record room
          </Button>
        </form>

        <div className="flex items-center gap-3">
          <div className="h-px flex-1 bg-stone-800" />
          <span className="text-[11px] uppercase tracking-widest text-stone-500">or</span>
          <div className="h-px flex-1 bg-stone-800" />
        </div>

        <button
          onClick={handleGoogle}
          className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 text-sm font-medium text-stone-200 hover:border-amber-600/50 hover:text-amber-100 flex items-center justify-center gap-2"
        >
          <svg width="16" height="16" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/></svg>
          Continue with Google
        </button>

        <div className="text-center text-xs">
          <Link href={`/login${next ? `?next=${encodeURIComponent(next)}` : ''}`} className="text-stone-400 hover:text-amber-300">
            Already have an account? Sign in
          </Link>
        </div>
      </div>
    </AuthShell>
  );
}
