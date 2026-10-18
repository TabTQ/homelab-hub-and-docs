/*
 * Service worker for the installable dashboard. It exists to make the app installable and to show a clear message when the
 * server cannot be reached. It deliberately caches almost nothing:
 *   - /assets/*  the fingerprinted JS/CSS/font files. Their names change with their content, and they hold no data.
 *   - /offline.html  a static page shown when a page navigation fails.
 * Everything else (every /api call, images, the HTML itself) always goes to the network, so nothing private is ever stored
 * here and a stale dashboard is never shown as if it were live.
 */
const VERSION = "v1";
const ASSETS = "hub-assets-" + VERSION;
const SHELL = "hub-shell-" + VERSION;
const OFFLINE = "/offline.html";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.add(OFFLINE)));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key !== ASSETS && key !== SHELL) await caches.delete(key);
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  if (url.pathname.startsWith("/assets/")) {
    event.respondWith((async () => {
      const cache = await caches.open(ASSETS);
      const hit = await cache.match(req);
      if (hit) return hit;
      const res = await fetch(req);
      if (res.ok && res.type === "basic") cache.put(req, res.clone());
      return res;
    })());
    return;
  }

  if (req.mode === "navigate") {
    event.respondWith(fetch(req).catch(async () => (await caches.match(OFFLINE)) || Response.error()));
  }
});
