# Production setup checklist

This file contains only manual configuration required outside the repository.
Never put real secrets here or in Git.

## REQUIRED NOW

### Vercel

1. Connect the repository branch `arena/01a044ab-vynyl-record` to the existing Vercel project.
2. Set `NEXT_PUBLIC_APP_URL` to the final HTTPS production origin.
3. Set `ADMIN_PASSWORD` and a different long random `ADMIN_SESSION_SECRET`.
4. Set `R2_UPLOAD_SECRET` to a new long random secret.
5. Set `PROCESSING_WORKER_SECRET` to a separate long random worker secret.
6. Redeploy and confirm the deployment commit matches the GitHub branch tip.

### Supabase

1. Configure:
   - `NEXT_PUBLIC_SUPABASE_URL`
   - `NEXT_PUBLIC_SUPABASE_ANON_KEY`
   - `SUPABASE_SERVICE_ROLE_KEY`
2. Apply migrations `00001` through `00007` in numeric order.
3. Enable Supabase Auth email/password sign-up if customer accounts are wanted now.
4. Verify email redirect URLs point to `${NEXT_PUBLIC_APP_URL}/login`.
5. Confirm Row Level Security is enabled and migration `00007_rls_hardening.sql` has run.

### Cloudflare R2

1. Create one private R2 bucket.
2. Create an R2 API token scoped only to that bucket with Object Read and Object Write permissions.
3. Set:
   - `R2_ACCOUNT_ID`
   - `R2_ACCESS_KEY_ID`
   - `R2_SECRET_ACCESS_KEY`
   - `R2_BUCKET`
   - `R2_REGION=auto`
4. Leave `R2_PUBLIC_BASE` empty for private objects and signed playback/download URLs.
5. Configure bucket CORS to allow the production origin for `PUT`, `GET`, and `HEAD` with `Content-Type` and `Range` headers.

### Persistent processing worker

1. Deploy the `worker/` directory to Railway, Fly.io, Render, or a small VPS.
2. Install FFmpeg on the host, or ensure the bundled FFmpeg binary is executable.
3. Set on the worker:
   - `NEXT_PUBLIC_SUPABASE_URL`
   - `SUPABASE_SERVICE_ROLE_KEY`
   - `R2_UPLOAD_SECRET`
   - `R2_ACCOUNT_ID`
   - `R2_ACCESS_KEY_ID`
   - `R2_SECRET_ACCESS_KEY`
   - `R2_BUCKET`
   - `R2_REGION=auto`
   - `WORKER_APP_URL`
   - `PROCESSING_WORKER_SECRET`
   - `WORKER_POLL_MS=2500`
4. Run `cd worker && npm install && npm start`.
5. Keep `ALLOW_INLINE_FFMPEG` unset or false on Vercel. The worker, not Vercel, owns long FFmpeg jobs.
6. Run at least one worker process. Multiple workers are supported by the conditional queued-to-processing claim.

### Stripe

1. Set `STRIPE_SECRET_KEY`.
2. Set `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`.
3. Set `STRIPE_WEBHOOK_SECRET`.
4. Configure the Stripe webhook endpoint:
   `https://YOUR_DOMAIN/api/webhook/stripe`
5. Subscribe at minimum to `checkout.session.completed`.
6. Verify the webhook signing secret matches the production endpoint.

## OPTIONAL

### AI writing assistant

Set one provider key:

- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`

The core recorder works without either key. Without a key, the writing assistant uses an editable local template and Whisper transcription is disabled.

### Transcription

Set `OPENAI_API_KEY`. The persistent worker will use the transcription provider and save timestamped words into `record_transcripts`.

### Domain and sharing

Set the canonical domain in `NEXT_PUBLIC_APP_URL`, then add it to Supabase Auth redirect URLs, R2 CORS, and Stripe success/cancel URLs.

## FUTURE

- Cloudflare Queue or another queue transport in place of Supabase polling.
- Move the worker from the application bridge endpoint to direct shared worker-module execution.
- Social-video worker for 9:16 MP4 rendering.
- Scheduled gift delivery provider and email delivery domain.
- Physical vinyl manufacturing / fulfillment.
