import type { Metadata } from 'next';
import './globals.css';
import { Toaster } from 'react-hot-toast';

export const metadata: Metadata = {
  title: 'Vinyl Voice Notes — Preserve Your Voice in Digital Wax',
  description:
    'Convert spoken voice notes into warm, vintage physical-feeling 3D vinyl records. Share timeless audio memories with realistic turntable playback and real-time scrolling parchment lyrics.',
  keywords: [
    'vinyl voice note',
    '3d turntable audio player',
    'vintage voice gift',
    'wedding vows audio vinyl',
    'grandparents voice memory',
    'analog audio mastering',
    'gramophone player web',
  ],
  authors: [{ name: 'Vinyl Voice Notes Studio' }],
  openGraph: {
    title: 'Vinyl Voice Notes — Preserve Your Voice in Digital Wax',
    description:
      'Send a timeless, crackling 3D vinyl message to someone you love. A gift they will listen to forever.',
    type: 'website',
    images: [
      {
        url: 'https://images.unsplash.com/photo-1539185441755-769473a23570?auto=format&fit=crop&w=1200&q=80',
        width: 1200,
        height: 630,
        alt: 'Vintage 3D Vinyl Turntable',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Vinyl Voice Notes — 3D Vintage Voice Memories',
    description: 'Convert voice recordings into vintage 3D vinyl records with synced parchment lyrics.',
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'Vinyl Voice Notes',
    applicationCategory: 'MultimediaApplication',
    operatingSystem: 'All',
    description:
      'Convert spoken audio into vintage 3D vinyl records with synchronized lyrics and analog warmth.',
    offers: {
      '@type': 'Offer',
      price: '0.00',
      priceCurrency: 'USD',
    },
  };

  return (
    <html lang="en">
      <head>
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
        />
      </head>
      <body className="bg-[#0c0a09] text-stone-100 antialiased selection:bg-amber-600 selection:text-white">
        <div className="film-grain" />
        <Toaster
          position="bottom-right"
          toastOptions={{
            duration: 4000,
            style: {
              background: '#1c1917',
              color: '#fef3c7',
              border: '1px solid rgba(217, 119, 6, 0.3)',
              borderRadius: '0.75rem',
              boxShadow: '0 10px 25px -5px rgba(0, 0, 0, 0.7)',
              fontFamily: 'var(--font-inter, system-ui, sans-serif)',
            },
            success: {
              iconTheme: {
                primary: '#d97706',
                secondary: '#1c1917',
              },
            },
          }}
        />
        {children}
      </body>
    </html>
  );
}
