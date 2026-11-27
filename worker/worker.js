// Movio helper: lets the Movio web app reach the catalogue, subtitles and the direct stream service
// from a browser (adds CORS), and keeps the API keys off the public page.
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS" };
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
    let body = buf;
    const b = new Uint8Array(buf);
    // Video chunks disguised as images: the real MPEG-TS starts after a short fake header.
    if (b.length > 400 && b[0] !== 0x47 && !(b[4] === 0x66 && b[5] === 0x74 && b[6] === 0x79 && b[7] === 0x70)) {
      for (let i = 1; i < Math.min(b.length - 376, 8192); i++) {
        if (b[i] === 0x47 && b[i + 188] === 0x47 && b[i + 376] === 0x47) { body = buf.slice(i); break; }
      }
    }
    const hh = new Headers(r.headers);
    for (const [k, v] of Object.entries(CORS)) hh.set(k, v);
    hh.set("Cache-Control", "public, max-age=3600");
    hh.delete("set-cookie"); hh.delete("content-encoding"); hh.delete("content-length");
    if (body !== buf) hh.set("Content-Type", "video/mp2t");
    return new Response(body, { status: r.status, headers: hh });
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

// Mist's player build string (part of its key); update if Mist changes its player.
const MIST_BUILD = "spv3-build-1787821613-50e5fc97c9dce367";
async function sha256hex(t) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(t));
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
const hexBytes = (h) => new Uint8Array(h.match(/../g).map((x) => parseInt(x, 16)));
function b64url(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}
// ---- Sync ----
const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
function newCode() {
  const b = crypto.getRandomValues(new Uint8Array(6));
  return [...b].map((x) => CODE_CHARS[x % CODE_CHARS.length]).join("");
}
const emptyState = () => ({ list: {}, cont: {}, pos: {} });
/** Per item, the newest change wins (deletions are kept as {del:true} so they spread too). */
function mergeState(a, b) {
  const out = emptyState();
  const LIMIT = { list: 500, cont: 60, pos: 800 };
  for (const sec of ["list", "cont", "pos"]) {
    const m = { ...(a?.[sec] || {}) };
    for (const [k, v] of Object.entries(b?.[sec] || {})) {
      if (!v || typeof v.at !== "number" || k.length > 80) continue;
      if (!m[k] || v.at > m[k].at) m[k] = v;
    }
    // Keep the newest; drop deletions older than 90 days.
    const cutoff = Date.now() - 90 * 864e5;
    out[sec] = Object.fromEntries(Object.entries(m).filter(([, v]) => !(v.del && v.at < cutoff))
      .sort((x, y) => y[1].at - x[1].at).slice(0, LIMIT[sec]));
  }
  // Profile: the name, newest wins (so a new device can greet you right after linking).
  const ok = (v) => v && typeof v.at === "number" && typeof v.name === "string" && v.name.length <= 24;
  const pa = ok(a?.prof) ? a.prof : null, pb = ok(b?.prof) ? b.prof : null;
  const pr = pb && (!pa || pb.at > pa.at) ? pb : pa;
  if (pr) out.prof = { at: pr.at, name: pr.name };
  return out;
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
      // Sync between devices: a short link code names a shared record.
      if (p === "/sync/new" && req.method === "POST") {
        for (let i = 0; i < 5; i++) {
          const code = newCode();
          if (!(await env.MOVIO_SYNC.get("s:" + code))) {
            await env.MOVIO_SYNC.put("s:" + code, JSON.stringify(emptyState()));
            return json200({ ok: true, code });
          }
        }
        return json200({ ok: false });
      }
      if (p.startsWith("/sync/")) {
        const code = p.slice(6).toUpperCase();
        if (!/^[A-Z2-9]{6}$/.test(code)) return withCors(new Response(JSON.stringify({ ok: false, error: "bad code" }), { status: 400, headers: { "Content-Type": "application/json" } }));
        const raw = await env.MOVIO_SYNC.get("s:" + code);
        if (!raw) return withCors(new Response(JSON.stringify({ ok: false, error: "unknown code" }), { status: 404, headers: { "Content-Type": "application/json" } }));
        let state = JSON.parse(raw);
        if (req.method === "PUT" || req.method === "POST") {
          const body = await req.text();
          if (body.length > 900_000) return withCors(new Response("too big", { status: 413 }));
          let incoming;
          try { incoming = JSON.parse(body); } catch { return withCors(new Response("bad json", { status: 400 })); }
          state = mergeState(state, incoming);
          await env.MOVIO_SYNC.put("s:" + code, JSON.stringify(state));
        }
        return json200({ ok: true, state });
      }
      // Mist: decrypts its server list, returns the first HLS server that answers.
      if (p.startsWith("/mist/")) {
        const [kind, id, se, ep] = p.slice(6).split("/");
        const MIST = "https://play.xpass.top";
        const page = kind === "tv" ? `${MIST}/e/tv/${id}/${se}/${ep}` : `${MIST}/e/movie/${id}`;
        const H = (ref) => ({ headers: { "User-Agent": UA, Referer: ref } });
        const html = await fetch(page, H("https://braflix.at/")).then((r) => r.text()).catch(() => "");
        const du = (html.match(/dataUrl="([^"]+)"/) || [])[1];
        if (!du) return json200({ ok: false });
        const d = new URL(du, MIST);
        const enc = (await fetch(d.href, H(page)).then((r) => r.text()).catch(() => "")).trim();
        let list = [];
        try {
          const keyHex = await sha256hex(`spv3-data-response|${MIST_BUILD}|${d.pathname}|${d.searchParams.get("token")}`);
          const key = await crypto.subtle.importKey("raw", hexBytes(keyHex), { name: "AES-GCM" }, false, ["decrypt"]);
          const b = b64url(enc);
          list = JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: b.slice(0, 12) }, key, b.slice(12))));
        } catch { return json200({ ok: false }); }
        for (const item of list.slice(0, 10)) {
          if (!item.url || item.url.startsWith("/video/")) continue;
          try {
            const pl = await fetch(new URL(item.url, MIST).href, H(page)).then((r) => r.json());
            const arr = Array.isArray(pl) ? pl : pl.playlist;
            const file = arr && arr[0] && ((arr[0].sources || [])[0] || {}).file;
            if (!file || !/^https:/.test(file) || /cf-master|\.mp4($|\?)/.test(file)) continue;
            const t = await fetch(file, H(MIST + "/"));
            if (!t.ok || !(await t.text()).startsWith("#EXTM3U")) continue;
            return json200({ ok: true, url: await hlsLink(env, url.origin, file, MIST + "/"), tracks: [] });
          } catch {}
        }
        return json200({ ok: false });
      }
      // Main (Vidzee): its stream API.
      if (p.startsWith("/main/")) {
        const [kind, id, se, ep] = p.slice(6).split("/");
        const q = kind === "tv" ? `type=tv&tmdb=${id}&s=${se}&e=${ep}` : `type=movie&tmdb=${id}`;
        const d = await fetch(`https://vidzy-0aej.onrender.com/api/stream?${q}`, { headers: { "User-Agent": UA } }).then((r) => r.json()).catch(() => null);
        const src = d && d.available !== false && (d.m3u8 || ((d.sources || []).find((x) => x && x.url) || {}).url);
        if (!src) return json200({ ok: false });
        return json200({ ok: true, url: await hlsLink(env, url.origin, src, "https://player.vidzee.wtf/"), tracks: [] });
      }
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
