'use client';

import React, { useState, useEffect, useRef, Suspense, useCallback } from 'react';
import dynamic from 'next/dynamic';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { Navbar } from '@/components/ui/Navbar';
import { Footer } from '@/components/ui/Footer';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { ParchmentLyricCard } from '@/components/3d/ParchmentLyricCard';
import { Recording } from '@/types';
import { DEMO_RECORDINGS, VINYL_STYLES, FILTER_PRESETS } from '@/lib/constants';
import { useVinylPlayer } from '@/lib/audio/useVinylPlayer';
import {
  Play,
  Pause,
  RotateCcw,
  Volume2,
  VolumeX,
  Share2,
  Download,
  Copy,
  Check,
  Disc3,
  Mic,
  Sparkles,
  AlertTriangle,
  Keyboard,
} from 'lucide-react';
import toast from 'react-hot-toast';

// 3D turntable — SSR unsafe, and only mounted once per page.
const AnimeTurntablePlayer = dynamic(
  () => import('@/components/3d/AnimeTurntablePlayer').then((m) => m.AnimeTurntablePlayer),
  {
    ssr: false,
    loading: () => (
      <div className="flex h-full w-full flex-col items-center justify-center bg-gradient-to-b from-[#ffd9b8] to-[#7c4f57]">
        <div className="flex h-16 w-16 animate-pulse items-center justify-center rounded-full border border-amber-200/50 bg-black/25">
          <Disc3 className="h-8 w-8 animate-spin text-amber-200" />
        </div>
        <span className="mt-4 font-mono text-[11px] uppercase tracking-[0.2em] text-stone-900/70">
          Threading the tonearm…
        </span>
      </div>
    ),
  }
);

export default function PlayRecordingPage() {
  const params = useParams();
  const slug = (params?.slug as string) || '';

  const [recording, setRecording] = useState<Recording | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [shareModalOpen, setShareModalOpen] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);
  const [showKeyboardHint, setShowKeyboardHint] = useState<boolean>(true);

  useEffect(() => {
    if (!slug) return;
    setLoading(true);
    const fallbackDemo = () =>
      DEMO_RECORDINGS.find((r) => r.slug.toLowerCase() === decodeURIComponent(slug).toLowerCase()) ||
      DEMO_RECORDINGS[0];

    fetch(`/api/play/${encodeURIComponent(slug)}`)
      .then((res) => {
        if (!res.ok) throw new Error('Not found');
        return res.json();
      })
      .then((data) => {
        setRecording(data?.recording ? data.recording : fallbackDemo());
      })
      .catch(() => setRecording(fallbackDemo()))
      .finally(() => setLoading(false));
  }, [slug]);

  const handlePlayerError = useCallback((message: string) => {
    toast.error(message, { duration: 6000 });
  }, []);

  const player = useVinylPlayer({
    src: recording?.processed_audio_url,
    fallbackSrc: recording?.raw_voice_url && recording.raw_voice_url !== recording.processed_audio_url
      ? recording.raw_voice_url
      : null,
    needleSrc: '/audio/needle-drop.mp3',
    needleDelayMs: 1150,
    onError: handlePlayerError,
  });

  const {
    isPlaying,
    isNeedleDropping,
    isReady,
    isLoading,
    error: playerError,
    currentTime,
    duration,
    volume,
    isMuted,
    toggle,
    restart,
    seek,
    setVolume,
    toggleMute,
  } = player;

  const totalDuration = duration || recording?.duration_seconds || 0;
  const progress = totalDuration > 0 ? Math.min(100, (currentTime / totalDuration) * 100) : 0;

  // Space = play/pause, ← → = skip. Ignored while typing.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;
      if (target?.isContentEditable) return;
      if (event.code === 'Space' || event.key === 'k') {
        event.preventDefault();
        setShowKeyboardHint(false);
        toggle();
      } else if (event.key === 'ArrowRight') {
        event.preventDefault();
        seek(Math.min(totalDuration || Infinity, currentTime + 5));
      } else if (event.key === 'ArrowLeft') {
        event.preventDefault();
        seek(Math.max(0, currentTime - 5));
      } else if (event.key === 'm') {
        toggleMute();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [toggle, seek, toggleMute, currentTime, totalDuration]);

  useEffect(() => {
    if (!isPlaying) return;
    const timer = setTimeout(() => setShowKeyboardHint(false), 4000);
    return () => clearTimeout(timer);
  }, [isPlaying]);

  const handleWordJump = (startTime: number) => {
    setShowKeyboardHint(false);
    if (!isReady) {
      toast('The wax is still buffering…', { icon: '⏳' });
      return;
    }
    player.play(startTime);
  };

  const handleCopyLink = async () => {
    const url = typeof window !== 'undefined' ? window.location.href : '';
    navigator.clipboard
      .writeText(url)
      .then(() => {
        setCopiedLink(true);
        toast.success('✨ Link copied to clipboard!');
        setTimeout(() => setCopiedLink(false), 2500);
      })
      .catch(() => toast.error('Could not reach the clipboard — copy the address bar instead.'));
  };

  const formatTime = (seconds: number) => {
    if (!Number.isFinite(seconds) || seconds < 0) return '00:00';
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const styleConfig = VINYL_STYLES.find((s) => s.id === recording?.vinyl_style) || VINYL_STYLES[0];
  const filterConfig = FILTER_PRESETS.find((f) => f.id === recording?.filter_preset) || FILTER_PRESETS[1];

  if (loading) {
    return (
      <div className="flex min-h-screen flex-col justify-between bg-[#0c0a09] text-stone-100">
        <Navbar />
        <div className="flex flex-1 flex-col items-center justify-center space-y-4">
          <div className="flex h-16 w-16 items-center justify-center rounded-full border border-amber-700/30 bg-amber-950/50">
            <Disc3 className="h-8 w-8 animate-spin text-amber-500" />
          </div>
          <p className="font-serif text-sm text-amber-200">Lifting the record from its sleeve…</p>
        </div>
        <Footer />
      </div>
    );
  }

  if (!recording) {
    return (
      <div className="min-h-screen bg-[#0c0a09] flex flex-col text-stone-100">
        <Navbar />
        <main className="flex-1 flex flex-col items-center justify-center gap-5 px-6 text-center">
          <Disc3 className="w-14 h-14 text-stone-600" />
          <div>
            <h1 className="text-2xl font-serif font-bold text-amber-100">Recording unavailable</h1>
            <p className="mt-2 text-sm text-stone-400">This vinyl note does not exist or has been removed.</p>
          </div>
          <Link href="/">
            <Button variant="primary" size="md">Return to the archive</Button>
          </Link>
        </main>
        <Footer />
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-[#0c0a09] text-stone-100 selection:bg-amber-600 selection:text-white">
      <Navbar />

      <main className="mx-auto w-full max-w-7xl flex-1 space-y-6 px-4 py-6 sm:px-6 lg:px-8">
        {/* Header */}
        <div className="flex flex-col justify-between gap-4 border-b border-amber-900/30 pb-6 lg:flex-row lg:items-end">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-600/30 bg-amber-950/60 px-3 py-1 font-mono text-xs text-amber-300">
                <Disc3 className="h-3.5 w-3.5 text-amber-400" />
                <span>3D Vinyl Player · 33⅓ RPM</span>
              </span>
              <span className="inline-flex items-center gap-1.5 rounded-full border border-stone-700 bg-stone-900 px-3 py-1 font-mono text-xs text-stone-300">
                <Sparkles className="h-3.5 w-3.5 text-amber-400" />
                <span>Drag to orbit · scroll to zoom</span>
              </span>
              {isNeedleDropping && (
                <span className="animate-pulse rounded-full border border-amber-300 bg-amber-600 px-3 py-1 font-mono text-xs font-bold text-stone-950">
                  ● needle dropping…
                </span>
              )}
            </div>
            <div>
              <h1 className="text-3xl font-serif font-bold tracking-tight text-stone-100 sm:text-4xl">
                {recording.title}
              </h1>
              <div className="mt-2 flex flex-wrap items-center gap-3 font-mono text-xs text-stone-400">
                <span className="flex items-center gap-1.5">
                  <span className="h-2 w-2 animate-pulse rounded-full bg-emerald-500" />
                  {recording.views} plays
                </span>
                <span>•</span>
                <span className="text-amber-300/80">
                  {styleConfig.name} • {filterConfig.name}
                </span>
                {recording.duration_seconds ? (
                  <>
                    <span>•</span>
                    <span>{formatTime(recording.duration_seconds)} master</span>
                  </>
                ) : null}
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShareModalOpen(true)}
              leftIcon={<Share2 className="h-4 h-4 text-amber-400" />}
            >
              Share Memory
            </Button>
            <Link href="/studio">
              <Button variant="primary" size="sm" leftIcon={<Mic className="h-4 w-4 text-stone-950" />}>
                Record Yours
              </Button>
            </Link>
          </div>
        </div>

        <div className="grid grid-cols-1 items-stretch gap-6 lg:grid-cols-12">
          {/* Turntable + player */}
          <div className="flex flex-col gap-4 lg:col-span-8">
            <div className="relative overflow-hidden rounded-3xl border border-amber-900/30 shadow-2xl">
              <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-start justify-between p-3">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-white/20 bg-black/35 px-3 py-1 font-mono text-[11px] text-amber-100 backdrop-blur-sm">
                  {styleConfig.name}
                </span>
                <span className="inline-flex items-center gap-1.5 rounded-full border border-white/15 bg-black/30 px-3 py-1 font-mono text-[11px] text-stone-200">
                  {isLoading && !isPlaying
                    ? 'buffering wax…'
                    : !isReady
                    ? 'loading…'
                    : isPlaying
                    ? 'playing'
                    : 'ready'}
                </span>
              </div>

              <div className="h-[380px] w-full sm:h-[460px] lg:h-[520px]">
                <Suspense fallback={<div className="h-full w-full bg-[#1c1917]" />}>
                  <AnimeTurntablePlayer
                    isPlaying={isPlaying}
                    isNeedleDropping={isNeedleDropping}
                    vinylStyle={recording.vinyl_style}
                    title={recording.title}
                    recipientName={recording.recipient_name || undefined}
                    senderName={recording.sender_name || undefined}
                  />
                </Suspense>
              </div>

              {/* Transport */}
              <div className="absolute inset-x-0 bottom-0 z-10 bg-gradient-to-t from-stone-950 via-stone-950/80 to-transparent p-4">
                <div className="space-y-2">
                  <div className="relative">
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-stone-800">
                      <div
                        className="h-full rounded-full bg-gradient-to-r from-amber-400 to-amber-600 transition-[width] duration-100"
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                    <input
                      type="range"
                      min={0}
                      max={totalDuration || 10}
                      step={0.1}
                      value={Math.min(currentTime, totalDuration || 10)}
                      onChange={(event) => seek(parseFloat(event.target.value))}
                      aria-label="Seek within record"
                      className="absolute inset-0 h-full w-full cursor-pointer appearance-none bg-transparent opacity-0"
                    />
                  </div>
                  <div className="flex items-center justify-between font-mono text-xs text-stone-400">
                    <span className="font-bold text-amber-300">{formatTime(currentTime)}</span>
                    <span>{formatTime(totalDuration)}</span>
                  </div>
                </div>

                <div className="mt-3 flex items-center justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={toggle}
                      disabled={!isReady && !isPlaying && !isNeedleDropping}
                      title={
                        isNeedleDropping
                          ? 'Needle is falling — click to cancel'
                          : isPlaying
                          ? 'Pause'
                          : 'Drop the needle'
                      }
                      className="flex h-14 w-14 items-center justify-center rounded-2xl border border-amber-300/40 bg-gradient-to-br from-amber-400 to-amber-700 text-stone-950 shadow-xl shadow-amber-950/60 transition-all hover:scale-105 active:scale-95 disabled:cursor-wait disabled:opacity-60"
                    >
                      {isNeedleDropping || (isLoading && isPlaying) ? (
                        <Disc3 className="h-6 w-6 animate-spin" />
                      ) : isPlaying ? (
                        <Pause className="h-6 w-6 fill-stone-950" />
                      ) : (
                        <Play className="ml-1 h-6 w-6 fill-stone-950" />
                      )}
                    </button>
                    <button
                      onClick={restart}
                      title="Play again from the top"
                      className="rounded-xl border border-stone-700 bg-stone-900/80 p-3 text-stone-300 transition-colors hover:text-amber-300"
                    >
                      <RotateCcw className="h-4 w-4" />
                    </button>
                    <div className="ml-1 hidden flex-col sm:flex">
                      <span className="text-xs font-bold text-amber-100">
                        {isNeedleDropping
                          ? 'Arm lowering onto the wax…'
                          : isPlaying
                          ? 'Playing'
                          : 'Ready when you are'}
                      </span>
                      <span className="font-mono text-[10px] text-stone-400">
                        {isNeedleDropping
                          ? 'voice fades in after the needle drop'
                          : `${filterConfig.name} • ${styleConfig.name}`}
                      </span>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 rounded-xl border border-stone-800 bg-stone-900/70 px-3 py-2">
                    <button onClick={toggleMute} className="text-stone-400 transition-colors hover:text-amber-300">
                      {isMuted || volume === 0 ? <VolumeX className="h-5 w-5" /> : <Volume2 className="h-5 w-5" />}
                    </button>
                    <input
                      type="range"
                      min={0}
                      max={1}
                      step={0.05}
                      value={isMuted ? 0 : volume}
                      onChange={(event) => setVolume(parseFloat(event.target.value))}
                      aria-label="Volume"
                      className="h-1.5 w-20 cursor-pointer appearance-none rounded-lg bg-stone-800 accent-amber-500 sm:w-28"
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Status / help strip */}
            {playerError ? (
              <div className="flex items-start gap-3 rounded-2xl border border-red-800/50 bg-red-950/40 p-4">
                <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-300" />
                <div className="space-y-2">
                  <p className="text-sm font-serif text-red-100">{playerError}</p>
                  <p className="text-xs leading-relaxed text-red-200/70">
                    The mastered file for this record is{' '}
                    <code className="rounded bg-black/40 px-1 font-mono text-[11px]">
                      {recording.processed_audio_url?.slice(0, 46)}
                      {recording.processed_audio_url?.length > 46 ? '…' : ''}
                    </code>
                    . If it was pressed before this deploy the audio may have left the temp disk —
                    re-press the record from the studio to make a permanent copy.
                  </p>
                  <div className="flex gap-2">
                    <Button variant="outline" size="sm" onClick={() => player.play(currentTime)}>
                      Try again
                    </Button>
                    <a href={recording.processed_audio_url} download={`${recording.slug || 'vynyl'}.mp3`}>
                      <Button variant="outline" size="sm" leftIcon={<Download className="h-4 w-4" />}>
                        Download instead
                      </Button>
                    </a>
                  </div>
                </div>
              </div>
            ) : (
              <div className="flex items-start gap-3 rounded-2xl border border-amber-900/20 bg-stone-900/60 p-4">
                <div className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full border border-amber-700/30 bg-amber-950/50">
                  <Sparkles className="h-4 w-4 text-amber-400" />
                </div>
                <div className="space-y-1">
                  <p className="text-sm font-serif text-stone-200">
                    Pressed once, played the same way every time — voice, tube filter, background
                    music and crackle are all mixed into this MP3.
                  </p>
                  <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] leading-relaxed text-stone-400">
                    <span className="inline-flex items-center gap-1 rounded-full border border-stone-700 bg-stone-950/60 px-2 py-0.5 font-mono">
                      <Keyboard className="h-3 w-3" /> space plays
                    </span>
                    <span className="inline-flex items-center gap-1 rounded-full border border-stone-700 bg-stone-950/60 px-2 py-0.5 font-mono">
                      ← → skip 5s
                    </span>
                    <span className="inline-flex items-center gap-1 rounded-full border border-stone-700 bg-stone-950/60 px-2 py-0.5 font-mono">
                      m mutes
                    </span>
                    <span>
                      {showKeyboardHint && !isPlaying
                        ? ' — or click the amber button to drop the needle.'
                        : '.'}
                    </span>
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* Lyrics */}
          <div className="flex min-h-[420px] flex-col lg:col-span-4">
            <ParchmentLyricCard
              transcript={recording.transcript_json || []}
              currentTime={currentTime}
              title={recording.title}
              recipientName={recording.recipient_name}
              senderName={recording.sender_name}
              createdAt={recording.created_at}
              onWordClick={handleWordJump}
            />
          </div>
        </div>
      </main>

      {/* Playback elements are owned by useVinylPlayer (src is set by the hook). */}
      <audio ref={player.audioRef} preload="auto" className="hidden" />
      <audio ref={player.needleRef} src="/audio/needle-drop.mp3" preload="auto" className="hidden" />

      <Modal
        isOpen={shareModalOpen}
        onClose={() => setShareModalOpen(false)}
        title="Share this record"
        subtitle="Send the link — it plays on phones and laptops alike"
        maxWidth="md"
      >
        <div className="space-y-6 pt-2">
          <div className="space-y-2">
            <label className="block font-mono text-xs text-stone-400">Permanent record link</label>
            <div className="flex items-center gap-2">
              <input
                type="text"
                readOnly
                value={typeof window !== 'undefined' ? window.location.href : ''}
                className="w-full rounded-xl border border-stone-700 bg-stone-950 px-3 py-2 font-mono text-xs text-amber-200"
              />
              <Button
                variant="primary"
                size="sm"
                onClick={handleCopyLink}
                leftIcon={copiedLink ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              >
                {copiedLink ? 'Copied' : 'Copy'}
              </Button>
            </div>
          </div>

          <div className="rounded-2xl border border-amber-700/30 bg-amber-950/20 p-4 text-center">
            <img src={`/api/qr/${recording.slug}?size=320`} alt="QR code for this record" className="mx-auto h-40 w-40 rounded-lg bg-white p-2" />
            <p className="mt-2 text-xs text-stone-400">Print it on a card. Let the scan become the surprise.</p>
            <a href={`/api/qr/${recording.slug}?size=1200`} download={`${recording.slug}-qr.png`} className="mt-3 inline-flex rounded-xl border border-amber-600/40 px-3 py-2 text-xs text-amber-200 hover:bg-amber-950/50">Download QR</a>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <a
              href={`https://wa.me/?text=${encodeURIComponent(
                `A voice pressed to vinyl for you: ${typeof window !== 'undefined' ? window.location.href : ''}`
              )}`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center justify-center gap-2 rounded-xl border border-stone-700 bg-stone-800 p-3 text-xs font-medium text-stone-200 hover:bg-stone-700"
            >
              <span>WhatsApp</span>
            </a>
            <a
              href={`mailto:?subject=${encodeURIComponent(`A vinyl voice note: ${recording.title}`)}&body=${encodeURIComponent(
                `I pressed a voice note for you:\n\n${typeof window !== 'undefined' ? window.location.href : ''}`
              )}`}
              className="flex items-center justify-center gap-2 rounded-xl border border-stone-700 bg-stone-800 p-3 text-xs font-medium text-stone-200 hover:bg-stone-700"
            >
              <span>Email</span>
            </a>
          </div>

          <div className="flex items-center justify-between border-t border-stone-800 pt-4">
            <div>
              <p className="text-xs font-serif font-bold text-stone-200">Mastered audio</p>
              <p className="font-mono text-[10px] text-stone-400">
                192 kbps MP3 • voice + {filterConfig.name} + ambience
              </p>
            </div>
            <a href={recording.processed_audio_url} download={`${recording.slug || 'vynyl'}.mp3`}>
              <Button variant="outline" size="sm" leftIcon={<Download className="h-4 w-4 text-amber-400" />}>
                Download MP3
              </Button>
            </a>
          </div>
        </div>
      </Modal>

      <Footer />
    </div>
  );
}
