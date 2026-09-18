import { getServiceSupabase, isSupabaseServerConfigured } from '@/lib/supabase/server';
import { PricingPlan, ResolvedEntitlement, UserEntitlement, RecordingEntitlement, BillingModel } from '@/types';
import { DEFAULT_PRICING_PLANS } from '@/lib/constants';

function defaultPlans(): PricingPlan[] {
  // Map old defaults to new shape with required fields
  return DEFAULT_PRICING_PLANS.map((p, i) => ({
    id: p.id,
    slug: (p as any).slug || (i === 0 ? 'free' : i === 1 ? 'premium-record' : i === 2 ? 'collector-monthly' : 'lifetime-archive'),
    name: p.name,
    description: (p as any).description || null,
    billing_model: (p as any).billing_model || (p.price_cents === 0 ? 'free' : p.price_cents >= 2900 ? 'lifetime' : 'per_recording') as BillingModel,
    price_cents: p.price_cents,
    currency: (p as any).currency || 'usd',
    billing_interval: (p as any).billing_interval || (p.price_cents === 0 ? null : p.price_cents < 2000 ? 'month' : 'one_time'),
    stripe_product_id: (p as any).stripe_product_id || null,
    stripe_price_id: p.stripe_price_id,
    max_duration_seconds: p.max_duration_seconds,
    included_recordings: (p as any).included_recordings ?? (p.price_cents === 0 ? 3 : p.price_cents < 2000 ? 1 : 20),
    allowed_filter_presets: p.allowed_filter_presets,
    allowed_vinyl_presets: (p as any).allowed_vinyl_presets || ['all'],
    allowed_bg_music_ids: p.allowed_bg_music_ids,
    allowed_vinyl_styles: p.allowed_vinyl_styles,
    can_adjust_crackle: p.can_adjust_crackle,
    can_download: (p as any).can_download ?? true,
    can_use_private_visibility: (p as any).can_use_private_visibility ?? p.price_cents > 0,
    can_use_advanced_mixer: (p as any).can_use_advanced_mixer ?? p.price_cents > 0,
    is_active: p.is_active,
    display_order: (p as any).display_order ?? i + 1,
    created_at: p.created_at,
    updated_at: (p as any).updated_at || new Date().toISOString(),
  }));
}

async function getAllPlansServer(): Promise<PricingPlan[]> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('pricing_plans').select('*').eq('is_active', true).order('display_order', { ascending: true });
      if (data && data.length > 0) return data as PricingPlan[];
    } catch {}
  }
  return defaultPlans().filter(p => p.is_active);
}

async function getPlanById(id: string): Promise<PricingPlan | null> {
  if (isSupabaseServerConfigured()) {
    try {
      const supabase = getServiceSupabase();
      const { data } = await supabase.from('pricing_plans').select('*').eq('id', id).maybeSingle();
      if (data) return data as PricingPlan;
    } catch {}
  }
  return defaultPlans().find(p => p.id === id) || null;
}

function buildEnabledFeatures(plan: PricingPlan | null) {
  if (!plan) {
    return {
      allowedFilterPresets: ['clean', 'gramophone'] as any,
      allowedVinylPresets: [] as string[],
      allowedBgMusicIds: ['none'] as string[],
      allowedVinylStyles: ['classic_red'] as any,
      canAdjustCrackle: false,
      canDownload: false,
      canUsePrivateVisibility: false,
      canUseAdvancedMixer: false,
    };
  }
  return {
    allowedFilterPresets: plan.allowed_filter_presets,
    allowedVinylPresets: plan.allowed_vinyl_presets || [],
    allowedBgMusicIds: plan.allowed_bg_music_ids,
    allowedVinylStyles: plan.allowed_vinyl_styles,
    canAdjustCrackle: plan.can_adjust_crackle,
    canDownload: plan.can_download ?? false,
    canUsePrivateVisibility: plan.can_use_private_visibility ?? false,
    canUseAdvancedMixer: plan.can_use_advanced_mixer ?? false,
  };
}

export async function resolveUserEntitlement(userId: string, recordingId?: string | null): Promise<ResolvedEntitlement> {
  const plans = await getAllPlansServer();
  const freePlan = plans.find(p => p.billing_model === 'free') || plans.find(p => p.price_cents === 0) || defaultPlans()[0];

  // No user -> free tier only
  if (!userId || userId === 'anonymous') {
    return {
      effectivePlan: freePlan,
      billingModel: freePlan?.billing_model || 'free',
      enabledFeatures: buildEnabledFeatures(freePlan),
      durationLimit: freePlan?.max_duration_seconds || 60,
      remainingUsage: freePlan?.included_recordings ?? null,
      source: 'free',
      expiration: null,
      status: 'active',
      reason: 'anonymous free tier',
      isPremium: false,
      userEntitlement: null,
      recordingEntitlement: null,
    };
  }

  if (!isSupabaseServerConfigured()) {
    // Local fallback: treat as free unless is_premium flag (backward compat) would have been set
    // For dev, we grant free
    return {
      effectivePlan: freePlan,
      billingModel: freePlan?.billing_model || 'free',
      enabledFeatures: buildEnabledFeatures(freePlan),
      durationLimit: freePlan?.max_duration_seconds || 60,
      remainingUsage: freePlan?.included_recordings ?? null,
      source: 'free',
      expiration: null,
      status: 'active',
      reason: 'local fallback free',
      isPremium: false,
      userEntitlement: null,
      recordingEntitlement: null,
    };
  }

  const supabase = getServiceSupabase();

  // Check per-recording entitlement first if recordingId provided
  let recordingEnt: RecordingEntitlement | null = null;
  if (recordingId) {
    try {
      const { data } = await supabase
        .from('recording_entitlements')
        .select('*, plan:pricing_plans(*)')
        .eq('recording_id', recordingId)
        .eq('user_id', userId)
        .eq('status', 'active')
        .maybeSingle();
      if (data) {
        recordingEnt = data as any;
        const plan = (data as any).plan as PricingPlan || await getPlanById(data.plan_id);
        if (plan) {
          return {
            effectivePlan: plan,
            billingModel: plan.billing_model,
            enabledFeatures: buildEnabledFeatures(plan),
            durationLimit: plan.max_duration_seconds,
            remainingUsage: null,
            source: 'per_recording' as any,
            expiration: null,
            status: 'active',
            reason: 'per-recording purchase',
            isPremium: true,
            userEntitlement: null,
            recordingEntitlement: recordingEnt,
          };
        }
      }
    } catch {}
  }

  // Check user entitlements: prioritize active lifetime, then active monthly, then trialing, then free
  try {
    const { data: ents } = await supabase
      .from('user_entitlements')
      .select('*, plan:pricing_plans(*)')
      .eq('user_id', userId)
      .in('status', ['active', 'trialing', 'past_due'])
      .order('created_at', { ascending: false });

    if (ents && ents.length > 0) {
      // Sort by precedence: lifetime > monthly > per_recording (should not be here) > free
      const sorted = [...ents].sort((a: any, b: any) => {
        const order: Record<string, number> = { lifetime: 0, monthly: 1, per_recording: 2, free: 3 };
        const aModel = a.plan?.billing_model || 'free';
        const bModel = b.plan?.billing_model || 'free';
        const aOrder = order[aModel] ?? 99;
        const bOrder = order[bModel] ?? 99;
        if (aOrder !== bOrder) return aOrder - bOrder;
        // active before trialing before past_due
        const statusOrder: Record<string, number> = { active: 0, trialing: 1, past_due: 2 };
        return (statusOrder[a.status] ?? 99) - (statusOrder[b.status] ?? 99);
      });

      for (const ent of sorted) {
        const plan = (ent as any).plan as PricingPlan || await getPlanById(ent.plan_id);
        if (!plan) continue;

        // Check expiration
        if (ent.expires_at && new Date(ent.expires_at) < new Date()) {
          // expired, skip unless it's lifetime (should have no expiry)
          if (plan.billing_model !== 'lifetime') continue;
        }

        // If monthly and past_due, still allow but mark reason
        const isPremium = plan.billing_model !== 'free';

        // For monthly with remaining_recordings limit
        let remaining = ent.remaining_recordings;
        if (plan.billing_model === 'monthly' && remaining !== null && remaining !== undefined && remaining <= 0) {
          // If out of credits, still premium features but no remaining recordings?
          // We allow but reason notes limit
        }

        return {
          effectivePlan: plan,
          billingModel: plan.billing_model,
          enabledFeatures: buildEnabledFeatures(plan),
          durationLimit: plan.max_duration_seconds,
          remainingUsage: remaining ?? plan.included_recordings ?? null,
          source: ent.source,
          expiration: ent.expires_at,
          status: ent.status,
          reason: ent.status === 'past_due' ? 'past_due but still active' : `${ent.source} entitlement`,
          isPremium,
          userEntitlement: ent as UserEntitlement,
          recordingEntitlement: null,
        };
      }
    }
  } catch (e) {
    console.warn('[entitlements] user lookup failed', e);
  }

  // Fallback to free
  return {
    effectivePlan: freePlan,
    billingModel: freePlan?.billing_model || 'free',
    enabledFeatures: buildEnabledFeatures(freePlan),
    durationLimit: freePlan?.max_duration_seconds || 60,
    remainingUsage: freePlan?.included_recordings ?? null,
    source: 'free',
    expiration: null,
    status: 'active',
    reason: 'free tier fallback',
    isPremium: false,
    userEntitlement: null,
    recordingEntitlement: null,
  };
}

export async function getUserEntitlements(userId: string): Promise<UserEntitlement[]> {
  if (!isSupabaseServerConfigured()) return [];
  const supabase = getServiceSupabase();
  const { data } = await supabase.from('user_entitlements').select('*, plan:pricing_plans(*)').eq('user_id', userId).order('created_at', { ascending: false });
  return (data as any) || [];
}

export async function getRecordingEntitlements(userId: string): Promise<RecordingEntitlement[]> {
  if (!isSupabaseServerConfigured()) return [];
  const supabase = getServiceSupabase();
  const { data } = await supabase.from('recording_entitlements').select('*, plan:pricing_plans(*)').eq('user_id', userId).order('created_at', { ascending: false });
  return (data as any) || [];
}
