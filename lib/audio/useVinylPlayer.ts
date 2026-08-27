'use client';

/**
 * useVinylPlayer — the shared playback engine for pressed records.
 * ---------------------------------------------------------------------------
 * Why this exists: the old page drove playback with ad-hoc `setTimeout` calls
 * inside `app/play/[slug]/page.tsx`, which broke in three reliable ways:
 *
 *  1. `audio.play()` was called from a `setTimeout` (outside the click's task).
 *     Mobile Safari / iOS WebView refuse that, so the needle fell and then
 *     nothing ever played. Now the element is started *inside* the gesture and
 *     held at volume 0 during the ritual, then faded up — same drama, always
 *     unlocked.
 *  2. A rejected play() promise was swallowed (`.catch(() => {})`), so a
 *     decode/404 failure looked like "the audio is silent". Failures now
 *     surface, and `onError` transparently re-points at `fallbackSrc`
 *     (e.g. the raw voice take when a mastered file went missing).
 *  3. Duration/seeking relied on `Infinity` metadata from `data:` URIs. The
 *     player now reads real `loadedmetadata` + `durationchange` events and
 *     ignores the "dropping" frames so the scrub bar can't fight the seek.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

export interface VinylPlayerOptions {
  /** Mastered record URL (may also be a data: URI for legacy records). */
  src: string | null | undefined;
  /** Optional second chance — the raw take, or any alternative URL. */
  fallbackSrc?: string | null;
  /** Needle-drop SFX URL. Omit to skip the ritual. */
  needleSrc?: string | null;
  /** How long the arm takes to fall before the voice fades in. */
  needleDelayMs?: number;
  initialVolume?: number;
  /** Fade time once the needle lands. */
  fadeInMs?: number;
  onError?: (message: string) => void;
}

export interface VinylPlayer {
  audioRef: React.RefObject<HTMLAudioElement>;
  needleRef: React.RefObject<HTMLAudioElement>;
  /** Which URL is currently wired into the <audio> element. */
  activeSrc: string | null;
  isPlaying: boolean;
  isNeedleDropping: boolean;
  isReady: boolean;
  isLoading: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  isMuted: boolean;
  error: string | null;
  play: (fromTime?: number) => void;
  pause: () => void;
  toggle: () => void;
  restart: () => void;
  seek: (time: number) => void;
  setVolume: (value: number) => void;
  toggleMute: () => void;
  /** Prime the elements on first tap so later programmatic play() is allowed. */
  unlock: () => void;
}

export function useVinylPlayer({
  src,
  fallbackSrc,
  needleSrc = '/audio/needle-drop.mp3',
  needleDelayMs = 1150,
  initialVolume = 0.85,
  fadeInMs = 340,
  onError,
}: VinylPlayerOptions): VinylPlayer {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const needleRef = useRef<HTMLAudioElement | null>(null);

  const [activeSrc, setActiveSrc] = useState<string | null>(src ?? null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isNeedleDropping, setIsNeedleDropping] = useState(false);
  const [isReady, setIsReady] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolumeState] = useState(initialVolume);
  const [isMuted, setIsMuted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Mutable mirrors so timer callbacks never read stale state.
  const volumeRef = useRef(initialVolume);
  const mutedRef = useRef(false);
  const droppingRef = useRef(false);
  const usedFallbackRef = useRef(false);
  const dropTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fadeTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const resumeAtRef = useRef(0);

  const targetVolume = () => (mutedRef.current ? 0 : volumeRef.current);

  // Records are fetched async: adopt a src that arrives after mount.
  useEffect(() => {
    usedFallbackRef.current = false;
    setActiveSrc(src ?? null);
  }, [src]);

  const clearTimers = () => {
    if (dropTimerRef.current) {
      clearTimeout(dropTimerRef.current);
      dropTimerRef.current = null;
    }
    if (fadeTimerRef.current) {
      clearInterval(fadeTimerRef.current);
      fadeTimerRef.current = null;
    }
  };

  /** Ramp the element volume up so the voice "arrives" instead of snapping. */
  const fadeIn = useCallback(
    (element: HTMLAudioElement, to: number) => {
      if (fadeTimerRef.current) clearInterval(fadeTimerRef.current);
      if (to <= 0.0001) {
        element.volume = 0;
        return;
      }
      const steps = Math.max(3, Math.round(fadeInMs / 55));
      const from = Math.min(0.08, to);
      let step = 0;
      element.volume = from;
      fadeTimerRef.current = setInterval(() => {
        step += 1;
        const t = step / steps;
        element.volume = Math.max(0, Math.min(1, from + (to - from) * t));
        if (step >= steps) {
          element.volume = Math.max(0, Math.min(1, to));
          if (fadeTimerRef.current) clearInterval(fadeTimerRef.current);
          fadeTimerRef.current = null;
        }
      }, fadeInMs / steps);
    },
    [fadeInMs]
  );

  const stopDropping = useCallback((element: HTMLAudioElement, atTime: number) => {
    droppingRef.current = false;
    setIsNeedleDropping(false);
    try {
      // Rewind the frames spent muted during the ritual: nothing is missed.
      if (atTime >= 0 && Math.abs(element.currentTime - atTime) > 0.05) {
        element.currentTime = atTime;
      }
    } catch {}
    setCurrentTime(atTime);
    fadeIn(element, targetVolume());
  }, [fadeIn]);

  const cancelDrop = useCallback((element: HTMLAudioElement) => {
    if (dropTimerRef.current) {
      clearTimeout(dropTimerRef.current);
      dropTimerRef.current = null;
    }
    if (droppingRef.current) {
      droppingRef.current = false;
      setIsNeedleDropping(false);
      element.volume = targetVolume();
    }
  }, []);

  /* ----------------------------- element wiring ---------------------------- */

  useEffect(() => {
    const element = audioRef.current;
    if (!element) return;

    const onLoaded = () => {
      const d = Number.isFinite(element.duration) && element.duration > 0 ? element.duration : 0;
      setDuration(d);
      setIsLoading(false);
      setIsReady(element.readyState >= 2);
      element.volume = droppingRef.current ? 0 : targetVolume();
    };
    const onTime = () => {
      // The scrubber freezes while the arm falls, then snaps back to the top.
      if (droppingRef.current) return;
      setCurrentTime(element.currentTime);
    };
    const onWaiting = () => setIsLoading(true);
    const onPlaying = () => {
      setIsLoading(false);
      setError(null);
    };
    const onEnded = () => {
      clearTimers();
      droppingRef.current = false;
      setIsNeedleDropping(false);
      setIsPlaying(false);
      setCurrentTime(element.duration || 0);
      element.volume = targetVolume();
    };
    const onMediaError = () => {
      const code = element.error?.code;
      const names: Record<number, string> = {
        1: 'aborted',
        2: 'network',
        3: 'decode',
        4: 'unsupported source',
      };
      const label = code ? names[code] || `code ${code}` : 'unknown';
      // eslint-disable-next-line no-console
      console.warn(`[VinylPlayer] playback failed (${label}) for`, activeSrc);

      if (code === 4 && !usedFallbackRef.current && fallbackSrc) {
        usedFallbackRef.current = true;
        // eslint-disable-next-line no-console
        console.warn('[VinylPlayer] retrying with fallback source');
        setActiveSrc(fallbackSrc);
        setError(null);
        setIsLoading(true);
        return;
      }
      setIsPlaying(false);
      setIsLoading(false);
      droppingRef.current = false;
      setIsNeedleDropping(false);
      const message =
        code === 2
          ? 'The mastered audio file could not be fetched from the server.'
          : code === 3
          ? 'The mastered audio could not be decoded by this browser.'
          : 'This record could not be played.';
      setError(message);
      onError?.(message);
    };

    element.addEventListener('loadedmetadata', onLoaded);
    element.addEventListener('durationchange', onLoaded);
    element.addEventListener('timeupdate', onTime);
    element.addEventListener('seeked', onTime);
    element.addEventListener('waiting', onWaiting);
    element.addEventListener('playing', onPlaying);
    element.addEventListener('canplay', onPlaying);
    element.addEventListener('ended', onEnded);
    element.addEventListener('error', onMediaError);

    return () => {
      element.removeEventListener('loadedmetadata', onLoaded);
      element.removeEventListener('durationchange', onLoaded);
      element.removeEventListener('timeupdate', onTime);
      element.removeEventListener('seeked', onTime);
      element.removeEventListener('waiting', onWaiting);
      element.removeEventListener('playing', onPlaying);
      element.removeEventListener('canplay', onPlaying);
      element.removeEventListener('ended', onEnded);
      element.removeEventListener('error', onMediaError);
    };
  }, [activeSrc, fallbackSrc, onError]);

  // Swap the element's source when the record (or the fallback) changes.
  useEffect(() => {
    const element = audioRef.current;
    usedFallbackRef.current = false;
    setError(null);
    setCurrentTime(0);
    setIsPlaying(false);
    setIsReady(false);
    setIsLoading(true);
    if (!element) return;
    if (activeSrc) {
      if (element.getAttribute('src') !== activeSrc) {
        element.src = activeSrc;
      }
      try {
        element.load();
      } catch {}
    }
  }, [activeSrc]);

  useEffect(() => () => clearTimers(), []);

  /* -------------------------------- controls ------------------------------- */

  const unlock = useCallback(() => {
    [audioRef.current, needleRef.current].forEach((el, i) => {
      if (!el) return;
      const before = el.muted;
      el.muted = true;
      const p = el.play();
      if (p && typeof p.then === 'function') {
        p.then(() => {
          if (i === 0 && !droppingRef.current) el.pause();
          else if (i === 1) el.pause();
          el.muted = before;
          try {
            el.currentTime = 0;
          } catch {}
        }).catch(() => {
          el.muted = before;
        });
      } else {
        el.muted = before;
      }
    });
  }, []);

  const play = useCallback(
    (fromTime?: number) => {
      const element = audioRef.current;
      if (!element || !activeSrc) return;

      const dur = Number.isFinite(element.duration) ? element.duration : 0;
      const raw = typeof fromTime === 'number' ? fromTime : element.currentTime;
      const atTop = raw < 0.7 || (dur > 0 && raw > dur - 0.35);
      const startAt = atTop ? 0 : raw;
      resumeAtRef.current = startAt;

      clearTimers();
      element.volume = 0;
      try {
        element.currentTime = startAt;
      } catch {}

      // Very short clips must not lose their first seconds to the ritual.
      const ritualMs = dur > 0 ? Math.max(0, Math.min(needleDelayMs, (dur - 0.35) * 1000)) : needleDelayMs;
      const withRitual = atTop && ritualMs > 120 && !!needleSrc && !!needleRef.current;

      if (withRitual) {
        const needle = needleRef.current!;
        try {
          needle.pause();
          needle.currentTime = 0;
          needle.volume = Math.min(1, targetVolume() + 0.05);
        } catch {}
        needle.play().catch(() => {
          /* SFX is decoration — never block the voice on it */
        });
        droppingRef.current = true;
        setIsNeedleDropping(true);
      } else {
        element.volume = targetVolume();
      }

      // Started inside the click handler, so the deferred reveal is always allowed.
      const started = element.play();
      if (started && typeof started.catch === 'function') {
        started.catch((err: DOMException) => {
          droppingRef.current = false;
          setIsNeedleDropping(false);
          setIsPlaying(false);
          // eslint-disable-next-line no-console
          console.warn('[VinylPlayer] play() rejected:', err?.name, err?.message);
          if (err?.name !== 'AbortError') {
            setError(
              err?.name === 'NotAllowedError'
                ? 'Your browser blocked autoplay — tap play once more.'
                : 'This record could not be started.'
            );
          }
        });
      }

      if (withRitual) {
        dropTimerRef.current = setTimeout(() => {
          dropTimerRef.current = null;
          if (!droppingRef.current) return;
          stopDropping(element, resumeAtRef.current);
          setIsPlaying(true);
        }, ritualMs);
      } else {
        setIsPlaying(true);
      }
    },
    [activeSrc, needleSrc, needleDelayMs, stopDropping]
  );

  const pause = useCallback(() => {
    const element = audioRef.current;
    clearTimers();
    if (element) cancelDrop(element);
    if (element) {
      try {
        element.pause();
      } catch {}
      element.volume = targetVolume();
    }
    const needle = needleRef.current;
    if (needle) {
      try {
        needle.pause();
        needle.currentTime = 0;
      } catch {}
    }
    setIsPlaying(false);
  }, [cancelDrop]);

  const toggle = useCallback(() => {
    const element = audioRef.current;
    if (!element) return;
    if (isPlaying || droppingRef.current) pause();
    else play();
  }, [isPlaying, pause, play]);

  const restart = useCallback(() => play(0), [play]);

  const seek = useCallback(
    (time: number) => {
      const element = audioRef.current;
      if (!element) return;
      const clamped = Math.max(0, Number.isFinite(duration) && duration > 0 ? Math.min(time, duration - 0.05) : time);
      if (clamped < 0.7 && (isPlaying || droppingRef.current)) {
        play(0);
        return;
      }
      cancelDrop(element);
      try {
        element.currentTime = clamped;
      } catch {}
      setCurrentTime(clamped);
      if (isPlaying) element.volume = targetVolume();
    },
    [cancelDrop, duration, isPlaying, play]
  );

  const applyVolume = (value: number) => {
    const element = audioRef.current;
    if (!element) return;
    if (droppingRef.current) return; // the fade owns the element while the arm falls
    element.volume = Math.max(0, Math.min(1, value));
  };

  const setVolume = useCallback(
    (value: number) => {
      const v = Math.max(0, Math.min(1, value));
      volumeRef.current = v;
      setVolumeState(v);
      if (v > 0 && mutedRef.current) {
        mutedRef.current = false;
        setIsMuted(false);
      }
      applyVolume(targetVolume());
      const needle = needleRef.current;
      if (needle) needle.volume = Math.min(1, targetVolume() + 0.05);
    },
    []
  );

  const toggleMute = useCallback(() => {
    mutedRef.current = !mutedRef.current;
    setIsMuted(mutedRef.current);
    applyVolume(targetVolume());
    const needle = needleRef.current;
    if (needle) needle.volume = Math.min(1, targetVolume() + 0.05);
  }, []);

  // Re-apply volume whenever the mix target changes (mute, fade end, etc).
  useEffect(() => {
    const element = audioRef.current;
    if (!element || droppingRef.current) return;
    element.volume = targetVolume();
  }, [volume, isMuted]);

  return {
    audioRef,
    needleRef,
    activeSrc,
    isPlaying,
    isNeedleDropping,
    isReady,
    isLoading,
    currentTime,
    duration,
    volume,
    isMuted,
    error,
    play,
    pause,
    toggle,
    restart,
    seek,
    setVolume,
    toggleMute,
    unlock,
  };
}
