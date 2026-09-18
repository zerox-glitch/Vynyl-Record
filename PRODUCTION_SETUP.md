# Production setup checklist — Auth, Entitlements, Stripe, R2, Worker

Never put real secrets in Git.

## Vercel (Next.js)

1. Connect branch `arena/01a0b1c1-vynyl-record` to Vercel project
2. Env:
   - `NEXT_PUBLIC_APP_URL=https://YOUR_DOMAIN`
   - `ADMIN_PASSWORD=long-random`
   - `ADMIN_SESSION_SECRET=different-long-random`
   - `R2_UPLOAD_SECRET=long-random`
   - `PROCESSING_WORKER_SECRET=long-random`
   - `NEXT_PUBLIC_SUPABASE_URL=https://xxx.supabase.co`
   - `NEXT_PUBLIC_SUPABASE_ANON_KEY=eyJ...`
   - `SUPABASE_SERVICE_ROLE_KEY=eyJ...` (service role, never expose to client)
   - `STRIPE_SECRET_KEY=sk_live_...` (or sk_test for staging)
   - `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=pk_live_...`
   - `STRIPE_WEBHOOK_SECRET=whsec_...`
   - Optional: `OPENAI_API_KEY`
   - Optional: R2 vars below
3. Redeploy, verify commit SHA matches branch tip

## Supabase

1. Set env vars as above
2. Run `SUPABASE_SETUP.sql` in SQL Editor (idempotent, includes 00010)
3. Auth → Providers:
   - Enable Email, enable Confirm email for prod
   - Site URL = `https://YOUR_DOMAIN`
   - Redirect URLs: `/login`, `/signup`, `/auth/callback`, `/reset-password`, `/account`, `/library`, `/studio`, `/*` for dev
4. Auth → Providers → Google:
   - Create OAuth client in Google Cloud Console (authorized redirect = `https://YOUR_PROJECT.supabase.co/auth/v1/callback`)
   - Enable Google in Supabase, paste Client ID/Secret
5. Verify RLS enabled, 00007 and 00010 applied
6. Optional: pg_cron for `requeue_stale_jobs`

## Cloudflare R2

1. Create private bucket
2. API token scoped to bucket Read+Write
3. Env:
   - `R2_ACCOUNT_ID`
   - `R2_ACCESS_KEY_ID`
   - `R2_SECRET_ACCESS_KEY`
   - `R2_BUCKET`
   - `R2_REGION=auto`
   - Leave `R2_PUBLIC_BASE` empty for signed URLs
4. CORS: allow production origin for PUT, GET, HEAD with Content-Type, Range

## Processing Worker

1. Deploy repo root to Railway/Fly/Render/VPS
2. Build `npm ci`, Start `npm run worker`
3. Env:
   - `NODE_ENV=production`
   - `NEXT_PUBLIC_SUPABASE_URL`
   - `NEXT_PUBLIC_SUPABASE_ANON_KEY`
   - `SUPABASE_SERVICE_ROLE_KEY`
   - R2 vars
   - `WORKER_POLL_MS=2500`
   - `PROCESSING_WORKER_SECRET`
4. Ensure FFmpeg binary executable
5. Keep `ALLOW_INLINE_FFMPEG` false on Vercel
6. Run at least 1 worker, multiple supported via atomic claim

## Stripe

1. Set keys as above
2. Webhook endpoint: `https://YOUR_DOMAIN/api/webhook/stripe`
   - Events: checkout.session.completed, customer.subscription.created, updated, deleted, invoice.paid, invoice.payment_failed, charge.refunded / refund.created
3. Verify signing secret
4. Products/Prices: Admin edits plan amount → new Stripe Price created, old archived, existing subs untouched. Check sync status in admin UI.
5. Test mode: if keys missing, checkout uses `demo_session_*` and webhook returns simulated note — cannot grant prod access

## Manual Provider Configuration (Summary)

### Supabase Email

- Enable email provider
- Enable email confirmation
- Site URL = production origin
- Redirect URLs include `/auth/callback`, `/login`, `/reset-password`, etc.

### Google OAuth

- Google Cloud → OAuth consent + Client ID (Web app)
- Authorized JS origins: prod + localhost
- Authorized redirect: `https://PROJECT.supabase.co/auth/v1/callback`
- Supabase → Auth → Providers → Google → Enable, paste ID/Secret
- Vercel env: Supabase URL/keys + NEXT_PUBLIC_APP_URL

### Stripe

- Product/Price creation/sync via admin UI (price change → new Price)
- Webhook endpoint and events as above

## Security

- All admin mutations require admin HMAC cookie + service role
- No secrets in client bundles
- Server-side entitlement resolution via `resolveUserEntitlement(userId, recordingId?)`
- Studio, processing, downloads, visibility, checkout enforce server-side
- Never trust browser plan ID, premium boolean, price

## Testing checklist

- Email signup, verification callback, login, logout, forgot/reset, magic link, Google callback structure, profile creation, free entitlement, per-recording, monthly, lifetime, expired/revoked, admin assign, suspend, price edit, webhook idempotency, RLS isolation, studio enforcement, checkout price trust, build
