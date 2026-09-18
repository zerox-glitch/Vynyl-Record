type Entry = { count: number; resetAt: number };

const store = new Map<string, Entry>();

export function rateLimit(key: string, limit: number, windowMs: number): { allowed: boolean; remaining: number; resetAt: number } {
  const now = Date.now();
  const entry = store.get(key);
  if (!entry || now > entry.resetAt) {
    const newEntry: Entry = { count: 1, resetAt: now + windowMs };
    store.set(key, newEntry);
    return { allowed: true, remaining: limit - 1, resetAt: newEntry.resetAt };
  }
  if (entry.count >= limit) {
    return { allowed: false, remaining: 0, resetAt: entry.resetAt };
  }
  entry.count += 1;
  return { allowed: true, remaining: limit - entry.count, resetAt: entry.resetAt };
}

export function getClientIp(req: Request | { headers: any; ip?: string }): string {
  // Try x-forwarded-for
  const headers = (req as any).headers;
  if (headers) {
    const xff = headers.get ? headers.get('x-forwarded-for') : headers['x-forwarded-for'];
    if (xff) return String(xff).split(',')[0].trim();
    const realIp = headers.get ? headers.get('x-real-ip') : headers['x-real-ip'];
    if (realIp) return String(realIp);
  }
  return 'unknown';
}

// Cleanup old entries every 5 minutes - only in Node runtime, not edge
// Use unref to prevent keeping serverless function alive
if (typeof setInterval !== 'undefined' && typeof process !== 'undefined' && process.env.NEXT_RUNTIME !== 'edge') {
  try {
    const interval = setInterval(() => {
      const now = Date.now();
      store.forEach((v, k) => {
        if (now > v.resetAt) store.delete(k);
      });
    }, 5 * 60 * 1000);
    // @ts-ignore - unref exists in Node
    if (interval && typeof interval.unref === 'function') interval.unref();
  } catch {}
}
