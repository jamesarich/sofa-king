// The web app's service worker: it keeps a copy of the app's own files, never the server's API, video, logos or admin
// portal, which are in its scope when a proxy serves the app at its root. The page, script and styles read from the
// network first, so an update shows on the next load, and fall back to the copy when the network fails or takes more
// than NETWORK_TIMEOUT_MS. A content-hashed .wasm never changes under its name, so its copy is served first; a new
// build names a new file. A copy is rewritten only when the response differs from it.
const CACHE = 'sofaking-app';
const SERVER_PATHS = ['/api/', '/media/', '/logos/', '/admin'];
const NETWORK_TIMEOUT_MS = 4000;
const HASHED_WASM = /\/[0-9a-f]{16,}\.wasm$/;

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
    const request = event.request;
    const url = new URL(request.url);
    if (request.method !== 'GET' || url.origin !== self.location.origin) return;
    if (SERVER_PATHS.some((path) => url.pathname.startsWith(path))) return;
    event.respondWith(HASHED_WASM.test(url.pathname) ? cacheFirst(event) : networkFirst(event));
});

// The copy of a hashed .wasm if there is one; otherwise the network's, kept for next time with the older hashed
// files dropped.
async function cacheFirst(event) {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(event.request);
    if (cached) return cached;
    const response = await fetch(event.request);
    if (response.ok) {
        const copy = response.clone();
        event.waitUntil(
            (async () => {
                for (const key of await cache.keys()) {
                    if (HASHED_WASM.test(new URL(key.url).pathname)) await cache.delete(key);
                }
                await cache.put(event.request, copy);
            })(),
        );
    }
    return response;
}

async function networkFirst(event) {
    const request = event.request;
    const cache = await caches.open(CACHE);
    const cached = await cache.match(request);
    let refreshed = null;
    const network = fetch(request).then((response) => {
        if (response.ok && !sameFile(response, cached)) refreshed = cache.put(request, response.clone());
        return response;
    });
    // Registered now, so a load the timeout passed over still finishes and refreshes the copy.
    event.waitUntil(network.then(() => refreshed, () => {}));
    if (!cached) return network.catch(() => Response.error());
    const timeout = new Promise((resolve) => setTimeout(() => resolve(cached), NETWORK_TIMEOUT_MS));
    return Promise.race([network, timeout]).catch(() => cached);
}

// True when the response is the file already kept, by its validators; without any, it is taken as changed.
function sameFile(response, cached) {
    if (!cached) return false;
    const etag = response.headers.get('etag');
    const modified = response.headers.get('last-modified');
    if (etag) return etag === cached.headers.get('etag');
    if (modified) return modified === cached.headers.get('last-modified');
    return false;
}
