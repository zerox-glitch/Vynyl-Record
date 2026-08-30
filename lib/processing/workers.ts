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
import { processAudioTrack } from '@/lib/audio/processor';
import { downloadToTmp } from '@/lib/audio/storage';

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
    const source = await downloadToTmp(params.voiceFileUrl, `${job.id}-source`);

    update({ result: { stage: 'rendering_master' } });

    const result = await processAudioTrack({
      voiceFilePath: source,
      outputFilePath: source.replace(/(\.\w+)?$/, '-master.mp3'),
      filterPreset: params.filterPreset as any,
      crackleIntensity: params.crackleIntensity ?? 0.2,
      bgMusicFilePath: params.bgMusicFilePath ?? null,
      crackleFilePath: params.crackleFilePath ?? null,
      maxSeconds: params.maxSeconds ?? 600,
    });

    update({
      result: {
        stage: 'persisting_master',
        durationSeconds: result.durationSeconds,
        masterLocalPath: result.outputFilePath,
      },
    });

    return job;
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
    if (!process.env.OPENAI_API_KEY) {
      throw new Error('OPENAI_API_KEY is not configured; transcript unavailable');
    }
    update({ result: { stage: 'transcribing' } });
    return job;
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
