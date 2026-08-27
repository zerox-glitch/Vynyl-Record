-- Distinct surface textures and ambience generated during the application build.
INSERT INTO public.audio_assets
  (id, title, category, file_url, is_premium_only, is_enabled, default_volume)
VALUES
  ('a7777777-7777-7777-7777-777777777777', 'Soft Shellac Surface', 'crackle', '/audio/crackle-soft-shellac.mp3', FALSE, TRUE, 0.18),
  ('a8888888-8888-8888-8888-888888888888', 'Dusty Attic Scratches', 'crackle', '/audio/crackle-dusty-attic.mp3', TRUE, TRUE, 0.30),
  ('a9999999-9999-9999-9999-999999999999', 'Warm Tape Room Tone', 'bg_music', '/audio/bg-tape-room.mp3', FALSE, TRUE, 0.16)
ON CONFLICT (id) DO UPDATE SET
  title = EXCLUDED.title,
  category = EXCLUDED.category,
  file_url = EXCLUDED.file_url,
  is_premium_only = EXCLUDED.is_premium_only,
  is_enabled = EXCLUDED.is_enabled,
  default_volume = EXCLUDED.default_volume;

UPDATE public.audio_assets
SET
  title = 'Needle Drop Intro',
  is_enabled = TRUE,
  default_volume = 0.60
WHERE id = 'a6666666-6666-6666-6666-666666666666';
