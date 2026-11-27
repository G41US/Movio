// Builds Movio's Live TV lists from iptv-org (free-to-air channels), keeping only streams that answer.
// Output: web/live/index.json (countries + categories), web/live/c/<cc>.json, web/live/cat/<id>.json
import { mkdir, writeFile, rm } from "node:fs/promises";

const API = "https://iptv-org.github.io/api";
const get = (f) => fetch(`${API}/${f}`).then((r) => r.json());
const [streams, channels, countries, categories, logos, feeds] = await Promise.all(
  ["streams.json", "channels.json", "countries.json", "categories.json", "logos.json", "feeds.json"].map(get));

const ch = new Map(channels.map((c) => [c.id, c]));
const logo = new Map();
for (const l of logos) if (!l.feed && !logo.has(l.channel)) logo.set(l.channel, l.url);
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

// One entry per channel: the best stream that works.
const cand = new Map();
for (const s of streams) {
  if (!s.channel || !/^https?:\/\//.test(s.url)) continue;
  const c = ch.get(s.channel);
  if (!c || c.is_nsfw || c.closed || c.replaced_by) continue;
  (cand.get(s.channel) || cand.set(s.channel, []).get(s.channel)).push(s);
}
const q = (s) => parseInt(s.quality) || 0;
for (const l of cand.values()) l.sort((a, b) => (b.url.startsWith("https") - a.url.startsWith("https")) || (q(b) - q(a)) || (a.labels.length - b.labels.length));

// Reads at most 64 KB (some "streams" are endless video files).
async function head(r) {
  const rd = r.body?.getReader(); if (!rd) return "";
  let out = "", n = 0;
  const dec = new TextDecoder();
  while (n < 65536) { const { done, value } = await rd.read(); if (done) break; n += value.length; out += dec.decode(value, { stream: true }); if (out.length > 20000) break; }
  rd.cancel().catch(() => {});
  return out;
}
// Each check gives up after 10 s, whatever it's waiting on.
const within = (p, ms) => Promise.race([p, new Promise((ok) => setTimeout(() => ok(false), ms))]);
const works = (s) => within(check(s), 10000);
async function check(s) {
  const h = { "User-Agent": s.user_agent || UA, ...(s.referrer ? { Referer: s.referrer } : {}) };
  try {
    const r = await fetch(s.url, { headers: h, signal: AbortSignal.timeout(8000), redirect: "follow" });
    if (!r.ok) return false;
    const t = await head(r);
    if (!t.includes("#EXTM3U")) return false;
    // Master playlist: make sure its first variant answers too.
    if (t.includes("#EXT-X-STREAM-INF")) {
      const v = t.split("\n").map((x) => x.trim()).find((x) => x && !x.startsWith("#"));
      if (!v) return false;
      const r2 = await fetch(new URL(v, r.url), { headers: h, signal: AbortSignal.timeout(8000) });
      return r2.ok && (await head(r2)).includes("#EXT");
    }
    return t.includes("#EXTINF") || t.includes("#EXT-X-");
  } catch { return false; }
}

const ids = [...cand.keys()];
const ok = [];
let i = 0, done = 0;
async function worker() {
  while (i < ids.length) {
    const id = ids[i++];
    for (const s of cand.get(id).slice(0, 3)) {
      if (await works(s)) { ok.push([id, s]); break; }
    }
    if (++done % 1000 === 0) console.log(`${done}/${ids.length} checked, ${ok.length} working`);
  }
}
await Promise.all(Array.from({ length: 250 }, worker));
console.log(`working channels: ${ok.length} of ${ids.length}`);

const out = (id, s) => {
  const c = ch.get(id);
  const o = { id, n: c.name, l: logo.get(id) || null, u: s.url, c: c.categories || [], cc: c.country };
  if (s.referrer) o.r = s.referrer;
  if (s.user_agent) o.a = s.user_agent;
  if (s.labels?.includes("Not 24/7")) o.p = 1;
  if (s.labels?.includes("Geo-blocked")) o.g = 1;
  return o;
};
const all = ok.map(([id, s]) => out(id, s)).sort((a, b) => a.n.localeCompare(b.n));
await rm("web/live", { recursive: true, force: true });
await mkdir("web/live/c", { recursive: true });
await mkdir("web/live/cat", { recursive: true });
const byC = {}, byCat = {};
for (const x of all) {
  (byC[x.cc] ||= []).push(x);
  for (const k of x.c.length ? x.c : ["general"]) (byCat[k] ||= []).push(x);
}
for (const [k, l] of Object.entries(byC)) await writeFile(`web/live/c/${k.toLowerCase()}.json`, JSON.stringify(l));
for (const [k, l] of Object.entries(byCat)) await writeFile(`web/live/cat/${k}.json`, JSON.stringify(l));
const cn = new Map(countries.map((c) => [c.code, c]));
await writeFile("web/live/index.json", JSON.stringify({
  at: Date.now(),
  total: all.length,
  countries: Object.entries(byC).map(([k, l]) => ({ code: k, name: cn.get(k)?.name || k, n: l.length })).sort((a, b) => b.n - a.n),
  categories: categories.filter((c) => byCat[c.id] && c.id !== "xxx").map((c) => ({ id: c.id, name: c.name, n: byCat[c.id].length })).sort((a, b) => b.n - a.n),
}));
console.log("lists written"); process.exit(0);
