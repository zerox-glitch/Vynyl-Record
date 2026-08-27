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

export async function createCheckoutSession({
  planId,
  planName,
  priceCents,
  successUrl,
  cancelUrl,
  customerEmail,
  isRecurring,
}: {
  planId: string;
  planName: string;
  priceCents: number;
  successUrl: string;
  cancelUrl: string;
  customerEmail?: string;
  isRecurring?: boolean;
}) {
  if (!isStripeConfigured()) {
    // Return simulated checkout URL for development/demo test mode
    const sessionId = `demo_session_${Date.now()}`;
    return {
      url: successUrl.replace('{CHECKOUT_SESSION_ID}', sessionId),
      sessionId,
    };
  }

  const session = await stripe.checkout.sessions.create({
    payment_method_types: ['card'],
    line_items: [
      {
        price_data: {
          currency: 'usd',
          product_data: {
            name: `Vinyl Voice Notes: ${planName}`,
            description: `Unlock extended recording duration, all vintage wax styles, and analog audio mastering filters.`,
            images: ['https://images.unsplash.com/photo-1539185441755-769473a23570?auto=format&fit=crop&w=800&q=80'],
          },
          unit_amount: priceCents,
          ...(isRecurring ? { recurring: { interval: 'month' as const } } : {}),
        },
        quantity: 1,
      },
    ],
    mode: isRecurring ? 'subscription' : 'payment',
    customer_email: customerEmail || undefined,
    success_url: successUrl,
    cancel_url: cancelUrl,
    metadata: {
      planId,
    },
  });

  return {
    url: session.url,
    sessionId: session.id,
  };
}
