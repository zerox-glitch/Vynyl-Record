'use client';

import React, { useEffect, useState } from 'react';
import { Modal } from '@/components/ui/Modal';
import { Disc3, Sparkles, CheckCircle2 } from 'lucide-react';
import { motion } from 'framer-motion';

interface LatheProcessingModalProps {
  isOpen: boolean;
  onClose: () => void;
  statusIndex: number; // 0: upload, 1: filters, 2: transcribe, 3: pressing, 4: complete
}

const STEPS = [
  { label: 'Uploading raw voice audio...', detail: 'Sending master stream to sandboxed audio buffer' },
  { label: 'Synthesizing analog filters...', detail: 'Executing FFmpeg tube saturation & acoustic horn curve' },
  { label: 'Transcribing words with AI...', detail: 'OpenAI Whisper generating word-level timestamps' },
  { label: 'Pressing digital wax...', detail: 'Cutting 33⅓ RPM grooves and generating 3D vinyl link' },
];

export const LatheProcessingModal: React.FC<LatheProcessingModalProps> = ({
  isOpen,
  onClose,
  statusIndex,
}) => {
  const currentStep = Math.min(statusIndex, STEPS.length - 1);
  const progressPercent = Math.min(100, Math.round(((statusIndex + 1) / STEPS.length) * 100));

  return (
    <Modal
      isOpen={isOpen}
      onClose={() => {}} // Non-dismissible while cutting wax
      maxWidth="md"
    >
      <div className="py-6 flex flex-col items-center text-center space-y-6">
        {/* Animated Vintage Cutting Lathe Graphic */}
        <div className="relative w-44 h-44 flex items-center justify-center">
          {/* Outer Glow */}
          <div className="absolute inset-0 rounded-full bg-amber-600/20 blur-2xl animate-pulse" />

          {/* Turntable Platter Rotating */}
          <div className="w-36 h-36 rounded-full bg-stone-950 border-4 border-amber-600/40 shadow-2xl relative flex items-center justify-center animate-[spin_3s_linear_infinite]">
            {/* Concentric Grooves */}
            <div className="w-28 h-28 rounded-full border border-stone-800" />
            <div className="w-20 h-20 rounded-full border border-stone-700" />
            <div className="w-12 h-12 rounded-full bg-red-900 border border-amber-500/50 flex items-center justify-center">
              <div className="w-2.5 h-2.5 rounded-full bg-stone-950" />
            </div>
          </div>

          {/* Lathe Cutting Arm (Needle) */}
          <div className="absolute top-2 right-4 w-16 h-20 pointer-events-none lathe-cutting">
            <div className="w-1 h-14 bg-gradient-to-b from-amber-400 to-amber-600 rounded-full transform rotate-45 origin-top" />
            <div className="absolute bottom-2 left-6 w-2 h-2 rounded-full bg-amber-300 shadow-md shadow-amber-400 animate-ping" />
          </div>

          {/* Spark Particles */}
          <div className="absolute inset-0 pointer-events-none flex items-center justify-center">
            <Sparkles className="w-5 h-5 text-amber-400 animate-bounce absolute top-4 left-6" />
            <Sparkles className="w-4 h-4 text-amber-300 animate-pulse absolute bottom-4 right-6" />
          </div>
        </div>

        {/* Step Status Text */}
        <div className="space-y-2">
          <h3 className="text-xl font-serif font-bold text-amber-100 tracking-wide">
            {STEPS[currentStep].label}
          </h3>
          <p className="text-xs text-stone-400 font-mono max-w-sm">
            {STEPS[currentStep].detail}
          </p>
        </div>

        {/* Progress Bar */}
        <div className="w-full space-y-2">
          <div className="w-full h-2 bg-stone-800 rounded-full overflow-hidden border border-stone-700">
            <motion.div
              className="h-full bg-gradient-to-r from-amber-600 via-amber-500 to-amber-400"
              initial={{ width: '10%' }}
              animate={{ width: `${progressPercent}%` }}
              transition={{ duration: 0.5, ease: 'easeInOut' }}
            />
          </div>
          <div className="flex justify-between items-center text-[11px] font-mono text-stone-400">
            <span>Analog Mastering in Progress</span>
            <span className="text-amber-400 font-bold">{progressPercent}%</span>
          </div>
        </div>

        {/* Step Checkpoints */}
        <div className="w-full grid grid-cols-2 gap-2 text-left pt-2">
          {STEPS.map((step, idx) => {
            const isCompleted = idx < statusIndex;
            const isCurrent = idx === statusIndex;

            return (
              <div
                key={idx}
                className={`p-2 rounded-xl border text-xs flex items-center gap-2 ${
                  isCompleted
                    ? 'bg-emerald-950/40 border-emerald-600/40 text-emerald-300'
                    : isCurrent
                    ? 'bg-amber-950/50 border-amber-500 text-amber-200'
                    : 'bg-stone-900/40 border-stone-800 text-stone-500'
                }`}
              >
                <CheckCircle2
                  className={`w-3.5 h-3.5 flex-shrink-0 ${
                    isCompleted
                      ? 'text-emerald-400'
                      : isCurrent
                      ? 'text-amber-400 animate-spin'
                      : 'text-stone-600'
                  }`}
                />
                <span className="truncate">{step.label.replace('...', '')}</span>
              </div>
            );
          })}
        </div>
      </div>
    </Modal>
  );
};
