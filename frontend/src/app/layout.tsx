import type { Metadata, Viewport } from 'next';
import localFont from 'next/font/local';
import { AppProviders } from '@/components/providers/AppProviders';
import './globals.css';

// Self-hosted (SIL OFL, see fonts/Inter-OFL-LICENSE.txt): builds need no Google Fonts access and the installed
// app makes no third-party font requests.
const inter = localFont({
  src: './fonts/inter-latin-wght.woff2',
  weight: '100 900',
  style: 'normal',
  variable: '--font-inter',
  display: 'swap',
  fallback: ['system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
});

export const metadata: Metadata = {
  title: { default: 'CareFlow HMS', template: '%s · CareFlow HMS' },
  description: 'CareFlow Smart Hospital Management System',
  applicationName: 'CareFlow HMS',
  robots: { index: false, follow: false },
  appleWebApp: { capable: true, title: 'CareFlow', statusBarStyle: 'default' },
  icons: { apple: '/icons/apple-touch-icon.png' },
  // Phone numbers and IDs in records must not be turned into links by iOS.
  formatDetection: { telephone: false, address: false, email: false },
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: '#0F766E',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body>
        <div id="__careflow">
          <AppProviders>{children}</AppProviders>
        </div>
      </body>
    </html>
  );
}
