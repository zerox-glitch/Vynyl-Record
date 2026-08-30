import { NextRequest, NextResponse } from 'next/server';
import { enqueueJob } from '@/lib/processing/queue';
import { saveRecording } from '@/lib/db';
import { getCustomerUser } from '@/lib/supabase/auth';
import { FilterPresetType, OccasionType, Recording, VinylStyleType } from '@/types';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function safeSlug(title: string): string {
  const chars = 'abcdefghjkmnpqrstuvwxyz23456789';
  let suffix = '';
  for (let i = 0; i < 6; i++) suffix += chars[Math.floor(Math.random() * chars.length)];
  return `${title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 28) || 'voice-note'}-${suffix}`;
}

function safeText(value: unknown, max: number): string {
  return typeof value === 'string' ? value.trim().slice(0, max) : '';
}
function safeNumber(value: unknown, fallback: number, min: number, max: number): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback;
}
function safeId(value: unknown): string | null {
  return typeof value === 'string' && /^[a-zA-Z0-9_-]{3,100}$/.test(value) ? value : null;
}

/**
 * Queue a recording after the browser has uploaded its original directly to
 * R2 (or to the signed local fallback). No audio bytes enter this request and
 * no FFmpeg runs here. The response is intentionally fast: the UI polls the
 * status endpoint and routes to the finished record only after completion.
 */
export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const recordId = safeId(body?.recordId);
    const originalStorageKey = safeText(body?.originalStorageKey, 240);
    const originalUrl = safeText(body?.originalUrl, 500);
    const originalContentType = safeText(body?.originalContentType, 80);

    if (!recordId) return NextResponse.json({ error: 'A valid record id is required.' }, { status: 400 });
    if (!originalStorageKey && !originalUrl) {
      return NextResponse.json({ error: 'The original upload has not been confirmed.' }, { status: 400 });
    }
    const customer = await getCustomerUser();
    const ownerUserId = customer?.id || 'anonymous';
    if (originalStorageKey && !originalStorageKey.startsWith(`users/${ownerUserId}/records/${recordId}/original/`)) {
      return NextResponse.json({ error: 'The original upload path is not owned by this record.' }, { status: 403 });
    }
    if (originalUrl && !originalUrl.startsWith('/api/records/')) {
      return NextResponse.json({ error: 'The original upload URL is invalid.' }, { status: 400 });
    }

    const title = safeText(body?.title, 120) || 'Untitled Memory';
    const recipientName = safeText(body?.recipientName, 120);
    const senderName = safeText(body?.senderName, 120);
    const occasion = safeText(body?.occasion, 40) as OccasionType;
    const filterPreset = (safeText(body?.filterPreset, 30) || 'gramophone') as FilterPresetType;
    const vinylStyle = (safeText(body?.vinylStyle, 40) || 'classic_red') as VinylStyleType;
    const crackleIntensity = safeNumber(body?.crackleIntensity, 0.22, 0, 1);
    const maxSeconds = Math.round(safeNumber(body?.maxSeconds, 600, 5, 1800));
    const durationSeconds = safeNumber(body?.durationSeconds, 0, 0, maxSeconds);

    const recording: Recording = {
      id: recordId,
      slug: safeSlug(title),
      user_id: customer?.id || null,
      title,
      recipient_name: recipientName,
      sender_name: senderName,
      processed_audio_url: originalUrl || '',
      raw_voice_url: originalUrl || '',
      transcript_json: [],
      vinyl_style: vinylStyle,
      filter_preset: filterPreset,
      crackle_intensity: crackleIntensity,
      bg_music_id: safeText(body?.bgMusicId, 120) || null,
      views: 0,
      created_at: new Date().toISOString(),
      duration_seconds: durationSeconds || undefined,
      visibility: 'unlisted',
      occasion: occasion || null,
      dedication: safeText(body?.dedication, 1000) || null,
      side_a_label: safeText(body?.sideALabel, 80) || null,
      side_b_label: safeText(body?.sideBLabel, 80) || null,
      original_storage_key: originalStorageKey || null,
      processing_state: 'queued',
      processing_progress: 0,
      processing_error: null,
    };

    await saveRecording(recording);
    const job = await enqueueJob({
      recording_id: recording.id,
      user_id: customer?.id || null,
      job_type: 'audio_master',
      params: {
        voiceFileUrl: originalUrl || null,
        originalStorageKey: originalStorageKey || null,
        originalContentType,
        title,
        filterPreset,
        crackleIntensity,
        bgMusicId: safeText(body?.bgMusicId, 120) || null,
        vinylStyle,
        maxSeconds,
      },
    });

    return NextResponse.json({
      success: true,
      queued: true,
      slug: recording.slug,
      recording,
      jobId: job.id,
      statusUrl: `/api/processing/status/${encodeURIComponent(recording.id)}`,
    }, { status: 202 });
  } catch (error: any) {
    console.error('[ProcessingQueue] enqueue error:', error);
    return NextResponse.json({ error: error?.message || 'Could not queue the recording.' }, { status: 500 });
  }
}
