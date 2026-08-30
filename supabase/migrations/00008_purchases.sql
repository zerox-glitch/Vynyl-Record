-- 00008: server-side purchase/entitlement state.
-- Stripe event_id is unique so webhook retries are idempotent.
CREATE TABLE IF NOT EXISTS public.purchases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  stripe_session_id TEXT UNIQUE NOT NULL,
  stripe_event_id TEXT UNIQUE,
  user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  customer_email TEXT,
  plan_id UUID,
  status TEXT NOT NULL DEFAULT 'paid' CHECK (status IN ('pending','paid','failed','refunded')),
  amount_cents INT,
  currency TEXT DEFAULT 'usd',
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS purchases_user_idx ON public.purchases(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS purchases_email_idx ON public.purchases(customer_email, created_at DESC);
ALTER TABLE public.purchases ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Owners can view purchases" ON public.purchases;
CREATE POLICY "Owners can view purchases" ON public.purchases
FOR SELECT USING (auth.uid() IS NOT NULL AND user_id = auth.uid());
