import { NextRequest, NextResponse } from 'next/server';
import { getAllPricingPlans, getPricingPlans, upsertPricingPlan, createAuditLog } from '@/lib/db';
import { PricingPlan } from '@/types';
import { isAdminRequest, requireAdmin, ADMIN_COOKIE_NAME } from '@/lib/admin-auth';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';
import { createStripePriceForPlan, archiveStripePrice, isStripeConfigured } from '@/lib/stripe';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  try {
    const plans = (await isAdminRequest(req)) ? await getAllPricingPlans() : await getPricingPlans();
    return NextResponse.json({ plans });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const plan: PricingPlan = await req.json();
    if (!plan.name || plan.price_cents === undefined) {
      return NextResponse.json({ error: 'Invalid plan data' }, { status: 400 });
    }

    // If price changed, handle Stripe price creation
    let beforePlan: PricingPlan | null = null;
    let stripeSyncStatus = 'no_change';

    if (isSupabaseServerConfigured()) {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('pricing_plans').select('*').eq('id', plan.id).maybeSingle();
      beforePlan = data as any;
    }

    let finalPlan = { ...plan };

    if (beforePlan && beforePlan.price_cents !== plan.price_cents) {
      // Price changed - create new Stripe Price
      if (isStripeConfigured()) {
        try {
          const productId = plan.stripe_product_id || beforePlan.stripe_product_id || '';
          const { priceId, productId: newProductId } = await createStripePriceForPlan({
            productId,
            amountCents: plan.price_cents,
            currency: plan.currency || 'usd',
            billingModel: plan.billing_model,
          });
          // Archive old price
          if (beforePlan.stripe_price_id) {
            await archiveStripePrice(beforePlan.stripe_price_id);
          }
          finalPlan.stripe_price_id = priceId;
          finalPlan.stripe_product_id = newProductId;
          stripeSyncStatus = 'created_new_price';
        } catch (e: any) {
          console.warn('Stripe price creation failed', e);
          stripeSyncStatus = `failed: ${e.message}`;
          // Still allow saving plan but without stripe sync
        }
      } else {
        stripeSyncStatus = 'demo_mode_no_stripe';
      }
    } else if (!plan.stripe_product_id && plan.price_cents > 0 && isStripeConfigured()) {
      // No product yet, create one
      try {
        const { priceId, productId } = await createStripePriceForPlan({
          productId: '',
          amountCents: plan.price_cents,
          currency: plan.currency || 'usd',
          billingModel: plan.billing_model,
        });
        finalPlan.stripe_product_id = productId;
        finalPlan.stripe_price_id = priceId;
        stripeSyncStatus = 'created_product_and_price';
      } catch (e: any) {
        stripeSyncStatus = `failed: ${e.message}`;
      }
    }

    const saved = await upsertPricingPlan(finalPlan);

    await createAuditLog({
      action: beforePlan ? 'plan_update' : 'plan_create',
      target_plan_id: saved.id,
      before_data: beforePlan,
      after_data: saved,
      metadata: { stripeSyncStatus, priceChanged: beforePlan ? beforePlan.price_cents !== plan.price_cents : false },
      admin_session_id: req.cookies.get(ADMIN_COOKIE_NAME)?.value || 'admin-session',
      admin_user_id: 'admin',
    });

    return NextResponse.json({ success: true, plan: saved, stripeSyncStatus });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    if (!id) return NextResponse.json({ error: 'ID required' }, { status: 400 });

    if (isSupabaseServerConfigured()) {
      const supabase = getServiceSupabase();
      const { data: userEnts } = await supabase.from('user_entitlements').select('id').eq('plan_id', id).limit(1);
      if (userEnts && userEnts.length > 0) {
        return NextResponse.json({ error: 'Cannot delete plan referenced by entitlements. Archive it instead.' }, { status: 400 });
      }
      const { data: recEnts } = await supabase.from('recording_entitlements').select('id').eq('plan_id', id).limit(1);
      if (recEnts && recEnts.length > 0) {
        return NextResponse.json({ error: 'Cannot delete plan referenced by purchases. Archive it instead.' }, { status: 400 });
      }
    }

    const { deletePricingPlan } = await import('@/lib/db');
    await deletePricingPlan(id);

    await createAuditLog({
      action: 'plan_delete',
      target_plan_id: id,
      metadata: { deleted: true },
      admin_session_id: req.cookies.get(ADMIN_COOKIE_NAME)?.value || 'admin-session',
      admin_user_id: 'admin',
    });

    return NextResponse.json({ success: true });
  } catch (e: any) {
    return NextResponse.json({ error: e.message }, { status: 500 });
  }
}
