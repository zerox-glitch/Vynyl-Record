import { NextRequest, NextResponse } from 'next/server';
import { getCustomerUser } from '@/lib/supabase/auth';
import { resolveUserEntitlement } from '@/lib/entitlements';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  try {
    const user = await getCustomerUser();
    const recordingId = req.nextUrl.searchParams.get('recording_id');
    const resolved = await resolveUserEntitlement(user?.id || 'anonymous', recordingId);
    return NextResponse.json({ entitlement: resolved, userId: user?.id || null });
  } catch (e: any) {
    return NextResponse.json({ error: e.message }, { status: 500 });
  }
}
