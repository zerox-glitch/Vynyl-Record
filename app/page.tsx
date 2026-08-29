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
  Mic,
  ArrowRight,
} from 'lucide-react';

// 3D turntable hero — client-only (WebGL), the single canvas of the page.
const AnimeTurntablePlayer = dynamic(
  () => import('@/components/3d/AnimeTurntablePlayer').then((m) => m.AnimeTurntablePlayer),
  {
    ssr: false,
    loading: () => (
      <div className="flex h-full w-full items-center justify-center bg-gradient-to-b from-[#2a1f1a] to-[#0c0a09]">
        <div className="h-10 w-10 rounded-full border border-amber-500/30 border-t-amber-400 animate-spin" />
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

      {/* Hero */}
      <section className="relative w-full pt-8 pb-20 lg:pt-14 lg:pb-28 overflow-hidden">
        {/* Warm wash — gradients only, no backdrop blurs to repaint while scrolling */}
        <div
          className="pointer-events-none absolute inset-x-0 top-0 h-[820px]"
          style={{
            background:
              'radial-gradient(70% 46% at 26% 8%, rgba(217,119,6,0.18), transparent 70%), radial-gradient(56% 40% at 78% 26%, rgba(234,88,12,0.14), transparent 72%), radial-gradient(90% 40% at 50% 100%, rgba(68,64,60,0.4), transparent 70%)',
          }}
        />

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-center">
            {/* Left: words */}
            <div className="lg:col-span-7 space-y-8 text-left z-10">
              <div className="space-y-5">
                <h1 className="text-4xl sm:text-5xl lg:text-[3.6rem] font-serif font-bold text-stone-100 tracking-tight leading-[1.05]">
                  {heroCopy.headline}
                </h1>
                <p className="text-base sm:text-lg text-stone-300/90 font-serif leading-relaxed max-w-xl">
                  {heroCopy.subheadline}
                </p>
              </div>

              <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-4 pt-2">
                <Link href="/studio">
                  <Button
                    variant="primary"
                    size="lg"
                    className="w-full sm:w-auto text-base px-8 py-4"
                    leftIcon={<Mic className="w-5 h-5 text-stone-950" />}
                    rightIcon={<ArrowRight className="w-4 h-4 ml-1" />}
                  >
                    {heroCopy.cta_text || 'Press Your Voice'}
                  </Button>
                </Link>
              </div>
            </div>

            {/* Right: the record itself */}
            <div className="lg:col-span-5 relative">
              <div className="relative w-full overflow-hidden rounded-3xl border border-amber-900/30 shadow-[0_20px_60px_-15px_rgba(217,119,6,0.3)]">
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
                Frequently Asked
              </h3>
              <p className="text-xs sm:text-sm text-stone-400">
                Quiet questions from people who want to send something that lasts.
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

      {/* Final close */}
      <section className="w-full py-24 border-t border-stone-800 relative bg-gradient-to-b from-stone-950 to-[#120d09]">
        <div className="max-w-3xl mx-auto px-4 text-center space-y-7">
          <h2 className="text-3xl sm:text-4xl font-serif font-bold text-stone-100">
            One thing they&apos;ll keep.
          </h2>
          <p className="text-base sm:text-lg text-stone-300 max-w-xl mx-auto leading-relaxed">
            Grandma&apos;s laugh. The first &ldquo;I love you.&rdquo; A wedding vow whispered, not shouted.
            Whatever you want them to have when you&apos;re not there &mdash; turn it into a record they can return to.
          </p>
          <div className="pt-2">
            <Link href="/studio">
              <Button
                variant="primary"
                size="lg"
                className="px-10 py-4 text-base"
                leftIcon={<Mic className="w-5 h-5 text-stone-950" />}
              >
                Press Your Voice
              </Button>
            </Link>
          </div>
        </div>
      </section>

      <Footer />
    </div>
  );
}
