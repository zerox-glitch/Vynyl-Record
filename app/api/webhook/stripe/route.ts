import { NextRequest, NextResponse } from 'next/server';
import { stripe, isStripeConfigured } from '@/lib/stripe';
import { savePurchase } from '@/lib/db';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

async function ensureIdempotent(eventId: string, type: string, data: any): Promise<boolean> {
  if (!isSupabaseServerConfigured()) return false;
  const supabase = getServiceSupabase();
  try {
    const { data: existing } = await supabase.from('stripe_webhook_events').select('id').eq('id', eventId).maybeSingle();
    if (existing) {
      console.log(`[Stripe Webhook] Duplicate event ${eventId} ignored`);
      return true;
    }
    await supabase.from('stripe_webhook_events').insert({ id: eventId, type, data, processed_at: new Date().toISOString() });
    return false;
  } catch (e) {
    console.warn('idempotency check failed', e);
    return false;
  }
}

async function grantEntitlementFromCheckout(session: any, eventId: string) {
  if (!isSupabaseServerConfigured()) return;
  const supabase = getServiceSupabase();

  const planId = session.metadata?.planId;
  const userId = session.metadata?.userId;
  const recordingId = session.metadata?.recordingId;
  const billingModel = session.metadata?.billingModel;
  const customerEmail = session.customer_details?.email || session.customer_email;
  const stripeCustomerId = typeof session.customer === 'string' ? session.customer : session.customer?.id || null;
  const subscriptionId = typeof session.subscription === 'string' ? session.subscription : session.subscription?.id || null;

  // Always save purchase record for idempotency and audit
  await savePurchase({
    stripe_session_id: session.id,
    stripe_event_id: eventId,
    user_id: userId || null,
    customer_email: customerEmail || null,
    plan_id: planId || null,
    status: session.payment_status === 'paid' ? 'paid' : 'pending',
    amount_cents: session.amount_total || null,
    currency: session.currency || 'usd',
    metadata: { mode: session.mode || null, billingModel, recordingId, subscriptionId },
  });

  if (!planId) {
    console.warn('[Stripe] No planId in session metadata');
    return;
  }

  // Fetch plan to know billing model if not in metadata
  const { data: plan } = await supabase.from('pricing_plans').select('*').eq('id', planId).maybeSingle();
  if (!plan) {
    console.warn('[Stripe] Plan not found', planId);
    return;
  }

  // Resolve user by userId or email
  let targetUserId = userId;
  if (!targetUserId && customerEmail) {
    const { data: profile } = await supabase.from('profiles').select('id').eq('email', customerEmail).maybeSingle();
    targetUserId = profile?.id || null;
  }
  if (!targetUserId) {
    console.warn('[Stripe] No user found for session', session.id);
    return;
  }

  // Update profile stripe_customer_id
  if (stripeCustomerId) {
    await supabase.from('profiles').update({ stripe_customer_id: stripeCustomerId, updated_at: new Date().toISOString() }).eq('id', targetUserId);
  }

  if (plan.billing_model === 'per_recording' && recordingId) {
    // Grant per-recording entitlement
    const { data: existing } = await supabase
      .from('recording_entitlements')
      .select('id')
      .eq('recording_id', recordingId)
      .eq('user_id', targetUserId)
      .eq('status', 'active')
      .maybeSingle();
    if (!existing) {
      await supabase.from('recording_entitlements').insert({
        recording_id: recordingId,
        user_id: targetUserId,
        plan_id: planId,
        status: 'active',
        stripe_checkout_session_id: session.id,
      });
      console.log(`[Stripe] Granted per-recording entitlement for ${recordingId} to ${targetUserId}`);
    }
    // Also update recording's entitlement fields for reprocessing
    await supabase.from('recordings').update({ entitlement_plan_id: planId, entitlement_source: 'per_recording' }).eq('id', recordingId);
  } else {
    // Account-wide entitlement
    // For monthly, set expires_at based on subscription
    let expiresAt: string | null = null;
    let remaining = plan.included_recordings || null;

    if (plan.billing_model === 'monthly') {
      // Will be updated by subscription events, but set 1 month from now as fallback
      expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
    } else if (plan.billing_model === 'lifetime') {
      expiresAt = null;
    }

    // Check if already has active entitlement for same plan and source stripe
    const { data: existingEnt } = await supabase
      .from('user_entitlements')
      .select('id')
      .eq('user_id', targetUserId)
      .eq('plan_id', planId)
      .eq('stripe_checkout_session_id', session.id)
      .maybeSingle();

    if (!existingEnt) {
      await supabase.from('user_entitlements').insert({
        user_id: targetUserId,
        plan_id: planId,
        status: 'active',
        source: 'stripe',
        starts_at: new Date().toISOString(),
        expires_at: expiresAt,
        remaining_recordings: remaining,
        stripe_customer_id: stripeCustomerId,
        stripe_subscription_id: subscriptionId,
        stripe_checkout_session_id: session.id,
      });
      console.log(`[Stripe] Granted ${plan.billing_model} entitlement to ${targetUserId}`);
    }

    // For backward compat, set is_premium true
    await supabase.from('profiles').update({ is_premium: true, updated_at: new Date().toISOString() }).eq('id', targetUserId);
  }
}

export async function POST(req: NextRequest) {
  if (!isStripeConfigured()) {
    return NextResponse.json({ received: true, note: 'Stripe simulated mode active - no production access granted' });
  }

  const payload = await req.text();
  const sig = req.headers.get('stripe-signature') || '';
  const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;

  if (!webhookSecret) {
    return NextResponse.json({ error: 'Stripe webhook signing secret is not configured.' }, { status: 503 });
  }

  try {
    const event = stripe.webhooks.constructEvent(payload, sig, webhookSecret);

    // Idempotency check
    const isDuplicate = await ensureIdempotent(event.id, event.type, event.data.object);
    if (isDuplicate) {
      return NextResponse.json({ received: true, duplicate: true });
    }

    console.log(`[Stripe Webhook] Processing ${event.type} ${event.id}`);

    switch (event.type) {
      case 'checkout.session.completed': {
        const session = event.data.object as any;
        await grantEntitlementFromCheckout(session, event.id);
        break;
      }

      case 'customer.subscription.created':
      case 'customer.subscription.updated': {
        const sub = event.data.object as any;
        if (!isSupabaseServerConfigured()) break;
        const supabase = getServiceSupabase();
        const customerId = typeof sub.customer === 'string' ? sub.customer : sub.customer?.id;
        const statusMap: Record<string, string> = {
          active: 'active',
          trialing: 'trialing',
          past_due: 'past_due',
          canceled: 'canceled',
          unpaid: 'past_due',
        };
        const newStatus = statusMap[sub.status] || sub.status;

        // Find entitlement by subscription id
        const { data: ent } = await supabase.from('user_entitlements').select('id, user_id').eq('stripe_subscription_id', sub.id).maybeSingle();
        if (ent) {
          await supabase
            .from('user_entitlements')
            .update({
              status: newStatus,
              expires_at: sub.current_period_end ? new Date(sub.current_period_end * 1000).toISOString() : null,
              updated_at: new Date().toISOString(),
            })
            .eq('id', ent.id);
          console.log(`[Stripe] Updated subscription ${sub.id} to ${newStatus}`);
        } else {
          // If no entitlement yet, try to find by customer and plan from metadata
          const planId = sub.metadata?.planId;
          if (planId && customerId) {
            const { data: profile } = await supabase.from('profiles').select('id').eq('stripe_customer_id', customerId).maybeSingle();
            if (profile) {
              await supabase.from('user_entitlements').insert({
                user_id: profile.id,
                plan_id: planId,
                status: newStatus,
                source: 'stripe',
                starts_at: new Date(sub.start_date * 1000).toISOString(),
                expires_at: sub.current_period_end ? new Date(sub.current_period_end * 1000).toISOString() : null,
                stripe_customer_id: customerId,
                stripe_subscription_id: sub.id,
              });
            }
          }
        }
        break;
      }

      case 'customer.subscription.deleted': {
        const sub = event.data.object as any;
        if (!isSupabaseServerConfigured()) break;
        const supabase = getServiceSupabase();
        await supabase
          .from('user_entitlements')
          .update({ status: 'canceled', expires_at: new Date().toISOString(), updated_at: new Date().toISOString() })
          .eq('stripe_subscription_id', sub.id);
        console.log(`[Stripe] Subscription deleted ${sub.id}`);
        break;
      }

      case 'invoice.paid': {
        const invoice = event.data.object as any;
        if (!isSupabaseServerConfigured()) break;
        const supabase = getServiceSupabase();
        const subId = typeof invoice.subscription === 'string' ? invoice.subscription : invoice.subscription?.id;
        if (subId) {
          await supabase
            .from('user_entitlements')
            .update({ status: 'active', updated_at: new Date().toISOString() })
            .eq('stripe_subscription_id', subId);
          console.log(`[Stripe] Invoice paid for sub ${subId}`);
        }
        break;
      }

      case 'invoice.payment_failed': {
        const invoice = event.data.object as any;
        if (!isSupabaseServerConfigured()) break;
        const supabase = getServiceSupabase();
        const subId = typeof invoice.subscription === 'string' ? invoice.subscription : invoice.subscription?.id;
        if (subId) {
          await supabase
            .from('user_entitlements')
            .update({ status: 'past_due', updated_at: new Date().toISOString() })
            .eq('stripe_subscription_id', subId);
          console.log(`[Stripe] Invoice payment failed for sub ${subId}`);
        }
        break;
      }

      case 'charge.refunded':
      case 'charge.refund.updated':
      case 'refund.created': {
        const charge = event.data.object as any;
        const paymentIntent = typeof charge.payment_intent === 'string' ? charge.payment_intent : charge.payment_intent?.id;
        console.log(`[Stripe Webhook] Refund received: ${event.id} pi:${paymentIntent}`);
        if (!isSupabaseServerConfigured()) break;
        const supabase = getServiceSupabase();
        // Try to find purchase by session and revoke
        // For per-recording refunds, we need to look up by payment_intent via checkout session
        // Simplified: if refund, mark entitlements as revoked for matching stripe_checkout_session_id
        // This requires mapping - for now log and mark any entitlements with matching customer as revoked if refund is full
        // In production, you'd store payment_intent in purchases and match
        // We'll handle via purchases metadata if possible
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
