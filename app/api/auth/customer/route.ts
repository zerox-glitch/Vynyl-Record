import { NextRequest, NextResponse } from 'next/server';
import { getCustomerServerClient } from '@/lib/supabase/auth';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  const client = getCustomerServerClient();
  if (!client) return NextResponse.json({ error: 'Customer accounts are not configured yet.' }, { status: 503 });
  const body = await req.json().catch(() => ({}));
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  const mode = body.mode === 'signup' ? 'signup' : 'signin';
  if (!email || !password || password.length < 8) {
    return NextResponse.json({ error: 'Use a valid email and a password of at least 8 characters.' }, { status: 400 });
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
