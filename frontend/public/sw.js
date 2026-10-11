/*
 * CareFlow service worker.
 *
 * Privacy rule: patient data is never stored on the device. API responses (/api/*), React Server Component
 * payloads and HTML pages always go to the network and are never cached. Only static, content-hashed app
 * assets (scripts, styles, fonts, icons) are cached so the app shell loads fast and installs as a PWA.
 * When a page cannot be loaded because the device is offline, a static offline page is shown instead.
 */
const VERSION = 'careflow-v1';
const STATIC_CACHE = `${VERSION}-static`;
const PRECACHE = ['/offline.html', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/apple-touch-icon.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(STATIC_CACHE).then((cache) => cache.addAll(PRECACHE)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(names.filter((n) => !n.startsWith(VERSION)).map((n) => caches.delete(n)));
      await self.clients.claim();
    })(),
  );
});

// The page asks a waiting worker to take over once the user accepts the update prompt.
self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') self.skipWaiting();
});

const isStaticAsset = (url) =>
  url.pathname.startsWith('/_next/static/') || url.pathname.startsWith('/icons/') || url.pathname === '/icon.svg';

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // Never intercept API calls or server component payloads: always live, never cached.
  if (url.pathname.startsWith('/api/') || request.headers.get('RSC') === '1') return;

  if (request.mode === 'navigate') {
    // Pages are always fetched live; fall back to the offline page only when the network is unreachable.
    event.respondWith(fetch(request).catch(() => caches.match('/offline.html')));
    return;
  }

  if (isStaticAsset(url)) {
    // Content-hashed and immutable: serve from cache, fill the cache on first use.
    event.respondWith(
      caches.open(STATIC_CACHE).then(async (cache) => {
        const hit = await cache.match(request);
        if (hit) return hit;
        const res = await fetch(request);
        if (res.ok) cache.put(request, res.clone());
        return res;
      }),
    );
  }
});
