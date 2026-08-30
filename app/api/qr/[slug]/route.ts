/**
 * /api/qr/[slug] -> PNG QR pointing at the share page for the record.
 *
 * Honors record visibility: private recordings return 404 to anonymous
 * callers; owners / admins get through. Same visibility gate that the
 * download route uses, so the QR endpoint can't leak a private record's
 * existence.
 */
import { NextRequest, NextResponse } from 'next/server';
import { generateQrPng } from '@/lib/qr/generator';
import { getRecordingBySlug } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(
  req: NextRequest,
  { params }: { params: { slug: string } }
) {
  const rec = await getRecordingBySlug(params.slug, { kind: 'anonymous' });
  if (!rec) return new NextResponse('Not Found', { status: 404 });

  const shareUrl = new URL(`/play/${rec.slug}`, req.nextUrl.origin).toString();
  const size = Number(req.nextUrl.searchParams.get('size') ?? '640') || 640;

  try {
    const buf = await generateQrPng(shareUrl, {
      size,
      ecc: 'M',
      fg: '#0c0a09',
      bg: '#ffffff',
    });
    return new NextResponse(buf, {
      status: 200,
      headers: {
        'Content-Type': 'image/png',
        'Content-Length': String(buf.length),
        'Cache-Control': 'private, max-age=60, stale-while-revalidate=300',
      },
    });
  } catch (err: any) {
    return NextResponse.json({ error: err?.message || 'QR generation failed' }, { status: 500 });
  }
}
