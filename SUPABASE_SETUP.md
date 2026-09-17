# Supabase setup — Production-grade Auth, Entitlements, Pricing

This document describes the authoritative database and Auth configuration required for Vynyl Record.

## 1. Database

### Apply migrations

Apply in numeric order or run consolidated `SUPABASE_SETUP.sql` (idempotent):

- 00001_initial_schema
- 00002_audio_asset_controls
- 00003_distinct_audio_assets
- 00004_functionality_fixes
- 00005_privacy_visibility
- 00006_proc_jobs_data_model
- 00007_rls_hardening
- 00008_purchases
- 00009_audio_asset_storage_trim
- 00010_profiles_entitlements_pricing (new)

00010 adds:

- `profiles.avatar_url`, `account_status` (active/suspended/disabled)
- trigger `handle_new_user` to auto-create profile on `auth.users` insert
- extended `pricing_plans` fields: slug, description, billing_model (free/per_recording/monthly/lifetime), currency, billing_interval, stripe_product_id, included_recordings, allowed_vinyl_presets, can_download, can_use_private_visibility, can_use_advanced_mixer, display_order, updated_at
- seed 4 canonical plans: Free, Premium Record ($9 per recording), Collector Monthly ($12/month), Lifetime Archive ($49 lifetime)
- `user_entitlements` (account-wide): status active/trialing/past_due/canceled/expired/revoked, source signup/stripe/admin/lifetime/migration, starts_at, expires_at, remaining_recordings, stripe refs
- `recording_entitlements` (per-recording): status active/expired/revoked/refunded
- `stripe_webhook_events` for idempotency
- `admin_audit_logs` for role changes, suspension, entitlement grant/revoke, credit changes, plan changes, deletion
- `recordings.entitlement_plan_id`, `entitlement_source` for reprocessing persistence
- auto-grant free entitlement on profile creation

### Verification queries

```sql
select table_name from information_schema.tables where table_schema='public' and table_name in ('profiles','pricing_plans','user_entitlements','recording_entitlements','admin_audit_logs','stripe_webhook_events','recordings');

select policyname, cmd from pg_policies where tablename in ('profiles','user_entitlements','recording_entitlements') order by tablename, cmd;

select slug, billing_model, price_cents, is_active, display_order from pricing_plans order by display_order;
```

## 2. Supabase Email Auth

Manual steps in Supabase Dashboard → Authentication → Providers:

1. Enable **Email** provider
2. Enable **Confirm email** for production (email confirmation required)
3. Set **Site URL** to `https://YOUR_DOMAIN` (or `http://localhost:3000` for dev)
4. Add Redirect URLs:
   - `https://YOUR_DOMAIN/login`
   - `https://YOUR_DOMAIN/signup`
   - `https://YOUR_DOMAIN/auth/callback`
   - `https://YOUR_DOMAIN/reset-password`
   - `https://YOUR_DOMAIN/account`
   - `https://YOUR_DOMAIN/library`
   - `https://YOUR_DOMAIN/studio`
   - For dev: `http://localhost:3000/*`
5. In Email Templates, ensure confirmation and recovery links use `{{ .ConfirmationURL }}` which will point to Site URL + /auth/callback
6. If using custom SMTP, configure in Auth → SMTP

Env required:

```
NEXT_PUBLIC_SUPABASE_URL
NEXT_PUBLIC_SUPABASE_ANON_KEY
SUPABASE_SERVICE_ROLE_KEY
NEXT_PUBLIC_APP_URL
```

## 3. Google OAuth

### Google Cloud Console

1. Create project or use existing
2. APIs & Services → OAuth consent screen → Configure (External, add app name, support email, scopes email/profile)
3. APIs & Services → Credentials → Create Credentials → OAuth client ID
   - Type: Web application
   - Name: Vynyl Record
   - Authorized JavaScript origins: `https://YOUR_DOMAIN`, `http://localhost:3000`
   - Authorized redirect URIs: `https://YOUR_PROJECT.supabase.co/auth/v1/callback`
     (Supabase Auth callback, NOT your app callback)
4. Copy Client ID and Client Secret

### Supabase Dashboard

1. Authentication → Providers → Google → Enable
2. Paste Client ID and Client Secret from Google
3. Save

### Vercel env

```
NEXT_PUBLIC_SUPABASE_URL (same)
NEXT_PUBLIC_SUPABASE_ANON_KEY
SUPABASE_SERVICE_ROLE_KEY
NEXT_PUBLIC_APP_URL=https://YOUR_DOMAIN
```

No additional Vercel variables for Google beyond Supabase ones. Supabase handles OAuth exchange.

### App callback

App uses `/auth/callback?next=/library` (or other safe next). The route validates `next` to prevent open-redirect: only relative paths starting with `/`, no `//`, no `..`, same origin.

## 4. Stripe

### Product and Price synchronization

- Admin creates/edits plan in `/admin` → Pricing & Stripe Plans
- When price_cents changes, server creates new Stripe Price for plan's Stripe Product, updates plan's `stripe_price_id`, archives old Price
- Existing subscriptions are NOT changed automatically — requires explicit migration
- Admin UI shows Stripe Product/Price IDs and sync status (via `/api/admin/pricing-stats` and response `stripeSyncStatus`)

If Stripe credentials unavailable, checkout returns `demo_session_*` URL and webhook returns simulated mode note. Demo mode cannot grant production access (webhook checks `isStripeConfigured()`).

### Webhook

1. Stripe Dashboard → Developers → Webhooks → Add endpoint
   - URL: `https://YOUR_DOMAIN/api/webhook/stripe`
   - Events:
     - `checkout.session.completed`
     - `customer.subscription.created`
     - `customer.subscription.updated`
     - `customer.subscription.deleted`
     - `invoice.paid`
     - `invoice.payment_failed`
     - `charge.refunded` (or `refund.created` / `charge.refund.updated`)
2. Copy Signing secret `whsec_...` → set `STRIPE_WEBHOOK_SECRET`
3. Set `STRIPE_SECRET_KEY` and `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`

Env:

```
STRIPE_SECRET_KEY=sk_live_...
NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=pk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
```

### Idempotency

Webhook stores event ID in `stripe_webhook_events` and ignores duplicates.

### Server-side price lookup

Checkout POST looks up plan from DB (never trusts browser price). Per-recording checkout requires `recordingId` and validates ownership.

## 5. RLS

- `profiles`: owner can SELECT own, UPDATE own permitted fields (full_name, avatar_url). Admin uses service role.
- `user_entitlements`: owner SELECT own
- `recording_entitlements`: owner SELECT own
- `admin_audit_logs`: service role only
- `purchases`: owner SELECT own (existing)
- `recordings`: public/unlisted visible, private owner-only (existing)
- All admin mutations require admin HMAC cookie and service role.

## 6. Rate limiting

Simple in-memory rate limiting applied in API routes via headers? For production, consider Upstash Redis or Vercel WAF. Current implementation adds basic checks in auth routes (signin, password-reset, magic-link, checkout) via Supabase built-in rate limiting and explicit 429 handling.

## 7. What NOT to change

- Do not delete seed plans; archive instead
- Do not drop `profiles` FK to `auth.users`
- Do not remove RLS
- Do not expose service-role keys in client bundles
