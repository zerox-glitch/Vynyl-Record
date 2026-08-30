# Vynyl-Record

## Local setup

1. Copy `.env.example` to `.env.local` and set a strong `ADMIN_PASSWORD` and `ADMIN_SESSION_SECRET`.
2. Run `npm install`.
3. Run `npm run dev` and open `http://localhost:3000`.

FFmpeg is supplied by the project dependencies. The production build generates all bundled demo, background, crackle, and needle-drop audio before compiling Next.js.

## Production integrations

- **Supabase:** Set all three Supabase variables and apply migrations `00001` through `00007` in numeric order. Supabase is the authoritative production database when configured. Without it, the application uses a temporary local demo store that is not durable across server restarts.
- **Cloudflare R2:** Set `R2_UPLOAD_SECRET`, `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, and `R2_BUCKET` to activate direct browser-to-R2 uploads and processed-object storage. `R2_PUBLIC_BASE` is optional; private records receive signed download URLs instead.
- **Processing worker:** Set `PROCESSING_WORKER_SECRET` (or `CRON_SECRET`). The endpoint `/api/processing/worker` drains one queued job per call. For real production FFmpeg durations, run a persistent Railway/Fly/VM process that polls this endpoint or calls the same `lib/processing/workers.ts` interface. Do not put long-running FFmpeg in a Vercel request.
- **OpenAI:** Set `OPENAI_API_KEY` for real word-level Whisper transcripts and the AI writing assistant. Without it, the core recorder still works; AI script generation falls back to a local editable template.
- **Customer Auth:** Set the Supabase URL + anon key to activate `/login` and owner-scoped `/library`; admin authentication remains separate.
- **Stripe:** Set `STRIPE_SECRET_KEY`, `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`, and `STRIPE_WEBHOOK_SECRET` for real checkout. Without them, checkout intentionally runs in local demo mode and must not be treated as production payment verification.
- **Application URL:** Set `NEXT_PUBLIC_APP_URL` to the canonical HTTPS production origin so Stripe and record links return users to the correct studio.

## Administrator access

The public navigation does not expose the administration area. Configure `ADMIN_PASSWORD` and, preferably, a separate `ADMIN_SESSION_SECRET` using `.env.example`, then visit `/admin/login` directly. Admin pages and all management API mutations require the signed, HTTP-only administrator session cookie.

Migration `00004` adds the profile membership fields, recording metadata, and corrected plan-to-audio permissions required by the current administration and studio interfaces.
