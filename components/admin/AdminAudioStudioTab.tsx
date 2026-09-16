'use client';

import React, { useEffect, useState, useRef } from 'react';
import { AudioAsset, AudioCategory } from '@/types';
import { Button } from '@/components/ui/Button';
import { 
  Upload, 
  Mic, 
  Square, 
  Play, 
  Pause, 
  Trash2, 
  Music, 
  Sparkles, 
  Volume2, 
  Check,
  Plus,
  Scissors
} from 'lucide-react';
import toast from 'react-hot-toast';

interface AdminAudioStudioTabProps {
  assets: AudioAsset[];
  onAssetAdded: (asset: AudioAsset) => void;
  onAssetDeleted: (id: string) => void;
  onAssetUpdated: (asset: AudioAsset) => void;
}

export const AdminAudioStudioTab: React.FC<AdminAudioStudioTabProps> = ({
  assets,
  onAssetAdded,
  onAssetDeleted,
  onAssetUpdated,
}) => {
  // Upload State
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadTitle, setUploadTitle] = useState<string>('');
  const [uploadCategory, setUploadCategory] = useState<AudioCategory>('bg_music');
  const [uploadIsPremium, setUploadIsPremium] = useState<boolean>(false);
  const [isUploading, setIsUploading] = useState<boolean>(false);
  const [uploadPreviewUrl, setUploadPreviewUrl] = useState<string | null>(null);
  const [uploadDuration, setUploadDuration] = useState<number>(0);
  const [trimEnabled, setTrimEnabled] = useState<boolean>(false);
  const [trimStart, setTrimStart] = useState<number>(0);
  const [trimEnd, setTrimEnd] = useState<number>(0);
  const [uploadInputKey, setUploadInputKey] = useState<number>(0);
  const uploadPreviewRef = useRef<HTMLAudioElement | null>(null);

  // Live Browser Mic Recorder Widget
  const [isRecordingMic, setIsRecordingMic] = useState<boolean>(false);
  const [micDuration, setMicDuration] = useState<number>(0);
  const [recordedBlob, setRecordedBlob] = useState<Blob | null>(null);
  const [micTitle, setMicTitle] = useState<string>('Custom Studio Effect');
  const [micCategory, setMicCategory] = useState<AudioCategory>('sound_effect');
  const [micPreviewUrl, setMicPreviewUrl] = useState<string | null>(null);

  // Audio Playback Preview State
  const [playingAssetId, setPlayingAssetId] = useState<string | null>(null);
  const playerRef = useRef<HTMLAudioElement | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const micChunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<NodeJS.Timeout | null>(null);

  // 1. File Upload. The browser sends bytes directly to R2; the Vercel API
  // receives only an intent request and the final metadata row.
  const uploadAsset = async (input: {
    file: File;
    title: string;
    category: AudioCategory;
    isPremiumOnly: boolean;
    durationSeconds: number;
    trimStartSeconds: number;
    trimEndSeconds: number;
  }): Promise<AudioAsset> => {
    const intentRes = await fetch('/api/audio/upload-asset', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'create_upload',
        filename: input.file.name,
        contentType: input.file.type,
        size: input.file.size,
      }),
    });
    const intent = await intentRes.json();
    if (!intentRes.ok) throw new Error(intent.error || 'Could not prepare persistent upload.');

    const uploadRes = await fetch(intent.uploadUrl, {
      method: 'PUT',
      headers: intent.headers || { 'Content-Type': input.file.type },
      body: input.file,
    });
    if (!uploadRes.ok) {
      const failure = await uploadRes.json().catch(() => ({}));
      throw new Error(failure.error || `Object upload failed (${uploadRes.status}).`);
    }

    const completeRes = await fetch('/api/audio/upload-asset', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'complete_upload',
        assetId: intent.assetId,
        key: intent.key,
        intent: intent.intent,
        filename: input.file.name,
        contentType: input.file.type,
        size: input.file.size,
        title: input.title,
        category: input.category,
        isPremiumOnly: input.isPremiumOnly,
        durationSeconds: input.durationSeconds,
        trimStartSeconds: input.trimStartSeconds,
        trimEndSeconds: input.trimEndSeconds,
      }),
    });
    const completed = await completeRes.json();
    if (!completeRes.ok || !completed.asset) throw new Error(completed.error || 'Could not save the uploaded asset.');
    return completed.asset as AudioAsset;
  };

  const clearUploadSelection = () => {
    if (uploadPreviewRef.current) uploadPreviewRef.current.pause();
    setUploadPreviewUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return null;
    });
    setUploadFile(null);
    setUploadTitle('');
    setUploadDuration(0);
    setTrimEnabled(false);
    setTrimStart(0);
    setTrimEnd(0);
    setUploadInputKey((value) => value + 1);
  };

  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!uploadFile) {
      toast.error('Please choose an audio file to upload');
      return;
    }
    if (trimEnabled && trimEnd - trimStart < 0.1) {
      toast.error('Choose an audio section at least 0.1 seconds long.');
      return;
    }

    try {
      setIsUploading(true);
      const asset = await uploadAsset({
        file: uploadFile,
        title: uploadTitle.trim() || uploadFile.name,
        category: uploadCategory,
        isPremiumOnly: uploadIsPremium,
        durationSeconds: uploadDuration,
        trimStartSeconds: trimEnabled ? trimStart : 0,
        trimEndSeconds: trimEnabled ? trimEnd : uploadDuration,
      });
      toast.success(`Asset "${asset.title}" uploaded and available in Studio.`);
      onAssetAdded(asset);
      clearUploadSelection();
    } catch (err: any) {
      toast.error(err.message || 'Error uploading asset', { duration: 7000 });
    } finally {
      setIsUploading(false);
    }
  };

  // 2. Live Mic Recorder for Custom Sound Effects
  const startMicRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      micStreamRef.current = stream;
      const recorder = new MediaRecorder(stream);
      mediaRecorderRef.current = recorder;
      micChunksRef.current = [];

      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) micChunksRef.current.push(e.data);
      };

      recorder.onstop = () => {
        const blob = new Blob(micChunksRef.current, { type: 'audio/webm' });
        setRecordedBlob(blob);
        const url = URL.createObjectURL(blob);
        setMicPreviewUrl((previousUrl) => {
          if (previousUrl) URL.revokeObjectURL(previousUrl);
          return url;
        });
      };

      recorder.start(100);
      setIsRecordingMic(true);
      setMicDuration(0);

      timerRef.current = setInterval(() => {
        setMicDuration((prev) => prev + 1);
      }, 1000);
      toast.success('Live mic recording started');
    } catch (err) {
      toast.error('Could not access microphone');
    }
  };

  const stopMicRecording = () => {
    if (mediaRecorderRef.current && isRecordingMic) {
      mediaRecorderRef.current.stop();
      if (timerRef.current) clearInterval(timerRef.current);
      micStreamRef.current?.getTracks().forEach((track) => track.stop());
      micStreamRef.current = null;
      setIsRecordingMic(false);
      toast.success('Sound effect recorded! Preview or save to library.');
    }
  };

  const saveMicRecording = async () => {
    if (!recordedBlob) return;

    try {
      setIsUploading(true);
      const file = new File([recordedBlob], `${micTitle.toLowerCase().replace(/\s+/g, '-')}.webm`, {
        type: 'audio/webm',
      });

      const asset = await uploadAsset({
        file,
        title: micTitle,
        category: micCategory,
        isPremiumOnly: false,
        durationSeconds: micDuration,
        trimStartSeconds: 0,
        trimEndSeconds: micDuration,
      });
      toast.success(`Microphone asset "${asset.title}" saved!`);
      onAssetAdded(asset);
      setRecordedBlob(null);
      setMicPreviewUrl((previousUrl) => {
        if (previousUrl) URL.revokeObjectURL(previousUrl);
        return null;
      });
    } catch (err: any) {
      toast.error(err.message || 'Failed to save recording');
    } finally {
      setIsUploading(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to remove this audio asset?')) return;
    try {
      const res = await fetch(`/api/audio/upload-asset?id=${id}`, { method: 'DELETE' });
      if (res.ok) {
        onAssetDeleted(id);
        toast.success('Audio asset removed');
      } else {
        const data = await res.json();
        throw new Error(data.error || 'Failed to delete asset');
      }
    } catch (err: any) {
      toast.error(err.message || 'Failed to delete asset');
    }
  };

  const updateAsset = async (id: string, updates: Partial<AudioAsset>) => {
    try {
      const res = await fetch('/api/audio/upload-asset', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, updates }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Update failed');
      onAssetUpdated(data.asset);
    } catch (err: any) {
      toast.error(err.message || 'Failed to update asset');
    }
  };

  const setCategoryEnabled = async (category: AudioCategory, enabled: boolean) => {
    const matching = assets.filter((asset) => asset.category === category);
    await Promise.all(matching.map((asset) => updateAsset(asset.id, { is_enabled: enabled })));
    toast.success(`${category.replace('_', ' ')} sounds ${enabled ? 'enabled' : 'disabled'}`);
  };

  const togglePlayAsset = (asset: AudioAsset) => {
    if (playingAssetId === asset.id) {
      if (playerRef.current) playerRef.current.pause();
      setPlayingAssetId(null);
    } else {
      if (playerRef.current) {
        playerRef.current.src = asset.file_url;
        playerRef.current.currentTime = Math.max(0, asset.trim_start_seconds || 0);
        playerRef.current.play()
          .then(() => setPlayingAssetId(asset.id))
          .catch(() => {
            setPlayingAssetId(null);
            toast.error('This audio asset is unavailable.');
          });
      }
    }
  };

  useEffect(() => {
    return () => {
      if (uploadPreviewUrl) URL.revokeObjectURL(uploadPreviewUrl);
    };
  }, [uploadPreviewUrl]);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      if (mediaRecorderRef.current?.state === 'recording') mediaRecorderRef.current.stop();
      micStreamRef.current?.getTracks().forEach((track) => track.stop());
      if (micPreviewUrl) URL.revokeObjectURL(micPreviewUrl);
    };
  }, [micPreviewUrl]);

  return (
    <div className="space-y-8">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 1. File Upload Card */}
        <form
          onSubmit={handleUploadSubmit}
          className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4 shadow-xl"
        >
          <div className="flex items-center gap-2 border-b border-stone-800 pb-3">
            <Upload className="w-5 h-5 text-amber-500" />
            <h3 className="font-serif font-bold text-lg text-amber-100">
              Upload Ambient / Static Asset
            </h3>
          </div>

          <div className="space-y-3">
            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">
                Asset Title
              </label>
              <input
                type="text"
                value={uploadTitle}
                onChange={(e) => setUploadTitle(e.target.value)}
                placeholder="e.g. 1930s Fireside Crackle"
                className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-sm text-stone-100"
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-mono text-stone-300 mb-1">
                  Category
                </label>
                <select
                  value={uploadCategory}
                  onChange={(e) => setUploadCategory(e.target.value as AudioCategory)}
                  className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs text-stone-100"
                >
                  <option value="bg_music">Background Melody</option>
                  <option value="crackle">Vinyl Crackle Loop</option>
                  <option value="sound_effect">Turntable Sound FX</option>
                </select>
              </div>

              <div className="flex items-center pt-5">
                <label className="flex items-center gap-2 text-xs text-stone-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={uploadIsPremium}
                    onChange={(e) => setUploadIsPremium(e.target.checked)}
                    className="rounded accent-amber-500"
                  />
                  <span>Premium Tier Only</span>
                </label>
              </div>
            </div>

            <div>
              <label className="block text-xs font-mono text-stone-300 mb-1">
                Select MP3 / WAV File
              </label>
              <input
                key={uploadInputKey}
                type="file"
                accept="audio/*,.m4a,.flac,.aac,.opus"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (!f) return;
                  if (uploadPreviewUrl) URL.revokeObjectURL(uploadPreviewUrl);
                  const previewUrl = URL.createObjectURL(f);
                  setUploadFile(f);
                  setUploadPreviewUrl(previewUrl);
                  setUploadDuration(0);
                  setTrimEnabled(false);
                  setTrimStart(0);
                  setTrimEnd(0);
                  if (!uploadTitle) setUploadTitle(f.name.replace(/\.[^/.]+$/, ''));
                }}
                className="w-full text-xs text-stone-400 file:mr-3 file:py-2 file:px-4 file:rounded-xl file:border-0 file:text-xs file:font-semibold file:bg-stone-800 file:text-amber-300 hover:file:bg-stone-700 cursor-pointer"
              />
            </div>

            {uploadPreviewUrl && (
              <div className="rounded-2xl border border-amber-900/40 bg-stone-950/80 p-3 space-y-3">
                <audio
                  ref={uploadPreviewRef}
                  src={uploadPreviewUrl}
                  controls
                  preload="metadata"
                  className="h-9 w-full"
                  onLoadedMetadata={(event) => {
                    const duration = Number(event.currentTarget.duration);
                    if (!Number.isFinite(duration) || duration <= 0) return;
                    setUploadDuration(duration);
                    setTrimStart(0);
                    setTrimEnd(duration);
                  }}
                  onPlay={(event) => {
                    if (trimEnabled && (event.currentTarget.currentTime < trimStart || event.currentTarget.currentTime >= trimEnd)) {
                      event.currentTarget.currentTime = trimStart;
                    }
                  }}
                  onTimeUpdate={(event) => {
                    if (trimEnabled && trimEnd > trimStart && event.currentTarget.currentTime >= trimEnd) {
                      event.currentTarget.pause();
                      event.currentTarget.currentTime = trimStart;
                    }
                  }}
                />

                <label className="flex cursor-pointer items-center gap-2 text-xs font-semibold text-amber-200">
                  <input
                    type="checkbox"
                    checked={trimEnabled}
                    disabled={uploadDuration <= 0}
                    onChange={(event) => setTrimEnabled(event.target.checked)}
                    className="accent-amber-500"
                  />
                  <Scissors className="h-3.5 w-3.5" />
                  Use only a specific section
                </label>

                {trimEnabled && uploadDuration > 0 && (
                  <div className="space-y-3">
                    <div className="grid grid-cols-2 gap-3">
                      <label className="text-[10px] font-mono text-stone-400">
                        Start (seconds)
                        <input
                          type="number"
                          min={0}
                          max={Math.max(0, trimEnd - 0.1)}
                          step={0.1}
                          value={Number(trimStart.toFixed(1))}
                          onChange={(event) => setTrimStart(Math.max(0, Math.min(Number(event.target.value), trimEnd - 0.1)))}
                          className="mt-1 w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1.5 text-xs text-stone-100"
                        />
                      </label>
                      <label className="text-[10px] font-mono text-stone-400">
                        End (seconds)
                        <input
                          type="number"
                          min={trimStart + 0.1}
                          max={uploadDuration}
                          step={0.1}
                          value={Number(trimEnd.toFixed(1))}
                          onChange={(event) => setTrimEnd(Math.min(uploadDuration, Math.max(Number(event.target.value), trimStart + 0.1)))}
                          className="mt-1 w-full rounded-lg border border-stone-700 bg-stone-900 px-2 py-1.5 text-xs text-stone-100"
                        />
                      </label>
                    </div>
                    <div className="space-y-1">
                      <input
                        aria-label="Trim start"
                        type="range"
                        min={0}
                        max={uploadDuration}
                        step={0.1}
                        value={trimStart}
                        onChange={(event) => setTrimStart(Math.min(Number(event.target.value), trimEnd - 0.1))}
                        className="w-full accent-amber-500"
                      />
                      <input
                        aria-label="Trim end"
                        type="range"
                        min={0}
                        max={uploadDuration}
                        step={0.1}
                        value={trimEnd}
                        onChange={(event) => setTrimEnd(Math.max(Number(event.target.value), trimStart + 0.1))}
                        className="w-full accent-amber-600"
                      />
                    </div>
                    <div className="flex items-center justify-between text-[10px] font-mono text-stone-400">
                      <span>Selected: {(trimEnd - trimStart).toFixed(1)}s of {uploadDuration.toFixed(1)}s</span>
                      <button
                        type="button"
                        onClick={() => {
                          if (!uploadPreviewRef.current) return;
                          uploadPreviewRef.current.currentTime = trimStart;
                          uploadPreviewRef.current.play().catch(() => toast.error('Preview could not be played.'));
                        }}
                        className="rounded-lg border border-amber-700/50 px-2 py-1 text-amber-300 hover:bg-amber-950/50"
                      >
                        Preview cut
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="pt-2">
            <Button
              type="submit"
              variant="primary"
              size="md"
              disabled={!uploadFile}
              isLoading={isUploading}
              leftIcon={<Plus className="w-4 h-4 text-stone-950" />}
              className="w-full"
            >
              Add to Asset Library
            </Button>
          </div>
        </form>

        {/* 2. Live Mic Recorder Studio Widget */}
        <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4 shadow-xl">
          <div className="flex items-center gap-2 border-b border-stone-800 pb-3">
            <Mic className="w-5 h-5 text-amber-500" />
            <h3 className="font-serif font-bold text-lg text-amber-100">
              Live Mic Sound Effect Recorder
            </h3>
          </div>

          <p className="text-xs text-stone-400">
            Record mechanical vinyl clicks, custom vocal drops, or room ambience directly through your browser microphone.
          </p>

          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-mono text-stone-300 mb-1">
                  Effect Title
                </label>
                <input
                  type="text"
                  value={micTitle}
                  onChange={(e) => setMicTitle(e.target.value)}
                  className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs text-stone-100"
                />
              </div>

              <div>
                <label className="block text-xs font-mono text-stone-300 mb-1">
                  Category
                </label>
                <select
                  value={micCategory}
                  onChange={(e) => setMicCategory(e.target.value as AudioCategory)}
                  className="w-full bg-stone-950 border border-stone-700 rounded-xl px-3 py-2 text-xs text-stone-100"
                >
                  <option value="sound_effect">Sound Effect</option>
                  <option value="crackle">Crackle Loop</option>
                  <option value="bg_music">Background Melody</option>
                </select>
              </div>
            </div>

            {/* Mic Controls */}
            <div className="flex items-center justify-between p-3 bg-stone-950 rounded-2xl border border-stone-800">
              <div className="flex items-center gap-3">
                {isRecordingMic ? (
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={stopMicRecording}
                    leftIcon={<Square className="w-4 h-4 fill-current" />}
                  >
                    Stop ({micDuration}s)
                  </Button>
                ) : (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={startMicRecording}
                    leftIcon={<Mic className="w-4 h-4 text-amber-400" />}
                  >
                    Record Live Mic
                  </Button>
                )}

                {micPreviewUrl && (
                  <audio src={micPreviewUrl} controls className="h-8 max-w-[180px]" />
                )}
              </div>

              {recordedBlob && (
                <Button
                  variant="primary"
                  size="sm"
                  onClick={saveMicRecording}
                  isLoading={isUploading}
                  leftIcon={<Check className="w-4 h-4 text-stone-950" />}
                >
                  Save to Library
                </Button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 3. Audio Asset Library Table */}
      <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-4">
        <div className="flex items-center justify-between border-b border-stone-800 pb-3">
          <div className="flex items-center gap-2">
            <Volume2 className="w-5 h-5 text-amber-500" />
            <h3 className="font-serif font-bold text-lg text-amber-100">
              Preserved Audio Assets ({assets.length})
            </h3>
          </div>
          <div className="flex flex-wrap justify-end gap-1.5">
            {(['bg_music', 'crackle', 'sound_effect'] as AudioCategory[]).map((category) => (
              <div key={category} className="flex rounded-lg overflow-hidden border border-stone-700">
                <button onClick={() => setCategoryEnabled(category, true)} className="px-2 py-1 text-[10px] text-emerald-300 hover:bg-emerald-950">Enable {category.replace('_', ' ')}</button>
                <button onClick={() => setCategoryEnabled(category, false)} className="px-2 py-1 text-[10px] text-red-300 hover:bg-red-950">Disable</button>
              </div>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          {assets.map((asset) => {
            const isPlaying = playingAssetId === asset.id;

            return (
              <div
                key={asset.id}
                className="flex items-center justify-between p-3 rounded-2xl bg-stone-950 border border-stone-800/80 hover:border-amber-700/40 transition-all"
              >
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => togglePlayAsset(asset)}
                    className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${
                      isPlaying
                        ? 'bg-amber-500 text-stone-950 animate-pulse'
                        : 'bg-stone-800 text-stone-300 hover:text-amber-300'
                    }`}
                  >
                    {isPlaying ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
                  </button>

                  <div>
                    <h4 className="text-sm font-medium text-stone-100">{asset.title}</h4>
                    <div className="flex items-center gap-2 text-[10px] font-mono text-stone-400">
                      <span className="capitalize">{asset.category.replace('_', ' ')}</span>
                      {asset.trim_end_seconds && asset.trim_end_seconds > (asset.trim_start_seconds || 0) && (
                        <span className="text-sky-300">• cut {(asset.trim_start_seconds || 0).toFixed(1)}s–{asset.trim_end_seconds.toFixed(1)}s</span>
                      )}
                      {asset.is_premium_only && (
                        <span className="text-amber-400 font-bold">• Gold Master Exclusive</span>
                      )}
                    </div>
                    <div className="mt-2 flex items-center gap-3 text-[10px] text-stone-400">
                      <label className="flex items-center gap-1 cursor-pointer">
                        <input type="checkbox" checked={asset.is_enabled !== false} onChange={(e) => updateAsset(asset.id, { is_enabled: e.target.checked })} className="accent-emerald-500" />
                        Available to users
                      </label>
                      <label className="flex items-center gap-1 cursor-pointer">
                        <input type="checkbox" checked={asset.is_premium_only} onChange={(e) => updateAsset(asset.id, { is_premium_only: e.target.checked })} className="accent-amber-500" />
                        Premium
                      </label>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <label className="hidden sm:flex items-center gap-2 text-[10px] text-stone-400">
                    Default level
                    <input type="range" min="0" max="100" step="5" value={Math.round((asset.default_volume ?? (asset.category === 'bg_music' ? 0.18 : 0.25)) * 100)} onChange={(e) => updateAsset(asset.id, { default_volume: Number(e.target.value) / 100 })} className="w-20 accent-amber-500" />
                    {Math.round((asset.default_volume ?? (asset.category === 'bg_music' ? 0.18 : 0.25)) * 100)}%
                  </label>
                  <button
                    onClick={() => handleDelete(asset.id)}
                    className="p-2 text-stone-500 hover:text-red-400 transition-colors"
                    title="Delete Asset"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      <audio
        ref={playerRef}
        onTimeUpdate={(event) => {
          const asset = assets.find((item) => item.id === playingAssetId);
          if (!asset?.trim_end_seconds || event.currentTarget.currentTime < asset.trim_end_seconds) return;
          event.currentTarget.pause();
          event.currentTarget.currentTime = Math.max(0, asset.trim_start_seconds || 0);
          setPlayingAssetId(null);
        }}
        onEnded={() => setPlayingAssetId(null)}
        className="hidden"
      />
    </div>
  );
};
