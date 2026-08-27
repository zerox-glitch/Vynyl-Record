import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import { v4 as uuidv4 } from 'uuid';
import {
  processAudioTrack,
  probeDurationSeconds,
  transcodeToMp3,
  audioScratchDir,
} from '@/lib/audio/processor';
import { persistRecordAudio, cleanupFiles, extensionFor } from '@/lib/audio/storage';
import { transcribeAudioWithTimestamps } from '@/lib/transcription';
import { saveRecording, getAudioAssets } from '@/lib/db';
import { DEFAULT_AUDIO_ASSETS } from '@/lib/constants';
import { FilterPresetType, VinylStyleType, Recording, AudioAsset } from '@/types';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Vercel caps lambda payload/body size; keep uploads sane. */
const MAX_UPLOAD_BYTES = 40 * 1024 * 1024;

function generateSlug(length = 6): string {
  const chars = 'abcdefghjkmnpqrstuvwxyz23456789';
  let result = '';
  for (let i = 0; i < length; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

/** Turn an asset id (uuid, short name, or file url) into an on-disk path. */
function findAssetFile(assets: AudioAsset[], id: string): string | null {
  const needle = String(id || '').toLowerCase().trim();
  if (!needle || needle === 'none' || needle === 'null') return null;

  const matches = assets.filter((a) => {
    const url = String(a.file_url || '');
    return (
      a.id.toLowerCase() === needle ||
      url.toLowerCase() === needle ||
      url.toLowerCase().includes(`/${needle}.`) ||
      url.toLowerCase().includes(needle) ||
      a.title.toLowerCase().includes(needle) ||
      a.category === needle
    );
  });

  const ordered = matches.length ? matches : assets.filter((a) => a.category === 'bg_music');
  for (const asset of ordered) {
    const url = String(asset.file_url || '');
    if (!url) continue;
    const fileName = path.basename(url.split('?')[0]);
    // persistAssetFile() stages custom uploads in both places.
    const candidates = [
      path.join(process.cwd(), 'public', url.startsWith('/') ? url.slice(1) : url),
      path.join(process.cwd(), 'public', 'audio', fileName),
      path.join(process.cwd(), 'public', 'records', fileName),
    ];
    for (const candidate of candidates) {
      try {
        if (fs.existsSync(candidate)) return candidate;
      } catch {}
    }
  }
  return null;
}

export async function POST(req: NextRequest) {
  const sessionId = uuidv4();
  const scratch = audioScratchDir();

  const voiceFilePath = path.join(scratch, `voice_${sessionId}`);
  const voiceMp3Path = path.join(scratch, `voice_${sessionId}.mp3`);
  const outputFilePath = path.join(scratch, `record_${sessionId}.mp3`);

  try {
    const formData = await req.formData();
    const audioFile = formData.get('audio') as File | null;

    if (!audioFile) {
      return NextResponse.json(
        { error: 'No audio recording file provided in payload.' },
        { status: 400 }
      );
    }

    if (audioFile.size < 200) {
      return NextResponse.json(
        { error: 'That recording is empty — check the microphone permission and record again.' },
        { status: 400 }
      );
    }

    if (audioFile.size > MAX_UPLOAD_BYTES) {
      return NextResponse.json(
        { error: `Recording is too large (${(audioFile.size / 1024 / 1024).toFixed(1)} MB).` },
        { status: 413 }
      );
    }

    const title = ((formData.get('title') as string) || 'Untitled Memory').trim() || 'Untitled Memory';
    const recipientName = (formData.get('recipientName') as string) || '';
    const senderName = (formData.get('senderName') as string) || '';
    const filterPreset = ((formData.get('filterPreset') as string) || 'gramophone') as FilterPresetType;
    const crackleIntensity = parseFloat((formData.get('crackleIntensity') as string) || '0.22');
    const bgMusicId = (formData.get('bgMusicId') as string) || null;
    const vinylStyle = ((formData.get('vinylStyle') as string) || 'classic_red') as VinylStyleType;
    const maxSeconds = Math.max(5, Math.min(1800, parseInt((formData.get('maxSeconds') as string) || '600', 10) || 600));

    // 1. Stage the upload. ffmpeg sniffs the container, so the extension only
    //    matters for the "give the browser the original" fallback below.
    const buffer = Buffer.from(await audioFile.arrayBuffer());
    await fs.promises.writeFile(voiceFilePath, buffer);

    let assets: AudioAsset[] = DEFAULT_AUDIO_ASSETS;
    try {
      const fromDb = await getAudioAssets();
      if (fromDb?.length) assets = fromDb;
    } catch (err: any) {
      console.warn('[API] asset lookup failed, using bundled defaults:', err?.message);
    }

    const bgMusicFilePath = bgMusicId ? findAssetFile(assets, bgMusicId) : null;
    if (bgMusicId && bgMusicId !== 'none' && !bgMusicFilePath) {
      console.warn(`[API] background music "${bgMusicId}" had no readable file — pressing voice + crackle only`);
    }

    // Crackle is a bed, not an effect: prefer the DB asset, then the shipped file.
    const crackleAssetPath = findAssetFile(
      assets.filter((a) => a.category === 'crackle'),
      'crackle'
    );
    const crackleFilePath =
      crackleAssetPath || path.join(process.cwd(), 'public', 'audio', 'crackle-vintage.mp3');

    // 2. A normalized mono/stereo mp3 of the take: used for transcription and as
    //    the record's fallback if the mastered file ever goes missing.
    const rawIsMp3 = await transcodeToMp3(voiceFilePath, voiceMp3Path);
    const sourceForTranscription = rawIsMp3 ? voiceMp3Path : voiceFilePath;

    // 3. Press the master (voice filter + BG + crackle, gain-compensated).
    const master = await processAudioTrack({
      voiceFilePath: sourceForTranscription,
      outputFilePath,
      filterPreset,
      crackleIntensity: Number.isFinite(crackleIntensity) ? crackleIntensity : 0.22,
      bgMusicFilePath,
      crackleFilePath: fs.existsSync(crackleFilePath) ? crackleFilePath : null,
      maxSeconds,
    });

    const durationSeconds = Number(
      (master.durationSeconds || (await probeDurationSeconds(master.outputFilePath)) || 0).toFixed(2)
    );

    // 4. Persist real files so playback can be streamed + seeked.
    const masterExt = master.container === 'mp3' ? 'mp3' : extensionFor(audioFile.type, '.webm').replace('.', '');
    const masterStored = persistRecordAudio(
      `record_${sessionId}.${masterExt}`,
      await fs.promises.readFile(master.outputFilePath)
    );

    let rawStored: { url: string; location: string } | null = null;
    if (rawIsMp3) {
      const stored = persistRecordAudio(`raw_${sessionId}.mp3`, await fs.promises.readFile(voiceMp3Path));
      rawStored = { url: stored.url, location: stored.location };
    } else {
      // Keep the original container but name it correctly (an .mp3 that is really
      // webm is exactly how "silent record" bugs are born).
      const rawExt = extensionFor(audioFile.type, '.webm').replace('.', '');
      const stored = persistRecordAudio(`raw_${sessionId}.${rawExt}`, buffer);
      rawStored = { url: stored.url, location: stored.location };
    }

    // 5. Transcription (best effort — never blocks the press).
    const transcriptJson = await transcribeAudioWithTimestamps(
      sourceForTranscription,
      durationSeconds || 10,
      title
    ).catch((err) => {
      console.warn('[API] transcription skipped:', err?.message);
      return [];
    });

    // 6. Persist the record.
    const slug = `${title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 28) || 'voice-note'}-${generateSlug(6)}`;

    const newRecording: Recording = {
      id: sessionId,
      slug,
      user_id: null,
      title,
      recipient_name: recipientName,
      sender_name: senderName,
      processed_audio_url: masterStored.url,
      raw_voice_url: rawStored?.url || masterStored.url,
      transcript_json: transcriptJson,
      vinyl_style: vinylStyle,
      filter_preset: filterPreset,
      crackle_intensity: Number.isFinite(crackleIntensity) ? crackleIntensity : 0.22,
      bg_music_id: bgMusicId,
      views: 0,
      created_at: new Date().toISOString(),
      duration_seconds: durationSeconds,
    };

    await saveRecording(newRecording);

    return NextResponse.json({
      success: true,
      recording: newRecording,
      slug,
      playUrl: `/play/${slug}`,
      engine: {
        strategy: master.strategy,
        container: master.container,
        mix: master.mix,
        storage: masterStored.location,
        durationSeconds,
        bytes: masterStored.bytes,
      },
    });
  } catch (error: any) {
    console.error('[AudioEngine] Error:', error);
    return NextResponse.json(
      { error: error?.message || 'Server failed to synthesize vinyl recording.' },
      { status: 500 }
    );
  } finally {
    cleanupFiles([voiceFilePath, voiceMp3Path, outputFilePath]);
  }
}
