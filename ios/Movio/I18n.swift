import SwiftUI

/// Movio's words in the viewer's language (English, French, Spanish, Portuguese, Arabic), plus what the
/// first-time setup asks: name, app language and subtitle language. Words come from i18n.json (shared with Android and web).
@MainActor
final class Profile: ObservableObject {
    static let shared = Profile()
    static var langs: [I18n.Lang] { I18n.langs }
    private let d = UserDefaults.standard

    @Published var lang: String { didSet { I18n.lang = lang; d.set(lang, forKey: "p.lang") } }
    @Published var subLang: String { didSet { I18n.subLang = subLang; d.set(subLang, forKey: "p.sub") } }
    @Published var name: String { didSet { d.set(name, forKey: "p.name") } }
    @Published var setupDone: Bool { didSet { d.set(setupDone, forKey: "p.done") } }

    private init() {
        let ud = UserDefaults.standard
        let device = Locale.preferredLanguages.first.map { String($0.prefix(2)) } ?? "en"
        let l = ud.string(forKey: "p.lang") ?? (I18n.langs.contains { $0.code == device } ? device : "en")
        lang = l
        subLang = ud.string(forKey: "p.sub") ?? l
        name = ud.string(forKey: "p.name") ?? ""
        setupDone = ud.bool(forKey: "p.done")
        I18n.lang = l
        I18n.subLang = subLang
    }

    var rtl: Bool { lang == "ar" }

    var greeting: String? {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return nil }
        let h = Calendar.current.component(.hour, from: Date())
        return I18n.f(h >= 5 && h < 12 ? "Good morning, {1}" : h >= 12 && h < 17 ? "Good afternoon, {1}" : "Good evening, {1}", n)
    }
}

enum I18n {
    struct Lang: Hashable { let code: String; let native: String; let english: String; let tmdb: String }
    static let langs = [
        Lang(code: "en", native: "English", english: "English", tmdb: "en-US"),
        Lang(code: "fr", native: "Français", english: "French", tmdb: "fr-FR"),
        Lang(code: "es", native: "Español", english: "Spanish", tmdb: "es-ES"),
        Lang(code: "pt", native: "Português", english: "Portuguese", tmdb: "pt-BR"),
        Lang(code: "ar", native: "العربية", english: "Arabic", tmdb: "ar-SA"),
    ]
    static var lang = UserDefaults.standard.string(forKey: "p.lang") ?? "en"
    static var subLang = UserDefaults.standard.string(forKey: "p.sub") ?? "en"
    static var tmdb: String { langs.first { $0.code == lang }?.tmdb ?? "en-US" }

    private static let table: [String: [String: String]] = {
        guard let u = Bundle.main.url(forResource: "i18n", withExtension: "json"), let data = try? Data(contentsOf: u),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: [String: String]] else { return [:] }
        return o
    }()
    /// "Season {1}" also matches "Season 3": numbers fill the gaps.
    private static let patterns: [String: [(NSRegularExpression, String)]] = table.mapValues { m in
        m.keys.filter { $0.contains("{") }.sorted { $0.count > $1.count }.compactMap { k in
            let parts = k.components(separatedBy: CharacterSet(charactersIn: "{}")).enumerated()
                .map { $0.offset % 2 == 0 ? NSRegularExpression.escapedPattern(for: $0.element) : "(\\d[\\d.,:]*)" }
            return (try? NSRegularExpression(pattern: "^" + parts.joined() + "$")).map { ($0, k) }
        }
    }

    static func t(_ s: String) -> String {
        guard lang != "en", !s.isEmpty, let m = table[lang] else { return s }
        if let hit = m[s] { return hit }
        guard s.contains(where: \.isNumber) else { return s }
        let ns = s as NSString
        for (re, key) in patterns[lang] ?? [] {
            guard let r = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)), let tpl = m[key] else { continue }
            return fill(tpl, (1..<r.numberOfRanges).map { ns.substring(with: r.range(at: $0)) })
        }
        return s
    }

    /// A sentence with a name in it, e.g. f("Because you watched {1}", title).
    static func f(_ key: String, _ args: Any...) -> String {
        fill(lang == "en" ? key : table[lang]?[key] ?? key, args.map { "\($0)" })
    }

    private static func fill(_ tpl: String, _ args: [String]) -> String {
        var r = tpl
        for (i, a) in args.enumerated() { r = r.replacingOccurrences(of: "{\(i + 1)}", with: a) }
        return r
    }

    /// Row names with a title or genre in them.
    static func row(_ name: String) -> String {
        guard lang != "en" else { return name }
        let direct = t(name)
        if direct != name { return direct }
        for p in ["Because you watched ", "Highly rated ", "Popular ", "More "] where name.hasPrefix(p) {
            let rest = String(name.dropFirst(p.count))
            return f(p + "{1}", p == "Because you watched " ? rest : t(rest))
        }
        return name
    }
}

func L(_ s: String) -> String { I18n.t(s) }

// Every plain-text label in Movio goes through I18n, so it shows in the viewer's language.
extension Text {
    init(_ s: String) { self.init(verbatim: I18n.t(s)) }
}
extension Label where Title == Text, Icon == Image {
    init(_ s: String, systemImage: String) { self.init { Text(verbatim: I18n.t(s)) } icon: { Image(systemName: systemImage) } }
}
extension View {
    func navigationTitle(_ s: String) -> some View { navigationTitle(Text(verbatim: I18n.t(s))) }
}
extension Button where Label == Text {
    init(_ s: String, action: @escaping () -> Void) { self.init(action: action) { Text(verbatim: I18n.t(s)) } }
}
