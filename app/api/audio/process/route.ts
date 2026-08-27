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

    // Resolve background & crackle paths - robust mapping for both UUID and short names
    let bgMusicFilePath: string | null = null;
    let crackleFilePath: string | null = path.join(process.cwd(), 'public', 'audio', 'crackle-vintage.mp3');

    if (!fs.existsSync(crackleFilePath)) {
      console.warn(`[API] Crackle file not found at ${crackleFilePath}, trying alternative`);
      const altCrackle = path.join(process.cwd(), 'public', 'audio', 'crackle-vintage.mp3');
      crackleFilePath = fs.existsSync(altCrackle) ? altCrackle : null;
    } else {
      console.log(`[API] Crackle file found: ${crackleFilePath}`);
    }

    // Map short names to actual files for reliability
    const BG_SHORT_MAP: Record<string, string> = {
      'rain': '/audio/bg-rain.mp3',
      'accordion': '/audio/bg-accordion.mp3',
      'guitar': '/audio/bg-guitar.mp3',
      'cello': '/audio/bg-cello.mp3',
      'a2222222-2222-2222-2222-222222222222': '/audio/bg-rain.mp3',
      'a3333333-3333-3333-3333-333333333333': '/audio/bg-accordion.mp3',
      'a4444444-4444-4444-4444-444444444444': '/audio/bg-guitar.mp3',
      'a5555555-5555-5555-5555-555555555555': '/audio/bg-cello.mp3',
    };

    if (bgMusicId && bgMusicId !== 'none' && bgMusicId !== 'null' && bgMusicId !== '') {
      const normalizedId = bgMusicId.toLowerCase().trim();
      
      // First check short map
      if (BG_SHORT_MAP[normalizedId]) {
        const mappedUrl = BG_SHORT_MAP[normalizedId];
        const resolved = path.join(process.cwd(), 'public', mappedUrl);
        if (fs.existsSync(resolved)) {
          bgMusicFilePath = resolved;
          console.log(`[API] BG music resolved via short map: ${bgMusicId} -> ${resolved}`);
        }
      } else if (BG_SHORT_MAP[bgMusicId]) {
        const mappedUrl = BG_SHORT_MAP[bgMusicId];
        const resolved = path.join(process.cwd(), 'public', mappedUrl);
        if (fs.existsSync(resolved)) {
          bgMusicFilePath = resolved;
          console.log(`[API] BG music resolved via exact short map: ${bgMusicId} -> ${resolved}`);
        }
      } else {
        // Try DB lookup
        const allAssets = await getAudioAssets();
        const asset = allAssets.find((a) => 
          a.id === bgMusicId || 
          a.id.toLowerCase() === normalizedId ||
          a.file_url.toLowerCase().includes(normalizedId) ||
          a.title.toLowerCase().includes(normalizedId)
        );
        if (asset && asset.file_url) {
          const fileUrl = asset.file_url.startsWith('/') ? asset.file_url : `/${asset.file_url}`;
          const resolved = path.join(process.cwd(), 'public', fileUrl);
          if (fs.existsSync(resolved)) {
            bgMusicFilePath = resolved;
            console.log(`[API] BG music resolved via DB: ${bgMusicId} -> ${asset.title} -> ${resolved}`);
          } else {
            console.warn(`[API] BG asset found but file missing: ${resolved}`);
            // Try alternative public path
            const altPath = path.join(process.cwd(), 'public', 'audio', path.basename(fileUrl));
            if (fs.existsSync(altPath)) {
              bgMusicFilePath = altPath;
              console.log(`[API] BG music fallback found: ${altPath}`);
            }
          }
        } else {
          // Last resort: try direct file in public/audio
          const directTry = path.join(process.cwd(), 'public', 'audio', `bg-${normalizedId}.mp3`);
          if (fs.existsSync(directTry)) {
            bgMusicFilePath = directTry;
            console.log(`[API] BG music resolved via direct file: ${directTry}`);
          } else {
            console.warn(`[API] BG music ID not resolved: ${bgMusicId}, available assets: ${allAssets.map(a=>a.id+':'+a.file_url).join(', ')}`);
          }
        }
      }
    } else {
      console.log(`[API] No BG music requested (id=${bgMusicId})`);
    }

    console.log(`[API] Final resolved paths - BG: ${bgMusicFilePath || 'NONE'}, Crackle: ${crackleFilePath || 'NONE'}`);

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
