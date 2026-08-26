import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { v4 as uuidv4 } from 'uuid';
import { processAudioTrack } from '@/lib/audio/processor';
import { transcribeAudioWithTimestamps } from '@/lib/transcription';
import { saveRecording, getAudioAssets } from '@/lib/db';
import { FilterPresetType, VinylStyleType, Recording } from '@/types';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function generateSlug(length: number = 8): string {
  const chars = 'abcdefghjkmnpqrstuvwxyz23456789';
  let result = '';
  for (let i = 0; i < length; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

export async function POST(req: NextRequest) {
  const sessionId = uuidv4();
  // Serverless Safe Writable Directory
  const tmpDir = os.tmpdir();

  const voiceFilePath = path.join(tmpDir, `voice_${sessionId}.webm`);
  const outputFilePath = path.join(tmpDir, `output_${sessionId}.mp3`);

  const tempFilesToPurge: string[] = [voiceFilePath, outputFilePath];

  try {
    const formData = await req.formData();
    const audioFile = formData.get('audio') as File | null;

    if (!audioFile) {
      return NextResponse.json(
        { error: 'No audio recording file provided in payload.' },
        { status: 400 }
      );
    }

    const title = (formData.get('title') as string) || 'Untitled Memory';
    const recipientName = (formData.get('recipientName') as string) || '';
    const senderName = (formData.get('senderName') as string) || '';
    const filterPreset = ((formData.get('filterPreset') as string) || 'gramophone') as FilterPresetType;
    const crackleIntensity = parseFloat((formData.get('crackleIntensity') as string) || '0.25');
    const bgMusicId = (formData.get('bgMusicId') as string) || null;
    const vinylStyle = ((formData.get('vinylStyle') as string) || 'classic_red') as VinylStyleType;

    // 1. Write incoming audio to serverless /tmp
    const arrayBuffer = await audioFile.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);
    await fs.promises.writeFile(voiceFilePath, buffer);

    // Resolve background & crackle paths
    let bgMusicFilePath: string | null = null;
    let crackleFilePath: string | null = path.join(process.cwd(), 'public', 'audio', 'crackle-vintage.mp3');

    if (!fs.existsSync(crackleFilePath)) {
      crackleFilePath = null;
    }

    if (bgMusicId && bgMusicId !== 'none') {
      const allAssets = await getAudioAssets();
      const asset = allAssets.find((a) => a.id === bgMusicId || a.file_url.includes(bgMusicId));
      if (asset && asset.file_url.startsWith('/')) {
        const resolved = path.join(process.cwd(), 'public', asset.file_url);
        if (fs.existsSync(resolved)) bgMusicFilePath = resolved;
      }
    }

    // 2. Synthesize audio via FFmpeg
    const { durationSeconds } = await processAudioTrack({
      voiceFilePath,
      outputFilePath,
      filterPreset,
      crackleIntensity,
      bgMusicFilePath,
      crackleFilePath,
    });

    if (!fs.existsSync(outputFilePath)) {
      throw new Error('Processed output file was not generated.');
    }

    // 3. Convert output MP3 buffer to Base64 Data URI (Zero disk write required)
    const processedBuffer = await fs.promises.readFile(outputFilePath);
    const processedAudioUrl = `data:audio/mp3;base64,${processedBuffer.toString('base64')}`;

    // 4. AI Transcription
    const transcriptJson = await transcribeAudioWithTimestamps(
      voiceFilePath,
      durationSeconds,
      title
    );

    // 5. Generate slug & persist
    const slug = `${title.toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 20)}-${generateSlug(6)}`;

    const newRecording: Recording = {
      id: sessionId,
      slug,
      user_id: null,
      title,
      recipient_name: recipientName,
      sender_name: senderName,
      processed_audio_url: processedAudioUrl,
      raw_voice_url: processedAudioUrl,
      transcript_json: transcriptJson,
      vinyl_style: vinylStyle,
      filter_preset: filterPreset,
      crackle_intensity: crackleIntensity,
      bg_music_id: bgMusicId,
      views: 0,
      created_at: new Date().toISOString(),
      duration_seconds: durationSeconds,
    };

    await saveRecording(newRecording);

    return NextResponse.json({
      success: true,
      recording: newRecording,
      slug: newRecording.slug,
      playUrl: `/play/${newRecording.slug}`,
    });
  } catch (error: any) {
    console.error('[AudioEngine] Error:', error);
    return NextResponse.json(
      { error: error.message || 'Server failed to synthesize vinyl recording.' },
      { status: 500 }
    );
  } finally {
    // 7. Cleanup /tmp
    for (const file of tempFilesToPurge) {
      try {
        if (fs.existsSync(file)) await fs.promises.unlink(file);
      } catch {}
    }
  }
}
