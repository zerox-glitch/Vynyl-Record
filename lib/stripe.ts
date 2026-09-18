import Stripe from 'stripe';

const stripeSecretKey = process.env.STRIPE_SECRET_KEY || 'sk_test_placeholder_key';

export const isStripeConfigured = () => {
  const key = process.env.STRIPE_SECRET_KEY || '';
  return Boolean(key.startsWith('sk_') && !key.includes('your_') && !key.includes('placeholder'));
};

export const stripe = new Stripe(stripeSecretKey, {
  apiVersion: '2025-02-24.acacia' as any,
  typescript: true,
});

export interface CreateCheckoutInput {
  planId: string;
  planName: string;
  priceCents: number;
  currency?: string;
  successUrl: string;
  cancelUrl: string;
  customerEmail?: string;
  customerId?: string;
  userId?: string;
  recordingId?: string | null;
  billingModel: 'free' | 'per_recording' | 'monthly' | 'lifetime';
  stripePriceId?: string | null;
  stripeProductId?: string | null;
  isRecurring?: boolean;
}

export async function createCheckoutSession(input: CreateCheckoutInput) {
  const {
    planId,
    planName,
    priceCents,
    currency = 'usd',
    successUrl,
    cancelUrl,
    customerEmail,
    customerId,
    userId,
    recordingId,
    billingModel,
    stripePriceId,
    stripeProductId,
    isRecurring,
  } = input;

  if (!isStripeConfigured()) {
    const sessionId = `demo_session_${Date.now()}`;
    return {
      url: successUrl.replace('{CHECKOUT_SESSION_ID}', sessionId),
      sessionId,
      demo: true,
    };
  }

  // Prefer existing Stripe Price ID if available and valid
  let lineItem: Stripe.Checkout.SessionCreateParams.LineItem;
  if (stripePriceId && stripePriceId.startsWith('price_')) {
    lineItem = {
      price: stripePriceId,
      quantity: 1,
    };
  } else {
    const productData: Stripe.Checkout.SessionCreateParams.LineItem.PriceData.ProductData = {
      name: `Vinyl Voice Notes: ${planName}`,
      description:
        billingModel === 'per_recording'
          ? `Unlock premium features for one recording. One-time purchase.`
          : billingModel === 'monthly'
          ? `Monthly access to all premium features. Renews until canceled.`
          : billingModel === 'lifetime'
          ? `Lifetime account-wide premium access. One-time payment.`
          : `Vinyl Voice Notes plan`,
      images: ['https://images.unsplash.com/photo-1539185441755-769473a23570?auto=format&fit=crop&w=800&q=80'],
    };
    if (stripeProductId && stripeProductId.startsWith('prod_')) {
      // Use existing product ID
      (productData as any).id = undefined;
    }

    lineItem = {
      price_data: {
        currency,
        product_data: productData,
        unit_amount: priceCents,
        ...(isRecurring ? { recurring: { interval: 'month' as const } } : {}),
      },
      quantity: 1,
    };
  }

  const mode = isRecurring || billingModel === 'monthly' ? 'subscription' : 'payment';

  const metadata: Record<string, string> = {
    planId,
    billingModel,
    ...(userId ? { userId } : {}),
    ...(recordingId ? { recordingId } : {}),
  };

  const session = await stripe.checkout.sessions.create({
    payment_method_types: ['card'],
    line_items: [lineItem],
    mode: mode as any,
    customer: customerId || undefined,
    customer_email: !customerId ? customerEmail || undefined : undefined,
    success_url: successUrl,
    cancel_url: cancelUrl,
    metadata,
    ...(mode === 'subscription'
      ? {
          subscription_data: {
            metadata,
          },
        }
      : {}),
    ...(billingModel === 'per_recording' && recordingId
      ? {
          payment_intent_data: {
            metadata,
          },
        }
      : {}),
  });

  return {
    url: session.url,
    sessionId: session.id,
    demo: false,
  };
}

// Helper to create a new Stripe Price when admin changes amount
export async function createStripePriceForPlan({
  productId,
  amountCents,
  currency = 'usd',
  billingModel,
}: {
  productId: string;
  amountCents: number;
  currency?: string;
  billingModel: string;
}) {
  if (!isStripeConfigured()) {
    return { priceId: `price_demo_${Date.now()}`, productId, demo: true };
  }

  // If productId not exists, create product first
  let prodId = productId;
  if (!prodId || !prodId.startsWith('prod_')) {
    const product = await stripe.products.create({
      name: `Vinyl Voice Notes - ${billingModel}`,
    });
    prodId = product.id;
  }

  const price = await stripe.prices.create({
    product: prodId,
    unit_amount: amountCents,
    currency,
    ...(billingModel === 'monthly' ? { recurring: { interval: 'month' } } : {}),
  });

  return { priceId: price.id, productId: prodId, demo: false };
}

export async function archiveStripePrice(priceId: string) {
  if (!isStripeConfigured()) return;
  if (!priceId || !priceId.startsWith('price_')) return;
  try {
    await stripe.prices.update(priceId, { active: false });
  } catch (e) {
    console.warn('Failed to archive price', priceId, e);
  }
}
