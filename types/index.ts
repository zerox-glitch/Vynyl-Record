export type UserRole = 'user' | 'admin';

export interface Profile {
  id: string;
  email: string;
  full_name: string | null;
  role: UserRole;
  stripe_customer_id: string | null;
  is_premium?: boolean;
  recording_count?: number;
  created_at: string;
  updated_at: string;
}

export interface SiteSettings {
  hero_copy: {
    headline: string;
    subheadline: string;
    cta_text: string;
  };
  branding_theme: {
    primary_color: string;
    bg_color: string;
    accent_color: string;
    font_heading: string;
    enable_grain_overlay: boolean;
  };
  faqs?: Array<{
    q: string;
    a: string;
  }>;
}

export type FilterPresetType = 'clean' | 'gramophone' | 'radio' | 'tape';

export type VinylStyleType = 
  | 'classic_red' 
  | 'midnight_blue' 
  | 'gold_edition' 
  | 'vintage_emerald' 
  | 'smoked_obsidian';

export interface PricingPlan {
  id: string;
  name: string;
  price_cents: number;
  stripe_price_id: string | null;
  max_duration_seconds: number;
  allowed_filter_presets: FilterPresetType[];
  allowed_bg_music_ids: string[];
  allowed_vinyl_styles: VinylStyleType[];
  can_adjust_crackle: boolean;
  is_active: boolean;
  created_at: string;
}

export type AudioCategory = 'bg_music' | 'crackle' | 'sound_effect';

export interface AudioAsset {
  id: string;
  title: string;
  category: AudioCategory;
  file_url: string;
  is_premium_only: boolean;
  is_enabled?: boolean;
  default_volume?: number;
  created_at: string;
  duration?: number;
}

export interface TranscriptWord {
  word: string;
  start: number;
  end: number;
}

export type RecordingVisibility = 'public' | 'unlisted' | 'private';
export type RecordingProcessingState =
  | 'idle'
  | 'queued'
  | 'processing'
  | 'completed'
  | 'failed';
export type DeliveryMode = 'link' | 'email' | 'scheduled';
export type OccasionType =
  | 'wedding'
  | 'anniversary'
  | 'birthday'
  | 'love_letter'
  | 'long_distance'
  | 'family'
  | 'grandparents'
  | 'baby'
  | 'memorial'
  | 'something_else';

export const OCCASIONS: Array<{
  id: OccasionType;
  label: string;
  prompt: string;
}> = [
  { id: 'wedding',       label: 'Wedding',        prompt: 'Write like you\u2019re standing next to the person you\u2019re marrying.' },
  { id: 'anniversary',    label: 'Anniversary',     prompt: 'What do you want them to know after fifty more years together?' },
  { id: 'birthday',       label: 'Birthday',        prompt: 'A birthday wish they\u2019ll play on their morning coffee.' },
  { id: 'love_letter',    label: 'Love Letter',     prompt: 'The things you\u2019d say if they were sitting across from you.' },
  { id: 'long_distance',  label: 'Long Distance',   prompt: 'You\u2019re far apart right now. What do you want them to feel tonight?' },
  { id: 'family',         label: 'Family Memory',   prompt: 'The kind of family story they\u2019ll tell their own kids one day.' },
  { id: 'grandparents',   label: 'For Grandparents', prompt: 'Say it now while they can still hear you.' },
  { id: 'baby',           label: 'For a Baby',       prompt: 'Things to tell a child when they\u2019re old enough to listen.' },
  { id: 'memorial',       label: 'Memorial',        prompt: 'Speak to the person who isn\u2019t here, the way you wish you could.' },
  { id: 'something_else', label: 'Something Else',  prompt: 'No category needed \u2014 just say it.' },
];

export interface Recording {
  id: string;
  slug: string;
  user_id?: string | null;
  title: string;
  recipient_name?: string | null;
  sender_name?: string | null;
  processed_audio_url: string;
  raw_voice_url: string;
  transcript_json: TranscriptWord[];
  vinyl_style: VinylStyleType;
  filter_preset: FilterPresetType;
  crackle_intensity: number;
  bg_music_id?: string | null;
  views: number;
  created_at: string;
  duration_seconds?: number;
  /** Visibility: public (anyone can find), unlisted (link only), private (owner only). */
  visibility?: RecordingVisibility;
  // Production fields (00006):
  processing_state?: RecordingProcessingState;
  processing_progress?: number;
  processing_error?: string | null;
  processing_started_at?: string | null;
  processing_completed_at?: string | null;
  recipient_email?: string | null;
  delivery_mode?: DeliveryMode;
  delivery_scheduled_for?: string | null;
  delivery_sent_at?: string | null;
  dedication?: string | null;
  side_a_label?: string | null;
  side_b_label?: string | null;
  cover_image_url?: string | null;
  occasion_date?: string | null;
  // R2 storage keys (preferred address; the *_url fields above stay for
  // backward compat with the existing demos that ship with bundled.mp3).
  original_storage_key?: string | null;
  processed_storage_key?: string | null;
  cover_storage_key?: string | null;
  // Occasion association
  occasion?: OccasionType | null;
}

export interface ProcessingJob {
  id: string;
  recording_id: string;
  user_id?: string | null;
  job_type:
    | 'audio_master'
    | 'transcription'
    | 'qr_render'
    | 'video_render'
    | 'artwork_resize';
  state: 'queued' | 'processing' | 'completed' | 'failed';
  attempts: number;
  max_attempts: number;
  params: Record<string, unknown>;
  result?: Record<string, unknown>;
  error?: string;
  last_heartbeat_at: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
}

/**
 * Worker contract. ANY audio / video / QR / transcription handle that does
 * heavy work gets implemented here. The default implementation runs on the
 * same node that this file is loaded into (handy for dev + small Vercel
 * functions); a Railway / Fly / dedicated VM worker uses the same interface.
 */
export interface ProcessingWorker {
  /** Stable identifier; jobs are routed per job_type below. */
  readonly workerId: string;
  /** Returns true if this worker can run a given job_type right now. */
  canHandle: (jobType: ProcessingJob['job_type']) => boolean;
  /** Run a single job. Updates the job row in-place so partial progress is visible. */
  process(
    job: ProcessingJob,
    update: (patch: Partial<ProcessingJob> & { heartbeat?: boolean }) => void
  ): Promise<ProcessingJob>;
}

export interface RecordEvent {
  id: string;
  recording_id?: string | null;
  user_id?: string | null;
  event_type: string;
  metadata: Record<string, unknown>;
  ip_hash?: string | null;
  user_agent_hash?: string | null;
  created_at: string;
}

export interface IntegrationSettings {
  id: number;
  stripe_publishable_key: string | null;
  stripe_secret_key: string | null;
  stripe_webhook_secret: string | null;
  updated_at: string;
}

export interface VinylStyleConfig {
  id: VinylStyleType;
  name: string;
  subtitle: string;
  baseColor: string;
  labelColor: string;
  grooveColor: string;
  brassAccent: string;
  isPremium?: boolean;
}

export interface FilterPresetConfig {
  id: FilterPresetType;
  name: string;
  year: string;
  description: string;
  badge: string;
  isPremium?: boolean;
}
