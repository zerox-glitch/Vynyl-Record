# The audio engine

Everything in this document runs on the phone. There is no server-side renderer, no FFmpeg, no native
library and no download: the signal chain is Kotlin over `FloatArray`, and the only platform code involved is
`AudioRecord` for capture, `MediaExtractor`/`MediaCodec` for import and AAC encoding, and `Canvas` for the
artwork.

The engine's own boundary is `core/audio/dsp`, which **has no Android dependency at all**. That is why a
three-second render can be run and asserted in a JVM unit test at full length, which is where most audio bugs
are normally caught — or not.

## The processing model

Interleaved stereo, `FloatArray` blocks, one thread per render.

```kotlin
interface PcmSource : Closeable {           // a voice, a bed, a file, a loop
    val sampleRate: Int
    val frameCount: Int                     // -1 when unknown (a looping bed)
    fun read(out: FloatArray, offset: Int, frames: Int): Int
}

interface PcmSink : Closeable {             // a WAV writer, an AAC encoder
    val sampleRate: Int
    fun write(block: FloatArray, offset: Int, frames: Int)
    fun finish()
}
```

* **One sample rate per render.** The studio renders at 44.1 kHz (48 kHz is selectable in Settings, and the
  engine refuses a source that does not match rather than resampling silently — the importer converts first).
* **Streaming.** `MasterRenderer` pulls 8 192-frame blocks. A three-minute record costs the same working set
  as a three-second one: this is a phone, and a ten-minute stereo float master would be 200 MB.
* **Absolute time.** Every layer that has a shape over the whole record — the needle lead-in, the fades, the
  crackle schedule, the surface noise — is indexed by absolute frame, so rendering in blocks of any size
  produces byte-identical output. `VinylBedTest` asserts exactly that with two different block sizes.
* **No allocation in the block loop.** Scratch buffers are allocated once and reused; the renderer's inner
  loops only read and write floats.

## The chain, in order

The order below is the order `VinylMasterChain.process` runs, and it is the web renderer's order — the sound
of this app is that chain, not a new one.

```
 1  source voice WAV            44.1 kHz stereo, streamed in blocks
 2  voice chain                 rumble (high-pass) → warmth → presence → tanh saturation
 3  voice dynamics              compressor (soft knee, makeup) → compander
 4  motor                       wow + flutter, one interpolated-delay pass
 5  vinyl tone                  high-pass → low-pass → mid bell
 6  background mix              tilted bed, side-chain ducked under the voice
 7  surface bed                 pink noise, shaped, stereo-decorrelated
 8  hiss                        a separate, quieter pink layer
 9  crackle                     scheduled transients, clustered
10  pops                        rarer, resonant, filtered
11  dust / needle               the drop before the voice, then a quiet bed
12  stereo width                mono-safe widening
13  master gain                 the recipe's level
14  limiter                     look-ahead, ceiling from the recipe
15  fades                       10 ms in, 300 ms out
```

### Voice chain (`Stages.kt`)

* `Saturator` — `tanh` drive with a **dry/wet mix**, so a preset can add harmonics without changing level.
  `drive` and `mix` both come from the recipe; at `drive = 0` the stage is a bypass (`isActive`).
* `Compressor` — attack, release, soft knee and makeup gain, applied per channel with linked detectors.
  A quiet phone memo and a loud recording arrive at the same perceived level without the compressor pumping.
* `WowFlutter` — one delay line, two modulation rates: wow 2.9–3.4 **per minute** (0.05–0.06 Hz, the slow
  drift of an off-centre pressing) and flutter 2 400–3 600 **per minute** (40–60 Hz, the fine shimmer).
  Cubic interpolation, and both in a single pass: two independent resamplers compound into the seasick
  artefact both stages exist to avoid.
* Tone — a 60–90 Hz rumble high-pass, a mid bell (`midGainDb` at `midFreqHz`), a warmth shelf and a presence
  band, all `Biquad` sections with their own state.

### The record (`VinylBed.kt`)

The bed is generated, never looped from a sample. Each layer has its **own** `Mulberry32` stream seeded from
`Seeds.child(seed, "surface" | "crackle" | "pops" | "dust" | "hiss-l" | …)`, so changing one layer cannot
shift another, and rendering in blocks cannot repeat a phrase.

| Layer | How it is made |
| --- | --- |
| Surface | pink noise (Voss-McCartney-ish filter cascade), shaped by the preset's `NoiseFilterShape`, decorrelated between channels |
| Hiss | a second, quieter pink layer, always `HIGHPASS_FLAT` — tape hiss is brighter than groove noise |
| Crackle | planned in advance: a jittered count from `crackleDensityPerMin`, events placed uniformly, then **clustered** — a record that snaps once tends to snap again. Each event gets its own RNG and its own filter state, so a transient that spans a block boundary stays continuous |
| Pops | rarer, louder, resonant: a fast decaying ring filtered with `crackleBrightness`, `popsDensityPerMin` from the recipe |
| Dust / needle | a needle-contact transient at the start (60 ms of shaped noise after `needleIntroMs` of lead-in), plus low-frequency rumble through a 28 Hz one-pole |

Events are refused in the first and last 20 ms of the record: a click on sample 0 sounds like a corrupted
file, and a pop during the fade-out sounds like a bug in the app.

The `VinylBed` is asked for a **whole-record plan** at construction (`crackleCount`, `popCount`), and
`render(fromFrame, frames, out)` fills `out[0 until frames * 2)` for the block starting at `fromFrame`.

### Music, ducking and mix

A background bed is decoded once into `cache/decoded/`, trimmed, and looped through `LoopSource` with a
cross-faded seam (a quarter second, never more than a third of the loop). It is **not** run through the voice
chain: a background that suffers the same broken machine as the voice stops sounding like a background.
Instead it gets its own high-shelf tilt (`musicClarity`) and a side-chain duck (`Ducker`) driven by the
voice's absolute level. Ducking is bounded so speech is never buried — that is the one thing the background
must not do.

## The five pressings

Every value below is a real number the engine consumes, not a slider position. The gradient is the product.

| | Clean Vinyl | Warm Vintage | Dusty Record | Old Family Record | Rare / Archival |
| --- | --- | --- | --- | --- | --- |
| id (stored) | `clean_vinyl` | `warm_vintage` | `dusty_record` | `old_family_record` | `rare_archival` |
| saturation drive / mix | 0.08 / 0.50 | 0.28 / 0.75 | 0.45 / 0.85 | 0.55 / 0.95 | 0.65 / 1.00 |
| high-pass | 90 Hz | 75 Hz | 70 Hz | 65 Hz | 60 Hz |
| low-pass | 16 500 Hz | 14 200 Hz | 12 600 Hz | 11 200 Hz | 9 800 Hz |
| mid bell | −1 dB @ 3.2 k | +1.5 dB @ 2.8 k | 0 dB @ 2.4 k | −2 dB @ 1.8 k | −3 dB @ 1.5 k |
| wow depth / rate | 3 ¢ / 3.4 | 6 ¢ / 3.3 | 8 ¢ / 3.2 | 12 ¢ / 3.1 | 18 ¢ / 2.9 (per minute) |
| flutter depth / rate | 0.6 ¢ / 2 400 | 1.2 ¢ / 2 700 | 1.8 ¢ / 2 900 | 2.6 ¢ / 3 300 | 3.0 ¢ / 3 600 (per minute) |
| surface level / shape | −42 dB, HP-flat | −34 dB, HP-flat | −28 dB, HP-low | −28 dB, flat | −24 dB, flat |
| hiss | −52 dB | −44 dB | −38 dB | −34 dB | −30 dB |
| crackle density / intensity / brightness | 10 per min / 0.20 / 0.62 | 22 / 0.38 / 0.55 | 38 / 0.55 / 0.46 | 28 / 0.45 / 0.36 | 48 / 0.65 / 0.30 |
| pops density / intensity | 0.4 per min / 0.55 | 0.9 / 0.65 | 1.6 / 0.75 | 1.0 / 0.65 | 2.0 / 0.80 |
| stereo width | 1.00 | 0.95 | 0.92 | 0.88 | 0.85 |
| limiter ceiling | 0.96 | 0.94 | 0.94 | 0.93 | 0.92 |
| master gain | 1.00 | 0.92 | 0.90 | 0.94 | 0.92 |
| music level / clarity | 0.16 / 0.85 | 0.18 / 0.75 | 0.17 / 0.70 | 0.15 / 0.62 | 0.13 / 0.55 |
| needle lead-in | 90 ms | 140 ms | 180 ms | 240 ms | 320 ms |

Every preset sets `seedFromRecordingId = true`, so the bed is derived from the record's id rather than being
identical across a library. A preset is a `VinylRecipe` — forty values — and every one of them is editable
through the fourteen knobs of the advanced panel (`VinylControl`), which *project* the recipe rather than
copying it: `MUSIC_LEVEL`, `SURFACE_LEVEL`, `CRACKLE`, `POP_DENSITY`, `HISS`, `RUMBLE`, `VOICE_WARMTH`,
`VOICE_PRESENCE`, `MUSIC_CLARITY`, `CRACKLE_BRIGHTNESS`, `WOW`, `FLUTTER`, `STEREO_WIDTH`, `NEEDLE_INTRO`.

`VinylPresetTest` pins the table above, the strict gradient between presets and the round trip of every knob.

## Determinism

```
seed = FNV-1a("$recordId:$presetId")        // Seed.derive
```

* The same record and the same recipe press to a **byte-identical** master, which is what makes "re-render"
  safe to offer without warning the user that the sound will move.
* Two records made from the same recording get different crackle patterns, because the id differs.
* Every procedural layer draws from its own child stream, so adding a layer later cannot shift the pops in an
  existing record's re-render.
* The processed voice is untouched by randomness; only the record's own noise is seeded.

## Output formats

| Format | Where it is produced | Notes |
| --- | --- | --- |
| **PCM WAV** (16-bit, 44.1/48 kHz, stereo) | `WavCodec.Writer`, streamed | the master. Written with a placeholder header and finalised on `finish()`, so an unfinished render is not a valid file |
| **M4A / AAC** | `AacEncoder` (`MediaCodec` + `MediaMuxer`), on demand | for leaving the device. Encoded at 192 kbit/s when the user exports; the Vault keeps the WAV |
| **PNG artwork** | `ArtworkRenderer` (Canvas, 1024×1024) | drawn from the record's own metadata and vinyl style: disc, grooves, label, brass rim, the title, the dedication and the occasion |
| **`.vynyl` bundle** | `VynylBundle.write` (ZIP, deflate 6) | `manifest.json`, `master.wav`, optional `source.wav`, optional `cover.png`, optional `waveform.json`, `checksums.txt` (CRC32 + SHA-256 per entry) |
| **waveform** | `WaveformExtractor` | 768 detailed peaks in `waveform.json`, 96 compact peaks on the row so a library card draws without touching a file |

The master is written to `cache/render/{jobId}/` and moved into `files/records/{id}/master.wav` by an atomic
rename only after it has passed validation.

## What "finished" means

`RenderValidator.validate` runs against the file that was **just written**, opened the way a player would open
it:

| Failure | Trigger |
| --- | --- |
| `MISSING` | no file, or 44 bytes of header and nothing else |
| `TOO_SMALL` | under 8 KB — a header and no audio |
| `UNDECODABLE` | the file cannot be read back, or reports no duration |
| `DURATION_MISMATCH` | the decoded length differs from the intended length by more than max(1.5 s, 5%) |
| `SILENT` | peak below −80 dBFS |
| `CLIPPED` | a sample above full scale |

`RenderPipeline` then measures the master (`AudioMeasurements`: peak dBFS, RMS dBFS, the fraction of the
record that is voice, the noise floor and the duration), and only then does the repository commit the record
as completed. `RenderWorker` reports the nine stages — Preparing source, Decoding, Mixing atmosphere, Adding
vinyl character, Mastering, Encoding, Generating waveform, Generating artwork, Complete — into the progress
notification.

## Limits and policy

* Maximum record length: **10 minutes** (`RenderLimits.MAX_RECORD_MS`). Longer is refused in the studio with
  a sentence, not after the fact.
* The free-space check runs **before** the press: the estimated master size plus a 64 MB margin.
* Cancellation is polled between blocks: a cancelled press leaves the writer unfinished and deletes its
  scratch directory, so it can never be mistaken for a master.
* WAV's 4 GB RIFF ceiling is documented rather than worked around; at 44.1 kHz stereo it is 6.7 hours, far
  beyond the limit above.

## What is not in the engine

* No loudness normalisation to a broadcast target. The limiter ceiling is the promise, and a personal record
  is not a streaming master.
* No denoise, de-reverb or source separation: the voice is treated as the performance, not as a problem.
* No pitch correction. Wow and flutter are a record's imperfection, applied after the performance, not a
  correction to it.
* No reverb. Room tone belongs to the recording, and the only ambience the app adds is the record's own.
* No resampling in the chain. Sources are converted at import, once, where the user's progress bar can be
  honest about it.
