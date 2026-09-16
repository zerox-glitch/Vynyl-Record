# Vynyl persistent processing worker

The worker claims Supabase jobs and executes the shared `lib/processing`
implementation on its own long-lived host. It does **not** send FFmpeg work
back to Vercel.

## Deploy

1. Deploy the **repository root** to Railway, Fly.io, Render, or a VPS.
2. Use `npm ci` as the build/install command.
3. Use `npm run worker` as the start command.
4. Keep at least one instance running continuously.

The root deployment is intentional: the worker imports the same audio engine,
preset recipes, queue, database, and R2 modules as the Next.js application.
This prevents a second/parallel mastering implementation.

Required variables:

```text
NODE_ENV=production
NEXT_PUBLIC_SUPABASE_URL
NEXT_PUBLIC_SUPABASE_ANON_KEY
SUPABASE_SERVICE_ROLE_KEY
R2_ACCOUNT_ID
R2_ACCESS_KEY_ID
R2_SECRET_ACCESS_KEY
R2_BUCKET
R2_REGION=auto
R2_PUBLIC_BASE=
WORKER_POLL_MS=2500
OPENAI_API_KEY=optional
```

`@ffmpeg-installer/ffmpeg` is installed with the root application. A system
FFmpeg binary is also supported. Job claiming uses the shared atomic queue
function, so multiple worker instances cannot successfully claim the same
queued row.
