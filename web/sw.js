// Keeps the app shell on the phone so Movio opens instantly; everything else comes from the network.
const SHELL = "movio-shell-v5";
const FILES = ["./", "index.html", "style.css", "app.js", "config.js", "manifest.webmanifest", "icons/icon-180.png",
  "fonts/jakarta_regular.ttf", "fonts/jakarta_semibold.ttf", "fonts/jakarta_bold.ttf", "fonts/jakarta_extrabold.ttf"];
self.addEventListener("install", (e) => { e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener("activate", (e) => { e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== SHELL).map((k) => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.origin !== location.origin) return;
  // Network first so updates land straight away; the saved copy is the fallback.
  e.respondWith(fetch(e.request).then((r) => { const c = r.clone(); caches.open(SHELL).then((s) => s.put(e.request, c)); return r; }).catch(() => caches.match(e.request)));
});
