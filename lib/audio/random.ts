/**
 * Deterministic small-state pseudo-random generator.
 * Mulberry32 is fast, widely-audited, and seed-stable. Used everywhere in
 * the vinyl processing pipeline so the same recording, the same preset,
 * and the same session always produce the same bed files.
 *
 * Cross-check is intentionally trivial: any 32-bit unsigned integer is a
 * valid seed. Mixing multiple sub-streams is done by hashing the parent
 * seed with a small integer and feeding the result back into a fresh
 * stream.
 */

/**
 * Returns a deterministic Mulberry32 generator seeded with `seed`.
 * Calling the returned function advances state and yields a 32-bit uniform
 * float in [0, 1).
 */
export function mulberry32(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * FNV-1a string hash -> uint32 seed. Stable across machines/runs; good
 * enough for audio timing jitter where full cryptographic unpredictability
 * is not required.
 */
export function hashStringToSeed(input: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/**
 * Mix a parent seed with a small integer / string label to derive an
 * independent downstream stream. Avoids the "all three beds sound the
 * same" pitfall.
 */
export function childSeed(parent: number, label: string | number): number {
  let s = parent;
  if (typeof label === 'number') {
    s ^= label + 0x9e3779b9;
  } else {
    s ^= hashStringToSeed(String(label));
  }
  // xorshift mix
  s ^= s >>> 16;
  s = Math.imul(s, 0x85ebca6b);
  s ^= s >>> 13;
  s = Math.imul(s, 0xc2b2ae35);
  s ^= s >>> 16;
  return s >>> 0;
}
