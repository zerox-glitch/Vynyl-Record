import { NextRequest, NextResponse } from 'next/server';
import { stripe, isStripeConfigured } from '@/lib/stripe';
import { savePurchase } from '@/lib/db';

export async function POST(req: NextRequest) {
  if (!isStripeConfigured()) {
    return NextResponse.json({ received: true, note: 'Stripe simulated mode active' });
  }

  const payload = await req.text();
  const sig = req.headers.get('stripe-signature') || '';
  const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;

  if (!webhookSecret) {
    return NextResponse.json({ error: 'Stripe webhook signing secret is not configured.' }, { status: 503 });
  }

  try {
    const event = stripe.webhooks.constructEvent(payload, sig, webhookSecret);

    switch (event.type) {
      case 'checkout.session.completed': {
        const session = event.data.object;
        await savePurchase({
          stripe_session_id: session.id,
          stripe_event_id: event.id,
          customer_email: session.customer_details?.email || session.customer_email || null,
          plan_id: session.metadata?.planId || null,
          status: session.payment_status === 'paid' ? 'paid' : 'pending',
          amount_cents: session.amount_total || null,
          currency: session.currency || 'usd',
          metadata: { mode: session.mode || null },
        });
        console.log(`[Stripe Webhook] Purchase persisted for session: ${session.id}`);
        break;
      }
      case 'charge.refunded': {
        // Stripe's charge object exposes payment_intent; entitlement refund
        // reconciliation can be added when a customer purchase is linked.
        console.log(`[Stripe Webhook] Refund received: ${event.id}`);
        break;
      }
      default:
        console.log(`[Stripe Webhook] Unhandled event type: ${event.type}`);
    }

    return NextResponse.json({ received: true });
  } catch (err: any) {
    console.error(`Webhook Error: ${err.message}`);
    return NextResponse.json({ error: `Webhook Error: ${err.message}` }, { status: 400 });
  }
}
