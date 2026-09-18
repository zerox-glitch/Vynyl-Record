export type UserRole = 'user' | 'admin';
export type AccountStatus = 'active' | 'suspended' | 'disabled';

export interface Profile {
  id: string;
  email: string;
  full_name: string | null;
  avatar_url?: string | null;
  role: UserRole;
  account_status?: AccountStatus;
  stripe_customer_id: string | null;
  is_premium?: boolean; // backward compat, not authoritative
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

export type BillingModel = 'free' | 'per_recording' | 'monthly' | 'lifetime';
export type BillingInterval = 'month' | 'year' | 'one_time' | null;

export interface PricingPlan {
  id: string;
  slug: string;
  name: string;
  description?: string | null;
  billing_model: BillingModel;
  price_cents: number;
  currency: string;
  billing_interval?: BillingInterval;
  stripe_product_id?: string | null;
  stripe_price_id: string | null;
  max_duration_seconds: number;
  included_recordings?: number | null;
  allowed_filter_presets: FilterPresetType[];
  allowed_vinyl_presets?: string[]; // ['all'] or list
  allowed_bg_music_ids: string[];
  allowed_vinyl_styles: VinylStyleType[];
  can_adjust_crackle: boolean;
  can_download?: boolean;
  can_use_private_visibility?: boolean;
  can_use_advanced_mixer?: boolean;
  is_active: boolean;
  display_order?: number;
  created_at: string;
  updated_at?: string;
}

export type AudioCategory = 'bg_music' | 'crackle' | 'sound_effect';

export interface AudioAsset {
  id: string;
  title: string;
  category: AudioCategory;
  /** Stable playback route. Uploaded objects themselves stay private in R2. */
  file_url: string;
  /** R2/local object key for admin-uploaded assets; bundled assets leave this null. */
  storage_key?: string | null;
  source_content_type?: string | null;
  source_size_bytes?: number | null;
  is_premium_only: boolean;
  is_enabled?: boolean;
  default_volume?: number;
  created_at: string;
  /** Browser-probed source duration and the non-destructive section used in masters. */
  duration_seconds?: number | null;
  trim_start_seconds?: number;
  trim_end_seconds?: number | null;
  /** Legacy duration field retained for bundled sound effects. */
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
  // Entitlement tracking for reprocessing
  entitlement_plan_id?: string | null;
  entitlement_source?: string | null;
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

// =========================================================
// New entitlement model
// =========================================================
export type EntitlementStatus = 'active' | 'trialing' | 'past_due' | 'canceled' | 'expired' | 'revoked';
export type EntitlementSource = 'signup' | 'stripe' | 'admin' | 'lifetime' | 'migration';
export type RecordingEntitlementStatus = 'active' | 'expired' | 'revoked' | 'refunded';

export interface UserEntitlement {
  id: string;
  user_id: string;
  plan_id: string;
  status: EntitlementStatus;
  source: EntitlementSource;
  starts_at: string;
  expires_at?: string | null;
  remaining_recordings?: number | null;
  stripe_customer_id?: string | null;
  stripe_subscription_id?: string | null;
  stripe_checkout_session_id?: string | null;
  granted_by_admin?: string | null;
  created_at: string;
  updated_at: string;
  // joined
  plan?: PricingPlan;
}

export interface RecordingEntitlement {
  id: string;
  recording_id: string;
  user_id: string;
  plan_id: string;
  status: RecordingEntitlementStatus;
  stripe_checkout_session_id?: string | null;
  purchase_id?: string | null;
  created_at: string;
  plan?: PricingPlan;
}

export interface ResolvedEntitlement {
  effectivePlan: PricingPlan | null;
  billingModel: BillingModel | null;
  enabledFeatures: {
    allowedFilterPresets: FilterPresetType[];
    allowedVinylPresets: string[];
    allowedBgMusicIds: string[];
    allowedVinylStyles: VinylStyleType[];
    canAdjustCrackle: boolean;
    canDownload: boolean;
    canUsePrivateVisibility: boolean;
    canUseAdvancedMixer: boolean;
  };
  durationLimit: number;
  remainingUsage?: number | null;
  source: EntitlementSource | 'free' | 'per_recording' | null;
  expiration?: string | null;
  status: EntitlementStatus | RecordingEntitlementStatus | 'none';
  reason?: string;
  isPremium: boolean;
  userEntitlement?: UserEntitlement | null;
  recordingEntitlement?: RecordingEntitlement | null;
}

export interface AdminAuditLog {
  id: string;
  action: string;
  target_user_id?: string | null;
  target_plan_id?: string | null;
  target_recording_id?: string | null;
  before_data?: Record<string, unknown> | null;
  after_data?: Record<string, unknown> | null;
  metadata: Record<string, unknown>;
  admin_session_id?: string | null;
  admin_user_id?: string | null;
  created_at: string;
}

export interface AuthUser {
  id: string;
  email?: string;
  email_confirmed_at?: string | null;
  last_sign_in_at?: string | null;
  provider?: string;
  providers?: string[];
}
