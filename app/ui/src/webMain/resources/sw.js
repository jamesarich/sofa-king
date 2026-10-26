// The web app's service worker: it keeps a copy of the app's own files, never the server's API, video, logos or admin
// portal, which are in its scope when a proxy serves the app at its root. It reads from the network first so an update
// shows on the next load, and serves the copy when the network fails.
const CACHE = 'sofaking-app';
const SERVER_PATHS = ['/api/', '/media/', '/logos/', '/admin'];

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
    const request = event.request;
    const url = new URL(request.url);
    if (request.method !== 'GET' || url.origin !== self.location.origin) return;
    if (SERVER_PATHS.some((path) => url.pathname.startsWith(path))) return;
    event.respondWith(
        fetch(request)
            .then((response) => {
                if (response.ok) {
                    const copy = response.clone();
                    event.waitUntil(caches.open(CACHE).then((cache) => cache.put(request, copy)));
                }
                return response;
            })
            .catch(() => caches.match(request).then((cached) => cached || Response.error())),
    );
});
