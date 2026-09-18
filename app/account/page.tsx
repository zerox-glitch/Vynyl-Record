import { redirect } from 'next/navigation';
import { getCustomerServerClient, getCustomerUserWithDetails } from '@/lib/supabase/auth';
import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';
import { resolveUserEntitlement, getUserEntitlements, getRecordingEntitlements } from '@/lib/entitlements';
import AccountClient from './AccountClient';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

export default async function AccountPage() {
  const user = await getCustomerUserWithDetails();
  if (!user) {
    redirect('/login?next=/account&expired=1');
  }

  let profile: any = null;
  let entitlements: any[] = [];
  let recordingEnts: any[] = [];
  let resolved: any = null;
  let recordingCount = 0;

  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data: prof } = await supabase.from('profiles').select('*').eq('id', user.id).maybeSingle();
      profile = prof;
      entitlements = await getUserEntitlements(user.id);
      recordingEnts = await getRecordingEntitlements(user.id);
      resolved = await resolveUserEntitlement(user.id);
      const { count } = await supabase.from('recordings').select('id', { count: 'exact', head: true }).eq('user_id', user.id);
      recordingCount = count || 0;
    } catch (e) {
      console.warn('account data fetch failed', e);
    }
  } else {
    resolved = await resolveUserEntitlement(user.id);
  }

  const provider = user.app_metadata?.provider || (user.identities?.[0]?.provider as string) || 'email';
  const providers = user.identities?.map((i: any) => i.provider) || [provider];
  const emailVerified = !!user.email_confirmed_at;

  return (
    <AccountClient
      user={{
        id: user.id,
        email: user.email || '',
        emailVerified,
        provider,
        providers,
        lastSignIn: user.last_sign_in_at || null,
        createdAt: user.created_at,
      }}
      profile={profile}
      entitlements={entitlements}
      recordingEntitlements={recordingEnts}
      resolved={resolved}
      recordingCount={recordingCount}
    />
  );
}
