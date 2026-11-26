// Movio helper: lets the Movio web app reach the catalogue, subtitles and the direct stream service
// from a browser (adds CORS), and keeps the API keys off the public page.
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET, OPTIONS" };
const UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
const PARTY = "https://vidsrc.party";

function withCors(r, extra = {}) {
  const h = new Headers(r.headers);
  for (const [k, v] of Object.entries({ ...CORS, ...extra })) h.set(k, v);
  h.delete("set-cookie");
  return new Response(r.body, { status: r.status, headers: h });
}

async function cached(target, init, seconds, ctx) {
  const cache = caches.default;
  const key = new Request(target);
  const hit = await cache.match(key);
  if (hit) return withCors(hit);
  const r = await fetch(target, init);
  const res = withCors(r, { "Cache-Control": `public, max-age=${seconds}` });
  if (r.ok) ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}

export default {
  async fetch(req, env, ctx) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      // Catalogue (TMDB)
      if (p.startsWith("/tmdb/")) {
        const t = new URL("https://api.themoviedb.org/3/" + p.slice(6));
        url.searchParams.forEach((v, k) => t.searchParams.set(k, v));
        t.searchParams.set("api_key", env.TMDB_KEY || "");
        if (!t.searchParams.has("language")) t.searchParams.set("language", "en-US");
        return cached(t.toString(), {}, 6 * 3600, ctx);
      }
      // Subtitle search (Wyzie)
      if (p === "/wyzie") {
        if (!env.WYZIE_KEY) return new Response("[]", { headers: { ...CORS, "Content-Type": "application/json" } });
        const t = new URL("https://sub.wyzie.io/search");
        url.searchParams.forEach((v, k) => t.searchParams.set(k, v));
        t.searchParams.set("key", env.WYZIE_KEY);
        return cached(t.toString(), {}, 6 * 3600, ctx);
      }
      // Intro / credits times (TheIntroDB)
      if (p === "/intro") {
        return cached("https://api.theintrodb.org/v4/media" + url.search, {}, 24 * 3600, ctx);
      }
      // Direct stream service: only its JSON API (the video itself plays straight from it)
      if (p.startsWith("/party/api/")) {
        const r = await fetch(PARTY + p.slice(6) + url.search, {
          headers: { Referer: PARTY + "/", Origin: PARTY, Accept: "application/json", "User-Agent": UA },
        });
        return withCors(r, { "Cache-Control": "no-store" });
      }
      // A subtitle file, as text (max 3 MB)
      if (p === "/sub") {
        const u = url.searchParams.get("u") || "";
        if (!/^https:\/\//.test(u)) return new Response("bad url", { status: 400, headers: CORS });
        const r = await fetch(u, { headers: { "User-Agent": UA, Referer: new URL(u).origin + "/" } });
        if (!r.ok) return new Response("", { status: r.status, headers: CORS });
        const buf = await r.arrayBuffer();
        if (buf.byteLength > 3 << 20) return new Response("too big", { status: 413, headers: CORS });
        return new Response(buf, { headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=86400" } });
      }
    } catch (e) {
      return new Response("helper error", { status: 502, headers: CORS });
    }
    return new Response("Movio helper is running", { headers: { ...CORS, "Content-Type": "text/plain" } });
  },
};
