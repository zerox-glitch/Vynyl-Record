import { NextRequest, NextResponse } from 'next/server';
import { createCheckoutSession } from '@/lib/stripe';
import { getPricingPlans } from '@/lib/db';
import { isStripeConfigured, stripe } from '@/lib/stripe';

export async function GET(req: NextRequest) {
  try {
    const sessionId = req.nextUrl.searchParams.get('session_id');
    const requestedPlanId = req.nextUrl.searchParams.get('plan');
    if (!sessionId) {
      return NextResponse.json({ error: 'Checkout session ID is required.' }, { status: 400 });
    }

    const plans = await getPricingPlans();
    if (sessionId.startsWith('demo_session_') && !isStripeConfigured()) {
      const plan = plans.find((item) => item.id === requestedPlanId && item.price_cents > 0);
      if (!plan) return NextResponse.json({ error: 'Plan not found.' }, { status: 404 });
      return NextResponse.json({ verified: true, planId: plan.id, demo: true });
    }

    if (!isStripeConfigured()) {
      return NextResponse.json({ error: 'Stripe is not configured.' }, { status: 503 });
    }

    const session = await stripe.checkout.sessions.retrieve(sessionId);
    const plan = plans.find((item) => item.id === session.metadata?.planId);
    if (!plan || session.payment_status !== 'paid') {
      return NextResponse.json({ error: 'Payment has not been completed.' }, { status: 402 });
    }

    return NextResponse.json({ verified: true, planId: plan.id });
  } catch (error: any) {
    return NextResponse.json({ error: error.message || 'Checkout verification failed.' }, { status: 400 });
  }
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { planId, customerEmail } = body;

    const plans = await getPricingPlans();
    const plan = plans.find((p) => p.id === planId);

    if (!plan) {
      return NextResponse.json({ error: 'Selected plan not found' }, { status: 404 });
    }
    if (plan.price_cents <= 0) {
      return NextResponse.json({ url: '/studio', sessionId: null });
    }

    const origin = process.env.NEXT_PUBLIC_APP_URL || req.nextUrl.origin;
    const successUrl = `${origin}/studio?session_id={CHECKOUT_SESSION_ID}&plan=${plan.id}`;
    const cancelUrl = `${origin}/#pricing`;

    const result = await createCheckoutSession({
      planId: plan.id,
      planName: plan.name,
      priceCents: plan.price_cents,
      successUrl,
      cancelUrl,
      customerEmail,
      isRecurring: plan.price_cents > 0 && plan.price_cents < 2000,
    });

    return NextResponse.json(result);
  } catch (error: any) {
    console.error('Checkout creation error:', error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
