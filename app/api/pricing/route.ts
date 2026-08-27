import { NextRequest, NextResponse } from 'next/server';
import { getAllPricingPlans, getPricingPlans, upsertPricingPlan } from '@/lib/db';
import { PricingPlan } from '@/types';
import { isAdminRequest, requireAdmin } from '@/lib/admin-auth';

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
    const saved = await upsertPricingPlan(plan);
    return NextResponse.json({ success: true, plan: saved });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
