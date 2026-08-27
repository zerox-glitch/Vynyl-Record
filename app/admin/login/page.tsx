'use client';

import React, { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { Disc3, LockKeyhole, Shield } from 'lucide-react';
import { Button } from '@/components/ui/Button';

export default function AdminLoginPage() {
  const router = useRouter();
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setLoading(true);
    setError('');
    try {
      const response = await fetch('/api/admin/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
      });
      const data = await response.json();
      if (!response.ok) {
        setError(data.error || 'Access denied.');
        return;
      }
      router.replace('/admin');
      router.refresh();
    } catch {
      setError('Could not reach the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="min-h-screen bg-[#0c0a09] text-stone-100 grid place-items-center px-4">
      <form onSubmit={submit} className="w-full max-w-md rounded-3xl border border-amber-700/30 bg-stone-900 p-8 shadow-2xl space-y-6">
        <Link href="/" className="inline-flex items-center gap-2 text-stone-400 hover:text-amber-300 text-sm">
          <Disc3 className="w-5 h-5" /> Vinyl Voice Notes
        </Link>
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-amber-500 text-xs font-mono uppercase tracking-widest">
            <Shield className="w-4 h-4" /> Separate restricted system
          </div>
          <h1 className="text-3xl font-serif font-bold">Admin sign in</h1>
          <p className="text-sm text-stone-400">This area is not part of the public website.</p>
        </div>
        <div>
          <label className="block text-xs font-semibold text-stone-300 mb-2">Administrator password</label>
          <div className="relative">
            <LockKeyhole className="absolute left-3 top-3 w-4 h-4 text-stone-500" />
            <input type="password" autoFocus required value={password} onChange={(e) => setPassword(e.target.value)} className="w-full rounded-xl border border-stone-700 bg-stone-950 py-2.5 pl-10 pr-3 text-sm outline-none focus:border-amber-500" />
          </div>
          {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
        </div>
        <Button type="submit" variant="primary" size="lg" className="w-full" isLoading={loading}>Enter operations hub</Button>
      </form>
    </main>
  );
}
