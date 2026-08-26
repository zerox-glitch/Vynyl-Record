import { NextRequest, NextResponse } from 'next/server';
import { getRecordingBySlug, incrementRecordingViews } from '@/lib/db';

export async function GET(
  req: NextRequest,
  { params }: { params: { slug: string } }
) {
  try {
    const slug = params.slug;
    if (!slug) {
      return NextResponse.json({ error: 'Slug parameter is required' }, { status: 400 });
    }

    const recording = await getRecordingBySlug(slug);
    if (!recording) {
      return NextResponse.json({ error: 'Vinyl recording not found' }, { status: 404 });
    }

    // Increment view count asynchronously
    await incrementRecordingViews(slug);

    return NextResponse.json({
      success: true,
      recording: {
        ...recording,
        views: (recording.views || 0) + 1,
      },
    });
  } catch (error: any) {
    console.error('Fetch recording error:', error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
