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
import { AnalogMixerControls } from '@/components/studio/AnalogMixerControls';
import { VinylStyleSelector } from '@/components/studio/VinylStyleSelector';
import { LatheProcessingModal } from '@/components/studio/LatheProcessingModal';
import { StripeUpgradeModal } from '@/components/studio/StripeUpgradeModal';
import { Button } from '@/components/ui/Button';
import { 
  FilterPresetType,
  VinylStyleType,
  AudioAsset,
  PricingPlan,
  OccasionType,
  OCCASIONS,
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
  const [occasion, setOccasion] = useState<OccasionType>('something_else');
  const [dedication, setDedication] = useState('');
  const [occasionDate, setOccasionDate] = useState('');
  const [sideALabel, setSideALabel] = useState('Side A · Your voice');
  const [sideBLabel, setSideBLabel] = useState('Side B · The rest of the story');
  const [senderName, setSenderName] = useState<string>('');

  // Audio & Vinyl Settings
  const [filterPreset, setFilterPreset] = useState<FilterPresetType>('gramophone');
  const [selectedBgMusicId, setSelectedBgMusicId] = useState<string | null>('a2222222-2222-2222-2222-222222222222');
  const [crackleIntensity, setCrackleIntensity] = useState<number>(0.15);
  const [crackleAssetId, setCrackleAssetId] = useState<string | null>(null);
  const [bgMusicVolume, setBgMusicVolume] = useState<number>(0.3);
  const [hissIntensity, setHissIntensity] = useState<number>(0.08);
  const [rumbleIntensity, setRumbleIntensity] = useState<number>(0.05);
  const [soundEffectId, setSoundEffectId] = useState<string | null>('a6666666-6666-6666-6666-666666666666');
  const [soundEffectVolume, setSoundEffectVolume] = useState<number>(0.6);
  const [musicClarity, setMusicClarity] = useState<number>(0.75);
  const [crackleBrightness, setCrackleBrightness] = useState<number>(0.65);
  const [voiceWarmth, setVoiceWarmth] = useState<number>(0.45);
  const [voicePresence, setVoicePresence] = useState<number>(0.5);
  const [wowFlutter, setWowFlutter] = useState<number>(0.12);
  const [introDelay, setIntroDelay] = useState<number>(1.15);
  const [vinylStyle, setVinylStyle] = useState<VinylStyleType>('classic_red');

  // Dynamic Data & Plans
  const [audioAssets, setAudioAssets] = useState<AudioAsset[]>(DEFAULT_AUDIO_ASSETS);
  const [pricingPlans, setPricingPlans] = useState<PricingPlan[]>(DEFAULT_PRICING_PLANS);
  const [activePlanId, setActivePlanId] = useState<string | null>(null);

  // Modals & Lathe Processing
  const [isProcessingModalOpen, setIsProcessingModalOpen] = useState<boolean>(false);
  const [latheStepIndex, setLatheStepIndex] = useState<number>(0);
  const [isUpgradeModalOpen, setIsUpgradeModalOpen] = useState<boolean>(false);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  // Restore the selected tier and verify completed checkout redirects.
  useEffect(() => {
    const storedPlanId = window.localStorage.getItem('vynyl_active_plan_id');
    if (storedPlanId) {
      setActivePlanId(storedPlanId);
    }

    const sessionId = searchParams.get('session_id');
    const planId = searchParams.get('plan');
    if (!sessionId || !planId) return;

    fetch(`/api/checkout?session_id=${encodeURIComponent(sessionId)}&plan=${encodeURIComponent(planId)}`)
      .then(async (res) => {
        const data = await res.json();
        if (!res.ok || !data.verified) throw new Error(data.error || 'Payment verification failed');
        setActivePlanId(data.planId);
        window.localStorage.setItem('vynyl_active_plan_id', data.planId);
        toast.success('🎉 Gold Master tier unlocked!');
        confetti({ particleCount: 80, spread: 70, origin: { y: 0.6 } });
        router.replace('/studio');
      })
      .catch((error) => toast.error(error.message));
  }, [router, searchParams]);

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

  const currentPlan = pricingPlans.find((p) => p.id === activePlanId) || pricingPlans.find((p) => p.price_cents === 0) || pricingPlans[0];
  const isPremium = Boolean(currentPlan && currentPlan.price_cents > 0);
  const maxDuration = currentPlan?.max_duration_seconds || 60;
  const isOverDurationLimit = !isPremium && recordedDuration > maxDuration;

  // Submit to the direct-upload + async processing pipeline. The browser
  // uploads bytes directly to R2 (or the signed local fallback), then this
  // lightweight request creates the durable job. No FFmpeg runs in Vercel.
  const handleSubmitAndPressWax = async () => {
    if (!audioBlob) {
      toast.error('Please record or upload your voice note first.');
      return;
    }
    if (!title.trim()) {
      toast.error('Please give your memory a title.');
      return;
    }

    let pollTimer: ReturnType<typeof setInterval> | null = null;
    try {
      setIsSubmitting(true);
      setIsProcessingModalOpen(true);
      setLatheStepIndex(0);

      const recordId = crypto.randomUUID();
      const sourceName = audioBlob instanceof File
        ? audioBlob.name
        : audioBlob.type.includes('mp4') ? 'voice.m4a' : 'voice.webm';
      const contentType = audioBlob.type || (sourceName.endsWith('.m4a') ? 'audio/mp4' : 'audio/webm');

      // 1. Request a one-time upload capability. The response contains no R2
      // credentials and is scoped to this record id + file type + max bytes.
      const intentRes = await fetch('/api/audio/upload-url', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          recordId,
          filename: sourceName,
          contentType,
          size: audioBlob.size,
        }),
      });
      const intent = await intentRes.json();
      if (!intentRes.ok) throw new Error(intent.error || 'Could not prepare the upload.');

      setLatheStepIndex(1);

      // 2. Upload the bytes directly. R2 mode uses a presigned PUT; local mode
      // uses the same PUT contract against the signed confirmation route.
      const uploadRes = await fetch(intent.uploadUrl, {
        method: 'PUT',
        headers: intent.headers,
        body: audioBlob,
      });
      const uploaded = await uploadRes.json().catch(() => ({}));
      if (!uploadRes.ok) throw new Error(uploaded.error || 'The recording upload failed.');

      setLatheStepIndex(2);

      // 3. Create the lightweight DB row + durable audio_master job.
      const queueRes = await fetch('/api/audio/process', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          recordId,
          originalStorageKey: intent.key,
          originalUrl: uploaded.url || null,
          originalContentType: contentType,
          title: title.trim(),
          recipientName: recipientName.trim(),
          senderName: senderName.trim(),
          occasion,
          filterPreset,
          crackleIntensity,
          bgMusicId: selectedBgMusicId || 'none',
          vinylStyle,
          maxSeconds: maxDuration + 5,
          durationSeconds: recordedDuration,
          dedication: dedication.trim(),
          occasionDate,
          sideALabel: sideALabel.trim(),
          sideBLabel: sideBLabel.trim(),
        }),
      });
      const queued = await queueRes.json();
      if (!queueRes.ok) throw new Error(queued.error || 'Could not queue your record.');

      setLatheStepIndex(3);
      toast.success('Your record is being pressed…', { duration: 4000 });

      // 4. Poll only tiny JSON status. The worker updates the row; when it
      // completes we navigate to the finished share page.
      pollTimer = setInterval(async () => {
        try {
          const statusRes = await fetch(`/api/processing/status/${encodeURIComponent(recordId)}`, { cache: 'no-store' });
          const status = await statusRes.json();
          const state = status?.recording?.state;
          if (state === 'completed') {
            if (pollTimer) clearInterval(pollTimer);
            setIsProcessingModalOpen(false);
            setIsSubmitting(false);
            confetti({ particleCount: 100, spread: 80, origin: { y: 0.5 } });
            router.push(`/play/${queued.slug}`);
          } else if (state === 'failed') {
            if (pollTimer) clearInterval(pollTimer);
            setIsProcessingModalOpen(false);
            setIsSubmitting(false);
            toast.error(status?.recording?.error || 'The press failed. You can try again.');
          }
        } catch {
          // A transient poll failure should not cancel a durable job.
        }
      }, 2500);
    } catch (err: any) {
      if (pollTimer) clearInterval(pollTimer);
      setIsProcessingModalOpen(false);
      setIsSubmitting(false);
      toast.error(err?.message || 'Could not press the record.');
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
              <span>Studio · Realistic 3D Turntable</span>
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
                <label className="block text-xs font-serif text-stone-300 mb-2 font-semibold">
                  What are you creating?
                </label>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
                  {OCCASIONS.map((item) => (
                    <button
                      type="button"
                      key={item.id}
                      onClick={() => setOccasion(item.id)}
                      className={`rounded-xl border px-2 py-2 text-left text-[11px] transition-colors ${
                        occasion === item.id
                          ? 'border-amber-500 bg-amber-950/60 text-amber-200'
                          : 'border-stone-700 bg-stone-950 text-stone-400 hover:border-amber-700/60 hover:text-amber-200'
                      }`}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
                <p className="mt-2 text-xs italic text-stone-500">
                  {OCCASIONS.find((item) => item.id === occasion)?.prompt}
                </p>
              </div>

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

              <div>
                <label className="block text-xs font-serif text-stone-300 mb-1.5 font-semibold">Dedication (optional)</label>
                <textarea value={dedication} onChange={(e) => setDedication(e.target.value)} rows={2} maxLength={1000} placeholder="A line they can read before the needle drops…" className="w-full resize-none rounded-xl border border-stone-700 bg-stone-950 px-4 py-2.5 text-sm text-stone-100 placeholder-stone-600 focus:border-amber-500 focus:outline-none focus:ring-1 focus:ring-amber-500" />
              </div>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <div>
                  <label className="block text-xs font-serif text-stone-300 mb-1.5 font-semibold">Date</label>
                  <input type="date" value={occasionDate} onChange={(e) => setOccasionDate(e.target.value)} className="w-full rounded-xl border border-stone-700 bg-stone-950 px-3 py-2.5 text-sm text-stone-100 focus:border-amber-500 focus:outline-none" />
                </div>
                <div>
                  <label className="block text-xs font-serif text-stone-300 mb-1.5 font-semibold">Side A label</label>
                  <input value={sideALabel} onChange={(e) => setSideALabel(e.target.value)} maxLength={80} className="w-full rounded-xl border border-stone-700 bg-stone-950 px-3 py-2.5 text-sm text-stone-100 focus:border-amber-500 focus:outline-none" />
                </div>
                <div>
                  <label className="block text-xs font-serif text-stone-300 mb-1.5 font-semibold">Side B label</label>
                  <input value={sideBLabel} onChange={(e) => setSideBLabel(e.target.value)} maxLength={80} className="w-full rounded-xl border border-stone-700 bg-stone-950 px-3 py-2.5 text-sm text-stone-100 focus:border-amber-500 focus:outline-none" />
                </div>
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
              allowedPresets={currentPlan?.allowed_filter_presets}
            />

            {/* 2. Background Atmosphere Selector */}
            <BackgroundMusicSelector
              assets={audioAssets}
              selectedBgMusicId={selectedBgMusicId}
              onChange={(id) => {
                setSelectedBgMusicId(id);
                const asset = audioAssets.find((item) => item.id === id);
                if (asset?.default_volume !== undefined) setBgMusicVolume(asset.default_volume);
              }}
              isPremium={isPremium}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
              allowedAssetIds={currentPlan?.allowed_bg_music_ids}
            />

            {/* 3. Vinyl Surface Noise & Crackle Slider */}
            <CrackleSlider
              intensity={crackleIntensity}
              onChange={setCrackleIntensity}
              canAdjust={isPremium || currentPlan?.can_adjust_crackle}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
            />

            <AnalogMixerControls
              assets={audioAssets}
              bgMusicVolume={bgMusicVolume}
              onBgMusicVolumeChange={setBgMusicVolume}
              crackleAssetId={crackleAssetId}
              onCrackleAssetChange={(id) => {
                setCrackleAssetId(id);
                const asset = audioAssets.find((item) => item.id === id);
                if (asset?.default_volume !== undefined) setCrackleIntensity(asset.default_volume);
              }}
              hissIntensity={hissIntensity}
              onHissChange={setHissIntensity}
              rumbleIntensity={rumbleIntensity}
              onRumbleChange={setRumbleIntensity}
              soundEffectId={soundEffectId}
              onSoundEffectChange={(id) => {
                setSoundEffectId(id);
                const asset = audioAssets.find((item) => item.id === id);
                if (asset?.default_volume !== undefined) setSoundEffectVolume(asset.default_volume);
              }}
              soundEffectVolume={soundEffectVolume}
              onSoundEffectVolumeChange={setSoundEffectVolume}
              musicClarity={musicClarity}
              onMusicClarityChange={setMusicClarity}
              crackleBrightness={crackleBrightness}
              onCrackleBrightnessChange={setCrackleBrightness}
              voiceWarmth={voiceWarmth}
              onVoiceWarmthChange={setVoiceWarmth}
              voicePresence={voicePresence}
              onVoicePresenceChange={setVoicePresence}
              wowFlutter={wowFlutter}
              onWowFlutterChange={setWowFlutter}
              introDelay={introDelay}
              onIntroDelayChange={setIntroDelay}
              isPremium={isPremium}
            />

            {/* 4. Vinyl Disc Edition / Color */}
            <VinylStyleSelector
              selectedStyle={vinylStyle}
              onChange={setVinylStyle}
              isPremium={isPremium}
              onTriggerUpgrade={() => setIsUpgradeModalOpen(true)}
              allowedStyles={currentPlan?.allowed_vinyl_styles}
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
        onSuccessUpgrade={(planId) => {
          setActivePlanId(planId);
          window.localStorage.setItem('vynyl_active_plan_id', planId);
        }}
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
