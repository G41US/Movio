import Foundation

struct SubTrack: Hashable, Identifiable {
    let label: String
    let lang: String
    let url: URL
    var id: String { url.absoluteString }
}

struct Cue { let start: Double; let end: Double; let text: String }

enum Subtitles {
    /// Up to 5 English versions (most downloaded, plain before hearing-impaired) plus one of a few other languages.
    static func find(_ kind: Kind, _ id: Int, _ s: Int, _ e: Int) async -> [SubTrack] {
        guard !Keys.wyzie.isEmpty else { return [] }
        let ep = kind == .tv ? "&season=\(s)&episode=\(e)" : ""
        guard let url = URL(string: "https://sub.wyzie.io/search?id=\(id)\(ep)&language=en,fr,es,pt,ar&key=\(Keys.wyzie)"),
              let res = try? await URLSession.shared.data(from: url),
              let a = try? JSONSerialization.jsonObject(with: res.0) as? [[String: Any]] else { return [] }
        let wanted = ["en", "fr", "es", "pt", "ar"]
        let groups = Dictionary(grouping: a.filter { ($0["url"] as? String)?.hasPrefix("https://") == true }) { $0["language"] as? String ?? "" }
        var out: [SubTrack] = []
        for lang in groups.keys.sorted(by: { (wanted.firstIndex(of: $0) ?? 99) < (wanted.firstIndex(of: $1) ?? 99) }) {
            let list = groups[lang]!.sorted {
                let h0 = $0["isHearingImpaired"] as? Bool ?? false, h1 = $1["isHearingImpaired"] as? Bool ?? false
                if h0 != h1 { return !h0 }
                return ($0["downloadCount"] as? Int ?? 0) > ($1["downloadCount"] as? Int ?? 0)
            }
            for (i, o) in list.prefix(lang == "en" ? 5 : 1).enumerated() {
                let name = (o["display"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? Locale.current.localizedString(forLanguageCode: lang) ?? lang
                if let u = URL(string: o["url"] as! String) { out.append(SubTrack(label: i == 0 ? name : "\(name) · \(i + 1)", lang: lang, url: u)) }
            }
        }
        return out
    }

    /// Backup list from the player service behind braflix (often 2+ English versions per title).
    static func party(_ kind: Kind, _ id: Int, _ s: Int, _ e: Int) async -> [SubTrack] {
        let base = "https://vidsrc.party"
        let path = kind == .tv ? "api/play/tv/\(id)/\(s)/\(e)" : "api/play/movie/\(id)"
        let h = ["Referer": base + "/", "Origin": base, "Accept": "application/json"]
        var file: [String: Any]?
        for _ in 0..<6 {
            guard let u = URL(string: "\(base)/\(path)?async=1&wait=1"), let d = await StreamAudio.fetch(u, h),
                  let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [] }
            file = (o["result"] as? [String: Any])?["file"] as? [String: Any]
            if file != nil && (o["status"] as? String) == "ready" { break }
            if ["error", "failed", "not_found"].contains(o["status"] as? String ?? "") { return [] }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
        guard let subs = file?["subtitles"] as? String, let u = URL(string: base + subs), let d = await StreamAudio.fetch(u, h),
              let a = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] else { return [] }
        var count: [String: Int] = [:]
        var out: [SubTrack] = []
        for o in a {
            guard let lang = o["lang"] as? String, let src = o["src"] as? String, src.hasSuffix(".vtt") || src.hasSuffix(".srt"),
                  let url = URL(string: base + src) else { continue }
            let n = count[lang, default: 0]
            if n >= (lang == "en" ? 5 : 1) { continue }
            count[lang] = n + 1
            let name = Locale.current.localizedString(forLanguageCode: lang) ?? (o["label"] as? String ?? lang)
            out.append(SubTrack(label: name + " · Alt" + (n == 0 ? "" : " \(n + 1)"), lang: lang, url: url))
        }
        return out.sorted { ($0.lang == "en" ? 0 : 1) < ($1.lang == "en" ? 0 : 1) }
    }

    static func load(_ url: URL, headers: [String: String] = [:]) async -> [Cue] {
        var req = URLRequest(url: url)
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        guard let res = try? await URLSession.shared.data(for: req) else { return [] }
        let data = res.0
        let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
        return parse(text)
    }

    /// Reads SRT or WebVTT into plain cues.
    static func parse(_ text: String) -> [Cue] {
        let stamp = try! NSRegularExpression(pattern: "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
        let tag = try! NSRegularExpression(pattern: "<[^>]+>|\\{\\\\[^}]*\\}")
        func secs(_ line: String) -> [Double] {
            let ns = line as NSString
            return stamp.matches(in: line, range: NSRange(location: 0, length: ns.length)).map { m in
                func g(_ i: Int) -> Double { let r = m.range(at: i); return r.location == NSNotFound ? 0 : Double(ns.substring(with: r)) ?? 0 }
                return g(1) * 3600 + g(2) * 60 + g(3) + g(4) / 1000
            }
        }
        var out: [Cue] = []
        let lines = text.replacingOccurrences(of: "\r", with: "").components(separatedBy: "\n")
        var i = 0
        while i < lines.count {
            let l = lines[i]
            if l.contains("-->") {
                let t = secs(l)
                i += 1
                var body: [String] = []
                while i < lines.count && !lines[i].trimmingCharacters(in: .whitespaces).isEmpty { body.append(lines[i]); i += 1 }
                if t.count >= 2 {
                    var s = body.joined(separator: "\n")
                    s = tag.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: "")
                    s = s.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&nbsp;", with: " ")
                        .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if !s.isEmpty && !isAd(s) { out.append(Cue(start: t[0], end: t[1], text: s)) }
                }
            } else { i += 1 }
        }
        return out.sorted { $0.start < $1.start }
    }

    /// A web address inside a subtitle line means it's an ad, not dialogue.
    static func isAd(_ s: String) -> Bool {
        s.range(of: "(?i)(https?://|www\\.|\\b[a-z0-9-]{2,}\\.(ru|com|net|org|io|tv|to|me|xyz|cc|info|co|top|live|site|online|club|vip)\\b)", options: .regularExpression) != nil
    }
}
