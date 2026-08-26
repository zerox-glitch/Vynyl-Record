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
  ChevronDown
} from 'lucide-react';

// SSR Safe Floating Vinyl Dynamic Import Directive
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

export const revalidate = 0; // Dynamic server component

export default async function HomePage() {
  const settings = await getSiteSettings();
  const pricingPlans = await getPricingPlans();

  const heroCopy = settings.hero_copy;
  const faqs = settings.faqs || [];

  return (
    <div className="min-h-screen bg-[#0c0a09] text-stone-100 flex flex-col selection:bg-amber-600 selection:text-white">
      <Navbar />

      {/* Hero Section */}
      <section className="relative w-full pt-8 pb-16 lg:pt-16 lg:pb-24 overflow-hidden">
        {/* Background Radial Glow */}
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-full max-w-7xl h-[600px] bg-radial-amber opacity-60 pointer-events-none" />

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
            {/* Left Content Column (7 Cols) */}
            <div className="lg:col-span-7 space-y-8 text-left z-10">
              {/* Emotional Feature Badges */}
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-950/70 border border-amber-500/40 text-amber-300 text-xs font-mono uppercase tracking-wider">
                  <Disc3 className="w-3.5 h-3.5 text-amber-400" />
                  <span>3D Realistic Turntable</span>
                </span>
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-stone-800 text-stone-300 text-xs font-mono uppercase tracking-wider">
                  <Feather className="w-3.5 h-3.5 text-amber-400" />
                  <span>Real-time Parchment Lyrics</span>
                </span>
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-stone-900 border border-stone-800 text-stone-300 text-xs font-mono uppercase tracking-wider">
                  <Radio className="w-3.5 h-3.5 text-amber-400" />
                  <span>Analog Tube Warmth</span>
                </span>
              </div>

              {/* Dynamic CMS Headline & Subheadline */}
              <div className="space-y-4">
                <h1 className="text-4xl sm:text-5xl lg:text-6xl font-serif font-bold text-stone-100 tracking-tight leading-[1.15]">
                  {heroCopy.headline}
                </h1>
                <p className="text-base sm:text-lg text-stone-300/90 font-serif leading-relaxed max-w-2xl">
                  {heroCopy.subheadline}
                </p>
              </div>

              {/* Primary CTAs & Lead Action */}
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
                      Listen to Sound Lab
                    </Button>
                  </Link>
                </div>

                <div className="flex items-center gap-4 text-xs text-stone-400 pt-1 font-mono">
                  <span className="flex items-center gap-1.5">
                    <Sparkles className="w-3.5 h-3.5 text-amber-500" />
                    <span>Instant browser recording</span>
                  </span>
                  <span>•</span>
                  <span className="flex items-center gap-1.5">
                    <ShieldCheck className="w-3.5 h-3.5 text-emerald-500" />
                    <span>No download or app required</span>
                  </span>
                </div>
              </div>
            </div>

            {/* Right Interactive 3D Vinyl Column (5 Cols) */}
            <div className="lg:col-span-5 relative flex items-center justify-center">
              {/* Glassmorphic Turntable Stage Base */}
              <div className="w-full max-w-md aspect-square rounded-3xl bg-gradient-to-b from-stone-900/60 to-stone-950/80 border border-amber-600/30 backdrop-blur-md shadow-2xl p-4 flex flex-col items-center justify-between relative overflow-hidden">
                <div className="w-full flex items-center justify-between text-[11px] font-mono text-amber-400/80 uppercase tracking-widest z-10 px-2">
                  <span>Interactive 3D Wax Canvas</span>
                  <span>Hover & Drag to Tilt</span>
                </div>

                <div className="w-full h-full flex-1">
                  <Suspense fallback={<div className="h-full flex items-center justify-center"><Disc3 className="w-8 h-8 text-amber-500 animate-spin" /></div>}>
                    <FloatingVinylHero />
                  </Suspense>
                </div>

                <div className="w-full text-center text-[11px] font-mono text-stone-400 z-10 pb-1">
                  Click and drag to inspect grooves & gold foil label
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Module: Interactive Sound Lab A/B/C Experience Bar */}
      <SoundExperienceBar />

      {/* Module: Master Memory Vault Showcase */}
      <MemoryShowcase />

      {/* Module: 4-Step How It Works Visual Process */}
      <HowItWorks />

      {/* Module: Dynamic Pricing Grid */}
      <PricingSection plans={pricingPlans} />

      {/* Dynamic FAQ Section */}
      {faqs.length > 0 && (
        <section className="w-full py-20 border-t border-stone-800/80 bg-stone-950/50">
          <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 space-y-12">
            <div className="text-center space-y-3">
              <h3 className="text-2xl sm:text-3xl font-serif font-bold text-stone-100">
                Frequently Asked Questions
              </h3>
              <p className="text-xs sm:text-sm text-stone-400">
                Everything you need to know about pressing voice notes into digital wax.
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

      {/* Final Emotional CTA Banner */}
      <section className="w-full py-20 border-t border-stone-800 relative bg-gradient-to-b from-stone-950 to-[#120d09]">
        <div className="max-w-4xl mx-auto px-4 text-center space-y-6">
          <div className="w-16 h-16 rounded-full bg-stone-900 border border-amber-500/40 mx-auto flex items-center justify-center shadow-2xl">
            <Heart className="w-7 h-7 text-amber-400 fill-amber-400/20" />
          </div>
          <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
            A Voice Note That Will Never Be Forgotten
          </h2>
          <p className="text-sm sm:text-base text-stone-300 max-w-xl mx-auto leading-relaxed">
            Don&apos;t let precious words fade away in chat apps. Press your voice into warm 3D wax today.
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
