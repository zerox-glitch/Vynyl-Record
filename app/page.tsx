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
  Music,
  Home
} from 'lucide-react';

// 3D anime turntable hero — client-only (WebGL), the single canvas of the page.
const AnimeTurntablePlayer = dynamic(
  () => import('@/components/3d/AnimeTurntablePlayer').then((m) => m.AnimeTurntablePlayer),
  {
    ssr: false,
    loading: () => (
      <div className="flex h-full w-full flex-col items-center justify-center bg-gradient-to-b from-[#ffd9b8] to-[#7c4f57]">
        <div className="w-16 h-16 rounded-full bg-black/25 border border-amber-200/50 flex items-center justify-center animate-pulse">
          <Disc3 className="w-8 h-8 text-amber-100 animate-spin" />
        </div>
        <span className="text-[11px] font-mono text-stone-900/70 mt-4 tracking-[0.2em] uppercase">
          Cleaning the stylus…
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

      {/* Hero Section - 3D anime turntable */}
      <section className="relative w-full pt-6 pb-16 lg:pt-12 lg:pb-24 overflow-hidden">
        {/* Warm background wash — gradients instead of blurred layers: no
            120px backdrop blurs to repaint while scrolling. */}
        <div
          className="pointer-events-none absolute inset-x-0 top-0 h-[820px]"
          style={{
            background:
              'radial-gradient(70% 46% at 26% 8%, rgba(217,119,6,0.16), transparent 70%), radial-gradient(56% 40% at 78% 26%, rgba(234,88,12,0.13), transparent 72%), radial-gradient(90% 40% at 50% 100%, rgba(68,64,60,0.35), transparent 70%)',
          }}
        />

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-center">
            {/* Left Content - 6 cols */}
            <div className="lg:col-span-6 space-y-7 text-left z-10">
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-950/70 border border-amber-500/40 text-amber-300 text-xs font-mono uppercase tracking-wider">
                  <Disc3 className="w-3.5 h-3.5 text-amber-400" />
                  <span>3D Anime Turntable</span>
                </span>
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-amber-900/30 text-stone-300 text-xs font-mono uppercase tracking-wider">
                  <Music className="w-3.5 h-3.5 text-amber-400" />
                  <span>Real Needle Drop</span>
                </span>
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-stone-800 text-stone-300 text-xs font-mono uppercase tracking-wider">
                  <Sparkles className="w-3.5 h-3.5 text-amber-400" />
                  <span>Lightweight • No Full Screen</span>
                </span>
              </div>

              <div className="space-y-4">
                <h1 className="text-4xl sm:text-5xl lg:text-[3.4rem] font-serif font-bold text-stone-100 tracking-tight leading-[1.1]">
                  {heroCopy.headline}
                </h1>
                <p className="text-base sm:text-lg text-stone-300/90 font-serif leading-relaxed max-w-xl">
                  {heroCopy.subheadline} Your voice pressed onto a hand-painted 3D record: the arm swings out, the stylus lands, and the wax starts turning. Orbit it, zoom in on the label, hear the crackle.
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
                      Hear the difference
                    </Button>
                  </Link>
                </div>

                <div className="flex flex-wrap items-center gap-4 text-xs text-stone-400 pt-1 font-mono">
                  <span className="flex items-center gap-1.5">
                    <Home className="w-3.5 h-3.5 text-amber-500" />
                    <span>One canvas • Drag to orbit</span>
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

            {/* Right - 3D turntable hero */}
            <div className="lg:col-span-6 relative flex flex-col gap-4">
              <div className="relative w-full overflow-hidden rounded-3xl border border-amber-900/30 shadow-2xl">
                <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-center justify-between p-3">
                  <span className="px-2.5 py-1 rounded-full border border-white/20 bg-black/35 text-[10px] uppercase tracking-wider font-mono text-amber-100">
                    Interactive • drag to orbit
                  </span>
                  <span className="px-2.5 py-1 rounded-full border border-white/15 bg-black/30 text-[10px] font-mono text-stone-200">
                    33⅓ rpm • cel-shaded
                  </span>
                </div>

                <div className="w-full h-[420px] sm:h-[520px]">
                  <Suspense fallback={<div className="h-full w-full bg-stone-950" />}>
                    <AnimeTurntablePlayer
                      isPlaying
                      vinylStyle="gold_edition"
                      title="Our Anniversary"
                      recipientName="Eleanor"
                      senderName="Arthur"
                    />
                  </Suspense>
                </div>

                <div className="pointer-events-none absolute inset-x-0 bottom-0 p-3 bg-gradient-to-t from-stone-950/90 to-transparent">
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] font-mono text-amber-200/85">Brass tonearm tracking inward · stylus lit</span>
                    <span className="text-[10px] font-mono text-stone-300/80">scroll to zoom</span>
                  </div>
                </div>
              </div>

              {/* CSS-only vinyl teaser: no second WebGL context needed */}
              <div className="w-full rounded-2xl border border-amber-900/20 bg-stone-900/60 p-3 flex items-center gap-3">
                <div className="relative h-24 w-24 flex-shrink-0 overflow-hidden rounded-xl border border-amber-600/20 bg-gradient-to-br from-[#241a15] to-[#0f0b09]">
                  <div className="absolute inset-0 m-auto h-[74%] w-[74%] animate-[spin_3.6s_linear_infinite] rounded-full bg-[#141210] shadow-[inset_0_0_18px_rgba(0,0,0,0.9)]">
                    <div className="absolute inset-0 rounded-full" style={{ background: 'repeating-radial-gradient(circle at 50% 50%, rgba(255,255,255,0.05) 0 1px, transparent 1px 4px)' }} />
                    <div className="absolute inset-0 m-auto h-[34%] w-[34%] rounded-full bg-gradient-to-br from-amber-500 to-amber-800" />
                    <div className="absolute inset-0 m-auto h-[7%] w-[7%] rounded-full bg-[#0f0b09]" />
                  </div>
                  <div className="absolute inset-0 bg-gradient-to-tr from-transparent via-white/10 to-transparent" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-serif font-bold text-amber-100">One scene, everywhere</p>
                  <p className="mt-1 text-[11px] leading-relaxed text-stone-400">
                    The same turntable is used on the hero, in the studio and on every record link — the
                    needle drop, crackle and background music are mixed into the mastered MP3 once, so
                    playback is identical every time.
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
                Everything about the 3D turntable and vintage wax pressing.
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
            Don&apos;t let precious words fade in chat apps. Press your voice onto warm 3D vinyl — needle drop, crackle and background atmosphere baked into one master, preserved forever behind a shareable link.
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
