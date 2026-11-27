"use strict";
// Movio's words in the viewer's language (English, French, Spanish, Portuguese, Arabic), and the first-time
// setup: name, app language, subtitle language. Words come from i18n.json (shared with the Android and iPhone apps).
const I = (() => {
  const LANGS = [
    { code: "en", native: "English", english: "English", tmdb: "en-US" },
    { code: "fr", native: "Français", english: "French", tmdb: "fr-FR" },
    { code: "es", native: "Español", english: "Spanish", tmdb: "es-ES" },
    { code: "pt", native: "Português", english: "Portuguese", tmdb: "pt-BR" },
    { code: "ar", native: "العربية", english: "Arabic", tmdb: "ar-SA" },
  ];
  const get = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
  const put = (k, v) => { try { localStorage.setItem(k, v); } catch {} };
  const device = (navigator.language || "en").slice(0, 2);
  const me = {
    LANGS,
    lang: get("p.lang", LANGS.some((l) => l.code === device) ? device : "en"),
    name: get("p.name", ""),
    done: get("p.done", "") === "1",
    table: {},
    pats: {},
  };
  me.sub = get("p.sub", me.lang);
  me.tmdb = () => (LANGS.find((l) => l.code === me.lang) || LANGS[0]).tmdb;
  me.save = () => { put("p.lang", me.lang); put("p.sub", me.sub); put("p.name", me.name); put("p.done", me.done ? "1" : ""); };
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fill = (tpl, args) => args.reduce((r, a, i) => r.split(`{${i + 1}}`).join(a), tpl);

  me.ready = fetch("i18n.json").then((r) => r.json()).then((o) => {
    me.table = o;
    // "Season {1}" also matches "Season 3": numbers fill the gaps.
    for (const [l, m] of Object.entries(o)) {
      me.pats[l] = Object.keys(m).filter((k) => k.includes("{")).sort((a, b) => b.length - a.length)
        .map((k) => [new RegExp("^" + k.split(/\{\d\}/).map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("(\\d[\\d.,:]*)") + "$"), k]);
    }
  }).catch(() => {});

  /** [s] in the app's language (unchanged if it isn't one of Movio's words). */
  me.t = (s) => {
    if (me.lang === "en" || !s) return s;
    const m = me.table[me.lang];
    if (!m) return s;
    if (m[s]) return m[s];
    if (!/\d/.test(s)) return s;
    for (const [re, k] of me.pats[me.lang] || []) {
      const hit = s.match(re);
      if (hit) return fill(m[k], hit.slice(1));
    }
    return s;
  };
  /** A sentence with a name in it, e.g. I.f("Good evening, {1}", name). */
  me.f = (key, ...args) => fill(me.lang === "en" ? key : me.table[me.lang]?.[key] || key, args);

  // Every piece of text on screen goes through t(), so screens don't need to know about languages.
  const KEEP = new Set(["SCRIPT", "STYLE", "INPUT", "TEXTAREA"]);
  function tr(node) {
    if (me.lang === "en") return;
    if (node.nodeType === 3) {
      const raw = node.nodeValue, s = raw.trim();
      if (!s || node.parentNode?.closest?.("[data-raw]") || KEEP.has(node.parentNode?.nodeName)) return;
      const out = me.t(s);
      if (out !== s) node.nodeValue = raw.replace(s, out);
      return;
    }
    if (node.nodeType !== 1 || KEEP.has(node.nodeName) && node.nodeName !== "INPUT") return;
    if (node.placeholder) node.placeholder = me.t(node.placeholder);
    if (node.closest?.("[data-raw]")) return;
    const w = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) tr(n);
    node.querySelectorAll?.("[placeholder]").forEach((e) => (e.placeholder = me.t(e.placeholder)));
  }
  new MutationObserver((list) => {
    for (const m of list) {
      if (m.type === "characterData") tr(m.target);
      else m.addedNodes.forEach(tr);
    }
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });

  function applyDir() {
    document.documentElement.lang = me.lang;
    document.documentElement.dir = me.lang === "ar" ? "rtl" : "ltr";
    tr(document.body);
  }

  me.greeting = () => {
    const n = (me.name || "").trim();
    if (!n) return null;
    const h = new Date().getHours();
    return me.f(h >= 5 && h < 12 ? "Good morning, {1}" : h >= 12 && h < 17 ? "Good afternoon, {1}" : "Good evening, {1}", n);
  };

  /** First launch (or the profile button): name → app language → subtitle language. */
  me.setup = (onDone, editing = false) => {
    let step = editing ? 1 : 0;
    const box = document.createElement("div");
    box.className = "setup";
    document.body.append(box);
    document.body.classList.add("in-setup");
    const choice = (label, sub, on, val) => `<button class="choice ${on ? "on" : ""}" data-v="${val}" data-raw><span><b>${esc(label)}</b>${sub ? `<small>${esc(sub)}</small>` : ""}</span><i></i></button>`;
    const draw = () => {
      applyDir();
      const dots = [0, 1, 2].map((i) => `<i class="${i === step ? "on" : i < step ? "past" : ""}"></i>`).join("");
      let body = "";
      if (step === 0) body = `<h1>${me.t("Welcome to Movio")}</h1><p>${me.t("Let's set things up. It only takes a moment.")}</p>
        <label>${me.t("What should we call you?")}</label>
        <input id="pname" maxlength="24" autocomplete="given-name" autocapitalize="words" placeholder="Your name" value="${esc(me.name)}">
        <div class="sbtns"><button class="btn white wide" id="snext">${me.t("Continue")}</button></div>
        ${editing || typeof linkPanel === "undefined" || Sync.code() ? "" : `<button class="slink" id="slink"><span class="slico">${LINK_ICON}</span>
          <span><b>${me.t("Already use Movio on another device?")}</b><small>${me.t("Link with a code")}</small></span><i>›</i></button>`}`;
      else if (step === 1) body = `<h1>${me.t("App language")}</h1><p>${me.t("Menus, buttons and film descriptions will use this language.")}</p>
        <div class="choices">${LANGS.map((l) => choice(l.native, l.code === "en" || me.t(l.english) === l.native ? "" : me.t(l.english), me.lang === l.code, l.code)).join("")}</div>
        <div class="sbtns"><button class="btn glass" id="sback">${me.t("Back")}</button><button class="btn white wide" id="snext">${me.t("Continue")}</button></div>`;
      else body = `<h1>${me.t("Subtitle language")}</h1><p>${me.t("Movio will turn these subtitles on by default.")}</p>
        <div class="choices">${LANGS.map((l) => choice(me.t(l.english), me.t(l.english) === l.native ? "" : l.native, me.sub === l.code, l.code)).join("")}
        ${choice(me.t("No subtitles"), "", me.sub === "off", "off")}</div>
        <div class="sbtns"><button class="btn glass" id="sback">${me.t("Back")}</button><button class="btn white wide" id="snext">${me.t(editing ? "Save" : "Start watching")}</button></div>`;
      box.innerHTML = `<div class="sin"><div class="shead"><b class="sbrand">Movio</b><span class="sdots">${dots}</span></div>${body}</div>`;
      const name = box.querySelector("#pname");
      if (name) { name.placeholder = me.t("Your name"); if (!matchMedia("(pointer: coarse)").matches) name.focus(); name.onkeydown = (e) => { if (e.key === "Enter") go(); }; }
      box.querySelectorAll(".choice").forEach((b) => b.onclick = () => {
        if (step === 1) { me.lang = b.dataset.v; me.sub = b.dataset.v; } else me.sub = b.dataset.v;
        draw();
      });
      box.querySelector("#slink") && (box.querySelector("#slink").onclick = () => linkPanel({ setup: true, onLinked: () => { step = 1; draw(); } }));
      box.querySelector("#sback") && (box.querySelector("#sback").onclick = () => { step--; draw(); });
      box.querySelector("#snext").onclick = go;
    };
    const go = () => {
      const name = box.querySelector("#pname");
      if (name && name.value.trim() !== me.name) { me.name = name.value.trim(); put("p.nameAt", Date.now()); }
      if (step < 2) { step++; return draw(); }
      me.done = true; me.save();
      box.classList.add("bye");
      document.body.classList.remove("in-setup");
      setTimeout(() => box.remove(), 300);
      onDone();
    };
    draw();
  };

  me.start = (run) => me.ready.then(() => {
    applyDir();
    if (me.done) { run(); hello(); } else me.setup(() => { run(); hello(); });
  });
  function hello() {
    const g = me.greeting();
    if (!g) return;
    setTimeout(() => {
      const d = document.createElement("div");
      d.className = "hello"; d.dataset.raw = ""; d.textContent = g;
      document.body.append(d);
      requestAnimationFrame(() => d.classList.add("on"));
      setTimeout(() => { d.classList.remove("on"); setTimeout(() => d.remove(), 500); }, 3400);
    }, 700);
  }
  /** Profile button: change name, language or subtitles; a new language reloads the page in it. */
  me.edit = () => {
    const before = me.lang;
    const step0 = () => me.setup(() => { if (me.lang !== before) location.reload(); }, false);
    step0();
  };
  return me;
})();
