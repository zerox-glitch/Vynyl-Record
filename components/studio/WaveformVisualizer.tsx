'use client';

import React, { useRef, useEffect } from 'react';

interface WaveformVisualizerProps {
  analyser: AnalyserNode | null;
  isRecording: boolean;
  className?: string;
}

export const WaveformVisualizer: React.FC<WaveformVisualizerProps> = ({
  analyser,
  isRecording,
  className,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animationFrameRef = useRef<number>();

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let bufferLength = 64;
    let dataArray = new Uint8Array(bufferLength);

    if (analyser) {
      analyser.fftSize = 128;
      bufferLength = analyser.frequencyBinCount;
      dataArray = new Uint8Array(bufferLength);
    }

    const render = () => {
      animationFrameRef.current = requestAnimationFrame(render);

      const width = canvas.width;
      const height = canvas.height;

      // Dark background
      ctx.fillStyle = '#0c0a09';
      ctx.fillRect(0, 0, width, height);

      // Center reference horizontal line
      ctx.strokeStyle = 'rgba(217, 119, 6, 0.2)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, height / 2);
      ctx.lineTo(width, height / 2);
      ctx.stroke();

      if (isRecording && analyser) {
        analyser.getByteFrequencyData(dataArray);

        const barWidth = (width / bufferLength) * 1.5;
        let x = 0;

        for (let i = 0; i < bufferLength; i++) {
          const barHeight = (dataArray[i] / 255) * (height * 0.85);

          // Golden gradient
          const gradient = ctx.createLinearGradient(0, height / 2 - barHeight / 2, 0, height / 2 + barHeight / 2);
          gradient.addColorStop(0, '#fcd34d');
          gradient.addColorStop(0.5, '#f59e0b');
          gradient.addColorStop(1, '#78350f');

          ctx.fillStyle = gradient;
          ctx.fillRect(x, height / 2 - barHeight / 2, barWidth - 1.5, barHeight || 2);

          x += barWidth;
        }
      } else {
        // Subtle ambient idle pulse
        const time = Date.now() * 0.003;
        const barWidth = width / 32;
        for (let i = 0; i < 32; i++) {
          const idleH = (Math.sin(time + i * 0.3) * 0.5 + 0.5) * 12 + 4;
          ctx.fillStyle = 'rgba(217, 119, 6, 0.35)';
          ctx.fillRect(i * barWidth, height / 2 - idleH / 2, barWidth - 2, idleH);
        }
      }
    };

    render();

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
  }, [analyser, isRecording]);

  return (
    <div className={`relative w-full rounded-xl overflow-hidden border border-amber-600/30 shadow-inner bg-stone-950 ${className}`}>
      <canvas
        ref={canvasRef}
        width={600}
        height={120}
        className="w-full h-24 sm:h-28 block"
      />
      {isRecording && (
        <div className="absolute top-2 right-3 flex items-center gap-2 bg-red-950/80 border border-red-500/50 px-2 py-0.5 rounded-full">
          <span className="w-2 h-2 rounded-full bg-red-500 animate-ping" />
          <span className="text-[10px] font-mono text-red-300 font-bold tracking-widest uppercase">
            Live Wax Feed
          </span>
        </div>
      )}
    </div>
  );
};
