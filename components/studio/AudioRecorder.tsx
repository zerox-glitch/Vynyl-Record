'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Mic, Square, Pause, Play, Upload, CheckCircle2, AlertCircle, RotateCcw } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { WaveformVisualizer } from './WaveformVisualizer';
import toast from 'react-hot-toast';

interface AudioRecorderProps {
  onAudioReady: (blob: Blob, durationSec: number) => void;
  onClearAudio: () => void;
  maxDurationSeconds?: number;
  isPremium?: boolean;
  onTriggerUpgrade?: () => void;
  onRecordingStateChange?: (state: 'idle' | 'recording' | 'paused' | 'stopped') => void;
}

type RecorderState = 'idle' | 'recording' | 'paused' | 'stopped';

/** Blob duration that also copes with MediaRecorder webm (duration === Infinity). */
function probeBlobDuration(url: string, fallbackSec: number): Promise<number> {
  return new Promise((resolve) => {
    const probe = new Audio();
    let settled = false;
    const finish = (value: number) => {
      if (settled) return;
      settled = true;
      probe.src = '';
      resolve(Number.isFinite(value) && value > 0 ? Math.round(value * 10) / 10 : Math.max(1, fallbackSec));
    };
    const read = () => {
      if (probe.duration === Infinity || !Number.isFinite(probe.duration)) {
        // Seeking past the end forces the browser to compute the real length.
        probe.currentTime = 1e101;
        probe.ondurationchange = () => {
          probe.ondurationchange = null;
          finish(probe.duration || fallbackSec);
        };
        setTimeout(() => finish(probe.duration || fallbackSec), 1200);
        return;
      }
      finish(probe.duration);
    };
    probe.onloadedmetadata = read;
    probe.onerror = () => finish(fallbackSec);
    probe.preload = 'metadata';
    probe.src = url;
  });
}

export const AudioRecorder: React.FC<AudioRecorderProps> = ({
  onAudioReady,
  onClearAudio,
  maxDurationSeconds = 60,
  isPremium = false,
  onTriggerUpgrade,
  onRecordingStateChange,
}) => {
  const [recordingState, setRecordingState] = useState<RecorderState>('idle');
  const [duration, setDuration] = useState<number>(0);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [isPlayingPreview, setIsPlayingPreview] = useState<boolean>(false);
  const [mimeTypeLabel, setMimeTypeLabel] = useState<string>('');

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const rafRef = useRef<number | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const previewAudioRef = useRef<HTMLAudioElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const objectUrlRef = useRef<string | null>(null);

  // Timing lives in refs: the previous implementation read `duration` from a
  // stale closure inside recorder.onstop, so the record was stamped ~0 seconds.
  const startedAtRef = useRef(0);
  const accumulatedRef = useRef(0);
  const durationRef = useRef(0);
  const limitFiredRef = useRef(false);
  const stoppedRef = useRef(false);

  const isLimitReached = duration >= maxDurationSeconds;

  const setState = useCallback(
    (next: RecorderState) => {
      setRecordingState(next);
      if (onRecordingStateChange) onRecordingStateChange(next);
    },
    [onRecordingStateChange]
  );

  const stopTimer = () => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
  };

  const tick = useCallback(() => {
    const elapsed = accumulatedRef.current + (Date.now() - startedAtRef.current) / 1000;
    durationRef.current = elapsed;
    setDuration(Math.floor(elapsed));
    if (!isPremium && elapsed >= maxDurationSeconds && !limitFiredRef.current) {
      limitFiredRef.current = true;
      stopRecording();
      toast('Reached your plan length limit', { icon: '⏳' });
      if (onTriggerUpgrade) onTriggerUpgrade();
      return;
    }
    rafRef.current = requestAnimationFrame(tick);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isPremium, maxDurationSeconds, onTriggerUpgrade]);

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const releaseObjectUrl = () => {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
  };

  const teardownStream = () => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }
    if (audioContextRef.current) {
      audioContextRef.current.close().catch(() => {});
      audioContextRef.current = null;
    }
  };

  const startRecording = async () => {
    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        toast.error('This browser has no microphone access — try Chrome, Safari or Firefox.');
        return;
      }
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
        },
      });

      streamRef.current = stream;
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      const audioCtx = new AudioCtx();
      if (audioCtx.state === 'suspended') await audioCtx.resume().catch(() => {});
      audioContextRef.current = audioCtx;

      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 128;
      source.connect(analyser);
      analyserRef.current = analyser;

      const candidates = [
        'audio/webm;codecs=opus',
        'audio/webm',
        'audio/ogg;codecs=opus',
        'audio/mp4;codecs=mp4a.40.2',
        'audio/mp4',
      ];
      const mimeType =
        (typeof MediaRecorder !== 'undefined' &&
          candidates.find((type) => MediaRecorder.isTypeSupported(type))) ||
        '';

      const recorder = new MediaRecorder(stream, mimeType ? { mimeType, audioBitsPerSecond: 128000 } : undefined);
      mediaRecorderRef.current = recorder;
      audioChunksRef.current = [];
      limitFiredRef.current = false;
      stoppedRef.current = false;
      setMimeTypeLabel(mimeType || 'browser default');

      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) audioChunksRef.current.push(event.data);
      };

      recorder.onerror = ((event: any) => {
        console.error('[Recorder] error', event?.error);
        stopTimer();
        setState('idle');
        toast.error('The recorder stopped unexpectedly — try again.');
      }) as EventListener;

      recorder.onstop = async () => {
        stopTimer();
        if (stoppedRef.current) return;
        stoppedRef.current = true;
        const elapsed = Math.max(0.4, durationRef.current || accumulatedRef.current);
        const blob = new Blob(audioChunksRef.current, {
          type: mimeType || 'audio/webm',
        });
        audioChunksRef.current = [];
        teardownStream();

        if (blob.size < 1200) {
          setState('idle');
          toast.error('No audio was captured — check the microphone input and record again.', {
            duration: 6000,
          });
          return;
        }

        releaseObjectUrl();
        const url = URL.createObjectURL(blob);
        objectUrlRef.current = url;
        setAudioUrl(url);

        const measured = await probeBlobDuration(url, elapsed);
        setDuration(Math.floor(measured));
        durationRef.current = measured;
        setState('stopped');
        onAudioReady(blob, measured);
      };

      accumulatedRef.current = 0;
      durationRef.current = 0;
      setDuration(0);
      // timeslice keeps chunks flowing so a crash loses at most a second
      recorder.start(1000);
      startedAtRef.current = Date.now();
      setState('recording');
      rafRef.current = requestAnimationFrame(tick);
      toast.success('Microphone live — speak your memory');
    } catch (err: any) {
      teardownStream();
      stopTimer();
      setState('idle');
      const name = err?.name || '';
      if (name === 'NotAllowedError' || name === 'SecurityError') {
        toast.error('Microphone blocked — allow access in your browser, then press record again.', {
          duration: 6000,
        });
      } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
        toast.error('No microphone found. Upload an audio file instead.', { duration: 6000 });
      } else {
        toast.error(`Could not start recording${name ? ` (${name})` : ''}.`);
      }
    }
  };

  const pauseRecording = () => {
    const recorder = mediaRecorderRef.current;
    if (recorder && recordingState === 'recording' && recorder.state !== 'inactive') {
      try {
        recorder.pause();
      } catch {}
      accumulatedRef.current += (Date.now() - startedAtRef.current) / 1000;
      stopTimer();
      setState('paused');
    }
  };

  const resumeRecording = () => {
    const recorder = mediaRecorderRef.current;
    if (recorder && recordingState === 'paused' && recorder.state === 'paused') {
      try {
        recorder.resume();
      } catch {}
      startedAtRef.current = Date.now();
      setState('recording');
      rafRef.current = requestAnimationFrame(tick);
    }
  };

  const stopRecording = () => {
    const recorder = mediaRecorderRef.current;
    if (recorder && recorder.state !== 'inactive') {
      accumulatedRef.current += (Date.now() - startedAtRef.current) / 1000;
      durationRef.current = accumulatedRef.current;
      try {
        recorder.stop();
      } catch {}
    }
    stopTimer();
  };

  // Reset & re-record
  const resetRecording = () => {
    stopTimer();
    const recorder = mediaRecorderRef.current;
    if (recorder && recorder.state !== 'inactive') {
      stoppedRef.current = true;
      try {
        recorder.stop();
      } catch {}
    }
    teardownStream();
    releaseObjectUrl();
    setAudioUrl(null);
    setDuration(0);
    durationRef.current = 0;
    accumulatedRef.current = 0;
    setState('idle');
    setIsPlayingPreview(false);
    onClearAudio();
  };

  // File Upload Alternate
  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 25 * 1024 * 1024) {
      toast.error('Audio file must be under 25MB');
      return;
    }
    if (file.type && !file.type.startsWith('audio/')) {
      toast.error('That file is not an audio recording.');
      return;
    }

    releaseObjectUrl();
    const url = URL.createObjectURL(file);
    objectUrlRef.current = url;
    setAudioUrl(url);
    setMimeTypeLabel(file.type || 'uploaded file');
    setState('stopped');

    probeBlobDuration(url, 0).then((measured) => {
      const safe = Math.max(1, measured || 0);
      setDuration(Math.floor(safe));
      durationRef.current = safe;
      onAudioReady(file, safe);
      toast.success(`Uploaded: ${file.name} (${formatTime(safe)})`);
    });

    if (e.target) e.target.value = '';
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      stopTimer();
      const recorder = mediaRecorderRef.current;
      if (recorder && recorder.state !== 'inactive') {
        stoppedRef.current = true;
        try {
          recorder.stop();
        } catch {}
      }
      teardownStream();
      releaseObjectUrl();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="w-full space-y-5">
      {/* Waveform Visualizer Screen */}
      <WaveformVisualizer
        analyser={analyserRef.current}
        isRecording={recordingState === 'recording'}
      />

      {/* Timer & Limits Bar */}
      <div className="flex items-center justify-between px-2 text-xs font-mono">
        <div className="flex items-center gap-2">
          <span className="text-stone-400">Recording Length:</span>
          <span
            className={`font-bold text-base ${
              isLimitReached ? 'text-amber-500 animate-pulse' : 'text-amber-400'
            }`}
          >
            {formatTime(duration)}
          </span>
          <span className="text-stone-500">/ {formatTime(maxDurationSeconds)}</span>
        </div>

        <div>
          {isLimitReached ? (
            <button
              onClick={onTriggerUpgrade}
              className="flex items-center gap-1.5 text-amber-400 hover:text-amber-300 underline text-xs font-sans font-semibold"
            >
              <AlertCircle className="w-3.5 h-3.5 text-amber-400" />
              <span>Unlock Unlimited Minutes</span>
            </button>
          ) : (
            <span className="text-stone-500 flex items-center gap-1">
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
              <span>Ready for Analog Pressing</span>
            </span>
          )}
          {mimeTypeLabel && recordingState === 'stopped' ? (
            <span
              title="Container captured by your browser — the server transcodes this to MP3"
              className="mt-1 block text-right font-mono text-[10px] text-stone-600"
            >
              captured: {mimeTypeLabel.replace('audio/', '')} · {formatTime(duration)}
            </span>
          ) : null}
        </div>
      </div>

      {/* Recording Control Center */}
      <div className="flex flex-wrap items-center justify-center gap-3 pt-2">
        {recordingState === 'idle' && (
          <>
            <Button
              variant="primary"
              size="lg"
              onClick={startRecording}
              leftIcon={<Mic className="w-5 h-5 text-stone-950 fill-stone-950 animate-pulse" />}
              className="min-w-[200px]"
            >
              Start Recording
            </Button>
            <Button
              variant="secondary"
              size="lg"
              onClick={() => fileInputRef.current?.click()}
              leftIcon={<Upload className="w-4 h-4 text-amber-400" />}
            >
              Upload Audio File
            </Button>
            <input
              type="file"
              ref={fileInputRef}
              onChange={handleFileUpload}
              accept="audio/*"
              className="hidden"
            />
          </>
        )}

        {recordingState === 'recording' && (
          <>
            <Button
              variant="secondary"
              size="lg"
              onClick={pauseRecording}
              leftIcon={<Pause className="w-5 h-5 text-amber-400" />}
            >
              Pause
            </Button>
            <Button
              variant="danger"
              size="lg"
              onClick={stopRecording}
              leftIcon={<Square className="w-5 h-5 fill-red-200" />}
              className="min-w-[180px]"
            >
              Finish Recording
            </Button>
          </>
        )}

        {recordingState === 'paused' && (
          <>
            <Button
              variant="primary"
              size="lg"
              onClick={resumeRecording}
              leftIcon={<Play className="w-5 h-5 fill-stone-950" />}
            >
              Resume
            </Button>
            <Button
              variant="danger"
              size="lg"
              onClick={stopRecording}
              leftIcon={<Square className="w-5 h-5 fill-red-200" />}
            >
              Finish Recording
            </Button>
          </>
        )}

        {recordingState === 'stopped' && audioUrl && (
          <div className="w-full flex flex-col sm:flex-row items-center justify-between gap-4 bg-stone-900/90 border border-amber-600/30 p-4 rounded-2xl">
            <div className="flex items-center gap-3 w-full sm:w-auto">
              <button
                onClick={() => {
                  if (previewAudioRef.current) {
                    if (isPlayingPreview) {
                      previewAudioRef.current.pause();
                      setIsPlayingPreview(false);
                    } else {
                      previewAudioRef.current.play();
                      setIsPlayingPreview(true);
                    }
                  }
                }}
                className="w-12 h-12 rounded-full bg-gradient-to-br from-amber-500 to-amber-700 flex items-center justify-center text-stone-950 hover:scale-105 transition-transform shadow-lg shadow-amber-950/60"
              >
                {isPlayingPreview ? (
                  <Pause className="w-5 h-5 fill-current" />
                ) : (
                  <Play className="w-5 h-5 fill-current ml-0.5" />
                )}
              </button>

              <div>
                <p className="text-sm font-serif font-bold text-amber-100">
                  Raw Voice Note Preview
                </p>
                <p className="text-xs text-stone-400 font-mono">
                  Length: {formatTime(duration)} • Ready for analog filter chain
                </p>
              </div>

              <audio
                ref={previewAudioRef}
                src={audioUrl}
                onEnded={() => setIsPlayingPreview(false)}
                className="hidden"
              />
            </div>

            <Button
              variant="outline"
              size="md"
              onClick={resetRecording}
              leftIcon={<RotateCcw className="w-4 h-4 text-amber-400" />}
            >
              Re-Record
            </Button>
          </div>
        )}
      </div>
    </div>
  );
};
