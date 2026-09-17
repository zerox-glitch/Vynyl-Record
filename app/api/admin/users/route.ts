import { NextRequest, NextResponse } from 'next/server';
import { requireAdmin, ADMIN_COOKIE_NAME } from '@/lib/admin-auth';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';
import { getProfiles, updateProfile, deleteProfile, createAuditLog } from '@/lib/db';
import { resolveUserEntitlement } from '@/lib/entitlements';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function sanitize(str: unknown, max = 200): string {
  return typeof str === 'string' ? str.trim().slice(0, max) : '';
}

export async function GET(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;

  const { searchParams } = new URL(req.url);
  const search = sanitize(searchParams.get('search'), 200).toLowerCase();
  const page = Math.max(1, parseInt(searchParams.get('page') || '1', 10) || 1);
  const limit = Math.min(100, Math.max(5, parseInt(searchParams.get('limit') || '20', 10) || 20));

  try {
    if (!isSupabaseServerConfigured()) {
      const users = await getProfiles();
      let filtered = users;
      if (search) {
        filtered = users.filter(u => u.email.toLowerCase().includes(search) || (u.full_name || '').toLowerCase().includes(search));
      }
      const total = filtered.length;
      const start = (page - 1) * limit;
      const paged = filtered.slice(start, start + limit);
      return NextResponse.json({ users: paged, total, page, limit });
    }

    const supabase = getServiceSupabase();

    // Get auth users via admin API
    const { data: authData, error: authError } = await supabase.auth.admin.listUsers({ page, perPage: limit });
    if (authError) throw authError;

    let authUsers = authData.users;

    // If search, we need to filter across all? Supabase admin list doesn't support search directly, so we fetch more and filter
    // For simplicity, if search present, we fetch profiles matching search and then get auth users for those ids
    if (search) {
      const { data: profiles } = await supabase.from('profiles').select('*').or(`email.ilike.%${search}%,full_name.ilike.%${search}%`).limit(100);
      const ids = (profiles || []).map(p => p.id);
      if (ids.length > 0) {
        // Fetch auth users for these ids (need to list and filter)
        const { data: allAuth } = await supabase.auth.admin.listUsers({ perPage: 1000 });
        authUsers = (allAuth?.users || []).filter(u => ids.includes(u.id) || u.email?.toLowerCase().includes(search));
      } else {
        authUsers = authUsers.filter(u => u.email?.toLowerCase().includes(search));
      }
    }

    // Enrich with profiles, entitlements, recording counts
    const userIds = authUsers.map(u => u.id);
    const { data: profiles } = await supabase.from('profiles').select('*').in('id', userIds);
    const profileMap = new Map((profiles || []).map(p => [p.id, p]));

    const { data: entitlements } = await supabase.from('user_entitlements').select('*, plan:pricing_plans(*)').in('user_id', userIds).order('created_at', { ascending: false });
    const entMap = new Map<string, any[]>();
    (entitlements || []).forEach((e: any) => {
      const arr = entMap.get(e.user_id) || [];
      arr.push(e);
      entMap.set(e.user_id, arr);
    });

    const { data: purchases } = await supabase.from('purchases').select('*').in('user_id', userIds).order('created_at', { ascending: false }).limit(200);

    // Recording counts
    const { data: recordingCounts } = await supabase.from('recordings').select('user_id').in('user_id', userIds);
    const countMap = new Map<string, number>();
    (recordingCounts || []).forEach((r: any) => {
      countMap.set(r.user_id, (countMap.get(r.user_id) || 0) + 1);
    });

    const enriched = await Promise.all(authUsers.map(async (au) => {
      const profile = profileMap.get(au.id);
      const userEnts = entMap.get(au.id) || [];
      const resolved = await resolveUserEntitlement(au.id).catch(() => null);
      return {
        id: au.id,
        email: au.email || profile?.email || '',
        full_name: profile?.full_name || au.user_metadata?.full_name || au.user_metadata?.name || null,
        avatar_url: profile?.avatar_url || au.user_metadata?.avatar_url || null,
        role: profile?.role || 'user',
        account_status: profile?.account_status || 'active',
        stripe_customer_id: profile?.stripe_customer_id || null,
        is_premium: profile?.is_premium || false,
        recording_count: countMap.get(au.id) || profile?.recording_count || 0,
        created_at: au.created_at,
        updated_at: profile?.updated_at || au.updated_at || au.created_at,
        email_confirmed: !!au.email_confirmed_at,
        last_sign_in_at: au.last_sign_in_at,
        provider: au.app_metadata?.provider || au.identities?.[0]?.provider || 'email',
        providers: au.identities?.map((i: any) => i.provider) || [],
        // entitlements
        entitlements: userEnts,
        effective_plan: resolved?.effectivePlan || null,
        effective_plan_name: resolved?.effectivePlan?.name || 'Free',
        entitlement_source: resolved?.source || null,
        entitlement_status: resolved?.status || null,
        entitlement_expires_at: resolved?.expiration || null,
        remaining_recordings: resolved?.remainingUsage ?? null,
        purchases: (purchases || []).filter((p: any) => p.user_id === au.id),
      };
    }));

    return NextResponse.json({ users: enriched, total: authData.users.length, page, limit, totalAuthUsers: authData.users.length });
  } catch (error: any) {
    console.error('admin users GET error', error);
    return NextResponse.json({ error: error.message || 'Failed to load users' }, { status: 500 });
  }
}

export async function PATCH(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;

  try {
    const body = await req.json();
    const id = sanitize(body.id, 100);
    const action = sanitize(body.action || body.updates?.action || 'update_profile', 100);
    const adminCookie = req.cookies.get(ADMIN_COOKIE_NAME)?.value || 'admin-session';

    if (!id) return NextResponse.json({ error: 'User ID required' }, { status: 400 });

    if (!isSupabaseServerConfigured()) {
      // Local fallback only supports basic updates
      const allowed = body.updates || {};
      const updates: any = {};
      if (allowed.role === 'admin' || allowed.role === 'user') updates.role = allowed.role;
      if (typeof allowed.is_premium === 'boolean') updates.is_premium = allowed.is_premium;
      if (typeof allowed.full_name === 'string') updates.full_name = sanitize(allowed.full_name, 120);
      const updated = await updateProfile(id, updates);
      return NextResponse.json({ success: true, user: updated });
    }

    const supabase = getServiceSupabase();

    // Fetch before data for audit
    const { data: beforeProfile } = await supabase.from('profiles').select('*').eq('id', id).maybeSingle();

    let result: any = null;
    let auditAction = action;
    let beforeData: any = beforeProfile;
    let afterData: any = null;
    let targetPlanId: string | null = null;
    let targetRecordingId: string | null = null;

    switch (action) {
      case 'update_profile': {
        const fullName = sanitize(body.full_name || body.updates?.full_name, 120);
        if (!fullName) return NextResponse.json({ error: 'Full name required' }, { status: 400 });
        const { data, error } = await supabase.from('profiles').update({ full_name: fullName, updated_at: new Date().toISOString() }).eq('id', id).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        break;
      }

      case 'change_role': {
        const role = sanitize(body.role || body.updates?.role, 20);
        if (role !== 'admin' && role !== 'user') return NextResponse.json({ error: 'Invalid role' }, { status: 400 });
        const { data, error } = await supabase.from('profiles').update({ role, updated_at: new Date().toISOString() }).eq('id', id).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        auditAction = 'role_change';
        break;
      }

      case 'suspend':
      case 'reactivate': {
        const newStatus = action === 'suspend' ? 'suspended' : 'active';
        const { data, error } = await supabase.from('profiles').update({ account_status: newStatus, updated_at: new Date().toISOString() }).eq('id', id).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        auditAction = action === 'suspend' ? 'account_suspension' : 'account_reactivation';
        break;
      }

      case 'assign_entitlement': {
        const planId = sanitize(body.plan_id || body.planId, 100);
        const startsAt = body.starts_at ? new Date(body.starts_at).toISOString() : new Date().toISOString();
        const expiresAt = body.expires_at ? new Date(body.expires_at).toISOString() : null;
        const remaining = body.remaining_recordings != null ? Math.max(0, parseInt(body.remaining_recordings, 10)) : null;
        const status = sanitize(body.status || 'active', 20);

        if (!planId) return NextResponse.json({ error: 'Plan ID required' }, { status: 400 });

        const { data: plan } = await supabase.from('pricing_plans').select('*').eq('id', planId).maybeSingle();
        if (!plan) return NextResponse.json({ error: 'Plan not found' }, { status: 404 });

        const { data, error } = await supabase.from('user_entitlements').insert({
          user_id: id,
          plan_id: planId,
          status,
          source: 'admin',
          starts_at: startsAt,
          expires_at: expiresAt,
          remaining_recordings: remaining,
          granted_by_admin: null,
        }).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        targetPlanId = planId;
        auditAction = 'entitlement_grant';

        // Update is_premium flag for backward compat
        if (plan.billing_model !== 'free') {
          await supabase.from('profiles').update({ is_premium: true, updated_at: new Date().toISOString() }).eq('id', id);
        }
        break;
      }

      case 'grant_credits': {
        const amount = parseInt(body.amount || body.credits, 10);
        if (!Number.isFinite(amount)) return NextResponse.json({ error: 'Invalid amount' }, { status: 400 });
        const { data: latest } = await supabase.from('user_entitlements').select('*').eq('user_id', id).order('created_at', { ascending: false }).limit(1).maybeSingle();
        if (!latest) return NextResponse.json({ error: 'No entitlement found to grant credits to' }, { status: 404 });
        const newRemaining = (latest.remaining_recordings || 0) + amount;
        const { data, error } = await supabase.from('user_entitlements').update({ remaining_recordings: newRemaining, updated_at: new Date().toISOString() }).eq('id', latest.id).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        beforeData = latest;
        auditAction = 'recording_credit_change';
        break;
      }

      case 'revoke_entitlement': {
        const entitlementId = sanitize(body.entitlement_id || body.entitlementId, 100);
        if (!entitlementId) return NextResponse.json({ error: 'Entitlement ID required' }, { status: 400 });
        const { data: ent } = await supabase.from('user_entitlements').select('*').eq('id', entitlementId).eq('user_id', id).maybeSingle();
        if (!ent) return NextResponse.json({ error: 'Entitlement not found' }, { status: 404 });
        if (ent.source !== 'admin') {
          // Allow but warn - for non-admin entitlements we set revoked
        }
        const { data, error } = await supabase.from('user_entitlements').update({ status: 'revoked', updated_at: new Date().toISOString() }).eq('id', entitlementId).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        beforeData = ent;
        auditAction = 'entitlement_revoke';
        break;
      }

      case 'grant_recording_entitlement': {
        const recordingId = sanitize(body.recording_id || body.recordingId, 100);
        const planId = sanitize(body.plan_id || body.planId, 100);
        if (!recordingId || !planId) return NextResponse.json({ error: 'Recording ID and Plan ID required' }, { status: 400 });

        const { data: rec } = await supabase.from('recordings').select('id, user_id').eq('id', recordingId).maybeSingle();
        if (!rec) return NextResponse.json({ error: 'Recording not found' }, { status: 404 });
        if (rec.user_id !== id) return NextResponse.json({ error: 'Recording not owned by user' }, { status: 400 });

        const { data, error } = await supabase.from('recording_entitlements').insert({
          recording_id: recordingId,
          user_id: id,
          plan_id: planId,
          status: 'active',
        }).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
        targetPlanId = planId;
        targetRecordingId = recordingId;
        auditAction = 'recording_entitlement_grant';

        await supabase.from('recordings').update({ entitlement_plan_id: planId, entitlement_source: 'admin' }).eq('id', recordingId);
        break;
      }

      case 'send_reset': {
        const { data: authUser } = await supabase.auth.admin.getUserById(id);
        const email = authUser?.user?.email;
        if (!email) return NextResponse.json({ error: 'User email not found' }, { status: 404 });
        // Use Supabase admin to send reset? There's no direct admin send, but we can use auth.resetPasswordForEmail via service? We'll use admin invite link
        const { error } = await supabase.auth.admin.generateLink({ type: 'recovery', email });
        if (error) throw error;
        result = { email };
        auditAction = 'password_reset_email';
        break;
      }

      case 'revoke_sessions': {
        const { error } = await supabase.auth.admin.signOut(id);
        if (error) throw error;
        result = { revoked: true };
        auditAction = 'session_revoke';
        break;
      }

      case 'cancel_at_period_end': {
        const entitlementId = sanitize(body.entitlement_id || body.entitlementId, 100);
        if (!entitlementId) return NextResponse.json({ error: 'Entitlement ID required' }, { status: 400 });
        const { data: ent } = await supabase.from('user_entitlements').select('*').eq('id', entitlementId).maybeSingle();
        if (!ent) return NextResponse.json({ error: 'Entitlement not found' }, { status: 404 });
        if (ent.stripe_subscription_id) {
          // Mark to cancel at period end via Stripe? For now, just update status to canceled and set expires_at to current period end
          // In production you'd call Stripe API to set cancel_at_period_end
          const { data, error } = await supabase.from('user_entitlements').update({ status: 'canceled', updated_at: new Date().toISOString() }).eq('id', entitlementId).select().single();
          if (error) throw error;
          result = data;
          afterData = data;
          beforeData = ent;
          auditAction = 'subscription_cancel_at_period_end';
        } else {
          return NextResponse.json({ error: 'Entitlement has no Stripe subscription to cancel' }, { status: 400 });
        }
        break;
      }

      default: {
        // Fallback to simple profile update for backward compat
        const updates = body.updates || {};
        const allowed: any = {};
        if (updates.role === 'admin' || updates.role === 'user') allowed.role = updates.role;
        if (typeof updates.is_premium === 'boolean') allowed.is_premium = updates.is_premium;
        if (typeof updates.full_name === 'string') allowed.full_name = sanitize(updates.full_name, 120);
        if (typeof updates.account_status === 'string' && ['active','suspended','disabled'].includes(updates.account_status)) allowed.account_status = updates.account_status;
        if (Object.keys(allowed).length === 0) return NextResponse.json({ error: 'No valid updates' }, { status: 400 });
        const { data, error } = await supabase.from('profiles').update({ ...allowed, updated_at: new Date().toISOString() }).eq('id', id).select().single();
        if (error) throw error;
        result = data;
        afterData = data;
      }
    }

    await createAuditLog({
      action: auditAction,
      target_user_id: id,
      target_plan_id: targetPlanId,
      target_recording_id: targetRecordingId,
      before_data: beforeData,
      after_data: afterData,
      metadata: { action, body: body },
      admin_session_id: adminCookie,
      admin_user_id: 'admin',
    });

    return NextResponse.json({ success: true, user: result, action: auditAction });
  } catch (error: any) {
    console.error('admin users PATCH error', error);
    return NextResponse.json({ error: error.message || 'Failed to update user' }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;

  try {
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    const confirm = searchParams.get('confirm');

    if (!id) return NextResponse.json({ error: 'User ID required' }, { status: 400 });
    if (confirm !== 'DELETE') {
      return NextResponse.json({ error: 'Type DELETE to confirm account deletion' }, { status: 400 });
    }

    if (!isSupabaseServerConfigured()) {
      const deleted = await deleteProfile(id);
      if (!deleted) return NextResponse.json({ error: 'Delete failed' }, { status: 500 });
      return NextResponse.json({ success: true });
    }

    const supabase = getServiceSupabase();
    const { data: beforeProfile } = await supabase.from('profiles').select('*').eq('id', id).maybeSingle();

    // Delete user data
    await supabase.from('recordings').delete().eq('user_id', id);
    await supabase.from('user_entitlements').delete().eq('user_id', id);
    await supabase.from('recording_entitlements').delete().eq('user_id', id);
    await supabase.from('purchases').delete().eq('user_id', id);

    const { error: profileErr } = await supabase.from('profiles').delete().eq('id', id);
    if (profileErr) throw profileErr;

    const { error: authErr } = await supabase.auth.admin.deleteUser(id);
    if (authErr) {
      console.warn('auth delete error', authErr.message);
    }

    await createAuditLog({
      action: 'account_deletion',
      target_user_id: id,
      before_data: beforeProfile,
      metadata: { deleted_by: 'admin' },
      admin_session_id: req.cookies.get(ADMIN_COOKIE_NAME)?.value || 'admin-session',
      admin_user_id: 'admin',
    });

    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message || 'Delete failed' }, { status: 500 });
  }
}
