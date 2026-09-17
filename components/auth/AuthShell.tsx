'use client';
import Link from 'next/link';
import { Disc3 } from 'lucide-react';
import React from 'react';

export function AuthShell({ children, title, subtitle }: { children: React.ReactNode; title: string; subtitle?: string }) {
  return (
    <main className="min-h-screen grid place-items-center bg-[#0c0a09] px-4 py-10 text-stone-100">
      <div className="w-full max-w-md">
        <div className="mb-8 flex flex-col items-center gap-3">
          <Link href="/" className="inline-flex items-center gap-2 rounded-full border border-amber-800/30 bg-stone-900 px-4 py-2 text-sm text-stone-300 hover:text-amber-200">
            <Disc3 className="h-5 w-5 text-amber-500" /> Vinyl Voice Notes
          </Link>
          <div className="text-center">
            <h1 className="font-serif text-3xl font-bold tracking-tight">{title}</h1>
            {subtitle && <p className="mt-2 text-sm text-stone-400">{subtitle}</p>}
          </div>
        </div>
        <div className="rounded-3xl border border-amber-700/30 bg-stone-900 p-8 shadow-2xl">
          {children}
        </div>
        <p className="mt-6 text-center text-[11px] text-stone-500">
          By continuing you agree to our Terms and Privacy Policy. Your recordings stay private unless you share the link.
        </p>
      </div>
    </main>
  );
}
