'use client';

import React, { useState, useEffect, Suspense } from 'react';
import dynamic from 'next/dynamic';
import { useRouter, useSearchParams } from 'next/navigation';
import { Navbar } from '@/components/ui/Navbar';
import { Footer } from '@/components/ui/Footer';
import { AudioRecorder } from '@/components/studio/AudioRecorder';
import { FilterSelector } from '@/components/studio/FilterSelector';
import { BackgroundMusicSelector } from '@/components/studio/BackgroundMusicSelector';
import { CrackleSlider } from '@/components/studio/CrackleSlider';
import { VinylStyleSelector } from '@/components/studio/VinylStyleSelector';
import { LatheProcessingModal } from '@/components/studio/LatheProcessingModal';
import { StripeUpgradeModal } from '@/components/studio/StripeUpgradeModal';
import { Button } from '@/components/ui/Button';
import { 
  FilterPresetType, 
  VinylStyleType, 
  AudioAsset, 
  PricingPlan 
} from '@/types';
import { 
  DEFAULT_AUDIO_ASSETS, 
  DEFAULT_PRICING_PLANS 
} from '@/lib/constants';
import { Disc3, Send, Crown, User, Heart, Mic2, Sparkles, Music2 } from 'lucide-react';
import toast from 'react-hot-toast';
import confetti from 'canvas-confetti';

const AnimeTurntablePlayer = dynamic(
  () => import('@/components/3d/AnimeTurntablePlayer').then((m) => m.AnimeTurntablePlayer),
  {
    ssr: false,
    loading: () => (
      <div className="flex h-full min-h-[300px] w-full flex-col items-center justify-center rounded-2xl border border-amber-900/20 bg-gradient-to-b from-[#ffd9b8] to-[#7c4f57]">
        <Disc3 className="h-8 w-8 animate-spin text-stone-900/60" />
        <span className="mt-3 font-mono text-[11px] uppercase tracking-[0.2em] text-stone-900/70">
          Warming up the turntable…
        </span>
      </div>
    ),
  }
);

function StudioContent() {
  const router = useRouter();
  const searchParams = useSearchParams();

  // Recorded Audio State
  const [audioBlob, setAudioBlob] = useState<Blob | null>(null);
  const [recordedDuration, setRecordedDuration] = useState<number>(0);
  const [recordingState, setRecordingState] = useState<'idle' | 'recording' | 'paused' | 'stopped'>('idle');

  // Metadata
  const [title, setTitle] = useState<string>('My Heartfelt Voice Note');
  const [recipientName, setRecipientName] = useState<string>('');
  const [senderName, setSenderName] = useState<string>('');

  // Audio & Vinyl Settings
  const [filterPreset, setFilterPreset] = useState<FilterPresetType>('gramophone');
  const [selectedBgMusicId, setSelectedBgMusicId] = useState<string | null>('a2222222-2222-2222-2222-222222222222');
  const [crackleIntensity, setCrackleIntensity] = useState<number>(0.22);
  const [vinylStyle, setVinylStyle] = useState<VinylStyleType>('classic_red');

  // Dynamic Data & Plans
  const [audioAssets, setAudioAssets] = useState<AudioAsset[]>(DEFAULT_AUDIO_ASSETS);
  const [pricingPlans, setPricingPlans] = useState<PricingPlan[]>(DEFAULT_PRICING_PLANS);
  const [isPremium, setIsPremium] = useState<boolean>(false);

  // Modals & Lathe Processing
  const [isProcessingModalOpen, setIsProcessingModalOpen] = useState<boolean>(false);
  const [latheStepIndex, setLatheStepIndex] = useState<number>(0);
  const [isUpgradeModalOpen, setIsUpgradeModalOpen] = useState<boolean>(false);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  // Check if user upgraded via URL param
  useEffect(() => {
    if (searchParams.get('upgraded') === 'true') {
      setIsPremium(true);
      toast.success('🎉 Gold Master tier unlocked! Extended minutes & all styles enabled.');
      confetti({ particleCount: 80, spread: 70, origin: { y: 0.6 } });
    }
  }, [searchParams]);

  // Load Audio Assets & Pricing Plans
  useEffect(() => {
    fetch('/api/audio/upload-asset')
      .then((res) => res.json())
      .then((data) => {
        if (data.assets && data.assets.length > 0) {
          setAudioAssets(data.assets);
        }
      })
      .catch(() => {});

    fetch('/api/pricing')
      .then((res) => res.json())
      .then((data) => {
        if (data.plans && data.plans.length > 0) {
          setPricingPlans(data.plans);
        }
      })
      .catch(() => {});
  }, []);

  const handleAudioReady = (blob: Blob, durationSec: number) => {
    setAudioBlob(blob);
    setRecordedDuration(durationSec);
    setRecordingState('stopped');
  };

  const handleClearAudio = () => {
    setAudioBlob(null);
    setRecordedDuration(0);
    setRecordingState('idle');
  };

  const handleRecordingStateChange = (state: 'idle' | 'recording' | 'paused' | 'stopped') => {
    setRecordingState(state);
  };

  const currentPlan = pricingPlans.find((p) => (isPremium ? p.price_cents > 0 : p.price_cents === 0)) || pricingPlans[0];
  const maxDuration = currentPlan?.max_duration_seconds || 60;
  const isOverDurationLimit = !isPremium && recordedDuration > maxDuration;

  // Submit to Server-Side Audio Engine
  const handleSubmitAndPressWax = async () => {
    if (!audioBlob) {
      toast.error('Please record or upload your voice note first.');
      return;
    }

    if (!title.trim()) {
      toast.error('Please give your memory a title.');
      return;
    }

    try {
      setIsSubmitting(true);
      setIsProcessingModalOpen(true);
      setLatheStepIndex(0);

      // Prepare payload
      const formData = new FormData();
      formData.append('audio', audioBlob, 'voice.webm');
      formData.append('title', title.trim());
      formData.append('recipientName', recipientName.trim());
      formData.append('senderName', senderName.trim());
      formData.append('filterPreset', filterPreset);
      formData.append('crackleIntensity', crackleIntensity.toString());
      formData.append('bgMusicId', selectedBgMusicId || 'none');
      formData.append('vinylStyle', vinylStyle);
      // Tell the engine the plan ceiling so a runaway upload can't be mastered.
      formData.append('maxSeconds', String(maxDuration + 5));

      // Progress animation simulation while waiting for API
      const stepInterval = setInterval(() => {
        setLatheStepIndex((prev) => {
          if (prev < 3) return prev + 1;
          return prev;
        });
      }, 1200);

      const res = await fetch('/api/audio/process', {
        method: 'POST',
        body: formData,
      });

      clearInterval(stepInterval);

      if (!res.ok) {
        const errorData = await res.json();
        throw new Error(errorData.error || 'Audio processing failed');
      }

      setLatheStepIndex(3);
      const data = await res.json();

      const engine = data?.engine;
      const engineNote = engine
        ? ` · ${engine.mix?.bgMusic ? 'ambience ' : ''}${engine.mix?.crackle ? 'crackle ' : ''}mixed, ${engine.durationSeconds?.toFixed?.(1) ?? '?'}s`
        : '';

      setTimeout(() => {
        setIsProcessingModalOpen(false);
        toast.success(`✨ Master wax pressed${engineNote}`, { duration: 5000 });
        confetti({ particleCount: 100, spread: 80, origin: { y: 0.5 } });
        router.push(`/play/${data.slug}`);
      }, 1000);
    } catch (err: any) {
      setIsProcessingModalOpen(false);
      setIsSubmitting(false);
      toast.error(err.message || 'Failed to press digital wax record.');
    }
  };

  return (
    <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
      {/* Studio Header */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6 border-b border-amber-900/30 pb-8">
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-950/60 border border-amber-600/30 text-amber-300 text-xs font-mono">
              <Music2 className="w-3.5 h-3.5 text-amber-400" />
              <span>Studio · 3D Anime Turntable</span>
            </span>
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-stone-700 text-stone-300 text-xs font-mono">
              <Sparkles className="w-3.5 h-3.5 text-amber-400" />
              <span>Single view · no full screen</span>
            </span>
          </div>
          <h1 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100 tracking-tight">
            The Sender Studio
          </h1>
          <p className="text-sm text-stone-400 max-w-2xl leading-relaxed">
            Record right here — the 3D turntable below stays put, nothing jumps to full screen. The press server mixes your voice, the vintage filter, background music and crackle into <span className="text-amber-300 font-bold">one gain-compensated MP3</span> so it lands around −26 dB instead of the buried-quiet −38 dB mixes of before.
          </p>
        </div>

        <div className="flex items-center gap-3">
          {isPremium ? (
            <div className="flex items-center gap-2 bg-gradient-to-r from-amber-600/20 to-amber-800/30 border border-amber-500/50 px-4 py-2 rounded-xl text-amber-300 text-xs font-mono">
              <Crown className="w-4 h-4 text-amber-400" />
              <span>Gold Master (10 Min)</span>
            </div>
          ) : (
            <Button variant="outline" size="md" onClick={() => setIsUpgradeModalOpen(true)} leftIcon={<Crown className="w-4 h-4 text-amber-400" />}>
              Upgrade
            </Button>
          )}
        </div>
      </div>

      {/* Live 3D preview — same component as the listening page, so what you
          see while recording is what the recipient gets. */}
      <div className="relative overflow-hidden rounded-3xl border border-amber-900/30 shadow-2xl">
        <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-center justify-between p-3">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-white/20 bg-black/35 px-3 py-1 font-mono text-[11px] text-amber-100">
            <Music2 className="h-3 w-3" /> live preview · {vinylStyle} · {filterPreset}
          </span>
          <span className="rounded-full border border-white/15 bg-black/30 px-3 py-1 font-mono text-[11px] text-stone-200">
            {recordingState === 'recording' ? 'platter spinning · recording' : 'drag to orbit'}
          </span>
        </div>
        <div className="h-[330px] w-full sm:h-[380px]">
          <Suspense
            fallback={<div className="flex h-full items-center justify-center bg-stone-950"><Disc3 className="h-8 w-8 animate-spin text-amber-500" /></div>}
          >
            <AnimeTurntablePlayer
              isPlaying={recordingState === 'recording'}
              isRecording={recordingState === 'recording'}
              vinylStyle={vinylStyle}
              title={title || 'Untitled Memory'}
              recipientName={recipientName || undefined}
              senderName={senderName || undefined}
              detail="low"
            />
          </Suspense>
        </div>
      </div>

      {/* Studio Workspace Layout */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
        {/* Left Column: Live Audio Capture & Dedication Details */}
        <div className="lg:col-span-7 space-y-6">
          {/* Step 1: Voice Note Capture Card */}
          <div className="p-6 rounded-3xl bg-stone-900/80 border border-amber-600/30 backdrop-blur-md shadow-2xl space-y-6">
            <div className="flex items-center justify-between border-b border-stone-800 pb-3">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 rounded-full bg-amber-600 text-stone-950 font-bold flex items-center justify-center text-xs">
                  1
                </div>
                <h3 className="font-serif font-bold text-lg text-amber-100">
                  Capture Voice Memory
                </h3>
              </div>
              <span className="text-xs text-stone-400 font-mono">
                {audioBlob ? 'Audio Locked' : 'Awaiting Input'}
              </span>
            </div>

            <AudioRecorder
              onAudioReady={handleAudioReady}
              onClearAudio={handleClearAudio}
              maxDurationSeconds={maxDuration}
              isPremium={isPremium}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
              onRecordingStateChange={handleRecordingStateChange}
            />
          </div>

          {/* Step 2: Memory Dedication & Engraving Card */}
          <div className="p-6 rounded-3xl bg-stone-900/80 border border-amber-600/30 backdrop-blur-md shadow-2xl space-y-4">
            <div className="flex items-center gap-2 border-b border-stone-800 pb-3">
              <div className="w-7 h-7 rounded-full bg-amber-600 text-stone-950 font-bold flex items-center justify-center text-xs">
                2
              </div>
              <h3 className="font-serif font-bold text-lg text-amber-100">
                Engrave Dedication on Wax
              </h3>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-serif text-stone-300 mb-1.5 font-semibold">
                  Memory Title (Engraved on Center Disc)
                </label>
                <input
                  type="text"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="e.g. Our 50th Anniversary Letter"
                  className="w-full bg-stone-950 border border-stone-700 focus:border-amber-500 rounded-xl px-4 py-2.5 text-sm text-stone-100 placeholder-stone-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                />
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-serif text-stone-300 mb-1.5 flex items-center gap-1.5 font-semibold">
                    <Heart className="w-3.5 h-3.5 text-amber-500" />
                    <span>Recipient Name</span>
                  </label>
                  <input
                    type="text"
                    value={recipientName}
                    onChange={(e) => setRecipientName(e.target.value)}
                    placeholder="e.g. Eleanor Vance"
                    className="w-full bg-stone-950 border border-stone-700 focus:border-amber-500 rounded-xl px-4 py-2.5 text-sm text-stone-100 placeholder-stone-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-serif text-stone-300 mb-1.5 flex items-center gap-1.5 font-semibold">
                    <User className="w-3.5 h-3.5 text-amber-500" />
                    <span>Sender Name</span>
                  </label>
                  <input
                    type="text"
                    value={senderName}
                    onChange={(e) => setSenderName(e.target.value)}
                    placeholder="e.g. Arthur"
                    className="w-full bg-stone-950 border border-stone-700 focus:border-amber-500 rounded-xl px-4 py-2.5 text-sm text-stone-100 placeholder-stone-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Right Column: Analog Audio Mastering & Customization */}
        <div className="lg:col-span-5 space-y-6">
          <div className="p-6 rounded-3xl bg-stone-900/80 border border-amber-600/30 backdrop-blur-md shadow-2xl space-y-6">
            <div className="flex items-center gap-2 border-b border-stone-800 pb-3">
              <div className="w-7 h-7 rounded-full bg-amber-600 text-stone-950 font-bold flex items-center justify-center text-xs">
                3
              </div>
              <h3 className="font-serif font-bold text-lg text-amber-100">
                Acoustic Mastering & Vinyl Style
              </h3>
            </div>

            {/* 1. Filter Preset Selector */}
            <FilterSelector
              selectedFilter={filterPreset}
              onChange={setFilterPreset}
              isPremium={isPremium}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
            />

            {/* 2. Background Atmosphere Selector */}
            <BackgroundMusicSelector
              assets={audioAssets}
              selectedBgMusicId={selectedBgMusicId}
              onChange={setSelectedBgMusicId}
              isPremium={isPremium}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
            />

            {/* 3. Vinyl Surface Noise & Crackle Slider */}
            <CrackleSlider
              intensity={crackleIntensity}
              onChange={setCrackleIntensity}
              canAdjust={isPremium || currentPlan?.can_adjust_crackle}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
            />

            {/* 4. Vinyl Disc Edition / Color */}
            <VinylStyleSelector
              selectedStyle={vinylStyle}
              onChange={setVinylStyle}
              isPremium={isPremium}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
            />

            {/* Submit & Press Action */}
            <div className="pt-4 border-t border-stone-800 space-y-3">
              {isOverDurationLimit ? (
                <Button
                  variant="primary"
                  size="lg"
                  onClick={() => setIsUpgradeModalOpen(true)}
                  className="w-full text-base"
                  leftIcon={<Crown className="w-5 h-5 text-stone-950" />}
                >
                  Unlock Duration to Press Record
                </Button>
              ) : (
                <Button
                  variant="primary"
                  size="lg"
                  onClick={handleSubmitAndPressWax}
                  disabled={!audioBlob || isSubmitting}
                  isLoading={isSubmitting}
                  className="w-full text-base"
                  leftIcon={<Disc3 className="w-5 h-5 text-stone-950 animate-spin-slow" />}
                  rightIcon={<Send className="w-4 h-4 ml-1" />}
                >
                  Press Digital Wax & Generate 3D Player
                </Button>
              )}

              <p className="text-center text-[11px] text-stone-500 font-mono">
                Synthesized in loss-free 192kbps MP3 with Whisper AI word timestamps.
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Lathe Animated Processing Modal */}
      <LatheProcessingModal
        isOpen={isProcessingModalOpen}
        onClose={() => setIsProcessingModalOpen(false)}
        statusIndex={latheStepIndex}
      />

      {/* Stripe Pricing Upgrade Modal */}
      <StripeUpgradeModal
        isOpen={isUpgradeModalOpen}
        onClose={() => setIsUpgradeModalOpen(false)}
        plans={pricingPlans}
        onSuccessUpgrade={() => setIsPremium(true)}
      />
    </main>
  );
}

export default function StudioPage() {
  return (
    <div className="min-h-screen bg-[#0c0a09] text-stone-100 flex flex-col selection:bg-amber-600 selection:text-white">
      <Navbar />
      <Suspense
        fallback={
          <div className="flex-1 flex items-center justify-center min-h-[400px]">
            <Disc3 className="w-10 h-10 text-amber-500 animate-spin" />
          </div>
        }
      >
        <StudioContent />
      </Suspense>
      <Footer />
    </div>
  );
}
