# Vynyl processing worker

This is a deployment-ready persistent worker entrypoint. It polls Supabase
for queued jobs and uses the protected application worker interface.

## Deploy

1. Deploy this `worker/` directory to Railway, Fly.io, Render, or a small VPS.
2. Install FFmpeg on the host (`apt-get install ffmpeg`) or make the bundled
   `@ffmpeg-installer/ffmpeg` binary available.
3. Set the variables below.
4. Run `npm install && npm start`.

Required variables:

```text
NEXT_PUBLIC_SUPABASE_URL
SUPABASE_SERVICE_ROLE_KEY
R2_UPLOAD_SECRET
R2_ACCOUNT_ID
R2_ACCESS_KEY_ID
R2_SECRET_ACCESS_KEY
R2_BUCKET
R2_REGION=auto
R2_PUBLIC_BASE=
OPENAI_API_KEY=optional
WORKER_POLL_MS=2500
WORKER_APP_URL=https://your-app.vercel.app
PROCESSING_WORKER_SECRET=the-same-secret-as-vercel
```

Job claiming uses a conditional `state = queued` update, preventing a second
worker from processing the same row. Stale heartbeats are requeued by the
migration helper / shared queue implementation.
