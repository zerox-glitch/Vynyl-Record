import { NextRequest, NextResponse } from 'next/server';
import { getCustomerServerClient } from '@/lib/supabase/auth';
import { rateLimit, getClientIp } from '@/lib/rate-limit';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  const ip = getClientIp(req);
  const rl = rateLimit(`auth:${ip}`, 10, 60 * 1000); // 10 attempts per minute
  if (!rl.allowed) {
    return NextResponse.json({ error: 'Too many attempts. Try again later.' }, { status: 429, headers: { 'Retry-After': Math.ceil((rl.resetAt - Date.now()) / 1000).toString() } });
  }

  const client = getCustomerServerClient();
  if (!client) return NextResponse.json({ error: 'Customer accounts are not configured yet.' }, { status: 503 });
  const body = await req.json().catch(() => ({}));
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  const mode = body.mode === 'signup' ? 'signup' : 'signin';
  if (!email || !password || password.length < 8) {
    return NextResponse.json({ error: 'Use a valid email and a password of at least 8 characters.' }, { status: 400 });
  }
  // Additional validation
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return NextResponse.json({ error: 'Invalid email format.' }, { status: 400 });
  }

  const result = mode === 'signup'
    ? await client.auth.signUp({ email, password })
    : await client.auth.signInWithPassword({ email, password });

  if (result.error) return NextResponse.json({ error: result.error.message }, { status: 401 });
  return NextResponse.json({ success: true, needsEmailConfirmation: mode === 'signup' && !result.data.session });
}

export async function DELETE() {
  const client = getCustomerServerClient();
  if (client) await client.auth.signOut();
  return NextResponse.json({ success: true });
}
