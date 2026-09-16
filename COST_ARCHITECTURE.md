# Initial cost architecture

## Recommended launch stack

- Vercel: Next.js frontend and lightweight orchestration/API.
- Supabase Free: Postgres, customer authentication, RLS, job state.
- Cloudflare R2: original and processed audio objects; no egress fee is the main reason to prefer it for media.
- Persistent worker: smallest Railway/Fly/Render/VPS instance only when real users need asynchronous FFmpeg.
- Cloudflare Workers: optional later for lightweight signed-URL/queue edge logic, not FFmpeg.

## Likely cost progression

1. Start at approximately $0/month while traffic is low and worker processing is manual/local.
2. The persistent FFmpeg worker is likely to become paid first because CPU time and uptime are required for dependable processing.
3. R2 storage/operations becomes the next variable cost as original audio and processed masters accumulate.
4. Supabase/Vercel upgrades are only justified by database size, bandwidth, function duration, or concurrency limits.

Do not force FFmpeg into Cloudflare Workers or Vercel functions. The current worker interface is replaceable so the persistent host can be introduced without rewriting the application.
