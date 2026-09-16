import { TranscriptWord } from '@/types';

export interface TranscriptProvider {
  readonly id: string;
  isConfigured(): boolean;
  transcribe(audioPath: string, estimatedDuration: number, fallbackTitle?: string): Promise<TranscriptWord[]>;
}

/** Provider abstraction. Existing Whisper helper remains the compatibility implementation. */
export class WhisperTranscriptProvider implements TranscriptProvider {
  readonly id = 'openai-whisper';
  isConfigured() { return Boolean(process.env.OPENAI_API_KEY); }
  async transcribe(audioPath: string, estimatedDuration: number, fallbackTitle?: string) {
    const { transcribeAudioWithTimestamps } = await import('@/lib/transcription');
    return transcribeAudioWithTimestamps(audioPath, estimatedDuration, fallbackTitle);
  }
}

export class DisabledTranscriptProvider implements TranscriptProvider {
  readonly id = 'disabled';
  isConfigured() { return false; }
  async transcribe(_audioPath: string, estimatedDuration: number, fallbackTitle?: string) {
    const { transcribeAudioWithTimestamps } = await import('@/lib/transcription');
    return transcribeAudioWithTimestamps('/nonexistent', estimatedDuration, fallbackTitle);
  }
}

export function getTranscriptProvider(): TranscriptProvider {
  return process.env.OPENAI_API_KEY ? new WhisperTranscriptProvider() : new DisabledTranscriptProvider();
}
