(function () {
  if (window.__fx) return; window.__fx = 1;
  var BRAND = 'Movio';
  var FONT = 'https://appassets.androidplatform.net/fonts/';
  var INK = '#07080D', PANEL = '#151722', VIOLET_TXT = '#B79BFF', GOLD = '#FFC94D';
  var GRAD = 'linear-gradient(90deg,#9B6DFF,#FF5FA2)';
  var HAIR = 'rgba(255,255,255,.12)';

  function css(id, text) {
    if (document.getElementById(id)) return;
    var s = document.createElement('style'); s.id = id; s.textContent = text;
    (document.head || document.documentElement).appendChild(s);
  }
  var fontCss =
    "@font-face{font-family:Jakarta;font-weight:400;src:url(" + FONT + "jakarta_regular.ttf)}" +
    "@font-face{font-family:Jakarta;font-weight:500 600;src:url(" + FONT + "jakarta_semibold.ttf)}" +
    "@font-face{font-family:Jakarta;font-weight:700;src:url(" + FONT + "jakarta_bold.ttf)}" +
    "@font-face{font-family:Jakarta;font-weight:800 900;src:url(" + FONT + "jakarta_extrabold.ttf)}";

  function isChallenge() {
    return !!window._cf_chl_opt || /just a moment/i.test(document.title) ||
      !!document.querySelector('#challenge-form, .cf-turnstile, [name=cf-turnstile-response]');
  }

  function fixChallenge() {
    document.title = BRAND;
    css('__cf_css', fontCss +
      "html,body{background:" + INK + "!important;font-family:Jakarta,system-ui,sans-serif!important}" +
      "h1,h2,p,img,footer,.footer,[role=contentinfo],.heading-favicon,.zone-name-title{display:none!important}" +
      ".main-wrapper,.main-content{margin:0!important;padding:0 24px!important;max-width:none!important}" +
      "body{display:flex!important;flex-direction:column!important;justify-content:center!important;min-height:100vh!important}" +
      "body::after{content:'Tap the box to continue';display:block;text-align:center;color:#9EA1B3;font:500 15px Jakarta,system-ui,sans-serif;margin-top:18px;order:-1;margin-bottom:18px}" +
      ".main-content{display:flex!important;flex-direction:column!important;align-items:center!important}");
  }

  // ---------- colour helpers ----------
  function parse(c) {
    var m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/.exec(c || '');
    if (!m) return null;
    return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] };
  }
  function hsl(c) {
    var r = c.r / 255, g = c.g / 255, b = c.b / 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, h = 0, s = 0;
    if (max !== min) {
      var d = max - min;
      s = l > .5 ? d / (2 - max - min) : d / (max + min);
      h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
      h *= 60;
    }
    return { h: h, s: s, l: l };
  }
  function isBlueAccent(x) { return x.h >= 180 && x.h <= 235 && x.s > .4 && x.l > .3 && x.l < .7; }
  function isNavy(x) { return x.h >= 195 && x.h <= 260 && x.l < .32; }
  function isYellow(x) { return x.h >= 35 && x.h <= 58 && x.s > .6 && x.l > .4; }

  function set(el, p, v) { el.style.setProperty(p, v, 'important'); }

  function recolor(el) {
    if (el.__fxc) return; el.__fxc = 1;
    var tag = el.tagName;
    if (tag === 'IFRAME' || tag === 'VIDEO' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'svg') return;
    var cs = getComputedStyle(el);
    var bg = parse(cs.backgroundColor);
    if (bg && bg.a > .05) {
      var x = hsl(bg);
      if (isBlueAccent(x)) {
        set(el, 'background', GRAD); set(el, 'color', '#fff'); set(el, 'border-color', 'transparent');
        if (parseFloat(cs.borderRadius) < 12 && el.offsetHeight < 90) set(el, 'border-radius', '14px');
      } else if (isNavy(x)) {
        var big = el.offsetWidth > innerWidth * .9;
        if (x.l < .1 || big) set(el, 'background-color', bg.a < 1 ? 'rgba(7,8,13,' + bg.a + ')' : INK);
        else {
          set(el, 'background-color', bg.a < 1 ? 'rgba(21,23,34,' + Math.max(bg.a, .85) + ')' : PANEL);
          set(el, 'border', '1px solid ' + HAIR);
          if (parseFloat(cs.borderRadius) < 14) set(el, 'border-radius', '18px');
        }
      }
    }
    var bi = cs.backgroundImage;
    if (bi && bi.indexOf('gradient(') >= 0) {
      var fixed = bi.replace(/rgba?\([^)]*\)/g, function (c) {
        var p = parse(c); if (!p) return c;
        return isNavy(hsl(p)) ? 'rgba(7,8,13,' + p.a + ')' : c;
      });
      if (fixed !== bi) set(el, 'background-image', fixed);
    }
    var fg = parse(cs.color);
    if (fg) {
      var y = hsl(fg);
      if (isBlueAccent(y) || (y.h >= 180 && y.h <= 235 && y.s > .5 && y.l >= .7)) set(el, 'color', VIOLET_TXT);
      else if (isYellow(y)) set(el, 'color', GOLD);
    }
    var bc = parse(cs.borderTopColor);
    if (bc && parseFloat(cs.borderTopWidth) > 0 && isBlueAccent(hsl(bc))) set(el, 'border-color', '#9B6DFF');
  }

  // ---------- structure ----------
  function hide(el) { if (el) set(el, 'display', 'none'); }

  function hideHeader() {
    document.querySelectorAll('img').forEach(function (img) {
      var meta = (img.getAttribute('src') || '') + ' ' + (img.alt || '') + ' ' + (img.className || '');
      if (!/logo/i.test(meta) || img.__fxh) return;
      img.__fxh = 1;
      var box = img.closest('header, nav, [class*="header" i], [id*="header" i]');
      if (!box) {
        box = img;
        while (box.parentElement && box.parentElement !== document.body &&
               box.parentElement.offsetHeight < 200) box = box.parentElement;
      }
      hide(box);
    });
  }

  var BRAND_RE = /(h{1,2}d\s*today|bra\s*flix)(\.(tv|at|to|com))?/gi;
  function fixText(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) {
      if (BRAND_RE.test(n.nodeValue)) { BRAND_RE.lastIndex = 0; n.nodeValue = n.nodeValue.replace(BRAND_RE, BRAND); }
    }
  }

  function hideJunk() {
    document.querySelectorAll('a[href]').forEach(function (a) {
      try {
        var h = new URL(a.href, location.href).hostname;
        var own = location.hostname.split('.').slice(-2).join('.');
        if (h && h.slice(-own.length) !== own && a.querySelector('img')) hide(a);
      } catch (e) {}
    });
    document.querySelectorAll('img[src*="aliexpress" i],img[src*="banner" i],img[src*="/ads/" i],img[alt*="aliexpress" i],a[href*="aliexpress" i],a[href*="click." i],a[href*="/aff" i]').forEach(function (el) { hide(el.closest('a') || el); });
    document.querySelectorAll('img').forEach(function (img) {
      var w = img.naturalWidth, h = img.naturalHeight;
      if (w && h && w / h > 4.5 && h < 160) hide(img.closest('a') || img);
    });
    document.querySelectorAll('iframe').forEach(function (f) {
      var r = f.getBoundingClientRect();
      if (r.height > 0 && r.height <= 130 && r.width > 200) hide(f);
    });
    document.querySelectorAll('body *').forEach(function (el) {
      if (el.children.length > 3) return;
      var t = (el.textContent || '').trim();
      if (/^tags\s*:/i.test(t) && t.length < 12) hide(el.parentElement);
    });
    document.querySelectorAll('[class*="share" i]').forEach(function (el) {
      if (!el.querySelector('video,iframe')) hide(el);
    });
  }

  // Tell the app where the Movies / TV pages live so the dock can open them.
  function reportLinks() {
    if (!window.MovioApp) return;
    var found = {};
    document.querySelectorAll('a[href]').forEach(function (a) {
      var t = (a.textContent || '').trim().toLowerCase();
      if (!found.movies && /^movies?$/.test(t)) found.movies = a.href;
      if (!found.tv && /^(tv[\s-]*shows?|tv[\s-]*series|series|tv)$/.test(t)) found.tv = a.href;
    });
    if (found.movies || found.tv) MovioApp.links(JSON.stringify(found));
  }

  window.__movioSearch = function (q) {
    var input = document.querySelector('input[type=search], input[name=keyword], input[name=q], input[name=s], input[name=query], input[name=search], input[placeholder*="search" i]');
    if (!input) return false;
    input.value = q;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    var form = input.form;
    if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); return true; }
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
    return true;
  };

  function fixSite() {
    if (document.title) document.title = BRAND;
    css('__site_css', fontCss +
      "html,body{background:" + INK + "!important}" +
      "body,body :not(i):not([class*='fa']):not([class*='icon' i]):not([class*='glyph' i]){font-family:Jakarta,system-ui,sans-serif!important}" +
      "body{padding-bottom:var(--fx-dock,140px)!important;color:#fff}" +
      "h1,h2,h3{letter-spacing:-.02em!important;font-weight:800!important}" +
      "a img{border-radius:14px!important}" +
      "input,select,textarea{background:rgba(255,255,255,.09)!important;border:1px solid " + HAIR + "!important;border-radius:14px!important;color:#fff!important}" +
      "footer,.footer,#footer{display:none!important}" +
      "a[href*='facebook.com'],a[href*='twitter.com'],a[href*='x.com/intent'],a[href*='whatsapp'],a[href*='t.me/share']{display:none!important}");
    hideHeader();
    hideJunk();
    var els = document.body.getElementsByTagName('*');
    for (var i = 0; i < els.length && i < 4000; i++) recolor(els[i]);
    fixText(document.body);
    reportLinks();
  }

  var pending = false;
  function run() {
    pending = false;
    if (!document.body) return;
    if (isChallenge()) fixChallenge(); else fixSite();
  }
  function schedule() { if (!pending) { pending = true; setTimeout(run, 120); } }
  document.addEventListener('DOMContentLoaded', function () {
    run();
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  });
  if (document.readyState !== 'loading') schedule();
})();
