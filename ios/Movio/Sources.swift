import Foundation

/// A player site that streams any title by TMDB id (same list as the Android app).
/// [web] sources only play inside their own page.
struct StreamSource: Hashable {
    let name: String
    let base: String
    var web = false

    func url(_ kind: Kind, _ id: Int, _ s: Int, _ e: Int) -> URL {
        if kind == .movie { return URL(string: "\(base)/movie/\(id)" + query(movie: true))! }
        if name == "Club" { return URL(string: "\(base)/tv/\(id)-\(s)-\(e)")! }
        return URL(string: "\(base)/tv/\(id)/\(s)/\(e)" + query(movie: false))!
    }

    private func query(movie: Bool) -> String {
        switch name {
        case "Vix": return "?autoplay=true"
        case "Braflix": return movie ? "" : "?autonext=1&ds_lang=en"
        default: return ""
        }
    }
}

enum Sources {
    static let all: [StreamSource] = [
        StreamSource(name: "Nero", base: "https://vidfast.pro"),
        StreamSource(name: "Flixify", base: "https://vidflix.club"),
        StreamSource(name: "Club", base: "https://moviesapi.to"),
        StreamSource(name: "Azute", base: "https://vidrock.to"),
        StreamSource(name: "Cine", base: "https://cinesrc.st/embed"),
        StreamSource(name: "Main", base: "https://player.vidzee.wtf/embed"),
        StreamSource(name: "Vid", base: "https://embed.wplay.me/embed"),
        StreamSource(name: "Peach", base: "https://peachify.net/embed"),
        StreamSource(name: "Mist", base: "https://play.xpass.top/e"),
        StreamSource(name: "Nest", base: "https://vidnest.fun"),
        StreamSource(name: "Pass", base: "https://vidcore.net"),
        StreamSource(name: "Vidmux", base: "https://vidlux.site/embed"),
        StreamSource(name: "Vix", base: "https://vixsrc.to"),
        StreamSource(name: "Vidsrc HD", base: "https://braflix.dev/embed", web: true),
        StreamSource(name: "Vidads", base: "https://vidstuck.cc/embed", web: true),
        StreamSource(name: "Braflix", base: "https://api.cineby.homes/embed", web: true),
        StreamSource(name: "Vidind", base: "https://player.vidify.top/embed", web: true),
        StreamSource(name: "Mistify", base: "https://vaplayer.ru/embed", web: true),
        StreamSource(name: "Diablo", base: "https://vsembed.ru/embed", web: true),
        StreamSource(name: "Vidsrc", base: "https://vidsrc2.ru/embed", web: true),
    ]

    /// Tried in order when a source can't play a title.
    static let fallback: [Int] = Array(all.indices.filter { !all[$0].web }.prefix(8))

    static func index(_ name: String?) -> Int { all.firstIndex { $0.name == name } ?? 0 }

    /// Average start time per source (failures count as slow), so the quickest are tried first.
    static func score(_ i: Int, _ seconds: Double) {
        let k = "speed:\(all[i].name)"
        let old = UserDefaults.standard.object(forKey: k) as? Double
        UserDefaults.standard.set(old.map { ($0 * 2 + seconds) / 3 } ?? seconds, forKey: k)
    }

    /// Shows go Club → Nero → Peach → Flixify, then the rest by speed. Films go fastest first.
    static func order(_ kind: Kind) -> [Int] {
        guard kind == .tv else { return fastest }
        var fixed: [Int] = []
        for n in ["Club", "Nero", "Peach", "Flixify"] { let i = index(n); if !fixed.contains(i) { fixed.append(i) } }
        return fixed + fastest.filter { !fixed.contains($0) }
    }

    static var fastest: [Int] {
        fallback.sorted { (UserDefaults.standard.object(forKey: "speed:\(all[$0].name)") as? Double ?? 12) < (UserDefaults.standard.object(forKey: "speed:\(all[$1].name)") as? Double ?? 12) }
    }
}

/// Injected into every frame of a source page: reports the video playlists/files the page loads,
/// so Movio can play them in its own player. Mutes the page's own video while we look.
let sniffJS = """
(function () {
  if (window.__ms) return; window.__ms = 1;
  var H = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.movio;
  if (!H) return;
  function post(t, u) { try { H.postMessage({ t: t, u: u, p: location.href }); } catch (e) {} }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return null; } }
  function report(u, ct, text) {
    u = abs(u); if (!u || u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
    if ((ct && /mpegurl/i.test(ct)) || (text && text.slice(0, 7) === '#EXTM3U') || /\\.m3u8(\\?|$|#)/i.test(u)) post('stream', u);
    else if (ct && /^video\\/(mp4|webm)/i.test(ct)) post('video', u);
    else if (/\\.(vtt|srt)(\\?|$)/i.test(u)) post('sub', u);
  }
  var of = window.fetch;
  if (of) window.fetch = function (input, init) {
    var u = typeof input === 'string' ? input : (input && input.url);
    return of.apply(this, arguments).then(function (r) {
      try {
        var ct = r.headers.get('content-type') || '';
        if (/mpegurl|^video\\//i.test(ct) || /\\.(m3u8|vtt|srt)/i.test(u)) report(r.url || u, ct);
        else if (/text\\/plain|^$/i.test(ct)) r.clone().text().then(function (t) { if (t.slice(0, 7) === '#EXTM3U') report(r.url || u, ct, t); }).catch(function () {});
      } catch (e) {}
      return r;
    });
  };
  var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this.__mu = u; return open.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    var x = this;
    x.addEventListener('load', function () {
      try {
        var ct = x.getResponseHeader('content-type') || '';
        var t = (x.responseType === '' || x.responseType === 'text') ? (x.responseText || '').slice(0, 16) : '';
        report(x.responseURL || x.__mu, ct, t);
      } catch (e) {}
    });
    return send.apply(this, arguments);
  };
  var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
  if (d && d.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
    get: d.get, configurable: true,
    set: function (v) {
      if (v && !/^(blob|data):/.test(v)) { if (/\\.m3u8/i.test(v)) post('stream', abs(v)); else post('video', abs(v)); }
      return d.set.call(this, v);
    },
  });
  // Movio is showing this page: let its videos play with sound, in every frame.
  window.addEventListener('message', function (e) {
    if (e.data !== 'movio-show') return;
    window.__show = 1;
    try { document.querySelectorAll('video').forEach(function (v) { v.muted = false; }); } catch (x) {}
    try { document.querySelectorAll('iframe').forEach(function (f) { f.contentWindow.postMessage('movio-show', '*'); }); } catch (x) {}
  });
  document.addEventListener('playing', function (e) { try { if (e.target.tagName === 'VIDEO' && !window.__show) e.target.muted = true; } catch (x) {} }, true);
  // Many players wait for a tap on play: tap the middle of each frame a few times.
  [2500, 5000, 8000].forEach(function (ms) {
    setTimeout(function () {
      if (window.__show) return;
      try {
        var v = document.querySelector('video'); if (v) { v.muted = true; v.play && v.play().catch(function () {}); }
        var el = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
        if (el) el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
      } catch (e) {}
    }, ms);
  });
})();
"""
