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
