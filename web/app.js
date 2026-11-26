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
  myList = inList(t) ? myList.filter((x) => key(x) !== key(t)) : [t, ...myList];
  db.set("myList", myList);
  toast(inList(t) ? "Added to My List" : "Removed from My List");
}
function savePos(t, s, e, pos, dur) {
  if (!(dur > 0)) return;
  positions[posKey(t, s, e)] = [pos, dur];
  db.set("positions", positions);
  if (pos > dur - 90 && t.kind === "movie") delete recent[key(t)];
  else recent[key(t)] = { t, s, e, pos, dur, at: Date.now() };
  db.set("recent", recent);
}
const continueWatching = () => Object.values(recent).sort((a, b) => b.at - a.at);

function toast(msg) {
  document.querySelector(".toast")?.remove();
  const d = document.createElement("div");
  d.className = "toast"; d.textContent = msg;
  document.body.appendChild(d);
  setTimeout(() => d.remove(), 1800);
}

// ---------- Catalogue ----------
const cache = new Map();
function tmdb(path, params = {}) {
  const q = new URLSearchParams(params).toString();
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
  return (o.results || []).map((x) => parse(x, kind)).filter((t) => t && t.poster);
}
async function rows(specs, ranked) {
  const res = await Promise.all(specs.map(([, f]) => f().catch(() => [])));
  return specs.map(([n], i) => ({ name: n, items: res[i], ranked: n === ranked })).filter((r) => r.items.length);
}
const homeRows = () => rows([
  ["Top 10 today", async () => (await list("trending/all/day")).slice(0, 10)],
  ["New in cinemas", () => list("movie/now_playing", "movie")],
  ["Trending this week", () => list("trending/all/week")],
  ["Popular movies", () => list("movie/popular", "movie")],
  ["Popular shows", () => list("tv/popular", "tv")],
  ["Top rated movies", () => list("movie/top_rated", "movie")],
  ["Top rated shows", () => list("tv/top_rated", "tv")],
], "Top 10 today");
const browseRows = (kind) => kind === "movie"
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
async function home() {
  view.innerHTML = loading();
  const rs = await homeRows();
  const trending = (rs.find((r) => r.name.startsWith("Trending")) || rs[0])?.items.filter((t) => t.backdrop && t.overview).slice(0, 6) || [];
  const cw = continueWatching();
  view.innerHTML = `
    <section class="hero"><div class="slides">${trending.map((t) => `
      <div class="slide"><img src="${img(t.backdrop, "w1280")}" alt=""><div class="shade"></div>
        <div class="info"><h3>${esc(t.name)}</h3>
          <div class="meta">${[t.year, t.kind === "tv" ? "Series" : "Movie", "★ " + t.rating.toFixed(1)].filter(Boolean).join("  ·  ")}</div>
          <div class="btns">
            <a class="btn white" href="${playTarget(t)}">${ICON.play}Play</a>
            <button class="btn glass" data-list="${key(t)}">${inList(t) ? ICON.check : ICON.plus}My List</button>
            <a class="btn glass round" href="${href(t)}">${ICON.info}</a>
          </div></div></div>`).join("")}</div>
      <div class="dots">${trending.map((_, i) => `<i class="${i ? "" : "on"}"></i>`).join("")}</div></section>
    <div class="rows">
      ${cw.length ? `<section class="row"><h2>Continue watching</h2><div class="scroll">${cw.map(contCard).join("")}</div></section>` : ""}
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

let lastQuery = "";
async function search() {
  view.innerHTML = `<div class="pad-top"><h1 class="title">Search</h1>
    <label class="searchbox"><svg viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg>
    <input id="q" type="search" placeholder="Movies, shows…" autocomplete="off" value="${esc(lastQuery)}"></label><div class="grid" id="results"></div></div>`;
  const q = document.getElementById("q");
  const out = document.getElementById("results");
  let timer, token = 0;
  const run = async () => {
    const text = q.value.trim();
    lastQuery = q.value;
    const my = ++token;
    const items = text.length >= 2 ? await list("search/multi", null, { query: text, include_adult: "false" }).catch(() => []) : await list("trending/all/week").catch(() => []);
    if (my === token) out.innerHTML = items.map(poster).join("");
  };
  q.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(run, 350); });
  run();
}

function myListView() {
  view.innerHTML = `<div class="pad-top"><h1 class="title">My List</h1>${myList.length
    ? `<div class="grid">${myList.map(poster).join("")}</div>`
    : `<div class="empty"><b>Nothing saved yet</b><span class="muted">Tap My List on any title to keep it here.</span></div>`}</div>`;
}

async function details(kind, id) {
  const known = titles[`${kind}:${id}`];
  view.innerHTML = `<a class="back" href="javascript:history.back()">${ICON.back}</a>${known ? `<div class="dhead"><img class="bg" src="${img(known.backdrop || known.poster, "w1280")}" alt=""><div class="shade"></div></div>` : ""}${loading()}`;
  const o = await tmdb(`${kind}/${id}`, { append_to_response: "credits,similar,images", include_image_language: "en,null" });
  const t = parse(o, kind);
  const logos = o.images?.logos || [];
  const logo = (logos.find((l) => l.iso_639_1 === "en") || logos[0])?.file_path;
  const runtime = o.runtime || o.episode_run_time?.[0];
  const seasons = (o.seasons || []).filter((s) => s.season_number > 0);
  const r = recent[key(t)];
  const saved = positions[posKey(t, 1, 1)];
  const label = kind === "tv" && r ? `Resume S${r.s} E${r.e}` : saved && saved[0] > 30 ? "Resume" : "Play";
  const meta = [t.year, t.rating ? "★ " + t.rating.toFixed(1) : null,
    kind === "movie" && runtime ? (runtime >= 60 ? `${Math.floor(runtime / 60)}h ${runtime % 60}m` : `${runtime}m`) : null,
    kind === "tv" && seasons.length ? (seasons.length === 1 ? "1 season" : `${seasons.length} seasons`) : null].filter(Boolean).join("  ·  ");
  const cast = (o.credits?.cast || []).slice(0, 15);
  const similar = (o.similar?.results || []).map((x) => parse(x, kind)).filter((x) => x && x.poster);
  const season = r ? r.s : seasons[0]?.season_number || 1;
  view.innerHTML = `<a class="back" href="javascript:history.back()">${ICON.back}</a>
    <div class="dhead"><img class="bg" src="${img(t.backdrop || t.poster, "w1280")}" alt=""><div class="shade"></div>
      ${logo ? `<img class="logo" src="${img(logo, "w500")}" alt="">` : `<h3>${esc(t.name)}</h3>`}</div>
    <div class="dbody">
      <div class="meta">${esc(meta)}</div>
      <div class="btns"><a class="btn white wide" href="${playTarget(t)}">${ICON.play}${label}</a>
        <button class="btn glass round" data-list="${key(t)}">${inList(t) ? ICON.check : ICON.plus}</button></div>
      ${o.tagline ? `<div class="tag">${esc(o.tagline)}</div>` : ""}
      <p>${esc(t.overview)}</p>
      <div class="genres">${esc((o.genres || []).map((g) => g.name).join(" · "))}</div>
    </div>
    ${kind === "tv" && seasons.length ? `<div style="margin-top:22px"><select class="season" id="season">${seasons.map((s) => `<option value="${s.season_number}" ${s.season_number === season ? "selected" : ""}>${esc(s.name)} ⌄</option>`).join("")}</select><div class="eps" id="eps">${loading()}</div></div>` : ""}
    ${cast.length ? `<section class="row" style="margin-top:24px"><h2>Cast</h2><div class="cast">${cast.map((c) => `<div><img loading="lazy" src="${img(c.profile_path, "w185")}" alt=""><b>${esc(c.name)}</b><small>${esc(c.character || "")}</small></div>`).join("")}</div></section>` : ""}
    ${similar.length ? `<div style="margin-top:24px">${rowHtml({ name: "More like this", items: similar })}</div>` : ""}`;
  if (kind === "tv" && seasons.length) {
    const sel = document.getElementById("season");
    const load = async () => {
      const n = +sel.value;
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
    sel.addEventListener("change", load);
    load();
  }
}

// ---------- Player ----------
const EMBEDS = [
  ["Nero", "https://vidfast.pro"], ["Flixify", "https://vidflix.club"], ["Club", "https://moviesapi.to"], ["Cine", "https://cinesrc.st/embed"],
  ["Main", "https://player.vidzee.wtf/embed"], ["Vid", "https://embed.wplay.me/embed"], ["Peach", "https://peachify.net/embed"],
  ["Mist", "https://play.xpass.top/e"], ["Nest", "https://vidnest.fun"], ["Pass", "https://vidcore.net"], ["Azute", "https://vidrock.to"],
  ["Vidmux", "https://vidlux.site/embed"], ["Vix", "https://vixsrc.to"],
];
function embedUrl(name, base, kind, id, s, e) {
  if (kind === "movie") return `${base}/movie/${id}${name === "Vix" ? "?autoplay=true" : ""}`;
  if (name === "Club") return `${base}/tv/${id}-${s}-${e}`;
  return `${base}/tv/${id}/${s}/${e}${name === "Vix" ? "?autoplay=true" : ""}`;
}
/** Films and shows: Club → Nero → Peach → Flixify, then Movio Direct, then the rest. */
function sourceOrder(kind) {
  const first = ["Club", "Nero", "Peach", "Flixify"];
  const rest = EMBEDS.map((x) => x[0]).filter((n) => !first.includes(n));
  return [...first, "Movio Direct", ...rest];
}
const canHls = () => !!document.createElement("video").canPlayType("application/vnd.apple.mpegurl");

let P = null; // the open player

function closePlayer() {
  if (!P) return;
  P.dead = true;
  savePlayer();
  P.tracks?.forEach((x) => x.blob && URL.revokeObjectURL(x.blob));
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
  P = { t, s: +s, e: +e, order: sourceOrder(kind), tried: new Set(), tracks: [], offset: 0, segments: [] };
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
  if (kind === "tv") tmdb(`tv/${id}/season/${s}`).then((o) => { if (me === P) { me.episodes = o.episodes || []; footer(); } }).catch(() => {});
  fetch(`${HELPER}/intro?${kind === "tv" ? `tmdb_id=${id}&season=${s}&episode=${e}` : `tmdb_id=${id}`}`).then((r) => r.json()).then((o) => {
    if (me !== P) return;
    for (const type of ["recap", "intro", "credits"]) for (const x of o[type] || []) me.segments.push({ type, a: (x.start_ms || 0) / 1000, b: x.end_ms == null ? null : x.end_ms / 1000 });
  }).catch(() => {});
  start(pick && P.order.includes(pick) ? pick : P.order[0], !pick);
}

function finding(text) {
  const st = document.getElementById("stage");
  if (!st || !P) return;
  st.innerHTML = `<div class="finding"><img src="${img(P.t.backdrop, "w1280")}" alt=""><b style="font-size:22px">${esc(P.t.name)}</b><div class="spin"></div><span class="soft" style="font-size:13px;font-weight:600">${esc(text)}</span></div>`;
}

async function start(name, auto = true) {
  const me = P;
  if (!me) return;
  savePlayer();
  me.source = name; me.auto = auto; me.tried.add(name); me.video = null;
  footer();
  if (name !== "Movio Direct") return embed(name);
  if (!canHls()) return next();
  finding("Finding the best stream");
  try {
    const path = me.t.kind === "tv" ? `tv/${me.t.id}/${me.s}/${me.e}` : `movie/${me.t.id}`;
    let file = null;
    for (let i = 0; i < 8 && me === P && !me.dead; i++) {
      const o = await fetch(`${HELPER}/party/api/play/${path}?async=1&wait=1`).then((r) => r.json());
      file = o?.result?.file || null;
      if (file && o.status === "ready") break;
      if (["error", "failed", "not_found"].includes(o?.status)) { file = null; break; }
      finding("Preparing the stream…");
      await sleep(5000);
    }
    if (me !== P || me.dead) return;
    const pl = file && (file.playlist || file.stream);
    if (!pl) return next();
    const url = pl.startsWith("http") ? pl : "https://vidsrc.party" + pl;
    play(url);
    loadSubtitles(file.subtitles ? "https://vidsrc.party" + file.subtitles : null);
  } catch { if (me === P) next(); }
}

function next() {
  if (!P) return;
  const n = P.order.find((x) => !P.tried.has(x));
  if (P.auto && n) start(n, true);
  else embed(P.source === "Movio Direct" ? P.order[0] : P.source);
}

function embed(name) {
  const me = P;
  const [, base] = EMBEDS.find((x) => x[0] === name) || EMBEDS[0];
  me.source = name;
  const st = document.getElementById("stage");
  st.innerHTML = `<iframe src="${embedUrl(name, base, me.t.kind, me.t.id, me.s, me.e)}" allow="autoplay; fullscreen; picture-in-picture; encrypted-media" allowfullscreen referrerpolicy="origin"></iframe>`;
  // We can't see inside their player; keep it on Continue watching.
  if (!recent[key(me.t)]) { recent[key(me.t)] = { t: me.t, s: me.s, e: me.e, pos: 0, dur: 1, at: Date.now() }; db.set("recent", recent); }
  footer();
}

function play(url) {
  const me = P;
  const st = document.getElementById("stage");
  st.innerHTML = `<video playsinline controls autoplay preload="auto" x-webkit-airplay="allow" src="${esc(url)}"></video>`;
  const v = st.querySelector("video");
  me.video = v;
  let ok = false, lastSave = 0;
  const fail = () => { if (me === P && !ok) { me.video = null; next(); } };
  const timer = setTimeout(fail, 30000);
  v.addEventListener("error", () => { clearTimeout(timer); fail(); });
  v.addEventListener("loadedmetadata", () => {
    clearTimeout(timer);
    if (v.duration > 0 && v.duration < 150) return fail(); // an ad clip, not the title
    ok = true;
    const saved = positions[posKey(me.t, me.s, me.e)];
    if (saved && saved[0] > 30 && saved[0] < v.duration - 60) v.currentTime = saved[0];
    v.play().catch(() => {});
  });
  v.addEventListener("timeupdate", () => {
    if (Date.now() - lastSave > 5000) { lastSave = Date.now(); savePlayer(); }
    footerSkip();
  });
  v.addEventListener("pause", savePlayer);
  v.addEventListener("ended", () => { savePlayer(); if (me.t.kind === "tv") nextEpisode(); });
}

function nextEpisode() {
  if (!P) return;
  const count = P.episodes?.length || 0;
  const [s, e] = count && P.e >= count ? [P.s + 1, 1] : [P.s, P.e + 1];
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

async function loadSubtitles(partyList) {
  const me = P;
  const kind = me.t.kind;
  const add = (items) => {
    if (me !== P) return;
    for (const x of items) if (!me.tracks.some((y) => y.url === x.url)) me.tracks.push(x);
    me.tracks.sort((a, b) => (a.lang === "en" ? 0 : 1) - (b.lang === "en" ? 0 : 1));
    me.tracks.slice(0, 14).forEach(attach);
  };
  // Wyzie: most downloaded first, plain before hearing-impaired; 5 English + 1 per other language
  fetch(`${HELPER}/wyzie?id=${me.t.id}${kind === "tv" ? `&season=${me.s}&episode=${me.e}` : ""}&language=en,fr,es,pt,ar`).then((r) => r.json()).then((a) => {
    const by = {};
    for (const o of Array.isArray(a) ? a : []) if (/^https:/.test(o.url || "")) (by[o.language] ||= []).push(o);
    const items = [];
    for (const [lang, l] of Object.entries(by)) {
      l.sort((x, y) => (x.isHearingImpaired - y.isHearingImpaired) || ((y.downloadCount || 0) - (x.downloadCount || 0)));
      l.slice(0, lang === "en" ? 5 : 1).forEach((o, i) => items.push({ url: o.url, lang, label: (o.display || langName(lang)) + (i ? ` · ${i + 1}` : "") }));
    }
    add(items);
  }).catch(() => {});
  // The stream service's own list ("Alt")
  if (partyList) fetch(`${HELPER}/party/api${partyList.replace("https://vidsrc.party/api", "")}`).then((r) => r.json()).then((a) => {
    const count = {};
    const items = [];
    for (const o of Array.isArray(a) ? a : []) {
      if (!/\.(vtt|srt)$/.test(o.src || "")) continue;
      const n = count[o.lang] || 0;
      if (n >= (o.lang === "en" ? 5 : 1)) continue;
      count[o.lang] = n + 1;
      items.push({ url: "https://vidsrc.party" + o.src, lang: o.lang, label: `${langName(o.lang)} · Alt${n ? " " + (n + 1) : ""}` });
    }
    add(items);
  }).catch(() => {});
}

async function attach(x) {
  const me = P;
  if (!me?.video || x.el) return;
  x.el = true;
  try {
    x.text = await fetch(`${HELPER}/sub?u=${encodeURIComponent(x.url)}`).then((r) => (r.ok ? r.text() : ""));
  } catch { x.text = ""; }
  if (me !== P || !x.text) { x.el = false; return; }
  build(x);
}

function build(x) {
  const v = P?.video;
  if (!v) return;
  if (x.blob) URL.revokeObjectURL(x.blob);
  x.blob = URL.createObjectURL(new Blob([toVtt(x.text, P.offset)], { type: "text/vtt" }));
  const wasOn = x.node && x.node.track.mode === "showing";
  x.node?.remove();
  const tr = document.createElement("track");
  tr.kind = "subtitles"; tr.label = x.label; tr.srclang = x.lang; tr.src = x.blob;
  v.appendChild(tr);
  x.node = tr;
  // First English version shows by default, until you pick another.
  const any = [...v.textTracks].some((t) => t.mode === "showing");
  if (wasOn || (!any && x.lang === "en" && !P.userPicked)) { tr.track.mode = "showing"; tr.default = true; }
  v.textTracks.onchange = () => { P && (P.userPicked = true); };
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
    <button class="chip" id="fsrc">Source · ${esc(P.source || "…")}</button>
    ${P.source === "Movio Direct" ? `<button class="chip" id="fe">Subs −0.5s</button><button class="chip" id="fl">Subs +0.5s</button>${P.offset ? `<button class="chip" id="fr">Reset ${P.offset > 0 ? "+" : ""}${P.offset.toFixed(1)}s</button>` : ""}` : ""}
    ${tv && P.episodes?.length ? `<button class="chip" id="feps">Episodes</button>` : ""}
    ${tv ? `<button class="chip" id="fnext">Next episode ›</button>` : ""}
    <button class="chip on" id="fskip" style="display:none"></button>`;
  f.querySelector("#fsrc").onclick = () => sheet("Sources", P.order.map((n) => ({ label: n, on: n === P.source, fn: () => {
    localStorage.setItem(`src:${key(P.t)}`, n); P.tried = new Set(); start(n, false);
  } })));
  f.querySelector("#fe") && (f.querySelector("#fe").onclick = () => shiftSubs(-0.5));
  f.querySelector("#fl") && (f.querySelector("#fl").onclick = () => shiftSubs(0.5));
  f.querySelector("#fr") && (f.querySelector("#fr").onclick = () => shiftSubs(0));
  f.querySelector("#feps") && (f.querySelector("#feps").onclick = () => sheet(`Season ${P.s}`, P.episodes.map((e) => ({
    label: `${e.episode_number}. ${e.name}`, on: e.episode_number === P.e, fn: () => location.replace(playHref(P.t, P.s, e.episode_number)),
  }))));
  f.querySelector("#fnext") && (f.querySelector("#fnext").onclick = nextEpisode);
}

function footerSkip() {
  const b = document.getElementById("fskip");
  const v = P?.video;
  if (!b || !v) return;
  const t = v.currentTime;
  const seg = P.segments.find((x) => t >= x.a && (x.b == null || t < x.b));
  if (!seg) { b.style.display = "none"; return; }
  b.style.display = "";
  b.textContent = seg.type === "credits" && P.t.kind === "tv" ? "Next episode ›" : `Skip ${seg.type} ›`;
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
  document.body.append(scrim, s);
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
});

async function route() {
  const h = location.hash.replace(/^#/, "") || "/";
  const parts = h.split("/").filter(Boolean);
  if (parts[0] !== "play") closePlayer();
  const tab = { "": "home", movies: "movies", tv: "tv", search: "search", list: "list" }[parts[0] || ""] || "";
  document.querySelectorAll("#tabs a").forEach((a) => a.classList.toggle("on", a.dataset.tab === tab));
  window.scrollTo(0, 0);
  if (!HELPER) { view.innerHTML = `<div class="empty pad-top"><b>Movio is almost ready</b><span class="muted">The helper server isn't set up yet.</span></div>`; return; }
  try {
    if (parts[0] === "movies") await browse("movie");
    else if (parts[0] === "tv") await browse("tv");
    else if (parts[0] === "search") await search();
    else if (parts[0] === "list") myListView();
    else if (parts[0] === "title") await details(parts[1], parts[2]);
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
route();
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
