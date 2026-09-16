import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import ffmpegInstaller from '@ffmpeg-installer/ffmpeg';
import { pressVinylMaster, probeDurationSeconds } from '../lib/audio/processor';
import { VINYL_PRESET_ORDER, VINYL_PRESETS } from '../lib/audio/presets';
import { hashStringToSeed } from '../lib/audio/random';
import { renderVinylBed } from '../lib/audio/vinylBed';

const outputDir = process.env.VYNYL_TEST_OUTPUT || path.join(os.tmpdir(), 'vynyl-preset-tests');
const voicePath = path.join(process.cwd(), 'public', 'audio', 'demo-anniversary.mp3');
const musicPath = path.join(process.cwd(), 'public', 'audio', 'bg-rain.mp3');
const ffmpegPath = ffmpegInstaller.path;

function ffmpegStats(file: string): { maxDb: number; meanDb: number; duration: number } {
  const result = spawnSync(ffmpegPath, ['-hide_banner', '-i', file, '-af', 'volumedetect', '-f', 'null', '-'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: 8 * 1024 * 1024,
  });
  const stderr = String(result.stderr || '');
  if (result.status !== 0) throw new Error(`FFmpeg validation failed for ${file}: ${stderr.slice(-500)}`);
  const durationMatch = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(stderr);
  const maxMatch = /max_volume:\s*(-?[\d.]+) dB/.exec(stderr);
  const meanMatch = /mean_volume:\s*(-?[\d.]+) dB/.exec(stderr);
  if (!durationMatch || !maxMatch || !meanMatch) throw new Error(`Could not parse FFmpeg stats for ${file}`);
  return {
    duration: Number(durationMatch[1]) * 3600 + Number(durationMatch[2]) * 60 + Number(durationMatch[3]),
    maxDb: Number(maxMatch[1]),
    meanDb: Number(meanMatch[1]),
  };
}

async function main() {
  if (!fs.existsSync(voicePath) || !fs.existsSync(musicPath)) throw new Error('Committed audio fixtures are missing.');
  fs.rmSync(outputDir, { recursive: true, force: true });
  fs.mkdirSync(outputDir, { recursive: true });

  const sourceDuration = await probeDurationSeconds(voicePath);
  const rows: Array<Record<string, string | number>> = [];
  const hashes = new Set<string>();

  for (const presetId of VINYL_PRESET_ORDER) {
    const preset = VINYL_PRESETS[presetId];
    const bedPath = path.join(outputDir, `${preset.id}-bed.wav`);
    const outputPath = path.join(outputDir, `${preset.id}.mp3`);
    const seed = hashStringToSeed(`audio-preset-test:${preset.id}`);
    const bed = renderVinylBed(sourceDuration, preset, seed, bedPath);
    await pressVinylMaster({
      voiceFilePath: voicePath,
      bgMusicFilePath: musicPath,
      bgMusicGain: 0.2,
      vinylBedFilePath: bed.filepath,
      outputFilePath: outputPath,
      preset,
      maxSeconds: 30,
    });

    const bytes = fs.readFileSync(outputPath);
    if (bytes.length < 4096) throw new Error(`${preset.id}: output is too small`);
    const stats = ffmpegStats(outputPath);
    if (Math.abs(stats.duration - sourceDuration) > 0.2) {
      throw new Error(`${preset.id}: duration ${stats.duration}s differs from source ${sourceDuration}s`);
    }
    if (stats.maxDb > -0.05) throw new Error(`${preset.id}: clipping risk at ${stats.maxDb} dBFS`);
    if (stats.meanDb < -48) throw new Error(`${preset.id}: master is unexpectedly quiet at ${stats.meanDb} dBFS`);

    const hash = createHash('sha256').update(bytes).digest('hex');
    hashes.add(hash);
    rows.push({
      preset: preset.id,
      seconds: stats.duration.toFixed(2),
      maxDb: stats.maxDb.toFixed(1),
      meanDb: stats.meanDb.toFixed(1),
      crackles: bed.diagnostics.crackleCount,
      pops: bed.diagnostics.popCount,
      sha256: hash.slice(0, 12),
    });
  }

  if (hashes.size !== VINYL_PRESET_ORDER.length) throw new Error('Two or more preset masters are byte-identical.');
  const meanLevels = new Set(rows.map((row) => row.meanDb));
  if (meanLevels.size < 3) throw new Error('Preset output levels do not differ enough to demonstrate distinct recipes.');

  console.table(rows);
  console.log(`Validated ${rows.length} playable, non-clipping, duration-matched, distinct masters in ${outputDir}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
