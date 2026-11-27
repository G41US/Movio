import VIDZEE_WASM from "./vidzee.wasm";
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
async function hlsLink(env, origin, u, ref, ua = "") {
  return `${origin}/hls?u=${encodeURIComponent(u)}&r=${encodeURIComponent(ref)}${ua ? `&a=${encodeURIComponent(ua)}` : ""}&k=${await sign(env, u + "|" + ref + (ua ? "|" + ua : ""))}`;
}

/** Fetches a playlist or segment the way the source's own page would, rewriting playlists so every part comes back through here. */
async function hls(req, env, url) {
  const u = url.searchParams.get("u") || "", ref = url.searchParams.get("r") || "", ua = url.searchParams.get("a") || "";
  if (!/^https?:\/\//.test(u) || (await sign(env, u + "|" + ref + (ua ? "|" + ua : ""))) !== url.searchParams.get("k")) return new Response("forbidden", { status: 403, headers: CORS });
  const h = { "User-Agent": ua || UA };
  if (ref) { h.Referer = ref; h.Origin = new URL(ref).origin; }
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
      return l.replace(m[0], `URI="${await hlsLink(env, origin, new URL(m[1], base).href, ref, ua)}"`);
    }
    return hlsLink(env, origin, new URL(l, base).href, ref, ua);
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
// AVO TV (free Nigerian channels): signs in with Movio's AVO account and hands out a fresh stream link.
const AVO_M = "9be57398-2860-4b42-b762-46eff7621c59" + "12.3" + "json" + "221399940658590650";
const AVO_PF = [{ protocol: "HLS", audioFormats: ["AAC"], container: "TS", videoCodecs: ["H264"], numberOfSegmentsForLive: 3, segmentDuration: 10000, drmTypes: ["CLEAR_KEY"], subtitleFormats: ["WEBVTT"] }];
async function avoCall(ep, body) {
  const d = JSON.stringify({ locale: "en-GB", ...body }), a = "client/" + ep;
  const h = [...new Uint8Array(await crypto.subtle.digest("MD5", new TextEncoder().encode(AVO_M + a + d)))].map((b) => b.toString(16).padStart(2, "0")).join("");
  const r = await fetch(`https://player.avo.tv/Catherine/api/12.3/json/221399940658590650/${h}/${a}`, {
    method: "POST", body: d, headers: { "Content-Type": "application/json", Origin: "https://player.avo.tv", Referer: "https://player.avo.tv/start", "User-Agent": UA },
  });
  return r.json();
}
// AVO_USER / AVO_PASS may hold several free accounts (one per line); each one streams to about two viewers at once.
function avoAccounts(env) {
  const u = String(env.AVO_USER || "").split(/\s*[\n,]\s*/).filter(Boolean), p = String(env.AVO_PASS || "").split(/\n/).filter(Boolean);
  return u.map((x, i) => [x, p[i] ?? p[0]]);
}
async function avoSession(env, n, fresh) {
  const [user, pass] = avoAccounts(env)[n];
  if (!fresh) { const c = await env.MOVIO_SYNC.get("avo:s:" + user, "json"); if (c) return c; }
  const l = await avoCall("login", { username: user, password: pass, persistent: true, deviceName: "Movio" });
  if (!l.key) throw new Error("AVO sign-in failed");
  const pr = await avoCall("profiles/list", { sessionId: l.key });
  const s = { key: l.key, guid: pr.profiles?.[0]?.guid || "" };
  await env.MOVIO_SYNC.put("avo:s:" + user, JSON.stringify(s), { expirationTtl: 86400 * 7 });
  return s;
}
async function avoUrl(env, id) {
  const n = avoAccounts(env).length, start = Math.floor(Math.random() * n);
  for (let k = 0; k < n; k++) {
    const a = (start + k) % n;
    for (const fresh of [false, true]) {
      const s = await avoSession(env, a, fresh);
      const r = await avoCall("channels/linear/getUrl", { sessionId: s.key, profileGuid: s.guid, channelId: id, channelType: "TV", playbackType: "LIVE", delay: 0, secureHttp: true, playbackFormats: AVO_PF });
      if (r.url) return r.url;
      if (r.errorType === 7) break;  // this account is busy: try the next one
      if (![26, 27, 37].includes(r.errorType)) throw new Error("AVO: " + JSON.stringify(r.errorDetails || r));
    }
  }
  throw new Error("AVO: all accounts busy");
}
const json200 = (o) => new Response(JSON.stringify(o), { headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" } });

export default {
  async fetch(req, env, ctx) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      // AVO TV channel: /avo/<channelId> → redirect to a fresh stream (no proxy needed, AVO allows any site).
      if (p === "/avo/list") {
        // What AVO offers to whoever's asking (the list can depend on the viewer's country).
        const s = await avoSession(env, 0, false);
        const r = await avoCall("channels/list", { sessionId: s.key, profileGuid: s.guid, type: "TV", playbackFormats: AVO_PF });
        const where = req.cf?.country || "?";
        return new Response(where + " · " + (r.channels || []).length + " channels\n\n" + (r.channels || []).map((c) => c.id + "  " + c.name).join("\n"), { headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8" } });
      }
      if (p.startsWith("/avo/")) {
        const id = Number(p.slice(5).replace(/\.m3u8$/, ""));
        if (!id || !avoAccounts(env).length) return new Response("not found", { status: 404, headers: CORS });
        const to = await avoUrl(env, id).catch(() => null);
        if (!to) return new Response("busy", { status: 503, headers: CORS });
        return new Response(null, { status: 302, headers: { ...CORS, Location: to, "Cache-Control": "no-store" } });
      }
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
      // Two community databases matched up (TheIntroDB + IntroDB): agree → average; disagree → the better-confirmed one.
      if (p === "/intro") {
        const q = url.searchParams, id = q.get("tmdb_id"), s = q.get("season"), e = q.get("episode"), tv = !!s;
        const ck = new Request("https://cache.movio/intro2?" + q.toString());
        const hit = await caches.default.match(ck);
        if (hit) return withCors(hit);
        const get = (u) => fetch(u, { headers: { "User-Agent": UA } }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
        const [a, ext] = await Promise.all([get("https://api.theintrodb.org/v4/media" + url.search),
          get(`https://api.themoviedb.org/3/${tv ? "tv" : "movie"}/${id}/external_ids?api_key=${env.TMDB_KEY}`)]);
        const imdb = ext?.imdb_id;
        const b = imdb ? await get(`https://api.introdb.app/segments?imdb_id=${imdb}` + (tv ? `&season=${s}&episode=${e}` : "")) : null;
        const out = {};
        const near = (x, y) => Math.abs(x.start_ms - y.start_ms) < 4000 && Math.abs((x.end_ms ?? 1e12) - (y.end_ms ?? 1e12)) < 4000;
        for (const [type, bk] of [["recap", "recap"], ["intro", "intro"], ["credits", "outro"], ["preview", null]]) {
          const xa = (a?.[type] || []).map((x) => ({ start_ms: x.start_ms || 0, end_ms: x.end_ms ?? null }));
          const y = bk && b?.[bk] ? b[bk] : null;
          const sure = y ? (y.confidence ?? 0.5) * Math.min(1, 0.5 + 0.25 * (y.submission_count || 1)) : 0;
          const yb = y ? { start_ms: y.start_ms || 0, end_ms: y.end_ms ?? null } : null;
          let v = xa;
          if (yb && !xa.length) v = sure >= 0.4 ? [yb] : [];
          else if (yb && near(xa[0], yb)) v = [{ start_ms: Math.round((xa[0].start_ms + yb.start_ms) / 2), end_ms: xa[0].end_ms == null || yb.end_ms == null ? xa[0].end_ms ?? yb.end_ms : Math.round((xa[0].end_ms + yb.end_ms) / 2) }, ...xa.slice(1)];
          else if (yb && sure >= 0.75) v = [yb];
          if (v.length) out[type] = v;
        }
        const res = new Response(JSON.stringify(out), { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=86400" } });
        ctx.waitUntil(caches.default.put(ck, res.clone()));
        return withCors(res);
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
      // Live TV: a channel's stream through here (for browsers, which need CORS, and for plain-http streams).
      if (p === "/live") {
        const u = url.searchParams.get("u") || "";
        if (!/^https?:\/\/[^\s]+$/.test(u) || !/m3u8|\/hls|playlist|manifest|live|stream/i.test(u)) return json200({ ok: false });
        return json200({ ok: true, url: await hlsLink(env, url.origin, u, url.searchParams.get("r") || "", url.searchParams.get("a") || "") });
      }
      // What people like and watch (counts only, nothing about who).
      if (p === "/stat" && req.method === "POST") {
        let e; try { e = await req.json(); } catch { return json200({ ok: false }); }
        const ev = { like: "likes", unlike: "likes", play: "plays" }[e?.ev];
        const kind = e?.kind === "tv" ? "tv" : "movie", id = +e?.id;
        if (!ev || !id) return json200({ ok: false });
        const st = JSON.parse((await env.MOVIO_SYNC.get("stats")) || '{"titles":{},"genres":{},"days":{}}');
        const d = e.ev === "unlike" ? -1 : 1;
        const k = `${kind}:${id}`;
        const t = (st.titles[k] ||= { name: String(e.name || "").slice(0, 80), kind, poster: e.poster || null, likes: 0, plays: 0 });
        t[ev] = Math.max(0, t[ev] + d);
        for (const g of (Array.isArray(e.genres) ? e.genres : []).slice(0, 6)) { const x = (st.genres[String(g).slice(0, 30)] ||= { likes: 0, plays: 0 }); x[ev] = Math.max(0, x[ev] + d); }
        const day = new Date().toISOString().slice(0, 10);
        const y = (st.days[day] ||= { likes: 0, plays: 0 }); y[ev] = Math.max(0, y[ev] + d);
        // Keep it small: the 400 busiest titles and the last 60 days.
        const top = Object.entries(st.titles).sort((a, b) => b[1].plays + b[1].likes * 3 - (a[1].plays + a[1].likes * 3)).slice(0, 400);
        st.titles = Object.fromEntries(top);
        st.days = Object.fromEntries(Object.entries(st.days).sort().slice(-60));
        await env.MOVIO_SYNC.put("stats", JSON.stringify(st));
        return json200({ ok: true });
      }
      if (p === "/stats") return json200(JSON.parse((await env.MOVIO_SYNC.get("stats")) || '{"titles":{},"genres":{},"days":{}}'));
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
          const next = JSON.stringify(state);
          if (next !== raw) await env.MOVIO_SYNC.put("s:" + code, next);  // only write when something changed
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
      // Main (Vidzee): its own player's servers first (films and series), then its older API (films).
      if (p.startsWith("/main/")) {
        const [kind, id, se, ep] = p.slice(6).split("/");
        const own = await vidzee(kind, id, se, ep);
        if (own) return json200({ ok: true, url: await hlsLink(env, url.origin, own, "https://player.vidzee.wtf/"), tracks: [] });
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

// ---------- Vidzee's player: each server answers with a link sealed by a small WebAssembly decoder ----------
let vzx;
function vidzeeDecrypt(b64) {
  if (!vzx) vzx = new WebAssembly.Instance(VIDZEE_WASM, { env: { abort: () => { throw new Error("vidzee abort"); } } }).exports;
  const e = vzx;
  const host = "player.vidzee.wtf";
  const sp = e.__new(host.length * 2, 2);
  const u16 = new Uint16Array(e.memory.buffer, sp, host.length);
  for (let i = 0; i < host.length; i++) u16[i] = host.charCodeAt(i);
  e.__pin(sp);
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const ab = e.__pin(e.__new(bytes.length, 1));
  new Uint8Array(e.memory.buffer, ab, bytes.length).set(bytes);
  const view = e.__new(12, 3);
  let dv = new DataView(e.memory.buffer);
  dv.setUint32(view, ab, true); dv.setUint32(view + 4, ab, true); dv.setUint32(view + 8, bytes.length, true);
  e.__pin(view);
  try {
    const r = e.decrypt(view, sp);
    dv = new DataView(e.memory.buffer);
    return new TextDecoder().decode(new Uint8Array(e.memory.buffer, dv.getUint32(r + 4, true), dv.getUint32(r + 8, true)));
  } finally { e.__unpin(view); e.__unpin(ab); e.__unpin(sp); e.__collect?.(); }
}
async function vidzee(kind, id, se, ep) {
  const path = kind === "tv" ? `tv/${id}/${se}/${ep}` : `movie/${id}`;
  const H = { "User-Agent": UA, Referer: "https://player.vidzee.wtf/", Origin: "https://player.vidzee.wtf" };
  const one = async (srv) => {
    const o = await fetch(`https://core.vidzee.wtf/streams/${path}?s=${encodeURIComponent(srv)}&e=1`, { headers: H }).then((r) => r.json());
    if (!o?.c) throw 0;
    const d = JSON.parse(vidzeeDecrypt(o.c));
    const u = d?.url;
    if (!u || !/^https:/.test(u)) throw 0;
    const t = await fetch(u, { headers: H });
    if (!t.ok || !(await t.text()).startsWith("#EXTM3U")) throw 0;
    return u;
  };
  // Ask every server at once; the first that really answers wins.
  return Promise.any(["peak", "dcloud", "tik", "v6:English"].map(one)).catch(() => null);
}
