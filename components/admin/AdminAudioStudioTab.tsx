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
  Plus 
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

  // 1. File Upload
  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!uploadFile) {
      toast.error('Please choose an audio file to upload');
      return;
    }

    try {
      setIsUploading(true);
      const formData = new FormData();
      formData.append('file', uploadFile);
      formData.append('title', uploadTitle.trim() || uploadFile.name);
      formData.append('category', uploadCategory);
      formData.append('is_premium_only', uploadIsPremium.toString());

      const res = await fetch('/api/audio/upload-asset', {
        method: 'POST',
        body: formData,
      });

      const data = await res.json();
      if (data.success && data.asset) {
        toast.success(`Asset "${data.asset.title}" uploaded!`);
        onAssetAdded(data.asset);
        setUploadFile(null);
        setUploadTitle('');
      } else {
        throw new Error(data.error || 'Upload failed');
      }
    } catch (err: any) {
      toast.error(err.message || 'Error uploading asset');
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

      const formData = new FormData();
      formData.append('file', file);
      formData.append('title', micTitle);
      formData.append('category', micCategory);
      formData.append('is_premium_only', 'false');

      const res = await fetch('/api/audio/upload-asset', {
        method: 'POST',
        body: formData,
      });

      const data = await res.json();
      if (data.success && data.asset) {
        toast.success(`Microphone asset "${data.asset.title}" saved!`);
        onAssetAdded(data.asset);
        setRecordedBlob(null);
        setMicPreviewUrl((previousUrl) => {
          if (previousUrl) URL.revokeObjectURL(previousUrl);
          return null;
        });
      } else {
        throw new Error(data.error || 'Failed to save recording');
      }
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
                type="file"
                accept="audio/*"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) {
                    setUploadFile(f);
                    if (!uploadTitle) setUploadTitle(f.name.replace(/\.[^/.]+$/, ''));
                  }
                }}
                className="w-full text-xs text-stone-400 file:mr-3 file:py-2 file:px-4 file:rounded-xl file:border-0 file:text-xs file:font-semibold file:bg-stone-800 file:text-amber-300 hover:file:bg-stone-700 cursor-pointer"
              />
            </div>
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

      <audio ref={playerRef} onEnded={() => setPlayingAssetId(null)} className="hidden" />
    </div>
  );
};
