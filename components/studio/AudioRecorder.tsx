'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Mic, Square, Pause, Play, RotateCcw, Upload, Volume2, CheckCircle2, AlertCircle } from 'lucide-react';
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

export const AudioRecorder: React.FC<AudioRecorderProps> = ({
  onAudioReady,
  onClearAudio,
  maxDurationSeconds = 60,
  isPremium = false,
  onTriggerUpgrade,
  onRecordingStateChange,
}) => {
  const [recordingState, setRecordingState] = useState<'idle' | 'recording' | 'paused' | 'stopped'>('idle');

  // Notify parent of recording state for fullscreen room
  useEffect(() => {
    if (onRecordingStateChange) onRecordingStateChange(recordingState);
  }, [recordingState, onRecordingStateChange]);
  const [duration, setDuration] = useState<number>(0);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [isPlayingPreview, setIsPlayingPreview] = useState<boolean>(false);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const timerIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const previewAudioRef = useRef<HTMLAudioElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Format seconds into MM:SS
  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  // Start recording
  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      streamRef.current = stream;
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      const audioCtx = new AudioCtx();
      audioContextRef.current = audioCtx;

      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 128;
      source.connect(analyser);
      analyserRef.current = analyser;

      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : MediaRecorder.isTypeSupported('audio/webm')
        ? 'audio/webm'
        : 'audio/mp4';

      const recorder = new MediaRecorder(stream, { mimeType });
      mediaRecorderRef.current = recorder;
      audioChunksRef.current = [];

      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      recorder.onstop = () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: mimeType });
        const url = URL.createObjectURL(audioBlob);
        setAudioUrl(url);
        onAudioReady(audioBlob, duration);
      };

      recorder.start(100);
      setRecordingState('recording');
      setDuration(0);

      // Start timer
      timerIntervalRef.current = setInterval(() => {
        setDuration((prev) => {
          const next = prev + 1;
          if (!isPremium && next >= maxDurationSeconds) {
            stopRecording();
            toast('Reached plan duration limit', { icon: '⏳' });
            if (onTriggerUpgrade) onTriggerUpgrade();
          }
          return next;
        });
      }, 1000);

      toast.success('Microphone live — speak your memory');
    } catch (err: any) {
      console.error('Error accessing microphone:', err);
      toast.error('Could not access microphone. Please check browser permissions.');
    }
  };

  // Pause recording
  const pauseRecording = () => {
    if (mediaRecorderRef.current && recordingState === 'recording') {
      mediaRecorderRef.current.pause();
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
      setRecordingState('paused');
    }
  };

  // Resume recording
  const resumeRecording = () => {
    if (mediaRecorderRef.current && recordingState === 'paused') {
      mediaRecorderRef.current.resume();
      timerIntervalRef.current = setInterval(() => {
        setDuration((prev) => prev + 1);
      }, 1000);
      setRecordingState('recording');
    }
  };

  // Stop recording
  const stopRecording = () => {
    if (mediaRecorderRef.current && (recordingState === 'recording' || recordingState === 'paused')) {
      mediaRecorderRef.current.stop();
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((track) => track.stop());
      }
      setRecordingState('stopped');
      toast.success('Recording captured! Ready to synthesize.');
    }
  };

  // Reset & re-record
  const resetRecording = () => {
    if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
    }
    if (audioUrl) {
      URL.revokeObjectURL(audioUrl);
      setAudioUrl(null);
    }
    setRecordingState('idle');
    setDuration(0);
    setIsPlayingPreview(false);
    onClearAudio();
  };

  // File Upload Alternate
  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      if (file.size > 25 * 1024 * 1024) {
        toast.error('Audio file must be under 25MB');
        return;
      }
      const url = URL.createObjectURL(file);
      setAudioUrl(url);
      setRecordingState('stopped');
      // Create audio to probe duration
      const probe = new Audio(url);
      probe.onloadedmetadata = () => {
        const dur = Math.round(probe.duration || 10);
        setDuration(dur);
        onAudioReady(file, dur);
        toast.success(`Uploaded: ${file.name} (${dur}s)`);
      };
    }
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((track) => track.stop());
      }
      if (audioContextRef.current) {
        audioContextRef.current.close();
      }
    };
  }, []);

  const isLimitReached = !isPremium && duration >= maxDurationSeconds;

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
