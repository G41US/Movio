"use strict";
// Movio for the web (iPhone: Safari → Share → Add to Home Screen).

const HELPER = ((window.MOVIO && window.MOVIO.helper) || "").replace(/\/$/, "");
const view = document.getElementById("view");
const img = (p, s = "w500") => (p ? `https://image.tmdb.org/t/p/${s}${p}` : "");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ICON = {
  play: '<svg viewBox="0 0 24 24"><path d="M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z"/></svg>',
  plus: '<svg viewBox="0 0 24 24"><path d="M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7z"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="m9.5 16.6-4.6-4.6-1.4 1.4 6 6 11-11-1.4-1.4z"/></svg>',
  heart: '<svg viewBox="0 0 24 24" class="line"><path d="M12 20.3s-7.8-4.6-7.8-10.4A4.4 4.4 0 0 1 12 7.2a4.4 4.4 0 0 1 7.8 2.7c0 5.8-7.8 10.4-7.8 10.4z"/></svg>',
  share: '<svg viewBox="0 0 24 24" class="line"><path d="M12 3.5v11M8 7.3l4-3.8 4 3.8M7.5 10.5H6.8a1.8 1.8 0 0 0-1.8 1.8v6.4a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8v-6.4a1.8 1.8 0 0 0-1.8-1.8h-.7"/></svg>',
  down: '<svg viewBox="0 0 24 24" class="line"><path d="M12 4v11M7.5 10.8 12 15.3l4.5-4.5M5 19.5h14"/></svg>',
  info: '<svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1 8v7h2v-7zm0-3v2h2V7z"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>',
};

// ---------- Saved on this phone ----------
const db = {
  get(k, d) { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
let myList = db.get("myList", []);
let recent = db.get("recent", {});
let positions = db.get("positions", {});
const titles = {};
const key = (t) => `${t.kind}:${t.id}`;
const posKey = (t, s, e) => (t.kind === "tv" ? `${key(t)}:${s}:${e}` : key(t));
const remember = (t) => { titles[key(t)] = t; return t; };
const inList = (t) => myList.some((x) => key(x) === key(t));
function toggleList(t) {
  const had = inList(t);
  myList = had ? myList.filter((x) => key(x) !== key(t)) : [t, ...myList];
  db.set("myList", myList);
  Sync.mark("l:" + key(t), had);
  toast(inList(t) ? "Added to My List" : "Removed from My List");
}
// ---------- Likes, shares, and what people watch (counts only) ----------
let likes = db.get("likes", {});
const liked = (t) => !!likes[key(t)];
function stat(ev, t, genres = []) {
  fetch(`${HELPER}/stat`, { method: "POST", body: JSON.stringify({ ev, kind: t.kind, id: t.id, name: t.name, poster: t.poster, genres }), keepalive: true }).catch(() => {});
}
function toggleLike(t, genres) {
  const on = !liked(t);
  if (on) likes[key(t)] = { at: Date.now(), t }; else delete likes[key(t)];
  db.set("likes", likes);
  stat(on ? "like" : "unlike", t, genres);
  toast(I.t(on ? "Liked" : "Removed from Liked"));
  return on;
}
const shareUrl = (t) => location.origin + location.pathname.replace(/index\.html$/, "") + href(t);
async function shareTitle(t) {
  const url = shareUrl(t);
  try {
    if (navigator.share) return await navigator.share({ title: t.name, text: I.f("Watch {1} on Movio", t.name), url });
  } catch (e) { if (e?.name === "AbortError") return; }
  try { await navigator.clipboard.writeText(url); toast(I.t("Link copied")); } catch { prompt(I.t("Copy this link"), url); }
}
const APK = "https://github.com/G41US/Movio/releases/latest";
function downloadNote() {
  const iphone = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  sheet(I.t("Watch offline"), [
    { label: I.t("Browsers can't keep films for offline. The Movio app can."), on: false, fn: () => {} },
    iphone ? { label: I.t("Get Movio for iPhone"), on: true, fn: () => window.open("https://github.com/G41US/Movio/releases/tag/ios-source", "_blank") }
           : { label: I.t("Get Movio for Android"), on: true, fn: () => window.open(APK, "_blank") },
  ]);
}

function savePos(t, s, e, pos, dur) {
  if (!(dur > 0)) return;
  positions[posKey(t, s, e)] = [pos, dur];
  db.set("positions", positions);
  Sync.mark("p:" + posKey(t, s, e), false, true);
  if (pos > dur - 90 && t.kind === "movie") { delete recent[key(t)]; Sync.mark("c:" + key(t), true); }
  else { recent[key(t)] = { t, s, e, pos, dur, at: Date.now() }; Sync.mark("c:" + key(t), false, true); }
  db.set("recent", recent);
}

// ---------- Sync between devices (a short link code, kept by the helper) ----------
const Sync = (() => {
  let ledger = db.get("syncAt", {}); // key -> time of last change (negative = removed)
  let timer = null, busy = false;
  const code = () => db.get("syncCode", "");
  const strip = (t) => ({ id: t.id, kind: t.kind, name: t.name, overview: (t.overview || "").slice(0, 400), poster: t.poster || null, backdrop: t.backdrop || null, year: t.year || null, rating: t.rating || 0 });
  const at = (k) => Math.abs(ledger[k] || 0);
  function mark(k, removed, quiet) {
    ledger[k] = (removed ? -1 : 1) * Date.now();
    db.set("syncAt", ledger);
    if (!code()) return;
    clearTimeout(timer);
    timer = setTimeout(push, quiet ? 15000 : 1500);
  }
  function state() {
    const st = { list: {}, cont: {}, pos: {} };
    myList.forEach((t, i) => { st.list[key(t)] = { at: at("l:" + key(t)) || 1000 - i, t: strip(t) }; });
    Object.values(recent).forEach((r) => { st.cont[key(r.t)] = { at: Math.max(at("c:" + key(r.t)), r.at || 0), t: strip(r.t), s: r.s, e: r.e }; });
    for (const [k, [p, d]] of Object.entries(positions)) st.pos[k] = { at: at("p:" + k) || 1, p: p > d - 90 ? -1 : p, d };
    for (const [k, v] of Object.entries(ledger)) {
      if (v >= 0) continue;
      const [sec, rest] = [k[0], k.slice(2)];
      if (sec === "l" && !st.list[rest]) st.list[rest] = { at: -v, del: true };
      if (sec === "c" && !st.cont[rest]) st.cont[rest] = { at: -v, del: true };
    }
    if (I.name) st.prof = { at: +localStorage.getItem("p.nameAt") || 1, name: I.name };
    return st;
  }
  function apply(st) {
    for (const [k, v] of Object.entries(st.list || {})) {
      if (v.at <= at("l:" + k)) continue;
      myList = myList.filter((x) => key(x) !== k);
      if (!v.del && v.t) myList.push(remember(v.t));
      ledger["l:" + k] = (v.del ? -1 : 1) * v.at;
    }
    myList.sort((a, b) => at("l:" + key(b)) - at("l:" + key(a)));
    for (const [k, v] of Object.entries(st.cont || {})) {
      if (v.at <= at("c:" + k)) continue;
      if (v.del) delete recent[k];
      else if (v.t) {
        const pk = v.t.kind === "tv" ? `${k}:${v.s}:${v.e}` : k;
        const pp = st.pos?.[pk];
        recent[k] = { t: remember(v.t), s: v.s || 1, e: v.e || 1, pos: pp ? (pp.p < 0 ? pp.d : pp.p) : 0, dur: pp?.d || 0, at: v.at };
      }
      ledger["c:" + k] = (v.del ? -1 : 1) * v.at;
    }
    for (const [k, v] of Object.entries(st.pos || {})) {
      if (v.at <= at("p:" + k)) continue;
      positions[k] = [v.p < 0 ? v.d : v.p, v.d];
      ledger["p:" + k] = v.at;
    }
    if (st.prof?.name && st.prof.at > (+localStorage.getItem("p.nameAt") || 0)) {
      I.name = st.prof.name; I.save(); localStorage.setItem("p.nameAt", st.prof.at);
    }
    db.set("myList", myList); db.set("recent", recent); db.set("positions", positions); db.set("syncAt", ledger);
  }
  async function push() {
    const c = code();
    if (!c || busy) return false;
    busy = true;
    try {
      const r = await fetch(`${HELPER}/sync/${c}`, { method: "PUT", body: JSON.stringify(state()) });
      const o = await r.json();
      if (o.ok && o.state) { apply(o.state); db.set("syncedAt", Date.now()); if (!P && (location.hash === "" || /^#\/(list)?$/.test(location.hash))) route(); return true; }
      if (r.status === 404) db.set("syncCode", "");
    } catch {} finally { busy = false; }
    return false;
  }
  async function create() {
    const o = await fetch(`${HELPER}/sync/new`, { method: "POST" }).then((r) => r.json()).catch(() => null);
    if (!o?.ok) return null;
    db.set("syncCode", o.code); await push(); return o.code;
  }
  async function join(c) {
    c = (c || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
    const r = await fetch(`${HELPER}/sync/${c}`).catch(() => null);
    if (!r || !r.ok) return false;
    db.set("syncCode", c); await push(); return true;
  }
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") push(); else if (timer) { clearTimeout(timer); push(); } });
  const last = () => db.get("syncedAt", 0);
  return { mark, push, create, join, code, last, off: () => db.set("syncCode", "") };
})();

/** The round profile button: language, linking devices, kids mode. */
function profileMenu() {
  const c = Sync.code();
  sheet(I.name || "Profile", [
    { label: I.t("Language & subtitles"), on: false, fn: () => I.edit() },
    { label: c ? I.f("Linked · code {1}", c) : I.t("Link devices"), on: !!c, fn: () => setTimeout(linkPanel, 50) },
    { label: I.t("Kids mode") + " · " + I.t(kids() ? "On" : "Off"), on: kids(), fn: () => { db.set("kids", !kids()); toast(I.t(kids() ? "Kids mode on" : "Kids mode off")); route(); } },
  ]);
}

/** Link devices: show this device's code, or type one from another device.
 *  opts.setup: opened from first-time setup (only "enter a code"; onLinked gets the welcome-back details). */
const LINK_ICON = '<svg viewBox="0 0 24 24"><rect x="2.5" y="4" width="13" height="9.5" rx="2.2"/><path d="M6 17h6"/><rect x="15" y="8" width="6.5" height="12" rx="1.8"/><path d="M17.6 17.4h1.3"/></svg>';
function linkPanel(opts = {}) {
  const box = document.createElement("div");
  box.className = "setup link";
  document.body.append(box);
  requestAnimationFrame(() => box.classList.add("in"));
  const close = () => { box.classList.add("bye"); setTimeout(() => box.remove(), 300); opts.onClose?.(); };
  let typed = "", busy = "", err = "", done = null;
  const ago = (t) => {
    if (!t) return I.t("Not synced yet");
    const m = Math.round((Date.now() - t) / 60000);
    return I.f("Last synced {1}", m < 1 ? I.t("just now") : m < 60 ? I.f("{1} min ago", m) : new Date(t).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }));
  };
  const tiles = (v, live) => `<div class="otp ${live ? "live" : ""}" data-raw>${[0, 1, 2, 3, 4, 5].map((i) =>
    `<span class="${live && i === Math.min(v.length, 5) ? "cur" : ""} ${v[i] ? "has" : ""}">${esc(v[i] || "")}</span>`).join("")}
    ${live ? `<input id="lcode" maxlength="6" inputmode="text" autocapitalize="characters" autocomplete="one-time-code" spellcheck="false" aria-label="${I.t("Type the code")}" value="${esc(v)}">` : ""}</div>`;
  const btn = (id, cls, label, wait) => `<button class="btn ${cls}" id="${id}" ${busy ? "disabled" : ""}>${busy === id ? `<i class="bspin"></i>${I.t(wait)}` : I.t(label)}</button>`;
  const draw = () => {
    const c = Sync.code();
    let body;
    if (done) body = `<div class="lorb ok"><svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></div>
      <h1 data-raw>${done.name ? esc(I.f("Welcome back, {1}", done.name)) : I.t("You're linked")}</h1>
      <p>${I.t("Your list and progress are here.")}</p>
      <div class="lstats"><div><b>${done.list}</b><span>${I.t("My List")}</span></div><div><b>${done.cont}</b><span>${I.t("Continue watching")}</span></div></div>
      <div class="sbtns"><button class="btn white wide" id="ldone">${I.t("Continue")}</button></div>`;
    else if (c && !opts.setup) body = `<div class="lorb">${LINK_ICON}</div>
      <h1>${I.t("Link devices")}</h1><p>${I.t("My List, Continue watching and where you stopped follow you on every device with the same code.")}</p>
      <div class="lpanel">
        <div class="lrow"><span class="lstat"><i></i>${I.t("Linked")}</span><span class="lago">${esc(ago(Sync.last()))}</span></div>
        <div class="lcap with">${I.t("This device's code")}<button class="lcopy" id="lcopy">${I.t("Copy")}</button></div>
        ${tiles(c)}
        <div class="lhint">${I.t("On your other device, open Link devices and type this code.")}</div>
      </div>
      <div class="sbtns">${btn("lun", "glass", "Unlink")}${btn("lnow", "white wide", "Sync now", "Syncing…")}</div>`;
    else body = `<div class="lorb">${LINK_ICON}</div>
      <h1>${I.t(opts.setup ? "Link with a code" : "Link devices")}</h1>
      <p>${I.t(opts.setup ? "Enter the code shown on your other device." : "My List, Continue watching and where you stopped follow you on every device with the same code.")}</p>
      <div class="lpanel">
        <div class="lcap">${I.t("Type the code")}</div>
        ${tiles(typed, true)}
        ${err ? `<div class="lerr">${esc(err)}</div>` : ""}
        <div class="sbtns">${btn("ljoin", "white wide", "Link", "Linking…")}</div>
      </div>
      ${opts.setup ? "" : `<div class="lor"><span>${I.t("or")}</span></div>
      <div class="lpanel lnew"><div><b>${I.t("No code yet?")}</b><small>${I.t("Start a new link from this device.")}</small></div>${btn("lnew", "glass", "Get a code", "Checking…")}</div>`}`;
    box.innerHTML = `<div class="sin"><div class="shead"><b class="sbrand">Movio</b><button class="icon lx" id="lx" aria-label="Close">${opts.setup && !done ? ICON.back || ICON.close : ICON.close}</button></div>${body}</div>`;
    const $ = (id) => box.querySelector("#" + id);
    $("lx").onclick = done ? () => $("ldone").click() : close;
    const inp = $("lcode");
    if (inp) {
      inp.oninput = () => {
        typed = inp.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 6);
        err = "";
        const spans = box.querySelectorAll(".otp span");
        spans.forEach((sp, i) => { sp.textContent = typed[i] || ""; sp.classList.toggle("has", !!typed[i]); sp.classList.toggle("cur", i === Math.min(typed.length, 5)); });
        box.querySelector(".lerr")?.remove();
        if (typed.length === 6) join();
      };
      inp.onkeydown = (e) => { if (e.key === "Enter") join(); };
      if (!matchMedia("(pointer: coarse)").matches) inp.focus();
    }
    const join = async () => {
      if (busy) return;
      if (typed.length < 6) { err = I.t("Type the code"); return draw(); }
      busy = "ljoin"; draw();
      const ok = await Sync.join(typed);
      busy = "";
      if (!ok) { err = I.t("That code wasn't found. Check it and try again."); draw(); box.querySelector(".otp")?.classList.add("shake"); return; }
      route();
      if (opts.setup) { done = { name: I.name, list: myList.length, cont: Object.keys(recent).length }; draw(); }
      else { toast(I.t("Linked")); draw(); }
    };
    $("ljoin") && ($("ljoin").onclick = join);
    $("ldone") && ($("ldone").onclick = () => { box.classList.add("bye"); setTimeout(() => box.remove(), 300); opts.onLinked?.(); });
    $("lnew") && ($("lnew").onclick = async () => {
      busy = "lnew"; err = ""; draw();
      const c2 = await Sync.create(); busy = "";
      if (!c2) err = I.t("Couldn't reach the server. Try again.");
      draw();
    });
    $("lcopy") && ($("lcopy").onclick = () => { navigator.clipboard?.writeText(c).then(() => toast(I.t("Copied"))); });
    $("lun") && ($("lun").onclick = () => { Sync.off(); typed = ""; draw(); });
    $("lnow") && ($("lnow").onclick = async () => { busy = "lnow"; draw(); const ok = await Sync.push(); busy = ""; draw(); toast(I.t(ok ? "Synced" : "Couldn't sync")); });
  };
  draw();
}

// ---------- Kids mode: family and animation titles only ----------
const KIDS_GENRES = new Set([16, 10751, 10762]);
const kids = () => db.get("kids", false);
const kidsOk = (o) => !kids() || (!o.adult && (o.genre_ids || (o.genres || []).map((g) => g.id)).some((g) => KIDS_GENRES.has(g)));

// ---------- Faster starts: remember which sources work ----------
const srcStats = db.get("srcStats", {});
function srcResult(name, ok) {
  const s = (srcStats[name] ||= { ok: 0, bad: 0 });
  ok ? s.ok++ : s.bad++;
  if (s.ok + s.bad > 40) { s.ok = Math.round(s.ok / 2); s.bad = Math.round(s.bad / 2); }
  db.set("srcStats", srcStats);
}
/** Sources that have been working lately move up; the one that played this title last goes first. */
function rankSources(order, t) {
  const score = (n) => { const s = srcStats[n]; return s ? (s.ok + 1) / (s.ok + s.bad + 2) : 0.5; };
  const ranked = [...order].sort((a, b) => score(b) - score(a) || order.indexOf(a) - order.indexOf(b));
  const last = localStorage.getItem(`ok:${key(t)}`);
  return last && ranked.includes(last) ? [last, ...ranked.filter((n) => n !== last)] : ranked;
}
const continueWatching = () => Object.values(recent).sort((a, b) => b.at - a.at);

function toast(msg) {
  document.querySelector(".toast")?.remove();
  const d = document.createElement("div");
  d.className = "toast"; d.textContent = msg;
  (document.querySelector(".player") || document.body).appendChild(d);
  setTimeout(() => d.remove(), 1800);
}

// ---------- Catalogue ----------
const cache = new Map();
function tmdb(path, params = {}) {
  // Titles and descriptions in the viewer's language (TMDB falls back to English where it has none).
  const q = new URLSearchParams({ language: I.tmdb(), ...params }).toString();
  const u = `${HELPER}/tmdb/${path}${q ? "?" + q : ""}`;
  if (!cache.has(u)) {
    const p = fetch(u).then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); });
    cache.set(u, p);
    p.catch(() => cache.delete(u));
  }
  return cache.get(u);
}
function parse(o, kind) {
  kind = kind || o.media_type;
  if (kind !== "movie" && kind !== "tv") return null;
  const name = o.title || o.name;
  if (!name) return null;
  const d = o.release_date || o.first_air_date || "";
  return remember({ id: o.id, kind, name, overview: o.overview || "", poster: o.poster_path, backdrop: o.backdrop_path, year: d.slice(0, 4) || null, rating: o.vote_average || 0 });
}
async function list(path, kind, params) {
  const o = await tmdb(path, params);
  return (o.results || []).filter(kidsOk).map((x) => parse(x, kind)).filter((t) => t && t.poster);
}
async function rows(specs, ranked) {
  const res = await Promise.all(specs.map(([, f]) => f().catch(() => [])));
  return specs.map(([n], i) => ({ name: n, items: res[i], ranked: n === ranked })).filter((r) => r.items.length);
}
// Kids mode: rows made for families instead of the usual charts.
const kidsRows = () => rows([
  ["Family movies", () => list("discover/movie", "movie", { with_genres: "10751", sort_by: "popularity.desc", certification_country: "US", "certification.lte": "PG" })],
  ["Animated movies", () => list("discover/movie", "movie", { with_genres: "16", sort_by: "popularity.desc", certification_country: "US", "certification.lte": "PG" })],
  ["Kids shows", () => list("discover/tv", "tv", { with_genres: "10762", sort_by: "popularity.desc" })],
  ["Animated shows", () => list("discover/tv", "tv", { with_genres: "16,10751", sort_by: "popularity.desc" })],
  ["Top rated family movies", () => list("discover/movie", "movie", { with_genres: "10751", sort_by: "vote_average.desc", "vote_count.gte": "500", certification_country: "US", "certification.lte": "PG" })],
], "");
const homeRows = () => kids() ? kidsRows() : rows([
  ["Top 10 today", async () => (await list("trending/all/day")).slice(0, 10)],
  ["New in cinemas", () => list("movie/now_playing", "movie")],
  ["Trending this week", () => list("trending/all/week")],
  ["Popular movies", () => list("movie/popular", "movie")],
  ["Popular shows", () => list("tv/popular", "tv")],
  ["Top rated movies", () => list("movie/top_rated", "movie")],
  ["Top rated shows", () => list("tv/top_rated", "tv")],
], "Top 10 today");
const browseRows = (kind) => kids() ? kidsRows().then((r) => r.filter((x) => x.items.some((t) => t.kind === kind))) : kind === "movie"
  ? rows([
    ["Top 10 movies", async () => (await list("trending/movie/day", "movie")).slice(0, 10)],
    ["Now playing", () => list("movie/now_playing", "movie")],
    ["Trending movies", () => list("trending/movie/week", "movie")],
    ["Popular", () => list("movie/popular", "movie")],
    ["Coming soon", () => list("movie/upcoming", "movie")],
    ["Top rated", () => list("movie/top_rated", "movie")],
  ], "Top 10 movies")
  : rows([
    ["Top 10 shows", async () => (await list("trending/tv/day", "tv")).slice(0, 10)],
    ["Airing today", () => list("tv/airing_today", "tv")],
    ["Trending shows", () => list("trending/tv/week", "tv")],
    ["Popular", () => list("tv/popular", "tv")],
    ["On the air", () => list("tv/on_the_air", "tv")],
    ["Top rated", () => list("tv/top_rated", "tv")],
  ], "Top 10 shows");

// ---------- Pieces ----------
const href = (t) => `#/title/${t.kind}/${t.id}`;
const playHref = (t, s = 1, e = 1) => `#/play/${t.kind}/${t.id}/${s}/${e}`;
const poster = (t) => `<a class="card" href="${href(remember(t))}"><img class="poster" loading="lazy" src="${img(t.poster, "w342")}" alt=""></a>`;
const ranked = (t, i) => `<a class="card ranked" href="${href(remember(t))}"><b>${i + 1}</b><img class="poster" loading="lazy" src="${img(t.poster, "w342")}" alt=""></a>`;
const rowHtml = (r) => `<section class="row"><h2>${esc(r.name)}</h2><div class="scroll">${r.items.map((t, i) => (r.ranked ? ranked(t, i) : poster(t))).join("")}</div></section>`;
const loading = () => `<div class="loading"><div class="spin"></div></div>`;
function left(p) {
  const m = Math.max(1, Math.round((p.dur - p.pos) / 60));
  const time = m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m left` : `${m}m left`;
  return p.t.kind === "tv" ? `S${p.s} · E${p.e}  ·  ${time}` : time;
}
const contCard = (p) => `<a class="card cont" href="${playHref(remember(p.t), p.s, p.e)}"><img loading="lazy" src="${img(p.t.backdrop || p.t.poster, "w500")}" alt=""><div class="shade"></div>
  <div class="t"><div class="n">${esc(p.t.name)}</div><div class="s">${esc(left(p))}</div><div class="bar"><i style="width:${Math.min(100, (p.pos / p.dur) * 100) || 0}%"></i></div></div></a>`;
function playTarget(t) {
  const r = recent[key(t)];
  return t.kind === "tv" && r ? playHref(t, r.s, r.e) : playHref(t);
}

// ---------- Screens ----------
// ---------- Live TV: free-to-air channels (iptv-org), checked every 12 hours ----------
const LIVE = "live";
const liveGet = (f) => fetch(`${LIVE}/${f}`, { cache: "no-cache" }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
let liveIndex = null, liveFav = db.get("liveFav", {});
const myCountry = () => {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "";
  const byTz = { "Africa/Lagos": "NG", "Africa/Accra": "GH", "Africa/Nairobi": "KE", "Africa/Johannesburg": "ZA", "Europe/London": "UK", "Europe/Paris": "FR" };
  return byTz[tz] || (navigator.language.split("-")[1] || "US").toUpperCase();
};
const flag = (cc) => `https://flagcdn.com/w40/${(cc === "UK" ? "gb" : cc).toLowerCase()}.png`;
const LIVE_CATS = ["news", "sports", "movies", "entertainment", "music", "kids", "documentary", "religious", "general"];
async function liveView() {
  liveIndex ||= await liveGet("index.json");
  if (!liveIndex) { view.innerHTML = `<div class="empty pad-top"><b>${I.t("Live TV is getting ready")}</b><span class="muted">${I.t("Try again in a few minutes.")}</span></div>`; return; }
  const st = { cc: sessionStorage.getItem("liveCc2") || "ALL", cat: sessionStorage.getItem("liveCat") || "", q: "", limit: 120 };
  if (!liveIndex.countries.some((c) => c.code === st.cc) && st.cc !== "ALL") st.cc = "US";
  const cName = (cc) => cc === "ALL" ? I.t("Worldwide") : liveIndex.countries.find((c) => c.code === cc)?.name || cc;
  // Quick picks in alphabetical order (the full list is under "All countries").
  const quick = () => [...new Set([st.cc, myCountry(), "NG", "GH", "KE", "ZA", "US", "UK", "FR"])].filter((c) => c !== "ALL" && liveIndex.countries.some((x) => x.code === c))
    .sort((a, b) => cName(a).localeCompare(cName(b)));
  let top = quick();
  view.innerHTML = `<div class="pad-top live">
    <div class="lhead"><h1 class="title">${I.t("Live TV")}</h1><span class="lcount">${I.f("{1} channels", liveIndex.total)}</span></div>
    <label class="searchbox"><svg viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg><input id="lq" type="search" placeholder="${I.t("Find a channel")}" autocomplete="off"></label>
    <div class="filters" id="lcc"></div><div class="filters" id="lcat"></div>
    <div id="lfav"></div><div class="lgrid" id="lgrid"></div></div>`;
  let list = [];
  const chips = () => {
    view.querySelector("#lcc").innerHTML = `<button class="chip ${st.cc === "ALL" ? "on" : ""}" data-cc="ALL">${I.t("All")}</button>`
      + top.map((c) => `<button class="chip cc ${st.cc === c ? "on" : ""}" data-cc="${c}"><img src="${flag(c)}" alt="">${esc(cName(c))}</button>`).join("")
      + `<button class="chip" id="lmore">${I.t("All countries")} ›</button>`;
    view.querySelector("#lcat").innerHTML = [["", "All"], ...LIVE_CATS.map((c) => [c, c[0].toUpperCase() + c.slice(1)])]
      .filter(([c]) => !c || liveIndex.categories.some((x) => x.id === c)).map(([c, n]) => `<button class="chip ${st.cat === c ? "on" : ""}" data-cat="${c}">${I.t(n)}</button>`).join("");
    view.querySelectorAll("[data-cc]").forEach((b) => (b.onclick = () => { st.cc = b.dataset.cc; sessionStorage.setItem("liveCc2", st.cc); chips(); load(); }));
    view.querySelectorAll("[data-cat]").forEach((b) => (b.onclick = () => { st.cat = b.dataset.cat; sessionStorage.setItem("liveCat", st.cat); st.limit = 120; chips(); draw(); }));
    const sorted = [...liveIndex.countries].sort((a, b) => a.name.localeCompare(b.name));
    view.querySelector("#lmore").onclick = () => sheet(I.t("Country"), sorted.map((c) => ({ label: `${c.name} · ${c.n}`, on: c.code === st.cc, fn: () => {
      st.cc = c.code; sessionStorage.setItem("liveCc2", st.cc); top = quick(); chips(); load(); } })));
  };
  const tile = (x, i) => `<button class="ltile" data-i="${i}"><div class="lpic">${x.l ? `<img src="${esc(x.l)}" alt="" loading="lazy">` : `<b>${esc(x.n.slice(0, 2))}</b>`}<span class="lbadge"><i></i>LIVE</span>${liveFav[x.id] ? `<span class="lstar">★</span>` : ""}</div><span class="lname" data-raw>${esc(x.n)}</span><small>${I.t(cap(x.c[0] || "general"))}</small></button>`;
  const cap = (s) => s[0].toUpperCase() + s.slice(1);
  const draw = () => {
    const q = st.q.toLowerCase();
    const shown = list.filter((x) => (!st.cat || x.c.includes(st.cat) || (st.cat === "general" && !x.c.length)) && (!q || x.n.toLowerCase().includes(q)));
    const favs = Object.values(liveFav).filter((x) => x && x.id);
    const fv = view.querySelector("#lfav");
    fv.innerHTML = favs.length && !q ? `<section class="row"><h2>${I.t("Favourites")}</h2><div class="scroll lfavs">${favs.map((x, i) => `<button class="ltile" data-f="${i}"><div class="lpic">${x.l ? `<img src="${esc(x.l)}" alt="">` : `<b>${esc(x.n.slice(0, 2))}</b>`}</div><span class="lname" data-raw>${esc(x.n)}</span></button>`).join("")}</div></section>` : "";
    fv.querySelectorAll("[data-f]").forEach((b) => (b.onclick = () => livePlay(favs, +b.dataset.f)));
    const g = view.querySelector("#lgrid");
    // Big lists (All has thousands) come in steps.
    g.innerHTML = shown.length ? shown.slice(0, st.limit).map(tile).join("") + (shown.length > st.limit ? `<div class="more" style="grid-column:1/-1"><button class="btn glass" id="lmorech">${I.f("Show more ({1} left)", shown.length - st.limit)}</button></div>` : "")
      : `<div class="empty" style="grid-column:1/-1"><b>${I.t("No channels here")}</b><span class="muted">${I.t("Try another country or category.")}</span></div>`;
    g.querySelector("#lmorech")?.addEventListener("click", () => { st.limit += 120; draw(); });
    g.querySelectorAll("[data-i]").forEach((b) => {
      b.onclick = () => livePlay(shown, +b.dataset.i);
      b.oncontextmenu = (e) => { e.preventDefault(); toggleFav(shown[+b.dataset.i]); draw(); };
    });
  };
  const load = async () => {
    view.querySelector("#lgrid").innerHTML = loading();
    st.limit = 120;
    const f = st.cc === "ALL" ? "all.json" : `c/${st.cc.toLowerCase()}.json`;
    list = (await liveGet(f)) || [];
    draw();
  };
  view.querySelector("#lq").addEventListener("input", (e) => { st.q = e.target.value.trim(); st.limit = 120; draw(); });
  chips(); load();
}
function toggleFav(x) {
  if (liveFav[x.id]) delete liveFav[x.id]; else liveFav[x.id] = x;
  db.set("liveFav", liveFav);
  toast(I.t(liveFav[x.id] ? "Added to Favourites" : "Removed from Favourites"));
}

/** The live player: one channel, with the next / previous channel a tap (or ↑ ↓) away. */
let LP = null;
function closeLive() {
  if (!LP) return;
  LP.hls?.destroy(); LP.el.remove(); document.removeEventListener("keydown", LP.keys, true);
  document.body.classList.remove("playing"); LP = null;
}
function livePlay(list, i) {
  closeLive();
  const el = document.createElement("div");
  el.className = "lplayer";
  document.body.append(el); document.body.classList.add("playing");
  LP = { list, i, el };
  const me = LP;
  const tune = async () => {
    const x = me.list[me.i];
    me.off = false;
    me.hls?.destroy(); me.hls = null;
    el.innerHTML = `<video playsinline autoplay></video>
      <div class="lbar top"><button class="icon" id="lpx">${ICON.back || ICON.close}</button>
        <div class="lnow">${x.l ? `<img src="${esc(x.l)}" alt="">` : ""}<div><b data-raw>${esc(x.n)}</b><span class="lbadge"><i></i>LIVE</span></div></div>
        <button class="icon" id="lpf" aria-label="Favourite">${liveFav[x.id] ? "★" : "☆"}</button></div>
      <div class="lmsg" id="lmsg"><div class="spin"></div><span>${I.t("Tuning in…")}</span></div>
      <div class="lbar bottom"><button class="lnav" id="lprev">‹ ${I.t("Previous")}</button><button class="icon" id="lpp">${ICON.pause || "❚❚"}</button><button class="icon" id="lfs">⛶</button><button class="lnav" id="lnext">${I.t("Next")} ›</button></div>`;
    const v = el.querySelector("video");
    const msg = el.querySelector("#lmsg");
    el.querySelector("#lpx").onclick = closeLive;
    el.querySelector("#lprev").onclick = () => { me.i = (me.i - 1 + me.list.length) % me.list.length; tune(); };
    el.querySelector("#lnext").onclick = () => { me.i = (me.i + 1) % me.list.length; tune(); };
    el.querySelector("#lpf").onclick = (e) => { toggleFav(x); e.currentTarget.textContent = liveFav[x.id] ? "★" : "☆"; };
    el.querySelector("#lpp").onclick = () => (v.paused ? v.play() : v.pause());
    el.querySelector("#lfs").onclick = () => (document.fullscreenElement ? document.exitFullscreen() : (el.requestFullscreen?.() || v.webkitEnterFullscreen?.()));
    v.onplaying = () => { msg.style.display = "none"; el.querySelector("#lpp").innerHTML = ICON.pause || "❚❚"; };
    v.onpause = () => { el.querySelector("#lpp").innerHTML = ICON.play; };
    const offline = () => { if (me !== LP || me.list[me.i] !== x) return; me.off = true; el.classList.remove("idle"); msg.style.display = ""; msg.innerHTML = `<b>${I.t("This channel is off air right now")}</b><button class="btn white" id="lskip">${I.t("Next channel")}</button>`; msg.querySelector("#lskip").onclick = () => el.querySelector("#lnext").click(); };
    // Straight from the channel when the browser allows it; otherwise through Movio's helper.
    const viaHelper = async () => {
      const o = await fetch(`${HELPER}/live?u=${encodeURIComponent(x.u)}&r=${encodeURIComponent(x.r || "")}&a=${encodeURIComponent(x.a || "")}`).then((r) => r.json()).catch(() => null);
      return o?.ok ? o.url : null;
    };
    const direct = x.u.startsWith("https") && !x.r && !x.a;
    const attempt = async (src, then) => {
      const native = v.canPlayType("application/vnd.apple.mpegurl");
      if (!native) await loadHls().catch(() => {});
      if (!native && window.Hls?.isSupported()) {
        const h = new Hls({ liveSyncDurationCount: 3, manifestLoadingMaxRetry: 1, levelLoadingMaxRetry: 1 });
        me.hls = h; h.loadSource(src); h.attachMedia(v);
        h.on(Hls.Events.MANIFEST_PARSED, () => v.play().catch(() => {}));
        h.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) { h.destroy(); if (me.hls === h) me.hls = null; then(); } });
      } else { v.src = src; v.onerror = then; v.play().catch(() => {}); }
    };
    const timer = setTimeout(() => { if (v.readyState < 2) offline(); }, 20000);
    v.addEventListener("playing", () => clearTimeout(timer), { once: true });
    const viaProxy = async () => { const u = await viaHelper(); if (u && me === LP && me.list[me.i] === x) attempt(u, offline); else offline(); };
    if (direct) attempt(x.u, viaProxy); else viaProxy();
  };
  me.keys = (e) => {
    if (me !== LP) return;
    if (e.key === "ArrowUp" || e.key === "PageUp") { e.preventDefault(); el.querySelector("#lprev").click(); }
    else if (e.key === "ArrowDown" || e.key === "PageDown") { e.preventDefault(); el.querySelector("#lnext").click(); }
    else if (e.key === "Escape") closeLive();
    else if (e.key === " ") { e.preventDefault(); el.querySelector("#lpp").click(); }
    else if (e.key.toLowerCase() === "f") el.querySelector("#lfs").click();
  };
  document.addEventListener("keydown", me.keys, true);
  // Controls fade after a few seconds without a touch.
  let idle;
  const wake = () => { el.classList.remove("idle"); clearTimeout(idle); idle = setTimeout(() => { if (!me.off && !el.querySelector("video")?.paused) el.classList.add("idle"); }, 3500); };
  el.addEventListener("pointermove", wake); el.addEventListener("pointerdown", wake); wake();
  tune();
}

// ---------- Platforms: what's on Netflix, Prime Video… (TMDB's US lists), all played in Movio ----------
const PLATFORMS = [
  { id: 8, name: "Netflix", logo: "/rK1KljqmbvO9HQa1PBFLILWah72.png", glow: "#E50914" },
  { id: 9, name: "Prime Video", logo: "/gMZdpavHmxFNnLpMHwVxfqeux2g.png", glow: "#1FA2FF" },
  { id: 337, name: "Disney+", logo: "/5eZ872CghnHFLB1j8grszbrx0dx.png", glow: "#3D7BFF" },
  { id: 1899, name: "HBO Max", logo: "/skypuy7SXuugIQeYg0IglmzoKaS.png", glow: "#8A3FFC" },
  { id: 350, name: "Apple TV", logo: "/v9SnyvYt0U3KGgBH3Sbh3z4yODe.png", glow: "#D9D9E3" },
  { id: 15, name: "Hulu", logo: "/44uAnmSqvA4yBOdbPWN8YgQHjWm.png", glow: "#1CE783" },
  { id: 386, name: "Peacock", logo: "/a1UIdq5BrkcAxnxcUhFsNbXnxeu.png", glow: "#FFC72C" },
  { id: 283, name: "Crunchyroll", logo: "/uFL3c4Cq8M6WoLymlC5Y8bmGytV.png", glow: "#F47521" },
];
const platRow = () => `<section class="row"><h2>${I.t("Platforms")}</h2><div class="scroll plats">${PLATFORMS.map((x) =>
   `<a class="plat" href="#/platform/${x.id}"><div class="ptile"><img class="fg" src="${img(x.logo, "w154")}" alt="" loading="lazy"></div><span data-raw>${esc(x.name)}</span></a>`).join("")}</div></section>`;
const PLAT_SORT = { popular: "popularity.desc", new: null, top: "vote_average.desc" };
async function platform(id) {
  const x = PLATFORMS.find((p) => p.id === +id) || PLATFORMS[0];
  const st = { kind: sessionStorage.getItem("platKind") || "tv", sort: sessionStorage.getItem("platSort") || "popular", page: 1, items: [] };
  view.innerHTML = `<div class="pad-top platpage">
    <div class="phead"><img src="${img(x.logo, "w154")}" alt=""><div><h1 class="title" data-raw>${esc(x.name)}</h1><small>${I.t("Everything here plays in Movio.")}</small></div></div>
    <div class="filters" id="pk"></div><div class="filters" id="ps"></div>
    <div class="grid" id="pg"></div><div class="more" id="pm"></div></div>`;
  const chips = () => {
    view.querySelector("#pk").innerHTML = [["tv", "Shows"], ["movie", "Movies"]].map(([k, l]) => `<button class="chip ${st.kind === k ? "on" : ""}" data-k="${k}">${I.t(l)}</button>`).join("");
    view.querySelector("#ps").innerHTML = [["popular", "Popular"], ["new", "New"], ["top", "Top rated"]].map(([k, l]) => `<button class="chip ${st.sort === k ? "on" : ""}" data-s="${k}">${I.t(l)}</button>`).join("");
    view.querySelectorAll("[data-k]").forEach((b) => (b.onclick = () => { st.kind = b.dataset.k; sessionStorage.setItem("platKind", st.kind); reset(); }));
    view.querySelectorAll("[data-s]").forEach((b) => (b.onclick = () => { st.sort = b.dataset.s; sessionStorage.setItem("platSort", st.sort); reset(); }));
  };
  const load = async () => {
    const prm = { with_watch_providers: x.id, watch_region: "US", page: st.page, include_adult: "false" };
    if (st.sort === "new") {
      prm.sort_by = st.kind === "movie" ? "primary_release_date.desc" : "first_air_date.desc";
      prm[st.kind === "movie" ? "primary_release_date.lte" : "first_air_date.lte"] = new Date().toISOString().slice(0, 10);
      prm["vote_count.gte"] = 10;
    } else { prm.sort_by = PLAT_SORT[st.sort]; prm["vote_count.gte"] = st.sort === "top" ? 300 : 20; }
    if (st.kind === "tv") prm.without_genres = "10763,10764,10767";
    const my = st;
    const o = await tmdb(`discover/${st.kind}`, prm).catch(() => ({}));
    if (my !== st && st.kind !== my.kind) return;
    const got = (o.results || []).filter(kidsOk).map((r) => parse(r, st.kind)).filter((t) => t && t.poster && !st.items.some((y) => key(y) === key(t)));
    st.items.push(...got);
    const g = view.querySelector("#pg");
    if (!g) return;
    g.insertAdjacentHTML("beforeend", got.map(poster).join(""));
    const more = view.querySelector("#pm");
    more.innerHTML = st.page < (o.total_pages || 1) && got.length ? `<button class="btn glass" id="pmore">${I.t("Show more")}</button>` : "";
    more.querySelector("#pmore")?.addEventListener("click", () => { st.page++; more.innerHTML = loading(); load(); });
    if (!st.items.length) g.innerHTML = `<div class="empty" style="grid-column:1/-1"><b>${I.t("No results")}</b></div>`;
  };
  const reset = () => { st.page = 1; st.items = []; view.querySelector("#pg").innerHTML = ""; view.querySelector("#pm").innerHTML = loading(); chips(); load(); };
  reset();
}

async function home() {
  view.innerHTML = loading();
  const rs = await homeRows();
  const trending = (rs.find((r) => r.name.startsWith("Trending")) || rs[0])?.items.filter((t) => t.backdrop && t.overview).slice(0, 6) || [];
  const cw = continueWatching();
  const initial = (I.name || "").trim().charAt(0).toUpperCase();
  view.innerHTML = `
    <button class="pfp ${initial ? "named" : ""}" onclick="profileMenu()" aria-label="Profile">${initial ? esc(initial) : '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7z"/></svg>'}</button>
    <section class="hero"><div class="slides">${trending.map((t) => `
      <div class="slide"><img src="${img(t.backdrop, "w1280")}" alt=""><div class="shade"></div>
        <div class="info"><h3>${esc(t.name)}</h3>
          <div class="meta">${[t.year, I.t(t.kind === "tv" ? "Series" : "Movie"), "★ " + t.rating.toFixed(1)].filter(Boolean).join("  ·  ")}</div>
          <p class="ov">${esc(t.overview)}</p>
          <div class="btns">
            <a class="btn white" href="${playTarget(t)}">${ICON.play}Play</a>
            <button class="btn glass" data-list="${key(t)}">${inList(t) ? ICON.check : ICON.plus}My List</button>
            <a class="btn glass round" href="${href(t)}">${ICON.info}</a>
          </div></div></div>`).join("")}</div>
      <div class="dots">${trending.map((_, i) => `<i class="${i ? "" : "on"}"></i>`).join("")}</div></section>
    <div class="rows">
      ${cw.length ? `<section class="row"><h2>Continue watching</h2><div class="scroll">${cw.map(contCard).join("")}</div></section>` : ""}
      ${kids() ? "" : platRow()}
      ${rs.map(rowHtml).join("")}
    </div>`;
  heroCarousel();
}

function heroCarousel() {
  const s = view.querySelector(".slides");
  if (!s) return;
  const dots = [...view.querySelectorAll(".dots i")];
  let i = 0, touched = 0;
  s.addEventListener("scroll", () => {
    i = Math.round(s.scrollLeft / s.clientWidth);
    dots.forEach((d, k) => d.classList.toggle("on", k === i));
  }, { passive: true });
  s.addEventListener("touchstart", () => (touched = Date.now()), { passive: true });
  const timer = setInterval(() => {
    if (!document.body.contains(s)) return clearInterval(timer);
    if (Date.now() - touched < 8000 || dots.length < 2) return;
    s.scrollTo({ left: ((i + 1) % dots.length) * s.clientWidth, behavior: "smooth" });
  }, 7000);
}

async function browse(kind) {
  view.innerHTML = `<div class="pad-top"><h1 class="title">${kind === "movie" ? "Movies" : "TV Shows"}</h1>${loading()}</div>`;
  const rs = await browseRows(kind);
  view.innerHTML = `<div class="pad-top"><h1 class="title">${kind === "movie" ? "Movies" : "TV Shows"}</h1><div class="rows">${rs.map(rowHtml).join("")}</div></div>`;
}

// ---------- Search: history, and filters by kind of film and year ----------
/** Movio's categories, by country and language (TMDB "discover"). kinds: which of movie/tv to look in. */
const CATS = [
  { id: "hollywood", name: "Hollywood", kinds: ["movie"], q: { with_origin_country: "US", with_original_language: "en" }, ok: (o) => o.original_language === "en" },
  { id: "nollywood", name: "Nollywood", kinds: ["movie", "tv"], q: { with_origin_country: "NG" }, ok: (o) => (o.origin_country || []).includes("NG"), few: true },
  { id: "kdrama", name: "K-drama", kinds: ["tv"], q: { with_original_language: "ko", with_genres: "18" }, ok: (o) => o.original_language === "ko" },
  { id: "anime", name: "Anime", kinds: ["tv", "movie"], q: { with_original_language: "ja", with_genres: "16" }, ok: (o) => o.original_language === "ja" && (o.genre_ids || []).includes(16) },
  { id: "chinese", name: "Chinese", kinds: ["tv", "movie"], q: { with_original_language: "zh" }, ok: (o) => ["zh", "cn"].includes(o.original_language) },
  { id: "bollywood", name: "Bollywood", kinds: ["movie"], q: { with_original_language: "hi" }, ok: (o) => o.original_language === "hi" },
  { id: "korean", name: "Korean movies", kinds: ["movie"], q: { with_original_language: "ko" }, ok: (o) => o.original_language === "ko" },
  { id: "turkish", name: "Turkish", kinds: ["tv"], q: { with_original_language: "tr" }, ok: (o) => o.original_language === "tr" },
  { id: "spanish", name: "Spanish", kinds: ["tv", "movie"], q: { with_original_language: "es" }, ok: (o) => o.original_language === "es" },
];
const thisYear = new Date().getFullYear();
const YEARS = [...Array(12)].map((_, i) => String(thisYear - i)).concat(["2010s", "2000s", "1990s", "Older"]);
const yearRange = (y) => !y ? null : y === "Older" ? [1900, 1989] : y.endsWith("s") ? [+y.slice(0, 4), +y.slice(0, 4) + 9] : [+y, +y];
const ofYear = (o, r) => { const y = +(o.release_date || o.first_air_date || "").slice(0, 4); return !r || (y >= r[0] && y <= r[1]); };
let lastQuery = "", filt = { cat: "", year: "" };
let history_ = db.get("searches", []);
function rememberSearch(text) {
  text = text.trim();
  if (text.length < 2) return;
  history_ = [text, ...history_.filter((x) => x.toLowerCase() !== text.toLowerCase())].slice(0, 12);
  db.set("searches", history_);
}
async function search() {
  view.innerHTML = `<div class="pad-top"><h1 class="title">Search</h1>
    <label class="searchbox"><svg viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg>
    <input id="q" type="search" placeholder="Movies, shows…" autocomplete="off" enterkeyhint="search" value="${esc(lastQuery)}"></label>
    <div class="filters" id="cats"></div><div class="filters" id="years"></div>
    <div id="recent"></div><div class="grid" id="results"></div></div>`;
  const q = document.getElementById("q");
  const out = document.getElementById("results");
  const chips = () => {
    document.getElementById("cats").innerHTML = [{ id: "", name: "All" }, ...CATS].map((c) => `<button class="chip ${filt.cat === c.id ? "on" : ""}" data-cat="${c.id}">${I.t(c.name)}</button>`).join("");
    document.getElementById("years").innerHTML = [`<button class="chip ${!filt.year ? "on" : ""}" data-year="">${I.t("Any year")}</button>`,
      ...YEARS.map((y) => `<button class="chip ${filt.year === y ? "on" : ""}" data-year="${y}">${I.t(y)}</button>`)].join("");
    view.querySelectorAll("[data-cat]").forEach((b) => (b.onclick = () => { filt.cat = b.dataset.cat; chips(); run(); }));
    view.querySelectorAll("[data-year]").forEach((b) => (b.onclick = () => { filt.year = b.dataset.year; chips(); run(); }));
    view.querySelector("#cats .on")?.scrollIntoView({ inline: "nearest", block: "nearest" });
  };
  const recentBox = () => {
    const r = document.getElementById("recent");
    const show = !q.value.trim() && history_.length;
    r.innerHTML = show ? `<div class="recent"><div class="rhead"><b>${I.t("Recent searches")}</b><button id="rclear">${I.t("Clear")}</button></div>
      ${history_.map((x, i) => `<div class="rrow"><button class="rq" data-i="${i}"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg><span data-raw>${esc(x)}</span></button><button class="rx" data-x="${i}" aria-label="Remove">${ICON.close}</button></div>`).join("")}</div>` : "";
    if (!show) return;
    r.querySelector("#rclear").onclick = () => { history_ = []; db.set("searches", []); recentBox(); };
    r.querySelectorAll(".rq").forEach((b) => (b.onclick = () => { q.value = history_[+b.dataset.i]; rememberSearch(q.value); run(); }));
    r.querySelectorAll(".rx").forEach((b) => (b.onclick = () => { history_.splice(+b.dataset.x, 1); db.set("searches", history_); recentBox(); }));
  };
  let timer, token = 0;
  const run = async () => {
    const text = q.value.trim();
    lastQuery = q.value;
    recentBox();
    const my = ++token;
    out.innerHTML = loading();
    const cat = CATS.find((c) => c.id === filt.cat), yr = yearRange(filt.year);
    let raw = [];
    try {
      if (text.length >= 2) {
        // Search, then keep only what matches the chosen kind and year.
        const pages = await Promise.all([1, 2].map((page) => tmdb("search/multi", { query: text, include_adult: "false", page })));
        raw = pages.flatMap((o) => o.results || []).filter((o) => o.media_type === "movie" || o.media_type === "tv")
          .filter((o) => (!cat || (cat.kinds.includes(o.media_type) && cat.ok(o))) && ofYear(o, yr));
      } else if (cat || yr) {
        const kinds = cat ? cat.kinds : ["movie", "tv"];
        const lists = await Promise.all(kinds.flatMap((k) => [1, 2].map((page) => {
          // Series: only ones enough people have watched, and no talk shows, news, reality, kids' TV or daily soaps
          // (those rarely have a stream). Cartoons are left out too, except under Anime.
          const prm = { sort_by: "popularity.desc", page, "vote_count.gte": k === "tv" ? (cat?.few ? 5 : 50) : 5, ...(cat?.q || {}) };
          if (k === "tv") prm.without_genres = "10763,10764,10767,10762,10766" + (cat && cat.id !== "anime" && cat.id !== "nollywood" ? ",16" : "");
          if (yr) {
            const [f, t] = k === "movie" ? ["primary_release_date.gte", "primary_release_date.lte"] : ["first_air_date.gte", "first_air_date.lte"];
            prm[f] = `${yr[0]}-01-01`; prm[t] = `${yr[1]}-12-31`;
          }
          return tmdb(`discover/${k}`, prm).then((o) => (o.results || []).map((x) => ({ ...x, media_type: k }))).catch(() => []);
        })));
        raw = lists.flat().sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
      } else raw = (await tmdb("trending/all/week")).results || [];
    } catch {}
    if (my !== token) return;
    const seen = new Set();
    const items = raw.filter(kidsOk).map((x) => parse(x, x.media_type)).filter((t) => t && t.poster && !seen.has(key(t)) && seen.add(key(t)));
    out.innerHTML = items.length ? items.map(poster).join("") : `<div class="empty" style="grid-column:1/-1"><b>${I.t("No results")}</b><span class="muted">${I.t(cat || yr ? "Try another year or category." : "Try a different title.")}</span></div>`;
  };
  q.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(run, 350); });
  q.addEventListener("keydown", (e) => { if (e.key === "Enter") { rememberSearch(q.value); q.blur(); } });
  // Opening a result counts as a finished search.
  out.addEventListener("click", (e) => { if (e.target.closest("a.card")) rememberSearch(q.value); });
  chips();
  run();
}

function myListView() {
  const tab = sessionStorage.getItem("listTab") || "list";
  const lk = Object.values(likes).filter((x) => x?.t).sort((a, b) => b.at - a.at).map((x) => remember(x.t));
  const items = tab === "liked" ? lk : myList;
  view.innerHTML = `<div class="pad-top"><h1 class="title">My List</h1>
    <div class="chips seg"><button class="chip ${tab === "list" ? "on" : ""}" data-tab="list">${I.t("Saved")} · ${myList.length}</button><button class="chip ${tab === "liked" ? "on" : ""}" data-tab="liked">${I.t("Liked")} · ${lk.length}</button></div>
    ${items.length ? `<div class="grid">${items.map(poster).join("")}</div>`
    : tab === "liked" ? `<div class="empty"><b>${I.t("Nothing liked yet")}</b><span class="muted">${I.t("Tap ♥ Like on any title to keep it here.")}</span></div>`
    : `<div class="empty"><b>Nothing saved yet</b><span class="muted">Tap My List on any title to keep it here.</span></div>`}</div>`;
  view.querySelectorAll(".seg .chip").forEach((b) => (b.onclick = () => { sessionStorage.setItem("listTab", b.dataset.tab); myListView(); }));
}

async function details(kind, id) {
  const known = titles[`${kind}:${id}`];
  view.innerHTML = `<a class="back" href="javascript:history.back()">${ICON.back}</a>${known ? `<div class="dhead"><img class="bg" src="${img(known.backdrop || known.poster, "w1280")}" alt=""><div class="shade"></div></div>` : ""}${loading()}`;
  const o = await tmdb(`${kind}/${id}`, { append_to_response: "credits,similar,images", include_image_language: `${I.lang},en,null` });
  const t = parse(o, kind);
  const logos = o.images?.logos || [];
  const logo = (logos.find((l) => l.iso_639_1 === I.lang) || logos.find((l) => l.iso_639_1 === "en") || logos[0])?.file_path;
  const runtime = o.runtime || o.episode_run_time?.[0];
  const seasons = (o.seasons || []).filter((s) => s.season_number > 0);
  const r = recent[key(t)];
  const saved = positions[posKey(t, 1, 1)];
  const label = kind === "tv" && r ? `Resume S${r.s} E${r.e}` : saved && saved[0] > 30 ? "Resume" : "Play";
  const meta = [t.year, t.rating ? "★ " + t.rating.toFixed(1) : null,
    kind === "movie" && runtime ? (runtime >= 60 ? `${Math.floor(runtime / 60)}h ${runtime % 60}m` : `${runtime}m`) : null,
    kind === "tv" && seasons.length ? I.t(seasons.length === 1 ? "1 season" : `${seasons.length} seasons`) : null].filter(Boolean).join("  ·  ");
  const cast = (o.credits?.cast || []).slice(0, 15);
  const similar = (o.similar?.results || []).map((x) => parse(x, kind)).filter((x) => x && x.poster);
  const season = r ? r.s : seasons[0]?.season_number || 1;
  view.innerHTML = `<a class="back" href="javascript:history.back()">${ICON.back}</a>
    <div class="dhead"><img class="bg" src="${img(t.backdrop || t.poster, "w1280")}" alt=""><div class="shade"></div>
      ${logo ? `<img class="logo" src="${img(logo, "w500")}" alt="">` : `<h3>${esc(t.name)}</h3>`}</div>
    <div class="dbody">
      <div class="meta">${esc(meta)}</div>
      <div class="btns"><a class="btn white wide" href="${playTarget(t)}">${ICON.play}${label}</a></div>
      <div class="acts">
        <button class="act ${inList(t) ? "on" : ""}" data-list="${key(t)}">${inList(t) ? ICON.check : ICON.plus}<span>${I.t("My List")}</span></button>
        <button class="act ${liked(t) ? "on" : ""}" id="alike">${ICON.heart}<span>${I.t(liked(t) ? "Liked" : "Like")}</span></button>
        <button class="act" id="ashare">${ICON.share}<span>${I.t("Share")}</span></button>
        <button class="act" id="adown">${ICON.down}<span>${I.t("Download")}</span></button>
      </div>
      ${o.tagline ? `<div class="tag">${esc(o.tagline)}</div>` : ""}
      <p>${esc(t.overview)}</p>
      <div class="genres">${esc((o.genres || []).map((g) => g.name).join(" · "))}</div>
    </div>
    ${kind === "tv" && seasons.length ? `<div style="margin-top:22px"><div class="seasonpick"><button class="season" id="season" data-n="${season}" aria-haspopup="listbox">
      <span>${esc(seasons.find((x) => x.season_number === season)?.name || I.t(`Season ${season}`))}</span><svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
      <div class="smenu" id="smenu" role="listbox" hidden>${seasons.map((x) => `<button role="option" data-n="${x.season_number}" class="${x.season_number === season ? "on" : ""}">
        <b>${esc(x.name)}</b><small>${x.episode_count ? I.t(`${x.episode_count} episodes`) : ""}</small></button>`).join("")}</div></div>
      <div class="eps" id="eps">${loading()}</div></div>` : ""}
    ${cast.length ? `<section class="row" style="margin-top:24px"><h2>Cast</h2><div class="cast">${cast.map((c) => `<a href="#/person/${c.id}"><img loading="lazy" src="${img(c.profile_path, "w185")}" alt=""><b>${esc(c.name)}</b><small>${esc(c.character || "")}</small></a>`).join("")}</div></section>` : ""}
    ${similar.length ? `<div style="margin-top:24px">${rowHtml({ name: "More like this", items: similar })}</div>` : ""}`;
  const genres = (o.genres || []).map((g) => g.name);
  const lb = document.getElementById("alike");
  lb.onclick = () => { const on = toggleLike(t, genres); lb.classList.toggle("on", on); lb.querySelector("span").textContent = I.t(on ? "Liked" : "Like"); };
  document.getElementById("ashare").onclick = () => shareTitle(t);
  document.getElementById("adown").onclick = downloadNote;
  if (kind === "tv" && seasons.length) {
    const sel = document.getElementById("season");
    const menu = document.getElementById("smenu");
    // Season picker: Movio's own dark menu (the browser's own list looked out of place, especially on Windows).
    const close = () => { menu.hidden = true; sel.classList.remove("open"); document.removeEventListener("pointerdown", outside, true); document.removeEventListener("keydown", keys, true); };
    const outside = (e) => { if (!e.target.closest(".seasonpick")) close(); };
    const keys = (e) => { if (e.key === "Escape") { e.preventDefault(); e.stopImmediatePropagation(); close(); sel.focus(); } };
    sel.onclick = () => {
      if (!menu.hidden) return close();
      menu.hidden = false; sel.classList.add("open");
      const on = menu.querySelector(".on");
      if (on) menu.scrollTop = on.offsetTop - menu.clientHeight / 2 + on.offsetHeight / 2;
      document.addEventListener("pointerdown", outside, true);
      document.addEventListener("keydown", keys, true);
    };
    menu.querySelectorAll("button").forEach((b) => b.onclick = () => {
      sel.dataset.n = b.dataset.n;
      sel.querySelector("span").textContent = b.querySelector("b").textContent;
      menu.querySelectorAll("button").forEach((x) => x.classList.toggle("on", x === b));
      close();
      load();
    });
    const load = async () => {
      const n = +sel.dataset.n;
      const eps = document.getElementById("eps");
      eps.innerHTML = loading();
      const so = await tmdb(`tv/${id}/season/${n}`).catch(() => ({}));
      eps.innerHTML = (so.episodes || []).map((e) => {
        const p = positions[posKey(t, n, e.episode_number)];
        return `<a class="ep" href="${playHref(t, n, e.episode_number)}"><div class="th"><img loading="lazy" src="${img(e.still_path, "w300")}" alt="">
          ${p ? `<div class="bar"><i style="width:${Math.min(100, (p[0] / p[1]) * 100)}%"></i></div>` : ""}</div>
          <div><b>${e.episode_number}. ${esc(e.name)}</b>${e.runtime ? `<small style="-webkit-line-clamp:1">${e.runtime}m</small>` : ""}<small>${esc(e.overview || "")}</small></div></a>`;
      }).join("");
    };
    load();
  }
}

// ---------- What people like and watch (open #/stats) ----------
async function statsView() {
  view.innerHTML = `<div class="pad-top">${loading()}</div>`;
  const st = await fetch(`${HELPER}/stats`, { cache: "no-store" }).then((r) => r.json()).catch(() => null);
  if (!st) { view.innerHTML = `<div class="empty pad-top"><b>Couldn't load</b></div>`; return; }
  const T = Object.entries(st.titles || {});
  const top = (f) => T.filter(([, v]) => v[f] > 0).sort((a, b) => b[1][f] - a[1][f]).slice(0, 20);
  const G = Object.entries(st.genres || {}).sort((a, b) => b[1].plays + b[1].likes - a[1].plays - a[1].likes).slice(0, 12);
  const D = Object.entries(st.days || {}).slice(-14);
  const sum = (f) => D.reduce((n, [, v]) => n + v[f], 0);
  const maxG = Math.max(1, ...G.map(([, v]) => v.plays + v.likes));
  const lst = (rows, f) => rows.length ? rows.map(([k, v], i) => `<a class="srow" href="#/title/${k.replace(":", "/")}"><i>${i + 1}</i>${v.poster ? `<img src="${img(v.poster, "w92")}" alt="">` : "<span></span>"}<b data-raw>${esc(v.name)}</b><em>${v[f]}</em></a>`).join("") : `<p class="muted" style="padding:8px 0">—</p>`;
  view.innerHTML = `<div class="pad-top stats"><h1 class="title">What people watch</h1>
    <div class="tiles"><div><b>${sum("plays")}</b><span>Plays · 14 days</span></div><div><b>${sum("likes")}</b><span>Likes · 14 days</span></div></div>
    <h2>Most played</h2>${lst(top("plays"), "plays")}
    <h2>Most liked</h2>${lst(top("likes"), "likes")}
    <h2>Categories</h2>${G.map(([g, v]) => `<div class="gbar"><span data-raw>${esc(g)}</span><div><i style="width:${((v.plays + v.likes) / maxG) * 100}%"></i></div><em>${v.plays} ▶ · ${v.likes} ♥</em></div>`).join("") || `<p class="muted">—</p>`}</div>`;
}

// ---------- Person: everything they've been in ----------
async function person(id) {
  view.innerHTML = `<a class="back" href="javascript:history.back()">${ICON.back}</a>${loading()}`;
  const o = await tmdb(`person/${id}`, { append_to_response: "combined_credits" });
  const seen = new Set();
  const cc = o.combined_credits || {};
  // Actors: roles they played. Directors, writers…: their work behind the camera too.
  const all = [...(cc.cast || []), ...(o.known_for_department && o.known_for_department !== "Acting" ? cc.crew || [] : [])]
    .filter((c) => (c.media_type === "movie" || c.media_type === "tv") && c.poster_path)
    // chat shows, news and award-show guest spots crowd out the real work
    .filter((c) => !(c.media_type === "tv" && ((c.genre_ids || []).some((g) => g === 10767 || g === 10763) || /^(self|himself|herself)\b/i.test(c.character || ""))))
    .sort((a, b) => (b.popularity || 0) - (a.popularity || 0))
    .filter((c) => { const k = c.media_type + c.id; if (seen.has(k)) return false; seen.add(k); return true; });
  const items = all.map((c) => ({ t: parse(c), role: c.character || c.job || "" })).filter((x) => x.t);
  const age = o.birthday ? Math.floor((Date.now() - new Date(o.birthday)) / 31557600000) : null;
  const facts = [I.t(o.known_for_department || ""), o.birthday ? (o.deathday ? `${o.birthday.slice(0, 4)}–${o.deathday.slice(0, 4)}` : I.t(`Born ${o.birthday.slice(0, 4)} · ${age}`)) : null, o.place_of_birth].filter(Boolean).join("  ·  ");
  const card = (x) => `<a class="pcard" href="${href(x.t)}" data-k="${x.t.kind}"><img class="poster" loading="lazy" src="${img(x.t.poster, "w342")}" alt="">
    <b>${esc(x.t.name)}</b><small>${esc([x.t.year, x.role].filter(Boolean).join(" · "))}</small></a>`;
  const nMovie = items.filter((x) => x.t.kind === "movie").length, nTv = items.length - nMovie;
  view.innerHTML = `<a class="back" href="javascript:history.back()">${ICON.back}</a>
    <div class="phead pad-top">
      <img class="pphoto" src="${img(o.profile_path, "h632")}" alt="">
      <div class="pinfo"><h1>${esc(o.name)}</h1><div class="meta">${esc(facts)}</div>
        ${o.biography ? `<p class="bio" id="bio">${esc(o.biography)}</p>` : ""}</div>
    </div>
    <div class="pfilter">${[["all", `All ${items.length}`], ["movie", `Movies ${nMovie}`], ["tv", `Shows ${nTv}`]].filter(([k], i) => i === 0 || (k === "movie" ? nMovie : nTv))
      .map(([k, l], i) => `<button class="chip ${i ? "" : "on"}" data-f="${k}">${l}</button>`).join("")}</div>
    <div class="grid pgrid">${items.map(card).join("") || `<div class="empty"><span class="muted">No titles found.</span></div>`}</div>`;
  document.getElementById("bio")?.addEventListener("click", (e) => e.currentTarget.classList.toggle("open"));
  view.querySelectorAll(".pfilter .chip").forEach((b) => b.onclick = () => {
    view.querySelectorAll(".pfilter .chip").forEach((x) => x.classList.toggle("on", x === b));
    view.querySelectorAll(".pcard").forEach((c) => (c.style.display = b.dataset.f === "all" || c.dataset.k === b.dataset.f ? "" : "none"));
  });
}

// ---------- Rows on laptops: arrow buttons ----------
new MutationObserver(() => {
  if (touch) return;
  for (const row of view.querySelectorAll(".row:not(.arrows)")) {
    const sc = row.querySelector(".scroll, .cast");
    if (!sc) continue;
    row.classList.add("arrows");
    for (const d of [-1, 1]) {
      const b = document.createElement("button");
      b.className = `arrow ${d < 0 ? "l" : "r"}`;
      b.innerHTML = `<svg viewBox="0 0 24 24"><path d="${d < 0 ? "M15 5l-7 7 7 7" : "M9 5l7 7-7 7"}"/></svg>`;
      b.onclick = () => sc.scrollBy({ left: d * sc.clientWidth * 0.85, behavior: "smooth" });
      row.append(b);
    }
  }
}).observe(view, { childList: true, subtree: true });

// ---------- Player ----------
/** Only sources that play in Movio's own player (no ads). Main has films only. */
const OWN = ["Club", "Peach", "Mist", "Main"];
const sourceOrder = (kind) => OWN.filter((n) => kind !== "tv" || n !== "Main");
const nativeHls = () => !!document.createElement("video").canPlayType("application/vnd.apple.mpegurl");
const canHls = () => nativeHls() || !!window.MediaSource;
let hlsLib = null;
const loadHls = () => (hlsLib ||= new Promise((ok, no) => { const sc = document.createElement("script"); sc.src = "hls.min.js"; sc.onload = () => ok(window.Hls); sc.onerror = no; document.head.append(sc); }));
const touch = matchMedia("(pointer: coarse)").matches;

let P = null; // the open player

function closePlayer() {
  if (!P) return;
  P.dead = true;
  savePlayer();
  P.tracks?.forEach((x) => x.blob && URL.revokeObjectURL(x.blob));
  P.hls?.destroy();
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  P = null;
  document.body.classList.remove("playing");
}

function savePlayer() {
  if (!P || !P.video || !(P.video.duration > 0)) return;
  savePos(P.t, P.s, P.e, P.video.currentTime, P.video.duration);
}

async function player(kind, id, s, e) {
  closePlayer();
  document.body.classList.add("playing");
  view.innerHTML = loading();
  let t = titles[`${kind}:${id}`];
  if (!t) t = parse(await tmdb(`${kind}/${id}`), kind);
  P = { t, s: +s, e: +e, order: rankSources(sourceOrder(kind), t), tried: new Set(), tracks: [], offset: 0, segments: [], pre: {} };
  const me = P;
  const pick = localStorage.getItem(`src:${key(t)}`);
  view.innerHTML = `<div class="player">
    <div class="pbar"><button class="icon" id="pclose">${ICON.close}</button>
      <div class="tt"><b>${esc(t.name)}</b><small>${kind === "tv" ? `S${s} · E${e}` : esc(t.year || "")}</small></div></div>
    <div class="stage" id="stage"></div>
    <div class="pfoot" id="pfoot"></div></div>`;
  document.getElementById("pclose").onclick = () => { closePlayer(); history.length > 1 ? history.back() : (location.hash = "#/"); };
  if ("mediaSession" in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({ title: kind === "tv" ? `${t.name} · S${s} E${e}` : t.name, artist: "Movio",
      artwork: t.poster ? [{ src: img(t.poster, "w342"), sizes: "342x513", type: "image/jpeg" }] : [] });
  }
  tmdb(`${kind}/${id}`).then((o) => { me.genres = (o.genres || []).map((g) => g.name); }).catch(() => {});
  if (kind === "tv") tmdb(`tv/${id}/season/${s}`).then((o) => { if (me === P) { me.episodes = o.episodes || []; footer(); } }).catch(() => {});
  fetch(`${HELPER}/intro?${kind === "tv" ? `tmdb_id=${id}&season=${s}&episode=${e}` : `tmdb_id=${id}`}`).then((r) => r.json()).then((o) => {
    if (me !== P) return;
    for (const type of ["recap", "intro", "credits"]) for (const x of o[type] || []) me.segments.push({ type, a: (x.start_ms || 0) / 1000, b: x.end_ms == null ? null : x.end_ms / 1000 });
  }).catch(() => {});
  // Ask every source at the same time (each gets 12 s), so a slow or dead one never makes you wait.
  // If this is the episode we got ready while the last one was playing, its answers are already here.
  const path = kind === "tv" ? `tv/${id}/${s}/${e}` : `movie/${id}`;
  const ready = ahead && ahead.path === path && Date.now() - ahead.at < 20 * 60000 ? ahead : null;
  ahead = null;
  for (const n of P.order) P.pre[n] = ready?.pre[n] || lookup(`${HELPER}/${n.toLowerCase()}/${path}`);
  // Subtitles load straight away, alongside the stream (not after it).
  if (ready?.subs) ready.subs.then((items) => { if (me === P) items.length ? loadSubtitles(null, items, true) : loadSubtitles(null); });
  else loadSubtitles(null);
  partySubs(path).then((list) => { if (me === P && list) loadSubtitles(list, [], true); });
  P.began = Date.now();
  // Never just spin: after 25 s with nothing playing, show what each source is doing and let the viewer pick.
  setTimeout(() => { if (me === P && !me.playing) slowPanel(); }, 25000);
  if (pick && P.order.includes(pick)) return start(pick, false);
  // Prefer the best-ranked source, but don't wait more than 3 s for it if another is already ready.
  start(await firstReady(me), true);
}

const lookup = (u) => fetch(u, { signal: AbortSignal.timeout ? AbortSignal.timeout(12000) : undefined }).then((r) => r.json()).catch(() => null);
async function firstReady(me) {
  const done = {};
  me.order.forEach((n) => me.pre[n].then((o) => (done[n] = o || {})));
  const t0 = Date.now();
  while (me === P) {
    const best = me.order.find((n) => !(n in done) || done[n].ok);
    if (!best) return me.order[0];
    if (done[best]?.ok) return best;
    if (Date.now() - t0 > 3000) { const ready = me.order.find((n) => done[n]?.ok); if (ready) return ready; }
    await sleep(150);
  }
  return me.order[0];
}

/** Taking too long: each source with how it's doing, plus try again. */
function slowPanel() {
  const st = document.getElementById("stage");
  if (!st || !P || P.playing) return;
  const me = P;
  const state = (n) => (me.source === n ? "Trying now" : me.tried.has(n) ? "Didn't play this title" : "Ready to try");
  st.innerHTML = `<div class="finding slow"><img src="${img(me.t.backdrop, "w1280")}" alt="">
    <b style="font-size:20px">${I.t("This is taking longer than usual")}</b>
    <span class="soft" style="font-size:13px;font-weight:600;max-width:340px">${I.t("Pick a source to try it now.")}</span>
    <div class="srcs">${me.order.map((n, i) => `<button class="srcb ${me.source === n ? "on" : ""}" data-n="${n}"><b>${I.f("Source {1}", i + 1)} · ${esc(n)}</b><small>${I.t(state(n))}</small></button>`).join("")}</div>
    <button class="btn glass" id="sagain">${I.t("Try again")}</button></div>`;
  st.querySelectorAll(".srcb").forEach((b) => (b.onclick = () => { me.tried = new Set(); start(b.dataset.n, false); }));
  st.querySelector("#sagain").onclick = () => { closePlayer(); route(); };
}

function finding(text) {
  const st = document.getElementById("stage");
  if (!st || !P) return;
  if (st.querySelector(".slow")) return;  // the source list is up: leave it
  st.innerHTML = `<div class="finding"><img src="${img(P.t.backdrop, "w1280")}" alt=""><b style="font-size:22px">${esc(P.t.name)}</b><div class="spin"></div><span class="soft" style="font-size:13px;font-weight:600">${esc(text)}</span></div>`;
}

async function start(name, auto = true) {
  const me = P;
  if (!me) return;
  savePlayer();
  me.source = name; me.auto = auto; me.tried.add(name); me.video = null;
  footer();
  if (!canHls()) return next();
  return viaHelper(name.toLowerCase());
}

/** Club / Peach streams, played through the helper so they play here in Movio's own player (no ads). */
async function viaHelper(src) {
  const me = P;
  finding("Finding the best stream");
  try {
    const path = me.t.kind === "tv" ? `tv/${me.t.id}/${me.s}/${me.e}` : `movie/${me.t.id}`;
    const o = (await (me.pre[me.source] || fetch(`${HELPER}/${src}/${path}`).then((r) => r.json()))) || {};
    if (me !== P || me.dead) return;
    if (!o.ok || !o.url) { if (o.ok === false) srcResult(me.source, false); return next(); }
    play(o.url);
    // The source's own subtitles join the list that's already loading.
    const own = (o.tracks || []).map((t, i) => ({ url: t.file, lang: /eng|english/i.test(t.label) ? "en" : "und", label: (t.label || "Subtitles") + " · " + me.source }));
    if (own.length) loadSubtitles(null, own, true);
  } catch { if (me === P) next(); }
}

async function partySubs(path) {
  try {
    for (let i = 0; i < 6; i++) {
      const o = await fetch(`${HELPER}/party/api/play/${path}?async=1&wait=1`).then((r) => r.json());
      if (o?.result?.file?.subtitles && o.status === "ready") return "https://vidsrc.party" + o.result.file.subtitles;
      if (["error", "failed", "not_found"].includes(o?.status)) return null;
      await sleep(5000);
    }
  } catch {}
  return null;
}

function next() {
  if (!P) return;
  const n = P.order.find((x) => !P.tried.has(x));
  if (n) return start(n, P.auto);
  if (Date.now() - P.began > 4000) return slowPanel();
  const st = document.getElementById("stage");
  if (st) st.innerHTML = `<div class="finding"><img src="${img(P.t.backdrop, "w1280")}" alt=""><b style="font-size:22px">${esc(P.t.name)}</b><span class="soft" style="font-size:13px;font-weight:600">No stream found right now. Try again later.</span></div>`;
}

function play(url) {
  const me = P;
  const st = document.getElementById("stage");
  me.hls?.destroy(); me.hls = null;
  // Laptops: the video's own full-screen button would leave Movio's subtitles behind, so full screen goes through Movio's (F, the chip, or a double-click).
  st.innerHTML = `<video playsinline controls ${touch ? "" : 'controlslist="nofullscreen" disablepictureinpicture'} autoplay preload="auto" x-webkit-airplay="allow"></video><div class="subs"></div>`;
  setSideways(touch && !localStorage.getItem("upright"));
  const v = st.querySelector("video");
  me.video = v;
  // Switching source (or a new try) makes a new video: give it the subtitles already loaded.
  me.tracks.forEach((x) => { x.node = null; if (x.text) build(x); });
  const savedVol = parseFloat(localStorage.getItem("vol"));
  if (savedVol > 0 && savedVol <= 1) v.volume = savedVol;
  if (nativeHls()) v.src = url;
  else loadHls().then((Hls) => {
    if (me !== P || me.video !== v) return;
    const h = new Hls({ maxBufferLength: 60 });
    me.hls = h;
    h.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) { if (d.type === "mediaError") h.recoverMediaError(); else fail(); } });
    h.loadSource(url);
    h.attachMedia(v);
  }).catch(() => fail());
  let ok = false, lastSave = 0;
  const fail = () => { if (me === P && !ok) { me.video = null; srcResult(me.source, false); next(); } };
  // A stream that hasn't started within 15 s is skipped.
  const timer = setTimeout(fail, 15000);
  v.addEventListener("playing", () => { if (!me.counted) { me.counted = true; stat("play", me.t, me.genres || []); } me.playing = true; }, { once: true });
  let recovered = false;
  v.addEventListener("error", () => {
    clearTimeout(timer);
    // A hiccup mid-film: let hls.js try to recover once before moving to another source.
    if (ok && me.hls && !recovered) { recovered = true; me.hls.recoverMediaError(); return; }
    if (ok) { ok = false; }
    fail();
  });
  v.addEventListener("loadedmetadata", () => {
    clearTimeout(timer);
    if (v.duration > 0 && v.duration < 150) return fail(); // an ad clip, not the title
    ok = true;
    srcResult(me.source, true);
    localStorage.setItem(`ok:${key(me.t)}`, me.source);
    const saved = positions[posKey(me.t, me.s, me.e)];
    if (saved && saved[0] > 30 && saved[0] < v.duration - 60) v.currentTime = saved[0];
    v.play().catch(() => {});
  });
  v.addEventListener("timeupdate", () => {
    if (Date.now() - lastSave > 5000) { lastSave = Date.now(); savePlayer(); }
    // Near the end of an episode, get the next one ready (its streams and subtitles), so it starts at once.
    if (me.t.kind === "tv" && !me.aheadDone && v.duration > 300 && v.currentTime > v.duration * 0.75) { me.aheadDone = true; prepareNext(me); }
    footerSkip();
  });
  v.addEventListener("pause", savePlayer);
  // iPhone's own full screen player can't see Movio's subtitle layer, so it shows the chosen track itself.
  v.addEventListener("webkitbeginfullscreen", () => P?.sub?.node && (P.sub.node.track.mode = "showing"));
  v.addEventListener("webkitendfullscreen", () => P?.tracks.forEach((y) => y.node && (y.node.track.mode = "hidden")));
  if (!touch) v.addEventListener("dblclick", (e) => { e.preventDefault(); fullScreen(); });
  // If the video alone still ends up full screen (e.g. Safari on a Mac), the browser draws the chosen subtitles itself.
  const nativeFs = () => {
    if (P?.video !== v) return;
    const on = document.fullscreenElement === v || document.webkitFullscreenElement === v;
    P?.tracks.forEach((y) => y.node && (y.node.track.mode = on && y === P.sub ? "showing" : "hidden"));
  };
  document.addEventListener("fullscreenchange", nativeFs);
  document.addEventListener("webkitfullscreenchange", nativeFs);
  // Picking from the video's own captions menu works too.
  v.textTracks.addEventListener("change", () => {
    if (!P || v.webkitDisplayingFullscreen || document.fullscreenElement === v) return;
    const on = [...v.textTracks].find((t) => t.mode === "showing");
    if (on) pickSub(P.tracks.find((y) => y.node?.track === on) || null);
  });
  v.addEventListener("ended", () => { savePlayer(); if (me.t.kind === "tv") nextEpisode(); });
}

let ahead = null; // the next episode, made ready while this one plays
function nextOf(me) {
  const count = me.episodes?.length || 0;
  return count && me.e >= count ? [me.s + 1, 1] : [me.s, me.e + 1];
}
function prepareNext(me) {
  const [s, e] = nextOf(me);
  const path = `tv/${me.t.id}/${s}/${e}`;
  const pre = {};
  // Ask the sources that work best first; the rest are asked when the episode opens.
  for (const n of me.order.slice(0, 3)) pre[n] = lookup(`${HELPER}/${n.toLowerCase()}/${path}`);
  const subs = fetch(`${HELPER}/wyzie?id=${me.t.id}&season=${s}&episode=${e}&language=en,fr,es,pt,ar`).then((r) => r.json()).then((a) => {
    const by = {};
    for (const o of Array.isArray(a) ? a : []) if (/^https:/.test(o.url || "")) (by[o.language] ||= []).push(o);
    const items = [];
    for (const [lang, l] of Object.entries(by)) {
      l.sort((x, y) => (x.isHearingImpaired - y.isHearingImpaired) || ((y.downloadCount || 0) - (x.downloadCount || 0)));
      l.slice(0, lang === "en" || lang === I.sub ? 5 : 1).forEach((o, i) => {
        // The subtitle in your language is downloaded now, so it's there from the first second.
        const it = { url: o.url, lang, label: (o.display || langName(lang)) + (i ? ` · ${i + 1}` : "") };
        if (i === 0 && (lang === I.sub || lang === "en")) it.pre = fetch(`${HELPER}/sub?u=${encodeURIComponent(o.url)}`).then((r) => (r.ok ? r.text() : "")).catch(() => "");
        items.push(it);
      });
    }
    return items;
  }).catch(() => []);
  ahead = { path, pre, subs, at: Date.now() };
}

function nextEpisode() {
  if (!P) return;
  const [s, e] = nextOf(P);
  location.replace(playHref(P.t, s, e));
}

// ---------- Subtitles: both lists, like the apps; tracks show in the iPhone player's own menu ----------
function toVtt(text, offset) {
  const lines = text.replace(/\r/g, "").replace(/^﻿/, "").split("\n");
  const stamp = /(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})/g;
  const fmt = (x) => { x = Math.max(0, x); const h = Math.floor(x / 3600), m = Math.floor(x / 60) % 60, s = Math.floor(x) % 60, ms = Math.round((x % 1) * 1000);
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}.${String(Math.min(ms, 999)).padStart(3, "0")}`; };
  const out = ["WEBVTT", ""];
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes("-->")) continue;
    const t = [...lines[i].matchAll(stamp)].map((m) => (+(m[1] || 0)) * 3600 + +m[2] * 60 + +m[3] + +m[4] / 1000);
    const body = [];
    i++;
    while (i < lines.length && lines[i].trim()) body.push(lines[i++]);
    const txt = body.join("\n").replace(/\{\\[^}]*\}/g, "").trim();
    // A web address in a line means it's an ad, not dialogue.
    if (t.length < 2 || !txt || /(https?:\/\/|www\.|\b[a-z0-9-]{2,}\.(ru|com|net|org|io|tv|to|me|xyz|cc|info|co|top|live|site|online|club|vip)\b)/i.test(txt)) continue;
    out.push(`${fmt(t[0] + offset)} --> ${fmt(t[1] + offset)}`, txt, "");
  }
  return out.join("\n");
}
const langName = (l) => { try { return new Intl.DisplayNames(["en"], { type: "language" }).of(l); } catch { return l; } };

async function loadSubtitles(partyList, extra = [], noWyzie = false) {
  const me = P;
  if (!me) return;
  const kind = me.t.kind;
  const add = (items) => {
    if (me !== P) return;
    for (const x of items) if (!me.tracks.some((y) => y.url === x.url)) me.tracks.push(x);
    const rank = (x) => (x.lang === I.sub ? 0 : x.lang === "en" ? 1 : 2);
    me.tracks.sort((a, b) => rank(a) - rank(b));
    me.tracks.slice(0, 14).forEach(attach);
  };
  if (extra.length) add(extra);
  if (noWyzie || me.wyzie) return partyList ? partyAlt(me, partyList, add) : undefined;
  me.wyzie = true;
  // Wyzie: most downloaded first, plain before hearing-impaired; 5 English + 1 per other language
  fetch(`${HELPER}/wyzie?id=${me.t.id}${kind === "tv" ? `&season=${me.s}&episode=${me.e}` : ""}&language=en,fr,es,pt,ar`).then((r) => r.json()).then((a) => {
    const by = {};
    for (const o of Array.isArray(a) ? a : []) if (/^https:/.test(o.url || "")) (by[o.language] ||= []).push(o);
    const items = [];
    for (const [lang, l] of Object.entries(by)) {
      l.sort((x, y) => (x.isHearingImpaired - y.isHearingImpaired) || ((y.downloadCount || 0) - (x.downloadCount || 0)));
      l.slice(0, lang === "en" || lang === I.sub ? 5 : 1).forEach((o, i) => items.push({ url: o.url, lang, label: (o.display || langName(lang)) + (i ? ` · ${i + 1}` : "") }));
    }
    add(items);
  }).catch(() => {});
  if (partyList) partyAlt(me, partyList, add);
}
// The stream service's own list ("Alt")
function partyAlt(me, partyList, add) {
  fetch(`${HELPER}/party/api${partyList.replace("https://vidsrc.party/api", "")}`).then((r) => r.json()).then((a) => {
    const count = {};
    const items = [];
    for (const o of Array.isArray(a) ? a : []) {
      if (!/\.(vtt|srt)$/.test(o.src || "")) continue;
      const n = count[o.lang] || 0;
      if (n >= (o.lang === "en" || o.lang === I.sub ? 5 : 1)) continue;
      count[o.lang] = n + 1;
      items.push({ url: "https://vidsrc.party" + o.src, lang: o.lang, label: `${langName(o.lang)} · Alt${n ? " " + (n + 1) : ""}` });
    }
    add(items);
  }).catch(() => {});
}

async function attach(x) {
  const me = P;
  if (!me || x.el) return;
  x.el = true;
  // The text loads even before there's a video; it's put on whichever video is playing once it arrives.
  if (!x.text) {
    try { x.text = await (x.pre || fetch(`${HELPER}/sub?u=${encodeURIComponent(x.url)}`).then((r) => (r.ok ? r.text() : ""))); } catch { x.text = ""; }
  }
  if (me !== P || !x.text) { x.el = false; return; }
  if (me.video) build(x);
}

function build(x) {
  const v = P?.video;
  if (!v) return;
  if (x.blob) URL.revokeObjectURL(x.blob);
  x.blob = URL.createObjectURL(new Blob([toVtt(x.text, P.offset)], { type: "text/vtt" }));
  x.node?.remove();
  const tr = document.createElement("track");
  tr.kind = "subtitles"; tr.label = x.label; tr.srclang = x.lang; tr.src = x.blob;
  v.appendChild(tr);
  x.node = tr;
  // Movio draws the subtitles itself (right size, turns with the player); the track only times the lines.
  tr.track.mode = v.webkitDisplayingFullscreen && P.sub === x ? "showing" : "hidden";
  tr.track.oncuechange = () => P?.sub === x && renderSub();
  // First English version shows by default, until you pick another.
  // The subtitle language picked at setup (none if "No subtitles").
  if (!P.sub && !P.userPicked && I.sub !== "off" && x.lang === I.sub) P.sub = x;
  renderSub();
  footer();
}

/** Draws the chosen subtitle line over the video. */
function renderSub() {
  const box = document.querySelector("#stage .subs");
  if (!box || !P) return;
  const cues = P.sub?.node?.track?.activeCues;
  const lines = cues ? [...cues].map((c) => esc(c.text.replace(/<[^>]+>/g, "")).replace(/\n/g, "<br>")) : [];
  box.innerHTML = lines.length ? `<span>${lines.join("<br>")}</span>` : "";
}

function pickSub(x) {
  if (!P) return;
  P.sub = x; P.userPicked = true;
  const fs = P.video?.webkitDisplayingFullscreen;
  P.tracks.forEach((y) => y.node && (y.node.track.mode = fs && y === x ? "showing" : "hidden"));
  renderSub(); footer();
}

function subsMenu() {
  const ready = P.tracks.filter((x) => x.node);
  sheet("Subtitles", [
    { label: "Off", on: !P.sub, fn: () => pickSub(null) },
    ...ready.map((x) => ({ label: x.label, on: x === P.sub, fn: () => pickSub(x) })),
    ...[["Size: Small", 0.8], ["Size: Medium", 1], ["Size: Large", 1.25]].map(([label, k]) => ({ label, on: subSize() === k, fn: () => setSubSize(k) })),
    { label: "Timing…", on: false, fn: () => setTimeout(timingMenu, 50) },
  ]);
}
const subSize = () => +(localStorage.getItem("subSize") || 0.8);
function setSubSize(k) { localStorage.setItem("subSize", k); document.documentElement.style.setProperty("--sub", k); }
document.documentElement.style.setProperty("--sub", subSize());
function timingMenu() {
  sheet("Subtitle timing", [
    ["Show 1s earlier", -1], ["Show 0.5s earlier", -0.5], ["Show 0.5s later", 0.5], ["Show 1s later", 1], ["Reset", 0],
  ].map(([label, d]) => ({ label, on: false, fn: () => shiftSubs(d) })));
}

function shiftSubs(d) {
  if (!P) return;
  P.offset = d === 0 ? 0 : P.offset + d;
  P.tracks.filter((x) => x.text).forEach(build);
  toast(P.offset ? `Subtitles ${P.offset > 0 ? "+" : ""}${P.offset.toFixed(1)}s` : "Subtitle timing reset");
  footer();
}

// ---------- Player footer: sources, timing, episodes, skip ----------
function footer() {
  const f = document.getElementById("pfoot");
  if (!f || !P) return;
  const tv = P.t.kind === "tv";
  f.innerHTML = `
    <button class="chip" id="fsrc">${esc(P.source || "Source")} ▾</button>
    ${touch ? `<button class="chip" id="frot">${sideways() ? "Upright" : "Sideways"}</button>` : ""}
    <button class="chip" id="ffull">Full screen</button>
    ${touch ? "" : `<button class="chip" id="fkeys">Keys ?</button>`}
    <button class="chip" id="fsub">${esc(I.f("Subs · {1}", P.sub ? P.sub.label.split(" · ")[0].slice(0, 10) : I.t("Off")))}${P.offset ? ` ${P.offset > 0 ? "+" : ""}${P.offset.toFixed(1)}s` : ""}</button>
    ${tv && P.episodes?.length ? `<button class="chip" id="feps">Episodes</button>` : ""}
    ${tv ? `<button class="chip" id="fnext">Next ›</button>` : ""}
    <button class="chip on" id="fskip" style="display:none"></button>`;
  f.querySelector("#fsrc").onclick = () => sheet("Sources", P.order.map((n) => ({ label: n, on: n === P.source, fn: () => {
    localStorage.setItem(`src:${key(P.t)}`, n); P.tried = new Set(); start(n, false);
  } })));
  f.querySelector("#ffull").onclick = fullScreen;
  f.querySelector("#fkeys") && (f.querySelector("#fkeys").onclick = () => sheet("Keyboard shortcuts", KEYS_HELP.map(([a, b]) => ({ label: `${a}  —  ${b}`, on: false, fn: () => {} }))));
  f.querySelector("#frot") && (f.querySelector("#frot").onclick = () => { localStorage.setItem("upright", sideways() ? "1" : ""); setSideways(!sideways()); });
  f.querySelector("#fsub").onclick = subsMenu;
  f.querySelector("#feps") && (f.querySelector("#feps").onclick = () => sheet(`Season ${P.s}`, P.episodes.map((e) => ({
    label: `${e.episode_number}. ${e.name}`, on: e.episode_number === P.e, fn: () => location.replace(playHref(P.t, P.s, e.episode_number)),
  }))));
  f.querySelector("#fnext") && (f.querySelector("#fnext").onclick = nextEpisode);
}

const sideways = () => !!document.querySelector(".player.sideways");
function sideClass() {
  const el = document.querySelector(".player");
  if (el) el.classList.toggle("side", !touch || el.classList.contains("sideways") || (innerWidth > innerHeight && innerHeight < 540));
}
addEventListener("resize", sideClass);
function setSideways(on) {
  document.querySelector(".player")?.classList.toggle("sideways", on);
  sideClass();
  footer(); idleBars();
}
/** Full screen (iPhone uses its own video player, which turns sideways with the phone). */
function fullScreen() {
  const v = P?.video;
  if (touch && v && v.webkitEnterFullscreen && !document.fullscreenEnabled) { try { v.webkitEnterFullscreen(); return; } catch {} }
  if (document.fullscreenElement) return void document.exitFullscreen().catch(() => {});
  const el = document.querySelector(".player");
  (el?.requestFullscreen?.() || Promise.resolve()).then(() => screen.orientation?.lock?.("landscape")).catch(() => {});
}
/** Sideways: hide the bars after 3s; a tap brings them back. */
function idleBars() {
  const el = document.querySelector(".player");
  if (!el) return;
  el.classList.remove("idle");
  clearTimeout(idleBars.t);
  idleBars.t = setTimeout(() => el.classList.add("idle"), 3000);
}
document.addEventListener("pointerdown", () => P && idleBars(), true);
document.addEventListener("mousemove", () => P && idleBars(), { passive: true });
// Laptop keys in the player (press ? for the list):
// Space/K pause · ←/→ 10s · J/L 10s · ↑/↓ volume · M mute · F full screen · C subtitles · N next episode · 0–9 jump · Esc close
const KEYS_HELP = [["Space or K", "Play / pause"], ["← / →", "Back / forward 10 seconds"], ["J / L", "Back / forward 10 seconds"], ["↑ / ↓", "Volume up / down"],
  ["M", "Mute / unmute"], ["F", "Full screen"], ["C", "Subtitles"], ["N", "Next episode"], ["0–9", "Jump to 0%–90%"], ["Esc", "Close menu / leave player"]];
const PKEYS = new Set([" ", "k", "arrowleft", "arrowright", "j", "l", "arrowup", "arrowdown", "m", "f", "c", "n", "?", ..."0123456789"]);
const playerKey = (ev) => P && !ev.metaKey && !ev.ctrlKey && !ev.altKey && !/input|select|textarea/i.test(ev.target.tagName) && PKEYS.has(ev.key.toLowerCase());
// The browser's own key handling (on the video, its controls or a focused button) would undo Movio's, so Movio takes these keys first.
for (const t of ["keyup", "keypress"]) window.addEventListener(t, (ev) => { if (playerKey(ev)) { ev.preventDefault(); ev.stopImmediatePropagation(); } }, true);
/** Centre pop-up for every key: icon, what happened, and a volume bar when it's about volume. Fades after a moment. */
const OSD_ICON = {
  play: '<path d="M8 5v14l11-7z" fill="#fff"/>',
  pause: '<path d="M7 5h4v14H7zM13 5h4v14h-4z" fill="#fff"/>',
  back: '<path d="M11 18V6l-8.5 6zM21 18V6l-8.5 6z" fill="#fff"/>',
  fwd: '<path d="M13 6v12l8.5-6zM3 6v12l8.5-6z" fill="#fff"/>',
  vol: '<path d="M4 9v6h4l5 4V5L8 9z" fill="#fff"/><path d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12" stroke="#fff" stroke-width="2" fill="none" stroke-linecap="round"/>',
  mute: '<path d="M4 9v6h4l5 4V5L8 9z" fill="#fff"/><path d="M16.5 9.5l5 5m0-5l-5 5" stroke="#fff" stroke-width="2" stroke-linecap="round"/>',
  jump: '<path d="M5 12h14M13 6l6 6-6 6" stroke="#fff" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>',
};
const clock = (t) => { t = Math.max(0, Math.floor(t)); const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), sec = String(t % 60).padStart(2, "0"); return h ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`; };
function osd(icon, text, sub, level) {
  const st = document.getElementById("stage");
  if (!st) return;
  let o = st.querySelector(".osd");
  if (!o) { o = document.createElement("div"); o.className = "osd"; st.append(o); }
  o.innerHTML = `<svg viewBox="0 0 24 24">${OSD_ICON[icon] || ""}</svg><b>${esc(text)}</b>${sub ? `<small>${esc(sub)}</small>` : ""}${level != null ? `<i><u style="width:${Math.round(level * 100)}%"></u></i>` : ""}`;
  o.classList.add("on");
  clearTimeout(osd.t);
  osd.t = setTimeout(() => o.classList.remove("on"), 1000);
}
const volOsd = (v) => (v.muted || v.volume === 0 ? osd("mute", "Muted", null, 0) : osd("vol", `${Math.round(v.volume * 100)}%`, "Volume", v.volume));
const vol = (v, d) => {
  if (v.muted && d > 0) { v.muted = false; if (v.volume === 0) v.volume = 0; }
  v.volume = Math.round(Math.min(1, Math.max(0, v.volume + d)) * 10) / 10;
  v.muted = v.volume === 0;
  localStorage.setItem("vol", v.volume);
  volOsd(v);
};
const seek = (v, d) => {
  if (!(v.duration > 0)) return;
  v.currentTime = Math.min(v.duration - 1, Math.max(0, v.currentTime + d));
  osd(d > 0 ? "fwd" : "back", d > 0 ? `+${d}s` : `−${-d}s`, `${clock(v.currentTime)} / ${clock(v.duration)}`);
};
window.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape" && P) {
    if (document.querySelector(".sheet")) { ev.preventDefault(); document.querySelector(".scrim")?.click(); return; }
    if (!document.fullscreenElement) document.getElementById("pclose")?.click();
    return;
  }
  if (!playerKey(ev)) return;
  ev.preventDefault(); ev.stopImmediatePropagation();
  if (ev.repeat && !/arrow/i.test(ev.key)) return;
  const k = ev.key.toLowerCase();
  const v = P.video;
  idleBars();
  if (k === "?") return sheet("Keyboard shortcuts", KEYS_HELP.map(([a, b]) => ({ label: `${a}  —  ${b}`, on: false, fn: () => {} })));
  if (k === "f") return fullScreen();
  if (k === "c") return subsMenu();
  if (k === "n") return P.t.kind === "tv" ? nextEpisode() : undefined;
  if (!v) return;
  if (k === " " || k === "k") { if (v.paused) { v.play().catch(() => {}); osd("play", "Play", clock(v.currentTime)); } else { v.pause(); osd("pause", "Paused", clock(v.currentTime)); } }
  else if (k === "arrowleft" || k === "j") seek(v, -10);
  else if (k === "arrowright" || k === "l") seek(v, 10);
  else if (k === "arrowup") vol(v, 0.1);
  else if (k === "arrowdown") vol(v, -0.1);
  else if (k === "m") { v.muted = !v.muted; if (!v.muted && v.volume === 0) v.volume = 0.5; volOsd(v); }
  else if (/^[0-9]$/.test(k) && v.duration > 0) { v.currentTime = v.duration * (+k / 10); osd("jump", `${+k * 10}%`, `${clock(v.currentTime)} / ${clock(v.duration)}`); }
}, true);

function footerSkip() {
  const b = document.getElementById("fskip");
  const v = P?.video;
  if (!b || !v) return;
  const t = v.currentTime;
  const seg = P.segments.find((x) => t >= x.a && (x.b == null || t < x.b));
  if (!seg) { b.style.display = "none"; return; }
  b.style.display = "";
  b.textContent = seg.type === "credits" && P.t.kind === "tv" ? "Next episode ›" : I.f("Skip {1} ›", I.t(seg.type));
  b.onclick = () => (seg.type === "credits" && P.t.kind === "tv") || seg.b == null ? nextEpisode() : (v.currentTime = seg.b);
}

function sheet(title, opts) {
  const scrim = document.createElement("div");
  scrim.className = "scrim";
  const s = document.createElement("div");
  s.className = "sheet";
  s.innerHTML = `<h4>${esc(title)}</h4>` + opts.map((o, i) => `<button class="opt ${o.on ? "on" : ""}" data-i="${i}"><i>${o.on ? "✓" : ""}</i>${esc(o.label)}</button>`).join("");
  const close = () => { s.remove(); scrim.remove(); };
  scrim.onclick = close;
  s.onclick = (ev) => { const b = ev.target.closest(".opt"); if (b) { close(); opts[+b.dataset.i].fn(); } };
  (document.querySelector(".player.sideways") || document.body).append(scrim, s);
}

// ---------- Routing ----------
document.addEventListener("click", (ev) => {
  const b = ev.target.closest("[data-list]");
  if (!b) return;
  ev.preventDefault();
  const t = titles[b.dataset.list];
  if (!t) return;
  toggleList(t);
  b.innerHTML = b.innerHTML.replace(/<svg[\s\S]*?<\/svg>/, inList(t) ? ICON.check : ICON.plus);
  b.classList.toggle("on", inList(t));
});

async function route() {
  const h = location.hash.replace(/^#/, "") || "/";
  const parts = h.split("/").filter(Boolean);
  if (parts[0] !== "play") closePlayer();
  const tab = { "": "home", movies: "movies", tv: "tv", live: "live", search: "search", list: "list" }[parts[0] || ""] || "";
  if (parts[0] !== "live") closeLive();
  document.querySelectorAll("#tabs a").forEach((a) => a.classList.toggle("on", a.dataset.tab === tab));
  view.scrollTo(0, 0);
  if (!HELPER) { view.innerHTML = `<div class="empty pad-top"><b>Movio is almost ready</b><span class="muted">The helper server isn't set up yet.</span></div>`; return; }
  try {
    if (parts[0] === "movies") await browse("movie");
    else if (parts[0] === "tv") await browse("tv");
    else if (parts[0] === "search") await search();
    else if (parts[0] === "list") myListView();
    else if (parts[0] === "title") await details(parts[1], parts[2]);
    else if (parts[0] === "person") await person(parts[1]);
    else if (parts[0] === "stats") await statsView();
    else if (parts[0] === "platform") await platform(parts[1]);
    else if (parts[0] === "live") await liveView();
    else if (parts[0] === "play") await player(parts[1], parts[2], parts[3] || 1, parts[4] || 1);
    else await home();
  } catch {
    view.innerHTML = `<div class="empty pad-top"><b>Couldn't load</b><span class="muted">Check your connection and try again.</span></div>`;
  }
}
// A missing picture just leaves the dark placeholder.
document.addEventListener("error", (e) => { if (e.target.tagName === "IMG") e.target.style.opacity = 0; }, true);
window.addEventListener("hashchange", route);
window.addEventListener("pagehide", savePlayer);
I.start(() => { route(); Sync.push(); });
if ("serviceWorker" in navigator) {
  // Updates are required: look for a new version on open, every 15 minutes and whenever the tab comes back.
  navigator.serviceWorker.register("sw.js").then((r) => {
    r.update();
    setInterval(() => r.update().catch(() => {}), 15 * 60e3);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) r.update().catch(() => {}); });
  }).catch(() => {});
  // A new version took over: reload so it's in use straight away (after the film, if one is playing).
  const hadOld = !!navigator.serviceWorker.controller;
  let reloading = false, pending = false;
  const reload = () => { if (!reloading && !P) { reloading = true; location.reload(); } };
  navigator.serviceWorker.addEventListener("controllerchange", () => { if (hadOld) { pending = true; reload(); } });
  setInterval(() => { if (pending) reload(); }, 2000);
}
