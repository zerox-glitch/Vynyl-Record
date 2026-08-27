'use client';

import React, { useState, useEffect, useRef, Suspense } from 'react';
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
  Sparkles,
  QrCode,
  Mic,
  Maximize2,
  Minimize2,
  Radio,
} from 'lucide-react';
import toast from 'react-hot-toast';
import confetti from 'canvas-confetti';

// SSR Safe Three.js Canvas Loading Directive
const TurntableScene = dynamic(
  () => import('@/components/3d/TurntableScene'),
  {
    ssr: false,
    loading: () => (
      <div className="w-full h-full min-h-[420px] flex flex-col items-center justify-center bg-stone-950/60 rounded-3xl border border-stone-800">
        <Disc3 className="w-12 h-12 text-amber-500 animate-spin" />
        <span className="text-xs font-mono text-amber-300 mt-4 tracking-widest uppercase">
          Initializing 3D Turntable Engine...
        </span>
      </div>
    ),
  }
);

export default function PlayRecordingPage() {
  const params = useParams();
  const slug = params?.slug as string;

  const [recording, setRecording] = useState<Recording | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [currentTime, setCurrentTime] = useState<number>(0);
  const [duration, setDuration] = useState<number>(0);
  const [volume, setVolume] = useState<number>(0.85);
  const [isMuted, setIsMuted] = useState<boolean>(false);

  // Modals & Share
  const [shareModalOpen, setShareModalOpen] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);
  const [isFullscreen, setIsFullscreen] = useState<boolean>(false);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const needleDropAudioRef = useRef<HTMLAudioElement | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const needleDropTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const [isNeedleDropping, setIsNeedleDropping] = useState<boolean>(false);

  const NEEDLE_DROP_DELAY_MS = 1150; // dramatic vinyl delay: needle drop SFX + brief silence before voice

  const clearNeedleDropTimeout = () => {
    if (needleDropTimeoutRef.current) {
      clearTimeout(needleDropTimeoutRef.current);
      needleDropTimeoutRef.current = null;
    }
  };

  useEffect(() => {
    return () => {
      clearNeedleDropTimeout();
    };
  }, []);

  // Fetch recording by slug
  useEffect(() => {
    if (!slug) return;

    setLoading(true);
    fetch(`/api/play/${slug}`)
      .then((res) => {
        if (!res.ok) throw new Error('Not found');
        return res.json();
      })
      .then((data) => {
        if (data.recording) {
          setRecording(data.recording);
        } else {
          // Fallback to demo recording matching slug
          const fallback = DEMO_RECORDINGS.find((r) => r.slug.toLowerCase() === slug.toLowerCase()) || DEMO_RECORDINGS[0];
          setRecording(fallback);
        }
      })
      .catch(() => {
        const fallback = DEMO_RECORDINGS.find((r) => r.slug.toLowerCase() === slug.toLowerCase()) || DEMO_RECORDINGS[0];
        setRecording(fallback);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [slug]);

  // Audio Playback Handlers — needle drop every time + dramatic delay
  const triggerNeedleDropThenPlayMain = (fromTime: number = 0) => {
    if (!audioRef.current) return;

    clearNeedleDropTimeout();

    // Reset main audio to requested start (usually 0)
    audioRef.current.currentTime = fromTime;
    setCurrentTime(fromTime);
    audioRef.current.volume = isMuted ? 0 : volume;

    // Always reset and play needle drop SFX
    if (needleDropAudioRef.current) {
      try {
        needleDropAudioRef.current.pause();
      } catch {}
      needleDropAudioRef.current.currentTime = 0;
      needleDropAudioRef.current.volume = isMuted ? 0 : Math.min(1, volume + 0.05);
      // Ensure it loads
      needleDropAudioRef.current.play().catch(() => {
        // Even if needle drop fails, continue to main after delay
      });
    }

    setIsNeedleDropping(true);
    setIsPlaying(true); // keeps turntable spinning + tonearm in playing position for dramatic effect

    needleDropTimeoutRef.current = setTimeout(() => {
      setIsNeedleDropping(false);
      if (audioRef.current) {
        audioRef.current.currentTime = fromTime;
        audioRef.current.volume = isMuted ? 0 : volume;
        audioRef.current.play().catch((err) => {
          console.warn('Main audio play error after needle drop:', err);
          setIsPlaying(false);
        });
      }
    }, NEEDLE_DROP_DELAY_MS);
  };

  const togglePlay = () => {
    if (!audioRef.current) return;

    // If currently playing or in needle-drop delay phase, pause everything
    if (isPlaying || isNeedleDropping) {
      clearNeedleDropTimeout();
      try {
        audioRef.current.pause();
      } catch {}
      if (needleDropAudioRef.current) {
        try {
          needleDropAudioRef.current.pause();
          needleDropAudioRef.current.currentTime = 0;
        } catch {}
      }
      setIsPlaying(false);
      setIsNeedleDropping(false);
      return;
    }

    // Determine if we are at the beginning / end — should always do needle drop in those cases
    const audioCurrent = audioRef.current.currentTime;
    const atBeginning = audioCurrent < 0.7 || currentTime < 0.7;
    const atEnd = duration > 0 && audioCurrent >= duration - 0.35;

    if (atBeginning || atEnd) {
      triggerNeedleDropThenPlayMain(0);
    } else {
      // Resume mid-track without needle drop
      audioRef.current.volume = isMuted ? 0 : volume;
      audioRef.current.play().catch((err) => {
        console.warn('Audio play error:', err);
      });
      setIsPlaying(true);
    }
  };

  const handleTimeUpdate = () => {
    if (audioRef.current) {
      setCurrentTime(audioRef.current.currentTime);
    }
  };

  const handleLoadedMetadata = () => {
    if (audioRef.current) {
      setDuration(audioRef.current.duration || 10);
    }
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const target = parseFloat(e.target.value);
    if (!audioRef.current) return;

    // If seeking to the very start while playing, re-trigger needle drop for authentic feel
    if (target < 0.5 && (isPlaying || isNeedleDropping)) {
      triggerNeedleDropThenPlayMain(0);
      return;
    }

    // If seeking to start while paused, just move head — next play will do needle drop
    clearNeedleDropTimeout();
    if (isNeedleDropping) {
      setIsNeedleDropping(false);
      if (needleDropAudioRef.current) {
        try {
          needleDropAudioRef.current.pause();
          needleDropAudioRef.current.currentTime = 0;
        } catch {}
      }
    }
    audioRef.current.currentTime = target;
    setCurrentTime(target);
  };

  const handleWordJump = (startTime: number) => {
    if (!audioRef.current) return;

    // If jumping to beginning, do full vinyl ritual with needle drop + delay
    if (startTime < 0.6) {
      triggerNeedleDropThenPlayMain(startTime);
      return;
    }

    // Mid-track jump — no needle drop, keep same mastered audio
    clearNeedleDropTimeout();
    setIsNeedleDropping(false);
    if (needleDropAudioRef.current) {
      try {
        needleDropAudioRef.current.pause();
        needleDropAudioRef.current.currentTime = 0;
      } catch {}
    }
    audioRef.current.currentTime = startTime;
    setCurrentTime(startTime);
    audioRef.current.volume = isMuted ? 0 : volume;
    audioRef.current.play().catch(() => {});
    setIsPlaying(true);
  };

  const handleRestart = () => {
    if (!audioRef.current) return;
    // Restart always does needle drop + delay + play from 0 for authentic vinyl ritual
    triggerNeedleDropThenPlayMain(0);
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseFloat(e.target.value);
    setVolume(val);
    if (audioRef.current) {
      audioRef.current.volume = val;
    }
    if (needleDropAudioRef.current) {
      needleDropAudioRef.current.volume = Math.min(1, val + 0.05);
    }
    if (val > 0) setIsMuted(false);
  };

  const toggleMute = () => {
    if (isMuted) {
      if (audioRef.current) audioRef.current.volume = volume;
      if (needleDropAudioRef.current) needleDropAudioRef.current.volume = Math.min(1, volume + 0.05);
      setIsMuted(false);
    } else {
      if (audioRef.current) audioRef.current.volume = 0;
      if (needleDropAudioRef.current) needleDropAudioRef.current.volume = 0;
      setIsMuted(true);
    }
  };

  const handleCopyLink = () => {
    const url = typeof window !== 'undefined' ? window.location.href : '';
    navigator.clipboard.writeText(url);
    setCopiedLink(true);
    toast.success('✨ Link copied to clipboard!');
    setTimeout(() => setCopiedLink(false), 2500);
  };

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen().catch(() => {});
      setIsFullscreen(false);
    }
  };

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const styleConfig = VINYL_STYLES.find((s) => s.id === recording?.vinyl_style) || VINYL_STYLES[0];
  const filterConfig = FILTER_PRESETS.find((f) => f.id === recording?.filter_preset) || FILTER_PRESETS[1];

  if (loading || !recording) {
    return (
      <div className="min-h-screen bg-[#0c0a09] flex flex-col justify-between text-stone-100">
        <Navbar />
        <div className="flex-1 flex flex-col items-center justify-center space-y-4">
          <Disc3 className="w-12 h-12 text-amber-500 animate-spin" />
          <p className="text-sm font-serif text-amber-200">Retrieving digital wax from archive...</p>
        </div>
        <Footer />
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="min-h-screen bg-[#0c0a09] text-stone-100 flex flex-col selection:bg-amber-600 selection:text-white"
    >
      <Navbar />

      {/* Main Experience Studio Canvas */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
        {/* Top Header Bar */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-stone-800 pb-6">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-xs font-mono uppercase tracking-widest text-amber-400">
                Live 3D Turntable Playback
              </span>
              <span className="text-stone-600">•</span>
              <span className="text-xs font-mono text-stone-400">
                {recording.views} Plays
              </span>
            </div>
            <h1 className="text-2xl sm:text-3xl font-serif font-bold text-stone-100">
              {recording.title}
            </h1>
          </div>

          {/* Quick Actions */}
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={toggleFullscreen}
              leftIcon={isFullscreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
            >
              {isFullscreen ? 'Exit Fullscreen' : 'Cinema Mode'}
            </Button>

            <Button
              variant="secondary"
              size="sm"
              onClick={() => setShareModalOpen(true)}
              leftIcon={<Share2 className="w-4 h-4 text-amber-400" />}
            >
              Share Memory
            </Button>

            <Link href="/studio">
              <Button
                variant="primary"
                size="sm"
                leftIcon={<Mic className="w-4 h-4 text-stone-950" />}
              >
                Record Yours
              </Button>
            </Link>
          </div>
        </div>

        {/* 3D Turntable & Synced Lyrics Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-stretch">
          {/* 3D Turntable Viewport (7 Cols) */}
          <div className="lg:col-span-7 flex flex-col rounded-3xl bg-stone-900/80 border border-amber-600/30 backdrop-blur-md shadow-2xl p-4 sm:p-6 relative overflow-hidden">
            {/* Ambient Spotlight Background */}
            <div className="absolute inset-0 bg-radial-amber opacity-40 pointer-events-none" />

            {/* Top Turntable Info Bar */}
            <div className="flex items-center justify-between text-xs z-10 mb-2">
              <div className="flex items-center gap-2">
                <span className="px-2.5 py-0.5 rounded-full bg-stone-800 border border-amber-500/30 text-amber-300 font-mono">
                  {styleConfig.name}
                </span>
                <span className="px-2.5 py-0.5 rounded-full bg-stone-800 border border-stone-700 text-stone-300 font-mono">
                  {filterConfig.name}
                </span>
                {isNeedleDropping && (
                  <span className="px-2.5 py-0.5 rounded-full bg-amber-600 text-stone-950 font-bold font-mono animate-pulse border border-amber-300">
                    ● Needle Dropping...
                  </span>
                )}
              </div>
              <span className="font-mono text-stone-400 text-[11px]">
                33⅓ RPM • Analog Master {isNeedleDropping ? '• Lowering Tonearm' : ''}
              </span>
            </div>

            {/* Interactive 3D Turntable Scene */}
            <div className="flex-1 w-full min-h-[380px] sm:min-h-[460px] relative">
              <Suspense
                fallback={
                  <div className="w-full h-full flex items-center justify-center">
                    <Disc3 className="w-10 h-10 text-amber-500 animate-spin" />
                  </div>
                }
              >
                <TurntableScene
                  isPlaying={isPlaying}
                  vinylStyle={recording.vinyl_style}
                  title={recording.title}
                  recipientName={recording.recipient_name || undefined}
                  senderName={recording.sender_name || undefined}
                />
              </Suspense>
            </div>

            {/* Master Playback Control Dashboard */}
            <div className="mt-4 pt-4 border-t border-stone-800/80 space-y-4 z-10">
              {/* Seeker Slider & Time */}
              <div className="space-y-1.5">
                <div className="relative flex items-center">
                  <input
                    type="range"
                    min={0}
                    max={duration || 10}
                    step={0.1}
                    value={currentTime}
                    onChange={handleSeek}
                    className="w-full h-1.5 bg-stone-800 rounded-lg appearance-none cursor-pointer accent-amber-500"
                  />
                </div>
                <div className="flex justify-between items-center text-xs font-mono text-stone-400">
                  <span className="text-amber-400 font-bold">{formatTime(currentTime)}</span>
                  <span>{formatTime(duration || recording.duration_seconds || 10)}</span>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  {/* Master Play Button — shows needle drop phase */}
                  <button
                    onClick={togglePlay}
                    className="w-14 h-14 rounded-2xl bg-gradient-to-br from-amber-500 to-amber-700 text-stone-950 flex items-center justify-center hover:scale-105 active:scale-95 transition-all shadow-xl shadow-amber-950/60 border border-amber-300/40"
                    title={
                      isNeedleDropping
                        ? 'Needle Dropping — Click to Cancel'
                        : isPlaying
                        ? 'Pause Turntable'
                        : 'Play Vinyl Note with Needle Drop'
                    }
                  >
                    {isNeedleDropping ? (
                      <Disc3 className="w-6 h-6 text-stone-950 animate-spin" />
                    ) : isPlaying ? (
                      <Pause className="w-6 h-6 fill-stone-950 stroke-[2.5]" />
                    ) : (
                      <Play className="w-6 h-6 fill-stone-950 stroke-[2.5] ml-1" />
                    )}
                  </button>

                  <button
                    onClick={handleRestart}
                    className="p-3 rounded-xl bg-stone-800 hover:bg-stone-700 text-stone-300 hover:text-amber-300 transition-colors border border-stone-700"
                    title="Restart with Needle Drop"
                  >
                    <RotateCcw className="w-4 h-4" />
                  </button>

                  {isNeedleDropping && (
                    <span className="text-[11px] font-mono text-amber-300 animate-pulse ml-1">
                      Lowering brass needle → {NEEDLE_DROP_DELAY_MS}ms to voice...
                    </span>
                  )}
                </div>

                {/* Volume & Mute Controls */}
                <div className="flex items-center gap-3">
                  <button
                    onClick={toggleMute}
                    className="text-stone-400 hover:text-amber-300 transition-colors"
                  >
                    {isMuted || volume === 0 ? (
                      <VolumeX className="w-5 h-5" />
                    ) : (
                      <Volume2 className="w-5 h-5" />
                    )}
                  </button>
                  <input
                    type="range"
                    min={0}
                    max={1}
                    step={0.05}
                    value={isMuted ? 0 : volume}
                    onChange={handleVolumeChange}
                    className="w-20 sm:w-28 h-1.5 bg-stone-800 rounded-lg appearance-none cursor-pointer accent-amber-500"
                  />
                </div>
              </div>
            </div>
          </div>

          {/* Synced Aged Parchment Lyric Card (5 Cols) */}
          <div className="lg:col-span-5 flex flex-col min-h-[460px]">
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

      {/* Hidden Audio Players — main stays identical every playback (crackle+bg baked), needle drop is separate SFX with delay */}
      <audio
        ref={audioRef}
        src={recording.processed_audio_url}
        preload="auto"
        onTimeUpdate={handleTimeUpdate}
        onLoadedMetadata={handleLoadedMetadata}
        onEnded={() => {
          clearNeedleDropTimeout();
          setIsPlaying(false);
          setIsNeedleDropping(false);
        }}
        className="hidden"
      />
      <audio
        ref={needleDropAudioRef}
        src="/audio/needle-drop.mp3"
        preload="auto"
        className="hidden"
      />

      {/* Share & Dedication Modal */}
      <Modal
        isOpen={shareModalOpen}
        onClose={() => setShareModalOpen(false)}
        title="Share Digital Vinyl Wax"
        subtitle="Send this timeless 3D player link to your loved one"
        maxWidth="md"
      >
        <div className="space-y-6 pt-2">
          {/* Share Link Input */}
          <div className="space-y-2">
            <label className="text-xs font-mono text-stone-400 block">
              Permanent 3D Player Link
            </label>
            <div className="flex items-center gap-2">
              <input
                type="text"
                readOnly
                value={typeof window !== 'undefined' ? window.location.href : ''}
                className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs font-mono text-amber-200 selection:bg-amber-600"
              />
              <Button
                variant="primary"
                size="sm"
                onClick={handleCopyLink}
                leftIcon={copiedLink ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
              >
                {copiedLink ? 'Copied' : 'Copy'}
              </Button>
            </div>
          </div>

          {/* Quick Share Links */}
          <div className="grid grid-cols-2 gap-3">
            <a
              href={`https://wa.me/?text=${encodeURIComponent(
                `Listen to this vintage 3D vinyl voice note I pressed for you: ${
                  typeof window !== 'undefined' ? window.location.href : ''
                }`
              )}`}
              target="_blank"
              rel="noopener noreferrer"
              className="p-3 rounded-xl bg-stone-800 hover:bg-stone-700 border border-stone-700 flex items-center justify-center gap-2 text-xs font-medium text-stone-200 transition-colors"
            >
              <span>Share to WhatsApp</span>
            </a>

            <a
              href={`mailto:?subject=${encodeURIComponent(
                `A vintage vinyl note for you: ${recording.title}`
              )}&body=${encodeURIComponent(
                `I preserved a voice note in 3D digital wax for you. Listen here:\n\n${
                  typeof window !== 'undefined' ? window.location.href : ''
                }`
              )}`}
              className="p-3 rounded-xl bg-stone-800 hover:bg-stone-700 border border-stone-700 flex items-center justify-center gap-2 text-xs font-medium text-stone-200 transition-colors"
            >
              <span>Send via Email</span>
            </a>
          </div>

          {/* Download Mastered MP3 */}
          <div className="pt-4 border-t border-stone-800 flex items-center justify-between">
            <div>
              <p className="text-xs font-serif font-bold text-stone-200">
                Lossless Master Audio File
              </p>
              <p className="text-[10px] text-stone-400 font-mono">
                192kbps Stereo MP3 with Gramophone Filters
              </p>
            </div>
            <a
              href={recording.processed_audio_url}
              download={`${recording.slug}.mp3`}
            >
              <Button
                variant="outline"
                size="sm"
                leftIcon={<Download className="w-4 h-4 text-amber-400" />}
              >
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
