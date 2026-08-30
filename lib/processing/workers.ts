/**
 * processing/workers.ts
 * ----------------------------------------------------------------------------
 * Concrete worker implementations for every job_type the app creates today.
 *
 * Today's jobs:
 *   audio_master    - vintage filter chain + crackle + bg music (FFmpeg)
 *   transcription   - Whisper word-level timestamps (configurable provider)
 *
 * Future hooks documented (job_type unions already on ``ProcessingJob``):
 *   qr_render       - QR-code SVG/PNG generation
 *   video_render    - 9:16 social video MP4 (artwork + waveform + audio)
 *   artwork_resize  - cover image variants at 1280/512/256
 *
 * The default in-process worker is fine for Vercel's free tier until
 * traffic warrants a dedicated worker. To move off, deploy any process
 * that uses ``claimNext()`` + the same worker interfaces on the Railway
 * / Cloudflare worker runtime. No application changes required.
 */

import { ProcessingJob, ProcessingWorker } from '@/types';
import {
  heartbeat,
  completeJob,
  failJob,
} from './queue';
import { processAudioTrack, probeDurationSeconds } from '@/lib/audio/processor';
import { downloadToTmp, persistRecordAudio, cleanupFiles } from '@/lib/audio/storage';
import { getRecordingByIdForStatus, updateRecordingProcessing, saveTranscript } from '@/lib/db';
import { getTranscriptProvider } from '@/lib/transcription/provider';
import { getStorage, buildRecordKey } from '@/lib/storage/r2';

// ============================================================================

/** Audio-master worker. Runs FFmpeg in-process (Vercel free tier). */
export const audioMasterWorker: ProcessingWorker = {
  workerId: 'audio-master-inline-v1',
  canHandle: (jobType) => jobType === 'audio_master',
  async process(
    job: ProcessingJob,
    update: (patch: Partial<ProcessingJob> & { heartbeat?: boolean }) => void
  ): Promise<ProcessingJob> {
    const params = (job.params || {}) as {
      voiceFileUrl?: string;
      bgMusicFilePath?: string | null;
      crackleFilePath?: string | null;
      originalStorageKey?: string | null;
      filterPreset?: string;
      vinylStyle?: string;
      crackleIntensity?: number;
      bgMusicVolume?: number;
      maxSeconds?: number;
    };

    if (!params.voiceFileUrl) {
      throw new Error('audio_master job missing voiceFileUrl');
    }

    update({ result: { stage: 'fetching_sources' } });

    // Pull the input bytes onto disk so FFmpeg can stream the file.
    let sourceUrl = params.voiceFileUrl || '';
    if (!sourceUrl && params.originalStorageKey && getStorage().isR2Configured) {
      sourceUrl = (await getStorage().signedDownloadUrl(params.originalStorageKey, 900)).url;
    }
    if (!sourceUrl) throw new Error('audio_master job has no source URL. Configure R2 or send a local upload URL.');
    const source = await downloadToTmp(sourceUrl, `${job.id}-source`);

    await updateRecordingProcessing(job.recording_id, {
      processing_state: 'processing',
      processing_progress: 15,
      processing_error: null,
      processing_started_at: new Date().toISOString(),
      processing_completed_at: null,
      processed_audio_url: '',
      duration_seconds: undefined,
    });
    update({ result: { stage: 'rendering_master' } });

    const outputPath = source.replace(/(\.\w+)?$/, '-master.mp3');
    const result = await processAudioTrack({
      voiceFilePath: source,
      outputFilePath: outputPath,
      filterPreset: params.filterPreset as any,
      crackleIntensity: params.crackleIntensity ?? 0.2,
      bgMusicFilePath: params.bgMusicFilePath ?? null,
      crackleFilePath: params.crackleFilePath ?? null,
      maxSeconds: params.maxSeconds ?? 600,
    });

    update({ result: { stage: 'persisting_master', durationSeconds: result.durationSeconds } });
    const masterBytes = await import('node:fs/promises').then((mod) => mod.readFile(result.outputFilePath));
    const storage = getStorage();
    let processedUrl = '';
    let processedKey: string | null = null;

    if (storage.isR2Configured) {
      processedKey = buildRecordKey({
        userId: job.user_id || 'anonymous',
        recordId: job.recording_id,
        variant: 'processed',
        filename: 'master.mp3',
      });
      const stored = await storage.putObject(processedKey, new Uint8Array(masterBytes), 'audio/mpeg');
      processedUrl = stored.url || (await storage.signedDownloadUrl(processedKey, 3600)).url;
    } else {
      processedUrl = persistRecordAudio(`record_${job.recording_id}.mp3`, masterBytes).url;
    }

    const duration = result.durationSeconds || (await probeDurationSeconds(result.outputFilePath));
    await updateRecordingProcessing(job.recording_id, {
      processing_state: 'completed',
      processing_progress: 100,
      processing_error: null,
      processing_started_at: new Date().toISOString(),
      processing_completed_at: new Date().toISOString(),
      processed_audio_url: processedUrl,
      duration_seconds: duration,
      processed_storage_key: processedKey,
    });
    cleanupFiles([source, result.outputFilePath]);

    return { ...job, result: {
      stage: 'completed',
      durationSeconds: duration,
      processedAudioUrl: processedUrl,
      processedStorageKey: processedKey,
    }};
  },
};

/**
 * Transcription worker. Provider-gated — failures fail the job cleanly
 * so the studio can show the right UI.
 */
export const transcriptionWorker: ProcessingWorker = {
  workerId: 'transcription-worker-v1',
  canHandle: (jobType) => jobType === 'transcription',
  async process(
    job: ProcessingJob,
    update: (patch: Partial<ProcessingJob> & { heartbeat?: boolean }) => void
  ): Promise<ProcessingJob> {
    const params = (job.params || {}) as { audioPath?: string; durationSeconds?: number; title?: string };
    const provider = getTranscriptProvider();
    if (!provider.isConfigured()) throw new Error('OPENAI_API_KEY is not configured; transcript unavailable');
    if (!params.audioPath) throw new Error('transcription job missing audioPath');
    update({ result: { stage: 'transcribing', provider: provider.id } });
    const words = await provider.transcribe(params.audioPath, params.durationSeconds || 10, params.title);
    await saveTranscript({ recordingId: job.recording_id, words, provider: provider.id, isPubliclyVisible: false });
    return { ...job, result: { stage: 'completed', provider: provider.id, wordCount: words.length } };
  },
};

/** Default registry the consumer can drain. */
export const DEFAULT_WORKERS: ProcessingWorker[] = [
  audioMasterWorker,
  transcriptionWorker,
];

// ============================================================================

/**
 * Drain one queued job (best-effort). The caller decides where to host
 * this loop: an in-process Vercel route, a Railway container, or a
 * Cloudflare Worker (lightweight jobs only — FFmpeg is too heavy for
 * the workers runtime).
 *
 * By default this is a single-process worker: claims, runs, commits.
 * The Railway deployment is a 1:1 swap — same interface.
 */
export async function runOneJob(
  opts: { userId?: string; requeueThresholdMs?: number } = {}
): Promise<{ ran: boolean; state: 'queued' | 'empty' | 'failed' | 'completed' }> {
  const { claimNext } = await import('./queue');
  const audioJob = await claimNext({ jobType: 'audio_master', userId: opts.userId });
  if (audioJob) return runClaimedJob(audioJob, audioMasterWorker);
  const txJob = await claimNext({ jobType: 'transcription', userId: opts.userId });
  if (txJob) return runClaimedJob(txJob, transcriptionWorker);
  return { ran: false, state: 'empty' };
}

async function runClaimedJob(
  job: ProcessingJob,
  worker: ProcessingWorker
): Promise<{ ran: boolean; state: 'queued' | 'empty' | 'failed' | 'completed' }> {
  if (!worker.canHandle(job.job_type)) {
    await heartbeat(job.id);
    await failJob(job.id, `Worker ${worker.workerId} cannot handle ${job.job_type}`, { requeue: true });
    return { ran: false, state: 'queued' };
  }
  try {
    const completed = await worker.process(job, (patch) => heartbeat(job.id, patch));
    await completeJob(job.id, completed.result ?? { stage: 'ok' });
    return { ran: true, state: 'completed' };
  } catch (err: any) {
    const requeue = job.attempts < job.max_attempts;
    await failJob(job.id, err?.message || String(err), { requeue });
    return { ran: true, state: requeue ? 'queued' : 'failed' };
  }
}
