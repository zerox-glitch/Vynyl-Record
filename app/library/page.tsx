/**
 * /library — customer-facing record library / dashboard.
 *
 * Privacy model: lists every recording visible to the current viewer
 * (public + unlisted, by default). Private recordings are excluded unless
 * the visitor proves ownership via a future auth step (the privacy gate
 * already supports a "owner" viewer kind via getRecordingsForViewer — once
 * Supabase Auth is wired in, that returns the user's private records too).
 *
 * Each row links to the play page, the QR PNG endpoint, the Audio
 * download endpoint, and a "Make a copy in the studio" CTA so a customer
 * can re-edit an old record without losing the original.
 */
import React from 'react';
import Link from 'next/link';
import { getRecordingsForViewer } from '@/lib/db';
import { getCustomerUser } from '@/lib/supabase/auth';
import { Navbar } from '@/components/ui/Navbar';
import { Footer } from '@/components/ui/Footer';
import { Button } from '@/components/ui/Button';
import { Disc3, Lock, ExternalLink, Sparkles, QrCode, Download, Pencil } from 'lucide-react';

// Force dynamic: render fresh on each request so VISIBILITY changes propagate.
export const dynamic = 'force-dynamic';
// The analytics fan-out runs server-side for signed-in customers; this
// page must run on Node for that.
export const runtime = 'nodejs';

export default async function LibraryPage() {
  const user = await getCustomerUser();
  const recordings = await getRecordingsForViewer(user ? { kind: 'user', userId: user.id } : { kind: 'anonymous' });

  return (
    <div className="flex min-h-screen flex-col bg-[#0c0a09] text-stone-100">
      <Navbar />

      <main className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6 lg:px-8">
        {/* Header */}
        <div className="flex flex-col gap-6 border-b border-amber-900/30 pb-10 md:flex-row md:items-end md:justify-between">
          <div className="space-y-3">
            <div className="inline-flex items-center gap-2 rounded-full bg-amber-950/60 border border-amber-600/40 px-3 py-1 font-mono text-xs uppercase tracking-widest text-amber-300">
              <Disc3 className="h-3 w-3" />
              <span>The Record Room</span>
            </div>
            <h1 className="font-serif text-3xl font-bold tracking-tight text-stone-100 sm:text-4xl">
              The records you&apos;ve pressed.
            </h1>
            <p className="max-w-xl text-sm leading-relaxed text-stone-400">
              Every memory you&apos;ve captured. Each row opens a full vinyl turntable,
              generates a printable QR for cards and gifts, and keeps the master
              close.
            </p>
          </div>
          <div className="flex flex-wrap gap-3">
            {!user && <Link href="/login"><Button variant="outline" size="lg">Sign in to save privately</Button></Link>}
            <Link href="/studio"><Button variant="primary" size="lg" leftIcon={<Sparkles className="h-4 w-4 text-stone-950" />}>Press a new voice</Button></Link>
          </div>
        </div>

        {recordings.length === 0 ? (
          <div className="mt-16 flex flex-col items-center gap-6 rounded-3xl border border-stone-800 bg-stone-900/60 p-12 text-center">
            <Disc3 className="h-12 w-12 text-amber-400 opacity-50" />
            <div className="space-y-2">
              <p className="font-serif text-xl text-stone-100">No records here yet.</p>
              <p className="text-sm text-stone-400">Start with a few seconds of the people you love.</p>
            </div>
            <Link href="/studio">
              <Button variant="primary" size="lg" leftIcon={<Sparkles className="h-4 w-4 text-stone-950" />}>
                Press your voice
              </Button>
            </Link>
          </div>
        ) : (
          <ul className="mt-12 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {recordings.map((rec) => (
              <li
                key={rec.id}
                className="group flex flex-col gap-4 rounded-3xl border border-stone-800 bg-stone-900/60 p-6 transition-all hover:-translate-y-1 hover:border-amber-600/40 hover:bg-stone-900"
              >
                {/* Cover — uses the same vinyl disc rendering as MemoryShowcase */}
                <div className="flex items-start justify-between">
                  <div
                    className="flex h-16 w-16 items-center justify-center rounded-full border border-white/10 p-1 shadow-xl transition-transform duration-700 group-hover:rotate-90"
                    style={{ backgroundColor: rec.vinyl_style ? undefined : '#121212' }}
                  >
                    <div
                      className="flex h-7 w-7 items-center justify-center rounded-full border"
                      style={{
                        backgroundColor: rec.vinyl_style ? '#121212' : '#991b1b',
                        borderColor: '#f59e0b',
                      }}
                    >
                      <div className="h-2 w-2 rounded-full bg-black" />
                    </div>
                  </div>
                  <span className="rounded-full bg-stone-950 border border-amber-600/30 px-2.5 py-0.5 text-[10px] font-mono uppercase tracking-wider text-amber-300">
                    {rec.visibility === 'public' ? 'Public' : rec.visibility === 'unlisted' ? 'Link' : 'Private'}
                  </span>
                </div>

                {/* Body */}
                <div className="space-y-1">
                  <h3 className="line-clamp-1 font-serif text-lg font-bold text-stone-100 group-hover:text-amber-100">
                    {rec.title || 'Untitled Memory'}
                  </h3>
                  {(rec.recipient_name || rec.sender_name) && (
                    <p className="text-xs text-stone-400">
                      To {rec.recipient_name || '—'} · from {rec.sender_name || '—'}
                    </p>
                  )}
                  <p className="font-mono text-[10px] text-stone-500">
                    {new Date(rec.created_at).toLocaleDateString(undefined, {
                      month: 'short', day: 'numeric', year: 'numeric',
                    })}
                  </p>
                </div>

                {/* Actions: Play (open in player), QR, Download MP3, Studio (re-edit). */}
                <div className="mt-auto flex flex-wrap items-center gap-2 pt-3">
                  <Link href={`/play/${rec.slug}`} className="flex-1">
                    <Button variant="secondary" size="sm" className="w-full!">
                      <ExternalLink className="h-3.5 w-3.5" />
                      <span>Open</span>
                    </Button>
                  </Link>
                  <a
                    href={`/api/qr/${rec.slug}?size=640`}
                    title="Download printable QR PNG"
                    className="rounded-xl border border-stone-700 bg-stone-900 p-2 text-stone-300 hover:text-amber-300"
                  >
                    <QrCode className="h-4 w-4" />
                  </a>
                  <a
                    href={`/api/play/${rec.slug}/download`}
                    title="Download mastered MP3"
                    className="rounded-xl border border-stone-700 bg-stone-900 p-2 text-stone-300 hover:text-amber-300"
                  >
                    <Download className="h-4 w-4" />
                  </a>
                  <Link
                    href={`/studio?duplicate=${rec.slug}`}
                    className="rounded-xl border border-stone-700 bg-stone-900 p-2 text-stone-300 hover:text-amber-300"
                    title="Re-edit this record in the studio"
                  >
                    <Pencil className="h-4 w-4" />
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        )}

        <p className="mt-12 text-center text-sm text-stone-500">
          <Lock className="mr-1 inline h-3.5 w-3.5 align-text-bottom" />
          Private records live behind the owner&apos;s sign-in — they don&apos;t appear here for anyone else.
        </p>
      </main>

      <Footer />
    </div>
  );
}
