'use client';

import React, { useState, useRef } from 'react';
import Link from 'next/link';
import { Recording } from '@/types';
import { Button } from '@/components/ui/Button';
import { 
  Disc3, 
  Play, 
  Pause, 
  ExternalLink, 
  Trash2, 
  Eye, 
  Heart, 
  Sparkles, 
  Filter, 
  Search 
} from 'lucide-react';
import toast from 'react-hot-toast';

interface AdminRecordingsTabProps {
  recordings: Recording[];
  onDeleteRecording: (id: string) => Promise<void>;
}

export const AdminRecordingsTab: React.FC<AdminRecordingsTabProps> = ({
  recordings,
  onDeleteRecording,
}) => {
  const [playingId, setPlayingId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const filtered = recordings.filter((r) => {
    const q = searchQuery.toLowerCase();
    return (
      r.title.toLowerCase().includes(q) ||
      (r.recipient_name && r.recipient_name.toLowerCase().includes(q)) ||
      (r.sender_name && r.sender_name.toLowerCase().includes(q)) ||
      r.slug.toLowerCase().includes(q)
    );
  });

  const togglePlay = (rec: Recording) => {
    if (playingId === rec.id) {
      if (audioRef.current) audioRef.current.pause();
      setPlayingId(null);
    } else {
      if (audioRef.current) {
        audioRef.current.src = rec.processed_audio_url;
        audioRef.current.play().catch(() => {});
        setPlayingId(rec.id);
      }
    }
  };

  const handleDelete = async (id: string, title: string) => {
    if (!confirm(`Are you sure you want to permanently delete recording "${title}"?`)) return;
    try {
      await onDeleteRecording(id);
      toast.success('Recording purged from wax archives');
    } catch {
      toast.error('Failed to delete recording');
    }
  };

  return (
    <div className="p-6 rounded-3xl bg-stone-900/80 border border-stone-800 space-y-6 shadow-xl">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-stone-800 pb-4">
        <div>
          <h3 className="font-serif font-bold text-lg text-amber-100 flex items-center gap-2">
            <Disc3 className="w-5 h-5 text-amber-500" />
            <span>Recordings Moderation & Audio Vault ({recordings.length})</span>
          </h3>
          <p className="text-xs text-stone-400">
            Preview synthesized vinyl masters, monitor play counts, and moderate user content.
          </p>
        </div>

        {/* Search */}
        <div className="relative min-w-[240px]">
          <Search className="w-4 h-4 text-stone-500 absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search title, recipient, slug..."
            className="w-full bg-stone-950 border border-stone-700 rounded-xl pl-9 pr-3 py-1.5 text-xs text-stone-100 focus:outline-none focus:border-amber-500"
          />
        </div>
      </div>

      <div className="space-y-3">
        {filtered.map((rec) => {
          const isPlaying = playingId === rec.id;

          return (
            <div
              key={rec.id}
              className="p-4 rounded-2xl bg-stone-950 border border-stone-800/80 hover:border-amber-700/40 flex flex-col sm:flex-row sm:items-center justify-between gap-4 transition-all"
            >
              <div className="flex items-center gap-4">
                <button
                  type="button"
                  onClick={() => togglePlay(rec)}
                  className={`w-11 h-11 rounded-2xl flex items-center justify-center transition-all ${
                    isPlaying
                      ? 'bg-amber-500 text-stone-950 animate-pulse shadow-lg shadow-amber-500/30'
                      : 'bg-stone-800 text-stone-300 hover:text-amber-300 hover:bg-stone-700'
                  }`}
                  title="Preview Audio"
                >
                  {isPlaying ? (
                    <Pause className="w-5 h-5 fill-current" />
                  ) : (
                    <Play className="w-5 h-5 fill-current ml-0.5" />
                  )}
                </button>

                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <h4 className="text-sm font-serif font-bold text-stone-100">{rec.title}</h4>
                    <span className="px-2 py-0.5 rounded-full bg-stone-900 border border-amber-600/30 text-amber-300 font-mono text-[10px] uppercase">
                      {rec.vinyl_style.replace('_', ' ')}
                    </span>
                  </div>

                  <div className="flex flex-wrap items-center gap-3 text-xs text-stone-400">
                    {rec.recipient_name && (
                      <span className="flex items-center gap-1 text-amber-200">
                        <Heart className="w-3 h-3 text-red-500 inline" />
                        <span>To: {rec.recipient_name}</span>
                      </span>
                    )}
                    <span className="capitalize text-stone-500">
                      Filter: {rec.filter_preset}
                    </span>
                    <span className="font-mono text-[11px] text-stone-500 flex items-center gap-1">
                      <Eye className="w-3 h-3" />
                      {rec.views || 0} plays
                    </span>
                    <span className="font-mono text-[11px] text-stone-500">
                      {new Date(rec.created_at).toLocaleDateString()}
                    </span>
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2 self-end sm:self-auto">
                <Link
                  href={`/play/${rec.slug}`}
                  target="_blank"
                  className="px-3 py-1.5 rounded-xl bg-stone-800 hover:bg-stone-700 text-stone-300 hover:text-amber-300 text-xs font-mono flex items-center gap-1.5 border border-stone-700 transition-colors"
                >
                  <span>Open 3D Player</span>
                  <ExternalLink className="w-3.5 h-3.5" />
                </Link>

                <button
                  onClick={() => handleDelete(rec.id, rec.title)}
                  className="p-2 rounded-xl text-stone-500 hover:text-red-400 hover:bg-stone-800 transition-colors"
                  title="Purge Recording"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            </div>
          );
        })}
      </div>

      <audio ref={audioRef} onEnded={() => setPlayingId(null)} className="hidden" />
    </div>
  );
};
