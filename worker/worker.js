// Movio helper: lets the Movio web app reach the catalogue, subtitles and the direct stream service
// from a browser (adds CORS), and keeps the API keys off the public page.
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET, OPTIONS" };
const UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
const PARTY = "https://vidsrc.party";
const CLUB = "https://moviesapi.to";
const CLUB_KEY = "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13";

// Stream links are signed so this helper can't be used as an open proxy.
async function sign(env, text) {
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode("movio:" + (env.TMDB_KEY || "x")), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(text));
  return btoa(String.fromCharCode(...new Uint8Array(sig).slice(0, 12))).replace(/[+/=]/g, (c) => ({ "+": "-", "/": "_", "=": "" }[c]));
}
async function hlsLink(env, origin, u, ref) {
  return `${origin}/hls?u=${encodeURIComponent(u)}&r=${encodeURIComponent(ref)}&k=${await sign(env, u + "|" + ref)}`;
}

/** Fetches a playlist or segment the way the source's own page would, rewriting playlists so every part comes back through here. */
async function hls(req, env, url) {
  const u = url.searchParams.get("u") || "", ref = url.searchParams.get("r") || "";
  if (!/^https:\/\//.test(u) || (await sign(env, u + "|" + ref)) !== url.searchParams.get("k")) return new Response("forbidden", { status: 403, headers: CORS });
  const h = { "User-Agent": UA, Referer: ref, Origin: new URL(ref).origin };
  const range = req.headers.get("Range");
  if (range) h.Range = range;
  const r = await fetch(u, { headers: h, redirect: "follow" });
  const ct = r.headers.get("content-type") || "";
  // Playlists and segments are often disguised (.js, .png…): look at the bytes, not the name.
  const buf = await r.arrayBuffer();
  const head = new TextDecoder().decode(buf.slice(0, 7));
  if (head !== "#EXTM3U") {
    const hh = new Headers(r.headers);
    for (const [k, v] of Object.entries(CORS)) hh.set(k, v);
    hh.set("Cache-Control", "public, max-age=3600");
    hh.delete("set-cookie"); hh.delete("content-encoding"); hh.delete("content-length");
    return new Response(buf, { status: r.status, headers: hh });
  }
  const text = new TextDecoder().decode(buf);
  const base = r.url;
  const origin = url.origin;
  const lines = await Promise.all(text.split("\n").map(async (line) => {
    const l = line.trim();
    if (!l) return line;
    if (l.startsWith("#")) {
      const m = l.match(/URI="([^"]+)"/);
      if (!m) return line;
      return l.replace(m[0], `URI="${await hlsLink(env, origin, new URL(m[1], base).href, ref)}"`);
    }
    return hlsLink(env, origin, new URL(l, base).href, ref);
  }));
  return new Response(lines.join("\n"), { headers: { ...CORS, "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-store" } });
}

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

const json200 = (o) => new Response(JSON.stringify(o), { headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" } });

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
      // Club: its player's API gives the stream; we hand back a link that plays through here.
      if (p.startsWith("/club/")) {
        const parts = p.slice(6).split("/");
        const kind = parts[0] === "tv" ? "tv" : "movie";
        const id = parts[1];
        const page = kind === "tv" ? `${CLUB}/tv/${id}-${parts[2]}-${parts[3]}` : `${CLUB}/movie/${id}`;
        const api = kind === "tv" ? `${CLUB}/api/vidora/v1/tv/${id}/${parts[2]}/${parts[3]}` : `${CLUB}/api/vidora/v1/movie/${id}`;
        const r = await fetch(api, { headers: { "x-player-key": CLUB_KEY, Referer: page, "User-Agent": UA, Accept: "application/json" } });
        const o = await r.json().catch(() => null);
        const src = o && o.sources && o.sources[0];
        if (!src || !src.url) return new Response(JSON.stringify({ ok: false }), { headers: { ...CORS, "Content-Type": "application/json" } });
        const tracks = (src.tracks || []).filter((t) => t.file).map((t) => ({ label: t.label || "", file: new URL(t.file, CLUB).href }));
        return new Response(JSON.stringify({ ok: true, url: await hlsLink(env, url.origin, src.url, CLUB + "/"), tracks }), {
          headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" } });
      }
      if (p === "/hls") return hls(req, env, url);
      // Peach: its player's stream API (backup API for films), played through here.
      if (p.startsWith("/peach/")) {
        const [kind, id, se, ep] = p.slice(7).split("/");
        const json = (u) => fetch(u, { headers: { "User-Agent": UA, Accept: "application/json" } }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
        const q = kind === "tv" ? `tmdb=${id}&s=${se}&e=${ep}` : `tmdb=${id}&type=movie`;
        let d = await json(`https://vidbolt.onrender.com/api/stream?${q}`);
        let src = d && d.available !== false && ((d.sources || []).find((x) => x && x.url) || {}).url;
        let ref = "https://embed.filmu.in/";
        if (!src) {
          d = await json(`https://vidzy-0aej.onrender.com/api/stream?type=${kind === "tv" ? "tv" : "movie"}&${q.replace("&type=movie", "")}`);
          src = d && d.available !== false && (d.m3u8 || ((d.sources || []).find((x) => x && x.url) || {}).url);
          ref = "https://player.vidzee.wtf/";
        }
        if (!src) return json200({ ok: false });
        return json200({ ok: true, url: await hlsLink(env, url.origin, src, ref), tracks: [] });
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
