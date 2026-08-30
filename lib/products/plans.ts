import { PricingPlan } from '@/types';
import { DEFAULT_PRICING_PLANS } from '@/lib/constants';

/**
 * Central product configuration. UI, checkout, and entitlement checks should
 * read this shape instead of scattering dollar values around components.
 * Existing database-backed plan records remain authoritative once Supabase
 * is configured; this is the safe local/development source.
 */
export type ProductTier = 'free' | 'premium' | 'heirloom' | 'physical_future';

export interface ProductDefinition {
  tier: ProductTier;
  displayName: string;
  description: string;
  priceCents: number | null;
  maxDurationSeconds: number | null;
  features: string[];
  availableNow: boolean;
  sourcePlanId?: string;
}

export const PRODUCT_DEFINITIONS: ProductDefinition[] = [
  {
    tier: 'free', displayName: 'Vintage Free', priceCents: 0,
    maxDurationSeconds: 60, availableNow: true, sourcePlanId: DEFAULT_PRICING_PLANS[0].id,
    description: 'A beautiful first record for the words you want to keep.',
    features: ['One minute of recording', 'Classic red wax', 'Shareable record link'],
  },
  {
    tier: 'premium', displayName: 'Gold Master Vinyl', priceCents: 900,
    maxDurationSeconds: 180, availableNow: true, sourcePlanId: DEFAULT_PRICING_PLANS[1].id,
    description: 'More room for the stories that take their time.',
    features: ['Three minutes of recording', 'Every mood + vinyl style', 'MP3 download', 'Custom dedication'],
  },
  {
    tier: 'heirloom', displayName: 'Heirloom Lifetime', priceCents: 2900,
    maxDurationSeconds: 600, availableNow: true, sourcePlanId: DEFAULT_PRICING_PLANS[2].id,
    description: 'A room for every voice and every chapter.',
    features: ['Ten minutes per record', 'Private storage', 'Advanced customization', 'Permanent keepsake experience'],
  },
  {
    tier: 'physical_future', displayName: 'Physical Vinyl', priceCents: null,
    maxDurationSeconds: null, availableNow: false,
    description: 'A real record, when we are ready to press it.',
    features: ['Production artwork', 'Manufacturing partner', 'Shipping and fulfillment'],
  },
];

export function productForPlan(planId: string, plans: PricingPlan[] = DEFAULT_PRICING_PLANS): ProductDefinition | null {
  const index = plans.findIndex((plan) => plan.id === planId);
  return index >= 0 ? PRODUCT_DEFINITIONS[index] || null : null;
}
