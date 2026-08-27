import React, { Suspense } from 'react';
import dynamic from 'next/dynamic';
import Link from 'next/link';
import { Navbar } from '@/components/ui/Navbar';
import { Footer } from '@/components/ui/Footer';
import { Button } from '@/components/ui/Button';
import { SoundExperienceBar } from '@/components/home/SoundExperienceBar';
import { MemoryShowcase } from '@/components/home/MemoryShowcase';
import { HowItWorks } from '@/components/home/HowItWorks';
import { PricingSection } from '@/components/home/PricingSection';
import { getSiteSettings, getPricingPlans } from '@/lib/db';
import { 
  Disc3, 
  Mic, 
  Sparkles, 
  Radio, 
  Feather, 
  Volume2, 
  ArrowRight,
  ShieldCheck,
  Heart,
  Flame,
  Sofa,
  Music,
  Home
} from 'lucide-react';

// Cozy Gramophone Room Hero - SSR Safe
const CozyGramophoneRoom = dynamic(
  () => import('@/components/3d/CozyGramophoneRoom'),
  {
    ssr: false,
    loading: () => (
      <div className="w-full h-full min-h-[520px] flex flex-col items-center justify-center bg-gradient-to-b from-stone-900/60 to-stone-950/80 rounded-3xl border border-amber-900/30">
        <div className="w-16 h-16 rounded-full bg-amber-950/50 border border-amber-600/30 flex items-center justify-center animate-pulse">
          <Disc3 className="w-8 h-8 text-amber-500 animate-spin" />
        </div>
        <span className="text-xs font-mono text-amber-300 mt-4 tracking-widest uppercase">
          Building Cozy Listening Room...
        </span>
        <span className="text-[10px] font-serif text-stone-500 mt-2 text-center px-4">
          Fireplace crackling • Brass horn polishing • Persian rug laying
        </span>
      </div>
    ),
  }
);

const FloatingVinylHero = dynamic(
  () => import('@/components/3d/FloatingVinylHero'),
  {
    ssr: false,
    loading: () => (
      <div className="w-full h-full min-h-[380px] flex flex-col items-center justify-center">
        <Disc3 className="w-12 h-12 text-amber-500 animate-spin" />
        <span className="text-xs font-mono text-amber-400/80 mt-3 tracking-widest uppercase">
          Loading 3D Digital Wax...
        </span>
      </div>
    ),
  }
);

export const revalidate = 0;

export default async function HomePage() {
  const settings = await getSiteSettings();
  const pricingPlans = await getPricingPlans();

  const heroCopy = settings.hero_copy;
  const faqs = settings.faqs || [];

  return (
    <div className="min-h-screen bg-[#0c0a09] text-stone-100 flex flex-col selection:bg-amber-600 selection:text-white">
      <Navbar />

      {/* Hero Section - Cozy Vintage Listening Room */}
      <section className="relative w-full pt-6 pb-16 lg:pt-12 lg:pb-24 overflow-hidden">
        {/* Warm cozy background glows */}
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-full max-w-7xl h-[800px] pointer-events-none">
          <div className="absolute top-20 left-1/4 w-[600px] h-[600px] bg-amber-600/10 rounded-full blur-[120px]" />
          <div className="absolute top-40 right-1/4 w-[500px] h-[500px] bg-orange-600/8 rounded-full blur-[100px]" />
          <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-[800px] h-[400px] bg-stone-800/20 rounded-full blur-[80px]" />
        </div>

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-center">
            {/* Left Content - 6 cols */}
            <div className="lg:col-span-6 space-y-7 text-left z-10">
              {/* Cozy badges */}
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-950/70 border border-amber-500/40 text-amber-300 text-xs font-mono uppercase tracking-wider">
                  <Home className="w-3.5 h-3.5 text-amber-400" />
                  <span>Cozy Vintage Room</span>
                </span>
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-amber-900/30 text-stone-300 text-xs font-mono uppercase tracking-wider">
                  <Music className="w-3.5 h-3.5 text-amber-400" />
                  <span>Brass Gramophone</span>
                </span>
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-stone-800 text-stone-300 text-xs font-mono uppercase tracking-wider">
                  <Flame className="w-3.5 h-3.5 text-orange-400" />
                  <span>Fireplace Lit • Interactive</span>
                </span>
              </div>

              <div className="space-y-4">
                <h1 className="text-4xl sm:text-5xl lg:text-[3.4rem] font-serif font-bold text-stone-100 tracking-tight leading-[1.1]">
                  {heroCopy.headline}
                </h1>
                <p className="text-base sm:text-lg text-stone-300/90 font-serif leading-relaxed max-w-xl">
                  {heroCopy.subheadline} Step into a warm 1920s study — fireplace crackling, brass gramophone horn glowing, Persian rug underfoot. Your voice preserved in digital wax, playable in a cozy interactive room.
                </p>
              </div>

              <div className="space-y-4 pt-2">
                <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-4">
                  <Link href="/studio">
                    <Button
                      variant="primary"
                      size="lg"
                      className="w-full sm:w-auto text-base px-8 py-4"
                      leftIcon={<Mic className="w-5 h-5 text-stone-950" />}
                      rightIcon={<ArrowRight className="w-4 h-4 ml-1" />}
                    >
                      {heroCopy.cta_text || 'Record Your Memory Now'}
                    </Button>
                  </Link>

                  <Link href="#experience">
                    <Button
                      variant="secondary"
                      size="lg"
                      className="w-full sm:w-auto text-base px-6 py-4"
                      leftIcon={<Volume2 className="w-4 h-4 text-amber-400" />}
                    >
                      Enter Listening Room
                    </Button>
                  </Link>
                </div>

                <div className="flex flex-wrap items-center gap-4 text-xs text-stone-400 pt-1 font-mono">
                  <span className="flex items-center gap-1.5">
                    <Sofa className="w-3.5 h-3.5 text-amber-500" />
                    <span>Cozy room • Drag to explore</span>
                  </span>
                  <span>•</span>
                  <span className="flex items-center gap-1.5">
                    <ShieldCheck className="w-3.5 h-3.5 text-emerald-500" />
                    <span>BG sounds + crackle baked in</span>
                  </span>
                </div>
              </div>

              {/* Mini features */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-4">
                <div className="p-3 rounded-xl bg-stone-900/60 border border-amber-900/20">
                  <div className="flex items-center gap-2 text-amber-400 mb-1">
                    <Disc3 className="w-4 h-4" />
                    <span className="text-xs font-mono uppercase">Needle Drop</span>
                  </div>
                  <p className="text-xs text-stone-400">Authentic brass needle drop every play + 1.1s dramatic pause</p>
                </div>
                <div className="p-3 rounded-xl bg-stone-900/60 border border-amber-900/20">
                  <div className="flex items-center gap-2 text-amber-400 mb-1">
                    <Radio className="w-4 h-4" />
                    <span className="text-xs font-mono uppercase">BG Atmosphere</span>
                  </div>
                  <p className="text-xs text-stone-400">Rain, accordion, guitar, cello mixed at 26% — always audible</p>
                </div>
                <div className="p-3 rounded-xl bg-stone-900/60 border border-amber-900/20">
                  <div className="flex items-center gap-2 text-amber-400 mb-1">
                    <Feather className="w-4 h-4" />
                    <span className="text-xs font-mono uppercase">Same Every Time</span>
                  </div>
                  <p className="text-xs text-stone-400">Mastered MP3 with crackle + BG baked, identical playback</p>
                </div>
              </div>
            </div>

            {/* Right - Cozy Room Hero - 6 cols */}
            <div className="lg:col-span-6 relative flex flex-col gap-4">
              <div className="w-full rounded-3xl overflow-hidden border border-amber-900/30 shadow-2xl bg-stone-950 relative">
                <div className="absolute top-0 left-0 right-0 z-10 p-3 flex items-center justify-between pointer-events-none">
                  <span className="px-2.5 py-1 rounded-full bg-stone-950/80 backdrop-blur-md border border-amber-500/30 text-amber-300 font-mono text-[10px] uppercase tracking-wider">
                    Interactive • Drag to Explore Room
                  </span>
                  <span className="px-2.5 py-1 rounded-full bg-stone-950/80 backdrop-blur-md border border-stone-700 text-stone-400 font-mono text-[10px]">
                    Fireplace • Rug • Gramophone
                  </span>
                </div>

                <div className="w-full h-[520px] sm:h-[600px]">
                  <Suspense fallback={<div className="h-full flex items-center justify-center"><Disc3 className="w-8 h-8 text-amber-500 animate-spin" /></div>}>
                    <CozyGramophoneRoom
                      isPlaying={false}
                      isNeedleDropping={false}
                      vinylStyle="gold_edition"
                      title="Our Anniversary"
                      recipientName="Eleanor"
                      senderName="Arthur"
                    />
                  </Suspense>
                </div>

                <div className="absolute bottom-0 left-0 right-0 p-3 bg-gradient-to-t from-stone-950/90 to-transparent pointer-events-none">
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] font-mono text-amber-300/80">Cozy Vintage Listening Room • 1920s Study</span>
                    <span className="text-[10px] font-mono text-stone-500">Scroll to zoom • Drag to orbit</span>
                  </div>
                </div>
              </div>

              {/* Small vinyl preview below room */}
              <div className="w-full rounded-2xl bg-stone-900/60 border border-amber-900/20 p-3 flex items-center gap-3">
                <div className="w-24 h-24 rounded-xl overflow-hidden border border-amber-600/20 flex-shrink-0">
                  <Suspense fallback={<div className="h-full flex items-center justify-center"><Disc3 className="w-6 h-6 text-amber-500 animate-spin" /></div>}>
                    <FloatingVinylHero />
                  </Suspense>
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-serif font-bold text-amber-100">Also includes classic turntable view</p>
                  <p className="text-[11px] text-stone-400 leading-relaxed mt-1">
                    Every record can be played in both the cozy gramophone room and the classic 3D turntable. Needle drop, crackle, and background atmosphere are baked into the mastered MP3 — identical every playback.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <SoundExperienceBar />
      <MemoryShowcase />
      <HowItWorks />
      <PricingSection plans={pricingPlans} />

      {faqs.length > 0 && (
        <section className="w-full py-20 border-t border-stone-800/80 bg-stone-950/50">
          <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 space-y-12">
            <div className="text-center space-y-3">
              <h3 className="text-2xl sm:text-3xl font-serif font-bold text-stone-100">
                Frequently Asked Questions
              </h3>
              <p className="text-xs sm:text-sm text-stone-400">
                Everything about the cozy listening room and vintage wax pressing.
              </p>
            </div>

            <div className="space-y-4">
              {faqs.map((faq, idx) => (
                <div
                  key={idx}
                  className="p-6 rounded-2xl bg-stone-900/70 border border-stone-800 space-y-2 hover:border-amber-600/30 transition-all"
                >
                  <h4 className="font-serif font-bold text-base text-amber-100">
                    {faq.q}
                  </h4>
                  <p className="text-xs sm:text-sm text-stone-400 leading-relaxed font-sans">
                    {faq.a}
                  </p>
                </div>
              ))}
            </div>
          </div>
        </section>
      )}

      <section className="w-full py-20 border-t border-stone-800 relative bg-gradient-to-b from-stone-950 to-[#120d09]">
        <div className="max-w-4xl mx-auto px-4 text-center space-y-6">
          <div className="w-16 h-16 rounded-full bg-stone-900 border border-amber-500/40 mx-auto flex items-center justify-center shadow-2xl">
            <Heart className="w-7 h-7 text-amber-400 fill-amber-400/20" />
          </div>
          <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
            A Voice Note That Will Never Be Forgotten
          </h2>
          <p className="text-sm sm:text-base text-stone-300 max-w-xl mx-auto leading-relaxed">
            Don&apos;t let precious words fade in chat apps. Press your voice into warm brass gramophone wax, with fireplace glow and background atmosphere — preserved forever in a cozy room.
          </p>
          <div className="pt-2">
            <Link href="/studio">
              <Button
                variant="primary"
                size="lg"
                className="px-10 py-4 text-base"
                leftIcon={<Mic className="w-5 h-5 text-stone-950" />}
              >
                Record Your Memory Now
              </Button>
            </Link>
          </div>
        </div>
      </section>

      <Footer />
    </div>
  );
}
