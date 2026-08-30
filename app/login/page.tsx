'use client';

import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { Disc3, Mail, LockKeyhole } from 'lucide-react';
import { Button } from '@/components/ui/Button';

export default function CustomerLoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<'signin' | 'signup'>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault(); setLoading(true); setError(''); setMessage('');
    try {
      const response = await fetch('/api/auth/customer', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode, email, password }),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || 'Could not sign in.');
      if (data.needsEmailConfirmation) {
        setMessage('Check your email to confirm the account, then come back to sign in.');
      } else {
        router.push('/library'); router.refresh();
      }
    } catch (err: any) { setError(err.message); }
    finally { setLoading(false); }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-[#0c0a09] px-4 text-stone-100">
      <form onSubmit={submit} className="w-full max-w-md space-y-6 rounded-3xl border border-amber-700/30 bg-stone-900 p-8 shadow-2xl">
        <Link href="/" className="inline-flex items-center gap-2 text-sm text-stone-400 hover:text-amber-300"><Disc3 className="h-5 w-5" /> Vinyl Voice Notes</Link>
        <div><h1 className="font-serif text-3xl font-bold">{mode === 'signin' ? 'Welcome back.' : 'Keep your records.'}</h1><p className="mt-2 text-sm text-stone-400">{mode === 'signin' ? 'Your voices are waiting on the shelf.' : 'Create a private room for the things worth keeping.'}</p></div>
        <label className="block text-xs font-semibold text-stone-300">Email<div className="relative mt-2"><Mail className="absolute left-3 top-3 h-4 w-4 text-stone-500" /><input required type="email" value={email} onChange={e => setEmail(e.target.value)} className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-3 text-sm outline-none focus:border-amber-500" /></div></label>
        <label className="block text-xs font-semibold text-stone-300">Password<div className="relative mt-2"><LockKeyhole className="absolute left-3 top-3 h-4 w-4 text-stone-500" /><input required minLength={8} type="password" value={password} onChange={e => setPassword(e.target.value)} className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-3 text-sm outline-none focus:border-amber-500" /></div></label>
        {error && <p className="text-sm text-red-400">{error}</p>}{message && <p className="text-sm text-emerald-400">{message}</p>}
        <Button type="submit" variant="primary" size="lg" className="w-full" isLoading={loading}>{mode === 'signin' ? 'Open the record room' : 'Create my record room'}</Button>
        <button type="button" onClick={() => setMode(mode === 'signin' ? 'signup' : 'signin')} className="w-full text-center text-xs text-stone-400 hover:text-amber-300">{mode === 'signin' ? 'New here? Create an account' : 'Already have an account? Sign in'}</button>
      </form>
    </main>
  );
}
