import { NextRequest, NextResponse } from 'next/server';
import { getProfiles, updateProfile, deleteProfile } from '@/lib/db';
import { requireAdmin } from '@/lib/admin-auth';

export async function GET(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const users = await getProfiles();
    return NextResponse.json({ users });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function PATCH(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const body = await req.json();
    const { id, updates } = body;
    if (!id || !updates) {
      return NextResponse.json({ error: 'User ID and updates required' }, { status: 400 });
    }

    const allowedUpdates = {
      ...(updates.role === 'admin' || updates.role === 'user' ? { role: updates.role } : {}),
      ...(typeof updates.is_premium === 'boolean' ? { is_premium: updates.is_premium } : {}),
    };
    if (Object.keys(allowedUpdates).length === 0) {
      return NextResponse.json({ error: 'No supported profile changes were provided.' }, { status: 400 });
    }
    const updated = await updateProfile(id, allowedUpdates);
    if (!updated) return NextResponse.json({ error: 'User not found.' }, { status: 404 });
    return NextResponse.json({ success: true, user: updated });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  const unauthorized = await requireAdmin(req);
  if (unauthorized) return unauthorized;
  try {
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    if (!id) return NextResponse.json({ error: 'User ID required' }, { status: 400 });

    const deleted = await deleteProfile(id);
    if (!deleted) return NextResponse.json({ error: 'User could not be deleted.' }, { status: 500 });
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
