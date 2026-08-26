import { NextRequest, NextResponse } from 'next/server';
import { getRecordings, deleteRecording } from '@/lib/db';

export async function GET() {
  try {
    const recordings = await getRecordings();
    return NextResponse.json({ recordings });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    if (!id) return NextResponse.json({ error: 'Recording ID is required' }, { status: 400 });

    await deleteRecording(id);
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
