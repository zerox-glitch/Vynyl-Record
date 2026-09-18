import { NextRequest, NextResponse } from 'next/server';
import { requireAdmin } from '@/lib/admin-auth';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;

  if (!isSupabaseServerConfigured()) {
    return NextResponse.json({ stats: {} });
  }

  const supabase = getServiceSupabase();

  try {
    const { data: userEnts } = await supabase.from('user_entitlements').select('plan_id, status, stripe_subscription_id');
    const { data: recEnts } = await supabase.from('recording_entitlements').select('plan_id');

    const stats: Record<string, { users: number; subs: number; purchases: number }> = {};

    (userEnts || []).forEach((e: any) => {
      if (!stats[e.plan_id]) stats[e.plan_id] = { users: 0, subs: 0, purchases: 0 };
      stats[e.plan_id].users += 1;
      if (e.stripe_subscription_id && e.status === 'active') stats[e.plan_id].subs += 1;
    });

    (recEnts || []).forEach((e: any) => {
      if (!stats[e.plan_id]) stats[e.plan_id] = { users: 0, subs: 0, purchases: 0 };
      stats[e.plan_id].purchases += 1;
    });

    return NextResponse.json({ stats });
  } catch (e: any) {
    return NextResponse.json({ error: e.message }, { status: 500 });
  }
}
