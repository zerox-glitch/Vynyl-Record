import { NextRequest, NextResponse } from 'next/server';
import { getAllPricingPlans, upsertPricingPlan } from '@/lib/db';
import { PricingPlan } from '@/types';

export async function GET() {
  try {
    const plans = await getAllPricingPlans();
    return NextResponse.json({ plans });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
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
