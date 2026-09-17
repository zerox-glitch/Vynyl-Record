'use client';
import { FormEvent, useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { LockKeyhole, Eye, EyeOff } from 'lucide-react';
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

export default function ResetPasswordPage() {
  const router = useRouter();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    const supabase = createSupabaseBrowserClient();
    if (!supabase) { setChecking(false); return; }
    supabase.auth.getSession().then(({ data }) => {
      if (!data.session) {
        // Might be in recovery flow via hash, still allow
      }
      setChecking(false);
    });
  }, []);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(''); setMessage('');
    if (password.length < 8) { setError('Password must be at least 8 characters.'); return; }
    if (password !== confirm) { setError('Passwords do not match.'); return; }
    setLoading(true);
    const supabase = createSupabaseBrowserClient();
    if (!supabase) { setError('Auth not configured'); setLoading(false); return; }
    const { error } = await supabase.auth.updateUser({ password });
    if (error) {
      setError(error.message);
    } else {
      setMessage('Password updated. Redirecting to your library…');
      setTimeout(() => { router.push('/library'); router.refresh(); }, 1200);
    }
    setLoading(false);
  }

  if (checking) {
    return (
      <AuthShell title="Reset password" subtitle="Checking your recovery link…">
        <div className="text-center text-sm text-stone-400">Please wait…</div>
      </AuthShell>
    );
  }

  return (
    <AuthShell title="Set a new password" subtitle="Choose a strong password you have not used elsewhere.">
      <form onSubmit={handleSubmit} className="space-y-4">
        <label className="block text-xs font-semibold text-stone-300">
          New password
          <div className="relative mt-2">
            <LockKeyhole className="absolute left-3 top-3 h-4 w-4 text-stone-500" />
            <input
              required
              minLength={8}
              type={showPw ? 'text' : 'password'}
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-10 text-sm outline-none focus:border-amber-500"
              placeholder="••••••••"
              autoComplete="new-password"
            />
            <button type="button" onClick={() => setShowPw(!showPw)} className="absolute right-3 top-2.5 text-stone-500 hover:text-stone-300">
              {showPw ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
          {password && (
            <div className="mt-2 space-y-1">
              {(() => {
                const s = passwordStrength(password);
                return (
                  <>
                    <div className="h-1 w-full rounded-full bg-stone-800">
                      <div className={`h-1 rounded-full ${s.color}`} style={{ width: `${(s.score / 5) * 100}%` }} />
                    </div>
                    <p className="text-[11px] text-stone-400">{s.label}</p>
                  </>
                );
              })()}
            </div>
          )}
        </label>
        <label className="block text-xs font-semibold text-stone-300">
          Confirm password
          <div className="relative mt-2">
            <LockKeyhole className="absolute left-3 top-3 h-4 w-4 text-stone-500" />
            <input
              required
              type={showPw ? 'text' : 'password'}
              value={confirm}
              onChange={e => setConfirm(e.target.value)}
              className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-3 text-sm outline-none focus:border-amber-500"
              placeholder="••••••••"
              autoComplete="new-password"
            />
          </div>
        </label>
        {error && <p className="text-sm text-red-400">{error}</p>}
        {message && <p className="text-sm text-emerald-400">{message}</p>}
        <Button type="submit" variant="primary" size="lg" className="w-full" isLoading={loading}>
          Update password
        </Button>
      </form>
    </AuthShell>
  );
}
