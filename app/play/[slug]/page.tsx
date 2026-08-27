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
  Mic,
  Maximize2,
  Minimize2,
  Flame,
  Sofa,
  Music,
  Sparkles,
} from 'lucide-react';
import toast from 'react-hot-toast';

// Cozy Gramophone Room - SSR Safe
const CozyGramophoneRoom = dynamic(
  () => import('@/components/3d/CozyGramophoneRoom'),
  {
    ssr: false,
    loading: () => (
      <div className="w-full h-full min-h-[560px] flex flex-col items-center justify-center bg-gradient-to-b from-[#1c1917] to-[#0c0a09] rounded-3xl border border-amber-900/30">
        <div className="w-20 h-20 rounded-full bg-amber-950/50 border border-amber-600/30 flex items-center justify-center animate-pulse">
          <Disc3 className="w-10 h-10 text-amber-500 animate-spin" />
        </div>
        <span className="text-xs font-mono text-amber-300 mt-5 tracking-widest uppercase">
          Building Cozy Listening Room...
        </span>
        <span className="text-[10px] font-serif text-stone-400 mt-2">
          Polishing brass horn • Lighting fireplace • Laying Persian rug
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

  const [shareModalOpen, setShareModalOpen] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);
  const [isFullscreen, setIsFullscreen] = useState<boolean>(false);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const needleDropAudioRef = useRef<HTMLAudioElement | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const needleDropTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const [isNeedleDropping, setIsNeedleDropping] = useState<boolean>(false);

  const NEEDLE_DROP_DELAY_MS = 1150;

  const clearNeedleDropTimeout = () => {
    if (needleDropTimeoutRef.current) {
      clearTimeout(needleDropTimeoutRef.current);
      needleDropTimeoutRef.current = null;
    }
  };

  useEffect(() => {
    return () => clearNeedleDropTimeout();
  }, []);

  useEffect(() => {
    if (!slug) return;
    setLoading(true);
    fetch(`/api/play/${slug}`)
      .then((res) => {
        if (!res.ok) throw new Error('Not found');
        return res.json();
      })
      .then((data) => {
        if (data.recording) setRecording(data.recording);
        else {
          const fallback = DEMO_RECORDINGS.find((r) => r.slug.toLowerCase() === slug.toLowerCase()) || DEMO_RECORDINGS[0];
          setRecording(fallback);
        }
      })
      .catch(() => {
        const fallback = DEMO_RECORDINGS.find((r) => r.slug.toLowerCase() === slug.toLowerCase()) || DEMO_RECORDINGS[0];
        setRecording(fallback);
      })
      .finally(() => setLoading(false));
  }, [slug]);

  const triggerNeedleDropThenPlayMain = (fromTime: number = 0) => {
    if (!audioRef.current) return;
    clearNeedleDropTimeout();
    audioRef.current.currentTime = fromTime;
    setCurrentTime(fromTime);
    audioRef.current.volume = isMuted ? 0 : volume;

    if (needleDropAudioRef.current) {
      try {
        needleDropAudioRef.current.pause();
      } catch {}
      needleDropAudioRef.current.currentTime = 0;
      needleDropAudioRef.current.volume = isMuted ? 0 : Math.min(1, volume + 0.05);
      needleDropAudioRef.current.play().catch(() => {});
    }

    setIsNeedleDropping(true);
    setIsPlaying(true);

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

    const audioCurrent = audioRef.current.currentTime;
    const atBeginning = audioCurrent < 0.7 || currentTime < 0.7;
    const atEnd = duration > 0 && audioCurrent >= duration - 0.35;

    if (atBeginning || atEnd) {
      triggerNeedleDropThenPlayMain(0);
    } else {
      audioRef.current.volume = isMuted ? 0 : volume;
      audioRef.current.play().catch((err) => console.warn('Audio play error:', err));
      setIsPlaying(true);
    }
  };

  const handleTimeUpdate = () => {
    if (audioRef.current) setCurrentTime(audioRef.current.currentTime);
  };
  const handleLoadedMetadata = () => {
    if (audioRef.current) setDuration(audioRef.current.duration || 10);
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const target = parseFloat(e.target.value);
    if (!audioRef.current) return;
    if (target < 0.5 && (isPlaying || isNeedleDropping)) {
      triggerNeedleDropThenPlayMain(0);
      return;
    }
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
    if (startTime < 0.6) {
      triggerNeedleDropThenPlayMain(startTime);
      return;
    }
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
    triggerNeedleDropThenPlayMain(0);
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseFloat(e.target.value);
    setVolume(val);
    if (audioRef.current) audioRef.current.volume = val;
    if (needleDropAudioRef.current) needleDropAudioRef.current.volume = Math.min(1, val + 0.05);
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
          <div className="w-16 h-16 rounded-full bg-amber-950/50 border border-amber-700/30 flex items-center justify-center">
            <Disc3 className="w-8 h-8 text-amber-500 animate-spin" />
          </div>
          <p className="text-sm font-serif text-amber-200">Opening the vintage listening room...</p>
          <p className="text-xs font-mono text-stone-500">Dusting gramophone horn • Stoking fireplace</p>
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

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
        {/* Cozy Header - Vintage Listening Room */}
        <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-4 border-b border-amber-900/30 pb-6">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-950/60 border border-amber-600/30 text-amber-300 text-xs font-mono">
                <Flame className="w-3.5 h-3.5 text-orange-400" />
                <span>Cozy Vintage Room • Fireplace Lit</span>
              </span>
              <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-stone-700 text-stone-300 text-xs font-mono">
                <Sofa className="w-3.5 h-3.5 text-amber-400" />
                <span>Interactive 3D • Drag to Look Around</span>
              </span>
              {isNeedleDropping && (
                <span className="px-3 py-1 rounded-full bg-amber-600 text-stone-950 font-bold font-mono text-xs animate-pulse border border-amber-300">
                  ● Needle Dropping...
                </span>
              )}
            </div>
            <div>
              <h1 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100 tracking-tight">
                {recording.title}
              </h1>
              <div className="flex items-center gap-3 mt-2 text-xs font-mono text-stone-400">
                <span className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                  {recording.views} plays in this room
                </span>
                <span>•</span>
                <span className="text-amber-300/80">{styleConfig.name} • {filterConfig.name}</span>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" onClick={toggleFullscreen} leftIcon={isFullscreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}>
              {isFullscreen ? 'Exit' : 'Cozy Fullscreen'}
            </Button>
            <Button variant="secondary" size="sm" onClick={() => setShareModalOpen(true)} leftIcon={<Share2 className="w-4 h-4 text-amber-400" />}>
              Share Memory
            </Button>
            <Link href="/studio">
              <Button variant="primary" size="sm" leftIcon={<Mic className="w-4 h-4 text-stone-950" />}>
                Record Yours
              </Button>
            </Link>
          </div>
        </div>

        {/* Cozy Room + Parchment Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-stretch">
          {/* Cozy 3D Room - 8 cols */}
          <div className="lg:col-span-8 flex flex-col gap-4">
            <div className="relative rounded-3xl overflow-hidden border border-amber-900/30 shadow-2xl bg-stone-950">
              {/* Room info overlay top */}
              <div className="absolute top-0 left-0 right-0 z-10 p-4 flex items-center justify-between pointer-events-none">
                <div className="flex items-center gap-2">
                  <span className="px-3 py-1 rounded-full bg-stone-950/80 backdrop-blur-md border border-amber-500/30 text-amber-300 font-mono text-xs">
                    <span className="inline-flex items-center gap-1.5">
                      <Music className="w-3 h-3" />
                      {styleConfig.name}
                    </span>
                  </span>
                  <span className="px-3 py-1 rounded-full bg-stone-950/80 backdrop-blur-md border border-stone-700 text-stone-300 font-mono text-xs hidden sm:inline-flex">
                    78 RPM Gramophone • Brass Horn
                  </span>
                </div>
                <span className="px-3 py-1 rounded-full bg-stone-950/80 backdrop-blur-md border border-stone-800 text-stone-400 font-mono text-[11px]">
                  Drag • Scroll to Zoom • Cozy Room
                </span>
              </div>

              {/* 3D Room */}
              <Suspense fallback={<div className="h-[620px] flex items-center justify-center"><Disc3 className="w-10 h-10 text-amber-500 animate-spin" /></div>}>
                <CozyGramophoneRoom
                  isPlaying={isPlaying}
                  isNeedleDropping={isNeedleDropping}
                  vinylStyle={recording.vinyl_style}
                  title={recording.title}
                  recipientName={recording.recipient_name || undefined}
                  senderName={recording.sender_name || undefined}
                />
              </Suspense>

              {/* Cozy bottom vignette with controls */}
              <div className="absolute bottom-0 left-0 right-0 z-10 p-4 bg-gradient-to-t from-stone-950/90 via-stone-950/50 to-transparent">
                <div className="flex flex-col gap-3">
                  {/* Progress */}
                  <div className="space-y-1.5">
                    <div className="flex items-center gap-2">
                      <input
                        type="range"
                        min={0}
                        max={duration || 10}
                        step={0.1}
                        value={currentTime}
                        onChange={handleSeek}
                        className="flex-1 h-1.5 bg-stone-800/80 rounded-lg appearance-none cursor-pointer accent-amber-500 backdrop-blur-sm"
                      />
                    </div>
                    <div className="flex justify-between items-center text-xs font-mono text-stone-400">
                      <span className="text-amber-300 font-bold">{formatTime(currentTime)}</span>
                      <span className="flex items-center gap-2">
                        {isNeedleDropping && <span className="text-amber-300 animate-pulse">Lowering brass needle → {NEEDLE_DROP_DELAY_MS}ms to voice...</span>}
                        <span>{formatTime(duration || recording.duration_seconds || 10)}</span>
                      </span>
                    </div>
                  </div>

                  {/* Play controls */}
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <button
                        onClick={togglePlay}
                        className="w-14 h-14 rounded-2xl bg-gradient-to-br from-amber-500 to-amber-700 text-stone-950 flex items-center justify-center hover:scale-105 active:scale-95 transition-all shadow-xl shadow-amber-950/60 border border-amber-300/40"
                        title={isNeedleDropping ? 'Needle Dropping — Click to Cancel' : isPlaying ? 'Pause Gramophone' : 'Play on Gramophone'}
                      >
                        {isNeedleDropping ? <Disc3 className="w-6 h-6 animate-spin" /> : isPlaying ? <Pause className="w-6 h-6 fill-stone-950" /> : <Play className="w-6 h-6 fill-stone-950 ml-1" />}
                      </button>
                      <button
                        onClick={handleRestart}
                        className="p-3 rounded-xl bg-stone-900/80 backdrop-blur-md hover:bg-stone-800 text-stone-300 hover:text-amber-300 transition-colors border border-stone-700"
                        title="Restart with Needle Drop"
                      >
                        <RotateCcw className="w-4 h-4" />
                      </button>
                      <div className="hidden sm:flex flex-col ml-1">
                        <span className="text-xs font-serif font-bold text-amber-100">
                          {isNeedleDropping ? 'Brass Horn Lowering...' : isPlaying ? 'Gramophone Playing' : 'Gramophone Ready'}
                        </span>
                        <span className="text-[10px] font-mono text-stone-400">
                          {isNeedleDropping ? 'Authentic 1920s ritual' : `${filterConfig.name} • ${styleConfig.name}`}
                        </span>
                      </div>
                    </div>

                    <div className="flex items-center gap-3 bg-stone-900/60 backdrop-blur-md px-3 py-2 rounded-xl border border-stone-800">
                      <button onClick={toggleMute} className="text-stone-400 hover:text-amber-300 transition-colors">
                        {isMuted || volume === 0 ? <VolumeX className="w-5 h-5" /> : <Volume2 className="w-5 h-5" />}
                      </button>
                      <input type="range" min={0} max={1} step={0.05} value={isMuted ? 0 : volume} onChange={handleVolumeChange} className="w-20 sm:w-28 h-1.5 bg-stone-800 rounded-lg appearance-none cursor-pointer accent-amber-500" />
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Cozy room description */}
            <div className="p-4 rounded-2xl bg-stone-900/60 border border-amber-900/20 flex items-start gap-3">
              <div className="w-8 h-8 rounded-full bg-amber-950/50 border border-amber-700/30 flex items-center justify-center flex-shrink-0 mt-0.5">
                <Sparkles className="w-4 h-4 text-amber-400" />
              </div>
              <div className="space-y-1">
                <p className="text-sm font-serif text-stone-200">
                  Welcome to the <span className="text-amber-300 font-bold">Cozy Vintage Listening Room</span> — a warm 1920s study with crackling fireplace, Persian rug, and a fully interactive brass gramophone.
                </p>
                <p className="text-xs text-stone-400 leading-relaxed">
                  Drag to look around, scroll to zoom, click the big amber button to lower the brass needle. Every playback includes authentic needle-drop SFX, a dramatic {NEEDLE_DROP_DELAY_MS}ms pause, then your voice with vintage crackle and background atmosphere baked in — same every time.
                </p>
              </div>
            </div>
          </div>

          {/* Parchment Lyrics - 4 cols */}
          <div className="lg:col-span-4 flex flex-col min-h-[520px]">
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

      {/* Hidden Audio - main is identical every time (BG + crackle baked), needle drop separate */}
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
      <audio ref={needleDropAudioRef} src="/audio/needle-drop.mp3" preload="auto" className="hidden" />

      <Modal isOpen={shareModalOpen} onClose={() => setShareModalOpen(false)} title="Share from the Listening Room" subtitle="Send this cozy gramophone link" maxWidth="md">
        <div className="space-y-6 pt-2">
          <div className="space-y-2">
            <label className="text-xs font-mono text-stone-400 block">Permanent Gramophone Room Link</label>
            <div className="flex items-center gap-2">
              <input type="text" readOnly value={typeof window !== 'undefined' ? window.location.href : ''} className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs font-mono text-amber-200" />
              <Button variant="primary" size="sm" onClick={handleCopyLink} leftIcon={copiedLink ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}>
                {copiedLink ? 'Copied' : 'Copy'}
              </Button>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <a href={`https://wa.me/?text=${encodeURIComponent(`Listen to this vintage gramophone message in a cozy room: ${typeof window !== 'undefined' ? window.location.href : ''}`)}`} target="_blank" rel="noopener noreferrer" className="p-3 rounded-xl bg-stone-800 hover:bg-stone-700 border border-stone-700 flex items-center justify-center gap-2 text-xs font-medium text-stone-200">
              <span>WhatsApp</span>
            </a>
            <a href={`mailto:?subject=${encodeURIComponent(`A gramophone note: ${recording.title}`)}&body=${encodeURIComponent(`I preserved a voice note in a cozy vintage room for you:\n\n${typeof window !== 'undefined' ? window.location.href : ''}`)}`} className="p-3 rounded-xl bg-stone-800 hover:bg-stone-700 border border-stone-700 flex items-center justify-center gap-2 text-xs font-medium text-stone-200">
              <span>Email</span>
            </a>
          </div>
          <div className="pt-4 border-t border-stone-800 flex items-center justify-between">
            <div>
              <p className="text-xs font-serif font-bold text-stone-200">Master Audio File</p>
              <p className="text-[10px] text-stone-400 font-mono">192kbps with BG + crackle baked in</p>
            </div>
            <a href={recording.processed_audio_url} download={`${recording.slug}.mp3`}>
              <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4 text-amber-400" />}>Download MP3</Button>
            </a>
          </div>
        </div>
      </Modal>

      <Footer />
    </div>
  );
}
