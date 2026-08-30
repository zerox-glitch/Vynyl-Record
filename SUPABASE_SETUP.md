# Supabase setup

This document describes exactly what Supabase is asked for and what the
application expects back. It is the human-facing companion to
`SUPABASE_SETUP.sql`.

## What the application expects from Supabase

The Next.js app uses Supabase as its authoritative database when the
following env vars are configured:

```text
NEXT_PUBLIC_SUPABASE_URL
NEXT_PUBLIC_SUPABASE_ANON_KEY
SUPABASE_SERVICE_ROLE_KEY
```

Anything below that is described as optional is a feature toggle that
improves behaviour when set, but does not block the app.

## What the app uses Supabase for

- Customer authentication (email/password via Supabase Auth)
- The recordings table (`/library`, `/play/[slug]`, ownership checks)
- The processing_jobs queue (worker claim, heartbeat, completion)
- The record_events table (analytics funnel: write-only from server)
- The record_transcripts table (Whisper payloads)
- The purchases table (Stripe webhook idempotency)

## What the app does NOT use Supabase for

- Object storage for audio files. Cloudflare R2 is the production
  object store. `lib/storage/r2.ts`, the worker, and the playback
  routes reference R2 keys/URLs. Audio bytes never enter Supabase.
- QR codes (PNG generation runs in `lib/qr/generator.ts`).
- Transcripts currently inline-fallback to `recordings.transcript_json`
  when `record_transcripts` is empty.
- Recording/scheduling emails (Supabase Auth is configured, but no SMTP
  provider is wired).

If you see a `storage.buckets` reference in the SQL, that bucket is kept
only because legacy cleanup code in `lib/db.ts:deleteRecording` reads the
R2-equivalent `/storage/v1/object/public/recordings/...` URL prefix
(see `00004_functionality_fixes.sql`). The bucket is **inert** for R2
deployments and safe to keep or drop.

## RLS policies applied by SUPABASE_SETUP.sql

`public.profiles` — owner-scoped SELECT/UPDATE
`public.recordings` — public/unlisted SELECT (link-addressable), owner-only INSERT/UPDATE/DELETE
`public.processing_jobs` — owner-only SELECT (server still uses service-role to write)
`public.record_transcripts` — owner-only SELECT via FK to `recordings.user_id`
`public.record_events` — no anon-read policy (service-role only)
`public.purchases` — owner-only SELECT
`public.site_settings`, `public.pricing_plans`, `public.audio_assets` — public SELECT

INSERT/UPDATE/DELETE on non-public tables remain service-role driven.
The application uses the **service role** key (`SUPABASE_SERVICE_ROLE_KEY`)
on every server-side write path; the anon key only reads the
public-side tables.

## Auth configuration required manually

1. Open Supabase → Authentication → Providers.
2. Ensure **Email** is enabled (the route `app/api/auth/customer/route.ts`
   uses `signUp` / `signInWithPassword`).
3. For production, enable **Confirm email** so a customer truly owns the
   account before they can claim private records.
4. Add the production origin to **URL Configuration → Additional Redirect
   URLs** so the email-confirmation link works.

## Environment variable names

| Variable | Where it is read | Required for |
|--------|------------------|---------------|
| `NEXT_PUBLIC_SUPABASE_URL` | `lib/supabase/client.ts`, `lib/supabase/server.ts`, `middleware.ts` | Anon auth, service-role reads/writes |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | same | Public Supabase reads, customer auth |
| `SUPABASE_SERVICE_ROLE_KEY` | `lib/db.ts`, `lib/processing/*` | Server-side writes (records, jobs, purchases, transcripts, events) |

## What Supabase must run manually

- Paste the contents of `SUPABASE_SETUP.sql` into Supabase SQL Editor and run it once. The script is idempotent (uses `IF NOT EXISTS`, `ON CONFLICT`, `DO $$ ... EXCEPTION WHEN OTHERS THEN NULL; END $$;`).
- In **Authentication → Providers**, enable **Email** (and **Email confirm** for production).
- In **Authentication → URL Configuration**, add `${NEXT_PUBLIC_APP_URL}/login` to Site URL and Redirect URLs.
- If you want the auto-unblock of stuck processing jobs, enable `pg_cron` in **Database → Extensions** and run once:
  ```sql
  select cron.schedule('requeue-stale-jobs','*/5 * * * *',$$select public.requeue_stale_jobs(10);$$);
  ```
  The function exists; scheduling is opt-in.

## What should NOT be changed

- Do not delete the rows already in `recordings` / `audio_assets` / `pricing_plans` / `site_settings`. The script is additive and uses `ON CONFLICT DO NOTHING` for seeds.
- Do not drop the `public.profiles` table. `auth.users(id)` references it via FK.
- Do not drop the `recordings.user_id` FK to `auth.users(id)` — the RLS policies and ownership join in `record_transcripts` depend on it.
- Do not remove RLS from `recordings`, `processing_jobs`, `record_transcripts`, `record_events`, `purchases`, `profiles`. The application server uses the service-role key, which bypasses RLS — but anon and authenticated clients use the anon key, which respects the policies.
- Do not skip migration 00007. The original `00001` granted `USING (true)` to both SELECT and INSERT on `public.recordings`, which lets an anon Supabase client read every record. 00007 is the only thing that restricts that.

## Migration conflicts and risks

- 00007 calls `DROP POLICY IF EXISTS "Public recordings read access" ON public.recordings` then re-creates the SELECT policy under a different name. If any external SQL already added a `Recordings are visible by privacy` policy or any policy on this table, the `CREATE POLICY` will fail with `policy already exists` and fall into the skipped block. The application never re-issues arbitrary RLS in code beyond `lib/supabase/server.ts` (server-side uses the service-role key).
- 00001 declares `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`. Supabase enables the modern default (`pgcrypto`/`uuid-ossp`) automatically; on a vanilla project the statement may emit a `permission denied to create extension` notice. The script does not hide that; rerun as a database superuser if needed.
- 00004 inserts `('recordings', 'recordings', TRUE)` and `('audio-assets', 'audio-assets', TRUE)` into `storage.buckets`. The SQL keeps them so `lib/db.ts:deleteRecording`'s URL prefix still resolves in dev, but in a real R2 project the buckets are inert.
- 00006 declares `recording_id UUID NOT NULL REFERENCES public.recordings(id) ON DELETE CASCADE` and the histories depend on it. If the order is changed or pre-existing `recordings` have NULL `recording_id`, the FK creation will fail. The current migrations already include the alignment, so this is only a concern if someone applies a partial subset.
- 00008 declares `stripe_session_id TEXT UNIQUE`. Stripe never reuses a session id, so uniqueness should not conflict. `stripe_event_id TEXT UNIQUE` permits idempotent replay.
- All five `ALTER TABLE ... ALTER CONSTRAINT` style changes use `DROP CONSTRAINT IF EXISTS` before `ADD CONSTRAINT`, so re-running the script is safe.

## Verification after running

Use the SQL Editor to confirm the new state matches expectations:

```sql
-- tables the app reads/writes
select table_name from information_schema.tables
where table_schema = 'public' and table_name in
('recordings','processing_jobs','record_events','record_transcripts','purchases');

-- policies on recordings
select policyname, cmd, qual, with_check
from pg_policies where tablename = 'recordings' order by cmd, policyname;

-- recordings columns the app actually touches
select column_name, data_type from information_schema.columns
where table_schema = 'public' and table_name = 'recordings'
order by ordinal_position;
```

If those three queries return the expected rows, the application database is
authoritatively configured.
