// Service worker: guarda la app en el dispositivo para que funcione sin conexión.
// Estrategia: se responde enseguida con lo guardado y, si hay conexión, se actualiza en segundo plano (la versión nueva se ve al abrir otra vez).

const CACHE = 'evability-web-3';
const CORE = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css',
  'js/main.js', 'js/theme.js', 'js/ui.js', 'js/db.js', 'js/store.js', 'js/pdfdoc.js', 'js/templates.js', 'js/ann.js', 'js/editor.js',
  'js/export.js', 'js/home.js', 'js/settings.js', 'js/pomodoro.js', 'js/gemini.js', 'js/summarypdf.js', 'js/analyze.js', 'js/stickers.js',
  'vendor/pdf.min.js', 'vendor/pdf.worker.min.js', 'vendor/pdf-lib.min.js', 'vendor/jszip.min.js',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png', 'icons/logo.png', 'easter-egg.mp3',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('message', (e) => {
  if (e.data?.type !== 'precache') return;
  e.waitUntil(caches.open(CACHE).then(async (c) => {
    for (const f of e.data.files) { try { if (!(await c.match(f))) await c.add(f); } catch { /* sin conexión: se intentará otro día */ } }
  }));
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return; // las llamadas a Gemini no pasan por aquí
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(req, { ignoreSearch: true });
    const network = fetch(req).then((res) => { if (res.ok) cache.put(req, res.clone()); return res; }).catch(() => null);
    if (hit) { e.waitUntil(network); return hit; }
    const res = await network;
    if (res) return res;
    if (req.mode === 'navigate') return (await cache.match('index.html')) || Response.error();
    return Response.error();
  })());
});
