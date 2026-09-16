import { createHash } from 'node:crypto';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';
import { patchLocalStore } from '@/lib/db';

export type FunnelEvent =
  | 'landing_view'
  | 'signup'
  | 'occasion_selected'
  | 'recording_started'
  | 'recording_completed'
  | 'upload_started'
  | 'upload_completed'
  | 'processing_started'
  | 'processing_completed'
  | 'processing_failed'
  | 'share_view'
  | 'share_copied'
  | 'download_requested'
  | 'gift_purchased';

function hash(value: string | null | undefined): string | null {
  return value ? createHash('sha256').update(value).digest('hex').slice(0, 32) : null;
}

/** Privacy-conscious analytics: metadata only, never raw audio/transcripts. */
export async function recordEvent(input: {
  eventType: FunnelEvent;
  recordingId?: string | null;
  userId?: string | null;
  metadata?: Record<string, unknown>;
  ip?: string | null;
  userAgent?: string | null;
}): Promise<void> {
  const row = {
    recording_id: input.recordingId ?? null,
    user_id: input.userId ?? null,
    event_type: input.eventType,
    metadata: input.metadata ?? {},
    ip_hash: hash(input.ip),
    user_agent_hash: hash(input.userAgent),
    created_at: new Date().toISOString(),
  };
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      await supabase.from('record_events').insert(row);
      return;
    } catch { /* analytics must never break the product */ }
  }
  patchLocalStore('recordEvents', (events: unknown) => [
    ...((events as any[]) || []).slice(-499), row,
  ]);
}
