'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { Disc3, Heart, Shield, Sparkles, Volume2, Music, HelpCircle } from 'lucide-react';
import { Modal } from './Modal';

export const Footer: React.FC = () => {
  const [termsModalOpen, setTermsModalOpen] = useState(false);
  const [privacyModalOpen, setPrivacyModalOpen] = useState(false);

  return (
    <footer className="w-full border-t border-stone-800/80 bg-stone-950 text-stone-400 mt-24 relative overflow-hidden">
      {/* Background Amber Ambient Glow */}
      <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-3/4 h-32 bg-amber-600/5 blur-[120px] pointer-events-none" />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-10">
          {/* Brand Column */}
          <div className="md:col-span-1 space-y-4">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-full bg-stone-900 border border-amber-600/40 flex items-center justify-center">
                <Disc3 className="w-5 h-5 text-amber-400" />
              </div>
              <span className="font-serif text-lg font-bold text-stone-100">
                Vinyl Voice Notes
              </span>
            </div>
            <p className="text-xs text-stone-400 leading-relaxed">
              Preserve your voice memories in handcrafted 3D digital wax. A timeless, crackling gift for anniversaries, family heirlooms, wedding vows, and long-distance lovers.
            </p>
            <div className="flex items-center gap-2 text-xs text-amber-500/90 font-mono">
              <Sparkles className="w-3.5 h-3.5" />
              <span>Engineered with Three.js & FFmpeg</span>
            </div>
          </div>

          {/* Quick Studio Links */}
          <div className="space-y-3">
            <h4 className="text-xs font-semibold tracking-wider uppercase text-stone-200">
              Sender Studio
            </h4>
            <ul className="space-y-2 text-xs">
              <li>
                <Link href="/studio" className="hover:text-amber-300 transition-colors">
                  Live Waveform Recorder
                </Link>
              </li>
              <li>
                <Link href="/#experience" className="hover:text-amber-300 transition-colors">
                  1920s Gramophone Filters
                </Link>
              </li>
              <li>
                <Link href="/#memories" className="hover:text-amber-300 transition-colors">
                  Sample Wax Vault
                </Link>
              </li>
              <li>
                <Link href="/#pricing" className="hover:text-amber-300 transition-colors">
                  Gold Master Plans
                </Link>
              </li>
            </ul>
          </div>

          {/* Technology & Mastering */}
          <div className="space-y-3">
            <h4 className="text-xs font-semibold tracking-wider uppercase text-stone-200">
              Analog Technology
            </h4>
            <ul className="space-y-2 text-xs">
              <li className="flex items-center gap-2">
                <Volume2 className="w-3.5 h-3.5 text-amber-500" />
                <span>33.3 RPM WebGL Physics</span>
              </li>
              <li className="flex items-center gap-2">
                <Music className="w-3.5 h-3.5 text-amber-500" />
                <span>FFmpeg Complex Filter Chain</span>
              </li>
              <li className="flex items-center gap-2">
                <Sparkles className="w-3.5 h-3.5 text-amber-500" />
                <span>Whisper Word-Level Timestamping</span>
              </li>
              <li className="flex items-center gap-2">
                <Shield className="w-3.5 h-3.5 text-amber-500" />
                <span>Lossless 192kbps MP3 Synthesis</span>
              </li>
            </ul>
          </div>

          {/* Governance & Admin */}
          <div className="space-y-3">
            <h4 className="text-xs font-semibold tracking-wider uppercase text-stone-200">
              Platform & Legal
            </h4>
            <ul className="space-y-2 text-xs">
              <li>
                <button
                  onClick={() => setTermsModalOpen(true)}
                  className="hover:text-amber-300 transition-colors"
                >
                  Terms of Service
                </button>
              </li>
              <li>
                <button
                  onClick={() => setPrivacyModalOpen(true)}
                  className="hover:text-amber-300 transition-colors"
                >
                  Privacy Policy & Wax Security
                </button>
              </li>
            </ul>
          </div>
        </div>

        {/* Bottom Bar */}
        <div className="mt-12 pt-6 border-t border-stone-900 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-stone-500">
          <p>© {new Date().getFullYear()} Vinyl Voice Notes Inc. All rights reserved.</p>
          <p className="flex items-center gap-1">
            Handcrafted with <Heart className="w-3.5 h-3.5 text-red-500 fill-red-500 inline" /> for memories that outlive time.
          </p>
        </div>
      </div>

      {/* Terms Modal */}
      <Modal
        isOpen={termsModalOpen}
        onClose={() => setTermsModalOpen(false)}
        title="Terms of Service"
        subtitle="Preserving digital memories respectfully"
        maxWidth="lg"
      >
        <div className="text-xs text-stone-300 space-y-4 max-h-96 overflow-y-auto pr-2">
          <p>
            Welcome to Vinyl Voice Notes. By recording, processing, or sharing audio through our service, you agree to these Terms of Service.
          </p>
          <h5 className="font-semibold text-amber-200">1. Content Ownership & Preservation</h5>
          <p>
            You retain 100% intellectual property ownership of all voice recordings and lyrics created on this platform. We act solely as the custodian and 3D playback engine.
          </p>
          <h5 className="font-semibold text-amber-200">2. Appropriate Content Guidelines</h5>
          <p>
            Users may not upload unlawful, harassing, defamatory, or abusive audio recordings. Content violating these standards is subject to immediate moderation and removal.
          </p>
          <h5 className="font-semibold text-amber-200">3. Permanent Storage Guarantee</h5>
          <p>
            We maintain redundant storage copies of all pressed vinyl records. Public playback links remain accessible indefinitely for your recipients.
          </p>
        </div>
      </Modal>

      {/* Privacy Modal */}
      <Modal
        isOpen={privacyModalOpen}
        onClose={() => setPrivacyModalOpen(false)}
        title="Privacy Policy & Audio Storage"
        subtitle="How we protect your voice recordings"
        maxWidth="lg"
      >
        <div className="text-xs text-stone-300 space-y-4 max-h-96 overflow-y-auto pr-2">
          <p>
            Your privacy and the intimate nature of personal voice notes are our highest priority.
          </p>
          <h5 className="font-semibold text-amber-200">1. Audio Processing & Encryption</h5>
          <p>
            Audio synthesis is executed in sandboxed server environments. Voice data is processed strictly to apply filters and generate transcriptions, and is never used to train public generative models.
          </p>
          <h5 className="font-semibold text-amber-200">2. Shareable Links</h5>
          <p>
            Your vinyl records are protected by unguessable 8-character cryptographic slugs. Only people you share the URL with can access the 3D player.
          </p>
          <h5 className="font-semibold text-amber-200">3. Data Deletion</h5>
          <p>
            You can request complete deletion of any pressed recording at any time through our operations team or moderation dashboard.
          </p>
        </div>
      </Modal>
    </footer>
  );
};
