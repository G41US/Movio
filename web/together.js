// ---------- Together: people you watch with, and watch parties that keep everyone's player in step ----------
// Uses app.js globals (HELPER, I, P, view, esc, img, toast, tmdb, parse, titles, playHref, Sync, PING).
const TG = (() => {
  const db = (k, d) => { try { return JSON.parse(localStorage.getItem("tg." + k)) ?? d; } catch { return d; } };
  const put = (k, v) => localStorage.setItem("tg." + k, JSON.stringify(v));
  let me = db("me", null), seenInvites = new Set();
  // Who you are to the server: your linked-devices code if you have one (so phone, TV and laptop are one person),
  // otherwise this browser.
  const who = () => {
    const c = Sync.code();
    const w = c ? "c:" + c : "d:" + (localStorage.getItem("devid") || "");
    const last = localStorage.getItem("tg.who");
    if (last && last !== w) localStorage.setItem("tg.prev", last);
    localStorage.setItem("tg.who", w);
    return w;
  };
  async function call(op, body = {}) {
    const r = await fetch(`${HELPER}/tg/${op}`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ who: who(), prev: localStorage.getItem("tg.prev") || undefined, name: I.name || "", ...body }) });
    const o = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(o.error || I.t("Couldn't reach Movio. Try again."));
    me = o; put("me", o); localStorage.removeItem("tg.prev");
    document.dispatchEvent(new CustomEvent("tg"));
    showInvites();
    return o;
  }
  const refresh = () => call("me").catch(() => me);

  // Invites from people you're connected with: a card at the top of the screen.
  function showInvites() {
    const inv = (me?.invites || []).find((x) => !seenInvites.has(x.id));
    if (!inv || document.querySelector(".tginvite") || (PARTY && PARTY.code === inv.room)) return;
    seenInvites.add(inv.id);
    const t = inv.title || {};
    const d = document.createElement("div");
    d.className = "tginvite";
    d.innerHTML = `${t.poster ? `<img src="${img(t.poster, "w154")}" alt="">` : ""}<div><small>${esc(I.f("{1} wants to watch with you", inv.from))}</small>
      <b>${esc(t.name || "")}</b></div><button class="btn white">${I.t("Join")}</button><button class="icon x" aria-label="${I.t("Not now")}">✕</button>`;
    d.querySelector(".btn").onclick = () => { d.remove(); call("dismiss", { id: inv.id }).catch(() => {}); join(inv.room); };
    d.querySelector(".x").onclick = () => { d.remove(); call("dismiss", { id: inv.id }).catch(() => {}); };
    document.body.append(d);
  }
  setInterval(() => { if (!document.hidden) refresh(); }, 20000);
  setTimeout(refresh, 2500);

  // ---- Watch parties ----
  async function start(t, s = 1, e = 1, invite = []) {
    const r = await fetch(`${HELPER}/room/new`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: I.name || "", title: { kind: t.kind, id: t.id, name: t.name, poster: t.poster, backdrop: t.backdrop, s, e } }) }).then((x) => x.json());
    if (!r.room) throw new Error("room");
    if (invite.length) call("invite", { to: invite, room: r.room, title: { kind: t.kind, id: t.id, name: t.name, poster: t.poster, s, e } }).catch(() => {});
    connect(r.room, true);
    location.hash = playHref(t, s, e);
  }
  async function join(code) {
    code = String(code || "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 4);
    const info = await fetch(`${HELPER}/room/${code}/info`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
    if (!info) { toast(I.t("That party code wasn't found.")); return false; }
    const t = info.title;
    titles[`${t.kind}:${t.id}`] ||= { kind: t.kind, id: t.id, name: t.name, poster: t.poster, backdrop: t.backdrop };
    connect(code, false);
    location.hash = playHref(t, t.s || 1, t.e || 1);
    return true;
  }
  function leave() {
    if (!PARTY) return;
    const p = PARTY; PARTY = null;
    try { p.ws.close(); } catch {}
    clearInterval(p.beat);
  }
  function connect(code, host) {
    leave();
    const p = { code, host, members: [], st: null, skew: 0, low: localStorage.getItem("tg.low") === "1", wait: localStorage.getItem("tg.wait") !== "0", reacts: true };
    PARTY = p;
    const open = () => {
      if (PARTY !== p) return;
      const ws = new WebSocket(`${HELPER.replace(/^http/, "ws")}/room/${code}/ws?name=${encodeURIComponent(I.name || "Friend")}`);
      p.ws = ws;
      ws.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch { return; } if (DBG) console.log("tg<", ev.data.slice(0, 200)); p.skew = m.now - Date.now(); onMessage(p, m); };
      ws.onclose = () => { if (PARTY === p) setTimeout(open, 2000); };   // dropped: come back in
    };
    open();
    p.beat = setInterval(() => drift(p), 1000);
  }
  const DBG = localStorage.getItem("tg.debug") === "1";
  const send = (p, m) => { if (DBG) console.log("tg>", JSON.stringify(m)); try { p?.ws?.readyState === 1 && p.ws.send(JSON.stringify(m)); } catch {} };
  const others = (p) => p.members.filter((m) => m.id !== p.you);
  const expected = (st, p) => st.pos + (st.playing ? (Date.now() + p.skew - st.at) / 1000 : 0);

  function onMessage(p, m) {
    if (m.t === "hello") { p.you = m.you; p.members = m.members; p.title = m.title; p.st = m.st; apply(p, true); }
    if (m.t === "members") {
      p.members = m.members;
      if (m.joined) toast(I.f("{1} joined", m.joined));
      if (m.left) toast(I.f("{1} left", m.left));
      bar(p); hold(p);
    }
    if (m.t === "state") {
      const ep = p.st && (p.st.s !== m.st.s || p.st.e !== m.st.e);
      p.st = m.st;
      if (ep && P && P.t.kind === "tv" && (P.s !== m.st.s || P.e !== m.st.e)) { location.replace(playHref(P.t, m.st.s, m.st.e)); return; }
      if ((m.why === "play" || m.why === "pause") && m.byId !== p.you) toast(I.f(m.st.playing ? "{1} pressed play" : "{1} paused", m.by));
      apply(p, false);
    }
    if (m.t === "react" && p.reacts) floatReact(m.e, m.by);
    if (m.t === "chat") chatAdd(p, m);
  }

  // Bring this player in line with the party: same episode, same moment, playing or paused.
  function apply(p, first) {
    const v = P?.video;
    bar(p);
    if (!v || !p.st || !(v.duration > 0)) return;
    if (P.t.kind === "tv" && (P.s !== p.st.s || P.e !== p.st.e)) { location.replace(playHref(P.t, p.st.s, p.st.e)); return; }
    p.applying = true; p.quiet = Date.now() + 1500;
    const want = expected(p.st, p);
    if (Math.abs(v.currentTime - want) > (first ? 1 : 1.5)) v.currentTime = Math.min(want, v.duration - 1);
    if (p.st.playing && !held(p)) v.play().catch(() => {}); else if (!p.st.playing) v.pause();
    setTimeout(() => (p.applying = false), 400);
    hold(p);
  }
  // Small gaps are closed by playing a touch faster or slower (unnoticeable); big ones by jumping.
  function drift(p) {
    const v = P?.video;
    if (!v || !p.st || !p.st.playing || held(p) || v.paused || v.seeking) { if (v) v.playbackRate = 1; return; }
    const d = expected(p.st, p) - v.currentTime;
    if (Math.abs(d) > 3) { v.playbackRate = 1; p.applying = true; p.quiet = Date.now() + 1500; v.currentTime = expected(p.st, p); setTimeout(() => (p.applying = false), 400); }
    else v.playbackRate = Math.abs(d) < 0.25 ? 1 : d > 0 ? 1.08 : 0.92;
  }
  // Someone is loading: everyone waits (unless "Wait for each other" is off).
  const held = (p) => p.wait && others(p).some((m) => m.waiting);
  function hold(p) {
    const v = P?.video, st = document.getElementById("stage");
    if (!v || !st) return;
    let box = st.querySelector(".tgwait");
    const who = others(p).filter((m) => m.waiting).map((m) => m.name);
    // The host's film starts for everyone as soon as the others have joined and loaded.
    if (p.host && !p.started && others(p).length && !others(p).some((m) => m.waiting) && v.readyState >= 2) {
      p.started = true; p.synced = true;
      st.querySelector(".tgwait")?.remove();
      send(p, { t: "state", playing: true, pos: v.currentTime, s: P.s, e: P.e, why: "start", echo: true });
      return;
    }
    const alone = p.host && !p.started;
    if (alone || (held(p) && p.st?.playing)) {
      if (!v.paused) { p.applying = true; p.quiet = Date.now() + 1200; v.pause(); setTimeout(() => (p.applying = false), 300); }
      if (!box) { box = document.createElement("div"); box.className = "tgwait"; st.append(box); }
      box.innerHTML = alone
        ? `<div class="tgcode">${[...p.code].map((c) => `<span>${c}</span>`).join("")}</div><b>${others(p).length ? esc(I.f("{1} is getting ready…", others(p)[0].name)) : I.t("Waiting for them to join…")}</b>
           <small>${I.t("They can join from your invite, or by entering this code in Movio's Together tab")}</small><button class="btn white">${I.t("Start now")}</button>`
        : `<div class="tgring"><span>${esc((who[0] || "?")[0])}</span></div><b>${esc(I.f("Waiting for {1}…", who.join(", ")))}</b><small>${I.t("You'll both carry on together in a moment.")}</small>`;
      box.querySelector(".btn")?.addEventListener("click", () => { p.started = true; p.synced = true; box.remove(); send(p, { t: "state", playing: true, pos: v.currentTime, s: P.s, e: P.e, why: "play", echo: true }); });
    } else if (box) {
      box.remove();
      if (p.st?.playing && v.paused) { p.applying = true; p.quiet = Date.now() + 1500; v.currentTime = expected(p.st, p); v.play().catch(() => {}); setTimeout(() => (p.applying = false), 400); }
    }
  }

  // Called by the player when a video element is ready.
  function attach(me) {
    const p = PARTY, v = me.video;
    if (!p || !v || v.tgDone) return;
    v.tgDone = true;
    p.started ||= !p.host;
    // Only once this player has caught up with the party (its own resume jump mustn't move everyone).
    const mine = (why) => {
      if (!p.synced || p.applying || PARTY !== p || Date.now() < (p.quiet || 0)) return;
      // A jump keeps the party playing (or paused) as it was; only Play and Pause change that.
      const playing = why === "seek" ? !!(p.st?.playing ?? !v.paused) : !v.paused;
      send(p, { t: "state", playing, pos: v.currentTime, s: me.s, e: me.e, why });
    };
    v.addEventListener("play", () => { if (!p.applying && held(p)) { mine("play"); p.applying = true; p.quiet = Date.now() + 1200; v.pause(); setTimeout(() => (p.applying = false), 300); return; } mine("play"); });
    v.addEventListener("pause", () => { if (!v.ended && !held(p)) mine("pause"); });
    v.addEventListener("seeked", () => mine("seek"));
    // Only a real stall (over a second) makes the others wait; tiny hiccups don't stop everyone.
    let wt = 0, told = true;
    const ready = () => { clearTimeout(wt); if (told) { told = false; send(p, { t: "wait", on: false }); } };
    v.addEventListener("waiting", () => { clearTimeout(wt); wt = setTimeout(() => { if (v.readyState < 3 && !told) { told = true; send(p, { t: "wait", on: true }); } }, 1200); });
    v.addEventListener("playing", ready);
    v.addEventListener("canplay", ready);
    v.addEventListener("loadedmetadata", () => setTimeout(() => { apply(p, true); setTimeout(() => (p.synced = true), 900); }, 300));
    lowData(p, me);
    bar(p);
  }
  // Low data: this screen only, the others keep full quality.
  function lowData(p, me = P) {
    const h = me?.hls;
    if (!h) return;
    if (!p.low) { h.autoLevelCapping = -1; return; }
    const set = () => { const i = h.levels.reduce((b, l, k) => (l.height && l.height <= 480 && (b < 0 || l.height > h.levels[b].height) ? k : b), -1); h.autoLevelCapping = i >= 0 ? i : 0; };
    h.levels?.length ? set() : h.on("hlsManifestParsed", set);
  }

  // The party bar on the player: who's here, reactions, chat and settings.
  function bar(p) {
    const st = document.getElementById("stage");
    if (!st || PARTY !== p) return;
    let b = st.querySelector(".tgbar");
    if (!b) {
      b = document.createElement("div"); b.className = "tgbar"; st.append(b);
      b.addEventListener("click", (ev) => {
        const r = ev.target.closest("[data-r]");
        if (r) { send(p, { t: "react", e: r.dataset.r }); return; }
        if (ev.target.closest(".tgchatbtn")) { st.classList.toggle("tgchat-on"); chatBox(p); return; }
        if (ev.target.closest(".tgset")) settings(p);
      });
    }
    const list = others(p);
    b.innerHTML = `<span class="pill"><i class="dot"></i>${list.length ? esc(I.f("Watching with {1}", list.map((m) => m.name).join(", "))) : esc(I.f("Party {1}", p.code))}</span>
      <span class="tgav">${p.members.map((m) => `<b title="${esc(m.name)}">${esc((m.name || "?")[0])}</b>`).join("")}</span>
      <span class="tgreacts">${["❤️", "😂", "😮", "😢", "🔥"].map((e) => `<button data-r="${e}">${e}</button>`).join("")}</span>
      <button class="tgchatbtn" aria-label="${I.t("Chat")}">💬</button><button class="tgset" aria-label="${I.t("Party settings")}">⚙️</button>`;
  }
  function settings(p) {
    sheet(I.t("Party settings"), [
      { label: I.t("Low data for me"), on: p.low, fn: () => { p.low = !p.low; localStorage.setItem("tg.low", p.low ? "1" : "0"); lowData(p); } },
      { label: I.t("Wait for each other"), on: p.wait, fn: () => { p.wait = !p.wait; localStorage.setItem("tg.wait", p.wait ? "1" : "0"); hold(p); } },
      { label: I.t("Show reactions"), on: p.reacts, fn: () => { p.reacts = !p.reacts; } },
      { label: I.f("Party code: {1}", p.code), on: false, fn: () => { navigator.clipboard?.writeText(p.code).then(() => toast(I.t("Copied"))); } },
      { label: I.t("Leave the party"), on: false, fn: () => { leave(); document.querySelector(".tgbar")?.remove(); document.querySelector(".tgwait")?.remove(); toast(I.t("You left the party")); } },
    ]);
  }
  function floatReact(e, by) {
    const st = document.getElementById("stage");
    if (!st) return;
    const f = document.createElement("div");
    f.className = "tgfloat"; f.textContent = e; f.style.right = `${40 + Math.random() * 90}px`;
    f.title = by || "";
    st.append(f); setTimeout(() => f.remove(), 2600);
  }
  function chatBox(p) {
    const st = document.getElementById("stage");
    let c = st.querySelector(".tgchat");
    if (!st.classList.contains("tgchat-on")) { c?.remove(); return; }
    if (!c) {
      c = document.createElement("div"); c.className = "tgchat";
      c.innerHTML = `<div class="tgmsgs"></div><form><input placeholder="${I.t("Message…")}" maxlength="300"></form>`;
      c.querySelector("form").onsubmit = (ev) => { ev.preventDefault(); const i = c.querySelector("input"); if (i.value.trim()) send(p, { t: "chat", m: i.value.trim() }); i.value = ""; };
      st.append(c);
      (p.chat || []).forEach((m) => chatLine(c, p, m));
    }
  }
  function chatAdd(p, m) {
    (p.chat ||= []).push(m);
    const c = document.querySelector(".tgchat");
    if (c) chatLine(c, p, m); else if (m.id !== p.you) toast(`${m.by}: ${m.m}`);
  }
  function chatLine(c, p, m) {
    const d = document.createElement("div");
    d.className = "tgmsg" + (m.id === p.you ? " mine" : "");
    d.innerHTML = m.id === p.you ? esc(m.m) : `<small>${esc(m.by)}</small>${esc(m.m)}`;
    const box = c.querySelector(".tgmsgs"); box.append(d); box.scrollTop = 1e6;
  }

  // ---- The Together tab ----
  const KINDS = { partner: ["❤️", "Partner"], friend: ["🤝", "Friend"], family: ["🏠", "Family"] };
  async function view_() {
    const sel = sessionStorage.getItem("tg.sel");
    view.innerHTML = loading();
    await refresh();
    const ppl = me?.people || [];
    const cur = ppl.find((x) => x.pid === sel) || ppl[0];
    const av = (x, big) => `<div class="tgp ${cur && x.pid === cur.pid ? "on" : ""}" data-pid="${x.pid}"><div class="tga">${esc((x.name || "?")[0])}${x.online ? "<i></i>" : ""}${x.kind === "partner" ? "<em>❤️</em>" : ""}</div><b>${esc(x.name)}</b><small>${I.t(KINDS[x.kind]?.[1] || "Friend")}</small></div>`;
    view.innerHTML = `<div class="pad-top tgpage">
      <h1 class="title">${I.t("Together")}</h1>
      <div class="tgpeople">${ppl.map((x) => av(x)).join("")}<div class="tgp add" id="tgadd"><div class="tga">+</div><b>${I.t("Add someone")}</b><small>${I.t("With a code")}</small></div></div>
      ${cur ? `<div class="tgcard"><div class="tgtop"><div><h2>${esc(I.f("With {1}", cur.name))}</h2>
          <span class="chip">${cur.online ? `<i class="dot"></i>${I.t("On Movio now")}` : I.t("Not on Movio right now")}</span>
          <span class="chip">${KINDS[cur.kind]?.[0] || ""} ${I.t(KINDS[cur.kind]?.[1] || "Friend")}</span></div></div>
        <p class="muted">${esc(I.f("To watch with {1}, open any film or episode and press Watch together. They'll get an invite.", cur.name))}</p>
        <div class="tgrow"><a class="btn white" href="#/">${I.t("Find something to watch")}</a><button class="btn glass" id="tgrm">${I.t("Remove")}</button></div></div>`
      : `<div class="tgcard tgempty"><div class="tgbig">👥</div><h2>${I.t("Watch with the people you love")}</h2>
        <p class="muted">${I.t("Add your partner, friends or family with a code. Then watch films and shows together, in sync, wherever you are.")}</p>
        <button class="btn white" id="tgadd2">${I.t("Add someone")}</button></div>`}
      <div class="tgcard"><h3>${I.t("Join a watch party")}</h3><p class="muted">${I.t("Got a 4-letter party code? Enter it here.")}</p>
        <form class="tgjoin" id="tgjoin"><input maxlength="4" placeholder="ABCD" autocapitalize="characters" autocomplete="off"><button class="btn white">${I.t("Join")}</button></form></div>
    </div>`;
    view.querySelectorAll(".tgp[data-pid]").forEach((d) => (d.onclick = () => { sessionStorage.setItem("tg.sel", d.dataset.pid); view_(); }));
    view.querySelector("#tgadd").onclick = addSheet;
    view.querySelector("#tgadd2")?.addEventListener("click", addSheet);
    view.querySelector("#tgrm")?.addEventListener("click", () => { if (confirm(I.f("Remove {1}?", cur.name))) call("remove", { pid: cur.pid }).then(view_); });
    view.querySelector("#tgjoin").onsubmit = (ev) => { ev.preventDefault(); join(ev.target.querySelector("input").value); };
  }
  function addSheet() {
    const scrim = document.createElement("div"); scrim.className = "scrim";
    const s = document.createElement("div"); s.className = "sheet tgadd";
    let kind = "partner";
    const code = me?.code || "·····";
    const share = `https://wa.me/?text=${encodeURIComponent(I.f("Add me on Movio to watch together. My code is {1}", code) + " — https://g41us.github.io/Movio/")}`;
    s.innerHTML = `<h4>${I.t("Add someone")}</h4><p class="muted">${I.t("Each person or group gets their own shared things.")}</p>
      <div class="label">${I.t("Your code for them")}</div><div class="tgcode">${[...code].map((c) => `<span>${c}</span>`).join("")}</div>
      <a class="btn glass" href="${share}" target="_blank" rel="noopener">${I.t("Share on WhatsApp")}</a>
      <div class="label" style="margin-top:18px">${I.t("Or enter their code")}</div>
      <input class="tgin" maxlength="5" placeholder="ABCDE" autocapitalize="characters" autocomplete="off">
      <div class="label" style="margin-top:14px">${I.t("They are your…")}</div>
      <div class="tgkinds">${Object.entries(KINDS).map(([k, [e, l]]) => `<button data-k="${k}" class="chip ${k === kind ? "on" : ""}">${e} ${I.t(l)}</button>`).join("")}</div>
      <div class="tgerr"></div><button class="btn white wide" id="tgcon">${I.t("Connect")}</button>`;
    const close = () => { s.remove(); scrim.remove(); };
    scrim.onclick = close;
    s.querySelectorAll("[data-k]").forEach((b) => (b.onclick = () => { kind = b.dataset.k; s.querySelectorAll("[data-k]").forEach((x) => x.classList.toggle("on", x === b)); }));
    s.querySelector("#tgcon").onclick = async () => {
      const c = s.querySelector(".tgin").value;
      if (c.trim().length < 5) { s.querySelector(".tgerr").textContent = I.t("Enter their 5-letter code."); return; }
      try { await call("connect", { code: c, kind }); close(); toast(I.t("Connected")); view_(); }
      catch (e) { s.querySelector(".tgerr").textContent = e.message; }
    };
    document.body.append(scrim, s);
  }
  // From a title's page: pick who to invite, then start.
  async function pickAndStart(t, s, e) {
    await refresh();
    const ppl = me?.people || [];
    const scrim = document.createElement("div"); scrim.className = "scrim";
    const d = document.createElement("div"); d.className = "sheet tgadd";
    const on = new Set(ppl.filter((x) => x.kind === "partner").map((x) => x.pid));
    d.innerHTML = `<h4>${I.t("Watch together")}</h4><p class="muted">${esc(t.name)}${t.kind === "tv" ? ` · S${s} E${e}` : ""}</p>
      ${ppl.length ? `<div class="label">${I.t("Invite")}</div><div class="tgpick">${ppl.map((x) => `<button data-pid="${x.pid}" class="${on.has(x.pid) ? "on" : ""}"><span class="tga">${esc(x.name[0])}${x.online ? "<i></i>" : ""}</span>${esc(x.name)}<em>✓</em></button>`).join("")}</div>`
        : `<p class="muted">${I.t("You'll get a code to share. Add people in the Together tab to invite them directly next time.")}</p>`}
      <button class="btn white wide" id="tgstart">${I.t("Start watch party")}</button>`;
    const close = () => { d.remove(); scrim.remove(); };
    scrim.onclick = close;
    d.querySelectorAll("[data-pid]").forEach((b) => (b.onclick = () => { on.has(b.dataset.pid) ? on.delete(b.dataset.pid) : on.add(b.dataset.pid); b.classList.toggle("on"); }));
    d.querySelector("#tgstart").onclick = () => { close(); start(t, s, e, [...on]).catch(() => toast(I.t("Couldn't reach Movio. Try again."))); };
    document.body.append(scrim, d);
  }
  function episode(s, e) { const p = PARTY; if (!p) return; p.st = { playing: true, pos: 0, at: Date.now() + p.skew, s, e }; send(p, { t: "state", playing: true, pos: 0, s, e, why: "next" }); }
  return { view: view_, join, leave, attach, episode, low: (me) => PARTY && lowData(PARTY, me), pickAndStart, refresh, apply: () => PARTY && apply(PARTY, false) };
})();
