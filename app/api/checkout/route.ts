import { NextRequest, NextResponse } from 'next/server';
import { createCheckoutSession } from '@/lib/stripe';
import { getPricingPlans } from '@/lib/db';

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { planId, customerEmail } = body;

    const plans = await getPricingPlans();
    const plan = plans.find((p) => p.id === planId) || plans[0];

    if (!plan) {
      return NextResponse.json({ error: 'Selected plan not found' }, { status: 404 });
    }

    const origin = req.headers.get('origin') || process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:3000';
    const successUrl = `${origin}/studio?upgraded=true&plan=${plan.id}`;
    const cancelUrl = `${origin}/#pricing`;

    const result = await createCheckoutSession({
      planId: plan.id,
      planName: plan.name,
      priceCents: plan.price_cents,
      successUrl,
      cancelUrl,
      customerEmail,
    });

    return NextResponse.json(result);
  } catch (error: any) {
    console.error('Checkout creation error:', error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
