import { NextRequest, NextResponse } from 'next/server';
import { createCheckoutSession } from '@/lib/stripe';
import { getPricingPlans, getAllPricingPlans, getPurchaseBySession } from '@/lib/db';
import { isStripeConfigured, stripe } from '@/lib/stripe';
import { getCustomerUser } from '@/lib/supabase/auth';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';
import { resolveUserEntitlement } from '@/lib/entitlements';
import { rateLimit, getClientIp } from '@/lib/rate-limit';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function sanitizeText(v: unknown, max = 200): string {
  return typeof v === 'string' ? v.trim().slice(0, max) : '';
}

export async function GET(req: NextRequest) {
  try {
    const sessionId = req.nextUrl.searchParams.get('session_id');
    const requestedPlanId = req.nextUrl.searchParams.get('plan');
    const recordingId = req.nextUrl.searchParams.get('recording_id');
    if (!sessionId) {
      return NextResponse.json({ error: 'Checkout session ID is required.' }, { status: 400 });
    }

    const plans = await getAllPricingPlans();
    if (sessionId.startsWith('demo_session_') && !isStripeConfigured()) {
      const plan = plans.find((item) => item.id === requestedPlanId && item.price_cents > 0);
      if (!plan) return NextResponse.json({ error: 'Plan not found.' }, { status: 404 });
      return NextResponse.json({ verified: true, planId: plan.id, demo: true, recordingId: recordingId || null });
    }

    if (!isStripeConfigured()) {
      return NextResponse.json({ error: 'Stripe is not configured.' }, { status: 503 });
    }

    const session = await stripe.checkout.sessions.retrieve(sessionId);
    const planId = session.metadata?.planId || requestedPlanId;
    const plan = plans.find((item) => item.id === planId);
    if (!plan || session.payment_status !== 'paid') {
      return NextResponse.json({ error: 'Payment has not been completed.' }, { status: 402 });
    }

    const purchase = await getPurchaseBySession(session.id).catch(() => null);
    return NextResponse.json({
      verified: true,
      planId: plan.id,
      billingModel: plan.billing_model,
      recordingId: session.metadata?.recordingId || recordingId || null,
      entitlement: purchase?.status === 'paid' ? 'paid' : 'pending_webhook',
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message || 'Checkout verification failed.' }, { status: 400 });
  }
}

export async function POST(req: NextRequest) {
  try {
    const ip = getClientIp(req);
    const rl = rateLimit(`checkout:${ip}`, 10, 60 * 1000);
    if (!rl.allowed) {
      return NextResponse.json({ error: 'Too many checkout attempts' }, { status: 429 });
    }

    const body = await req.json().catch(() => ({}));
    const planId = sanitizeText(body.planId, 100);
    const recordingId = body.recordingId ? sanitizeText(body.recordingId, 100) : null;
    const customerEmail = body.customerEmail ? sanitizeText(body.customerEmail, 200) : undefined;

    if (!planId) return NextResponse.json({ error: 'Plan ID required' }, { status: 400 });

    // Server-side plan lookup - never trust browser price
    const plans = await getAllPricingPlans();
    const plan = plans.find((p) => p.id === planId);

    if (!plan) {
      return NextResponse.json({ error: 'Selected plan not found' }, { status: 404 });
    }
    if (!plan.is_active) {
      return NextResponse.json({ error: 'Selected plan is not active' }, { status: 400 });
    }
    if (plan.price_cents <= 0 && plan.billing_model === 'free') {
      return NextResponse.json({ url: '/studio', sessionId: null });
    }

    // Require authentication for checkout, or safely return to checkout after auth
    const user = await getCustomerUser();
    if (!user) {
      // If not authenticated, return a login URL that will bring them back
      const origin = process.env.NEXT_PUBLIC_APP_URL || req.nextUrl.origin;
      const checkoutPath = `/api/checkout?planId=${encodeURIComponent(planId)}${recordingId ? `&recordingId=${encodeURIComponent(recordingId)}` : ''}`;
      // Actually, we should require auth now - spec says checkout must require authentication or safely return after auth
      // For API, we return 401 with login redirect
      return NextResponse.json(
        { error: 'Authentication required', loginUrl: `/login?next=${encodeURIComponent(`/studio?checkout_plan=${planId}${recordingId ? `&recording=${recordingId}` : ''}`)}` },
        { status: 401 }
      );
    }

    // Validate recording ownership if per-recording checkout
    if (plan.billing_model === 'per_recording') {
      if (!recordingId) {
        return NextResponse.json({ error: 'Recording ID required for per-recording purchase' }, { status: 400 });
      }
      if (isSupabaseServerConfigured()) {
        const supabase = getServiceSupabase();
        const { data: rec } = await supabase.from('recordings').select('id, user_id').eq('id', recordingId).maybeSingle();
        if (!rec) return NextResponse.json({ error: 'Recording not found' }, { status: 404 });
        if (rec.user_id && rec.user_id !== user.id) {
          return NextResponse.json({ error: 'Not authorized for this recording' }, { status: 403 });
        }
      }
    }

    // Check existing entitlement to avoid double purchase for lifetime/monthly?
    // Allow but warn - for monthly, if already active monthly, we still allow (could be renewal)
    // For lifetime, if already has lifetime, block
    const resolved = await resolveUserEntitlement(user.id, recordingId);
    if (resolved.billingModel === 'lifetime' && plan.billing_model === 'lifetime' && resolved.status === 'active') {
      return NextResponse.json({ error: 'You already have lifetime access', url: '/account' }, { status: 400 });
    }

    // Get stripe customer id if exists
    let stripeCustomerId: string | undefined;
    if (isSupabaseServerConfigured()) {
      const supabase = getServiceSupabase();
      const { data: profile } = await supabase.from('profiles').select('stripe_customer_id').eq('id', user.id).maybeSingle();
      stripeCustomerId = profile?.stripe_customer_id || undefined;
    }

    const origin = process.env.NEXT_PUBLIC_APP_URL || req.nextUrl.origin;
    // Safe redirect after checkout
    const successUrl =
      plan.billing_model === 'per_recording' && recordingId
        ? `${origin}/play/${encodeURIComponent(recordingId)}?session_id={CHECKOUT_SESSION_ID}&plan=${plan.id}&recording_id=${recordingId}`
        : `${origin}/studio?session_id={CHECKOUT_SESSION_ID}&plan=${plan.id}${recordingId ? `&recording_id=${recordingId}` : ''}`;

    const cancelUrl = recordingId ? `${origin}/studio?recording=${recordingId}` : `${origin}/#pricing`;

    const result = await createCheckoutSession({
      planId: plan.id,
      planName: plan.name,
      priceCents: plan.price_cents,
      currency: plan.currency || 'usd',
      successUrl,
      cancelUrl,
      customerEmail: customerEmail || user.email,
      customerId: stripeCustomerId,
      userId: user.id,
      recordingId: recordingId,
      billingModel: plan.billing_model,
      stripePriceId: plan.stripe_price_id,
      stripeProductId: plan.stripe_product_id,
      isRecurring: plan.billing_model === 'monthly',
    });

    return NextResponse.json({ ...result, billingModel: plan.billing_model });
  } catch (error: any) {
    console.error('Checkout creation error:', error);
    return NextResponse.json({ error: error.message || 'Checkout failed' }, { status: 500 });
  }
}
