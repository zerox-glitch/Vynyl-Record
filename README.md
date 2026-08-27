# Vynyl-Record

## Local setup

1. Copy `.env.example` to `.env.local` and set a strong `ADMIN_PASSWORD` and `ADMIN_SESSION_SECRET`.
2. Run `npm install`.
3. Run `npm run dev` and open `http://localhost:3000`.

FFmpeg is supplied by the project dependencies. The production build generates all bundled demo, background, crackle, and needle-drop audio before compiling Next.js.

## Production integrations

- **Supabase:** Set all three Supabase variables and apply migrations `00001` through `00004` in numeric order. Without Supabase, the application uses a temporary local demo store that is not durable across server restarts.
- **OpenAI:** Set `OPENAI_API_KEY` for real word-level Whisper transcripts. Without it, audio processing still works with demo transcript text.
- **Stripe:** Set `STRIPE_SECRET_KEY`, `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`, and `STRIPE_WEBHOOK_SECRET` for real checkout. Without them, checkout intentionally runs in local demo mode.
- **Application URL:** Set `NEXT_PUBLIC_APP_URL` to the canonical HTTPS production origin so Stripe returns users to the correct studio.

## Administrator access

The public navigation does not expose the administration area. Configure `ADMIN_PASSWORD` and, preferably, a separate `ADMIN_SESSION_SECRET` using `.env.example`, then visit `/admin/login` directly. Admin pages and all management API mutations require the signed, HTTP-only administrator session cookie.

Migration `00004` adds the profile membership fields, recording metadata, and corrected plan-to-audio permissions required by the current administration and studio interfaces.
