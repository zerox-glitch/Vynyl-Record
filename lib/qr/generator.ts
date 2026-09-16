/**
 * qr/generator.ts
 * ----------------------------------------------------------------------------
 * QR code generation for every finished record. The QR resolves to the
 * sharing page so it can be printed on cards / gifts / wedding materials.
 *
 * Implementation: thin wrapper around the audited `qrcode` package
 * (https://github.com/soldair/node-qrcode, ISO/IEC 18004 certified). We
 * prefer an off-the-shelf encoder over hand-rolled Reed-Solomon because a
 * QR that scans wrong is worse than no QR at all.
 *
 * API:
 *   await generateQrSvg(text, { size: 240, ecc: 'M' }) -> '<svg>...</svg>'
 *   await generateQrPngDataUrl(text, ...) -> 'data:image/png;base64,...'
 */
import QRCode from 'qrcode';

export type QrEcc = 'L' | 'M' | 'Q' | 'H';
export interface QrOptions {
  /** Pixel square size in the returned SVG / PNG. */
  size?: number;
  /** Quiet-zone width in modules per side (default 4). */
  margin?: number;
  /** Foreground color (default '#0c0a09'). */
  fg?: string;
  /** Background color (default 'transparent' for SVG, '#ffffff' for PNG). */
  bg?: string;
  /** Error correction level (default 'M'). */
  ecc?: QrEcc;
}

/** Render a clean SVG; vector at any zoom level. */
export async function generateQrSvg(text: string, opts: QrOptions = {}): Promise<string> {
  return await QRCode.toString(text, {
    type: 'svg',
    errorCorrectionLevel: opts.ecc ?? 'M',
    margin: opts.margin ?? 2,
    width: opts.size ?? 240,
    color: {
      dark: opts.fg ?? '#0c0a09',
      light: opts.bg ?? '#ffffff00',
    },
  });
}

/** Render a PNG buffer (suitable for caching, downloads, embedding in PDFs). */
export async function generateQrPng(text: string, opts: QrOptions = {}): Promise<Buffer> {
  return await QRCode.toBuffer(text, {
    errorCorrectionLevel: opts.ecc ?? 'M',
    margin: opts.margin ?? 2,
    width: opts.size ?? 480,
    color: {
      dark: opts.fg ?? '#0c0a09',
      light: opts.bg ?? '#ffffff',
    },
  });
}

/** Render a PNG and return a data: URL the browser can <img src=...>-directly. */
export async function generateQrPngDataUrl(text: string, opts: QrOptions = {}): Promise<string> {
  const buf = await generateQrPng(text, opts);
  return `data:image/png;base64,${buf.toString('base64')}`;
}
