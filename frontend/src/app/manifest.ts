import type { MetadataRoute } from 'next';

/** Web app manifest: makes CareFlow installable as a standalone app on desktop and mobile. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    id: '/',
    name: 'CareFlow HMS',
    short_name: 'CareFlow',
    description: 'CareFlow Smart Hospital Management System',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    orientation: 'any',
    background_color: '#F6F5F1',
    theme_color: '#0F766E',
    categories: ['medical', 'health', 'productivity'],
    icons: [
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icons/maskable-192.png', sizes: '192x192', type: 'image/png', purpose: 'maskable' },
      { src: '/icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  };
}
