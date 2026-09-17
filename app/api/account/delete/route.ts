import { NextRequest, NextResponse } from 'next/server';
import { getCustomerServerClient } from '@/lib/supabase/auth';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  const client = getCustomerServerClient();
  if (!client) return NextResponse.json({ error: 'Auth not configured' }, { status: 503 });
  const { data: { user } } = await client.auth.getUser();
  if (!user) return NextResponse.json({ error: 'Not authenticated' }, { status: 401 });

  const body = await req.json().catch(() => ({}));
  if (body.confirm !== 'DELETE') {
    return NextResponse.json({ error: 'Type DELETE to confirm' }, { status: 400 });
  }

  if (!isSupabaseServerConfigured()) {
    return NextResponse.json({ error: 'Supabase not configured' }, { status: 503 });
  }

  try {
    const supabase = getServiceSupabase();
    // Log audit
    await supabase.from('admin_audit_logs').insert({
      action: 'account_deletion_request',
      target_user_id: user.id,
      metadata: { requested_by: 'self', email: user.email },
      admin_user_id: user.id,
    });

    // Delete recordings (service role)
    await supabase.from('recordings').delete().eq('user_id', user.id);

    // Delete entitlements (cascade should handle but explicit)
    await supabase.from('user_entitlements').delete().eq('user_id', user.id);
    await supabase.from('recording_entitlements').delete().eq('user_id', user.id);

    // Delete profile
    await supabase.from('profiles').delete().eq('id', user.id);

    // Delete auth user via admin
    const { error: authErr } = await supabase.auth.admin.deleteUser(user.id);
    if (authErr) {
      console.warn('auth delete failed', authErr.message);
      // Still sign out
    }

    await client.auth.signOut();
    return NextResponse.json({ success: true });
  } catch (e: any) {
    return NextResponse.json({ error: e.message || 'Delete failed' }, { status: 500 });
  }
}
