import Foundation
import SwiftUI

/// What to play: a film, or one episode of a show.
struct PlayRequest: Identifiable, Hashable {
    let title: Title
    var season: Int = 1
    var episode: Int = 1
    /// Set when playing a download.
    var offline: String? = nil
    var id: String { "\(title.key):\(season):\(episode)" }
}

/// Where you are in something you started watching.
struct Progress: Codable, Hashable {
    var title: Title
    var season: Int
    var episode: Int
    var position: Double
    var duration: Double
    var updated: Date
    var fraction: Double { duration > 0 ? min(1, position / duration) : 0 }
}

/// My List, resume positions and Continue watching, kept on the phone.
@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published var myList: [Title] = []
    @Published private(set) var recent: [String: Progress] = [:]
    @Published var playing: PlayRequest?
    @Published var sourceName: String?

    private var positions: [String: [Double]] = [:]
    private let d = UserDefaults.standard

    private init() {
        myList = load("myList") ?? []
        liked = load("liked") ?? []
        recent = load("recent") ?? [:]
        positions = load("positions") ?? [:]
        sourceName = d.string(forKey: "source")
    }

    private func load<T: Decodable>(_ k: String) -> T? {
        guard let data = d.data(forKey: k) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
    private func save<T: Encodable>(_ k: String, _ v: T) {
        if let data = try? JSONEncoder().encode(v) { d.set(data, forKey: k) }
    }

    func inList(_ t: Title) -> Bool { myList.contains { $0.key == t.key } }
    func toggle(_ t: Title) {
        let had = inList(t)
        if had { myList.removeAll { $0.key == t.key } } else { myList.insert(t, at: 0) }
        save("myList", myList)
        mark("l:\(t.key)", removed: had)
    }

    static func posKey(_ t: Title, _ s: Int, _ e: Int) -> String { t.kind == .tv ? "\(t.key):\(s):\(e)" : t.key }

    func position(_ t: Title, _ s: Int, _ e: Int) -> (Double, Double)? {
        guard let p = positions[Store.posKey(t, s, e)], p.count == 2 else { return nil }
        return (p[0], p[1])
    }

    func savePosition(_ t: Title, _ s: Int, _ e: Int, _ pos: Double, _ dur: Double) {
        guard dur > 0 else { return }
        positions[Store.posKey(t, s, e)] = [pos, dur]
        save("positions", positions)
        // Finished films drop off Continue watching; a finished episode leaves the show there for the next one.
        let done = pos > dur - 90
        if done && t.kind == .movie { recent[t.key] = nil; mark("c:\(t.key)", removed: true, quiet: true) }
        else { recent[t.key] = Progress(title: t, season: s, episode: e, position: pos, duration: dur, updated: Date()); mark("c:\(t.key)", removed: false, quiet: true) }
        save("recent", recent)
        mark("p:\(Store.posKey(t, s, e))", removed: false, quiet: true)
    }

    func remove(_ t: Title) { recent[t.key] = nil; save("recent", recent); mark("c:\(t.key)", removed: true) }

    // MARK: Likes, search history, and what people watch (counts only)

    @Published private(set) var liked: [Title] = []
    @Published private(set) var searches: [String] = UserDefaults.standard.stringArray(forKey: "searches") ?? []
    func isLiked(_ t: Title) -> Bool { liked.contains { $0.key == t.key } }
    @discardableResult
    func toggleLike(_ t: Title, genres: [String]) -> Bool {
        let on = !isLiked(t)
        if on { liked.insert(t, at: 0) } else { liked.removeAll { $0.key == t.key } }
        save("liked", liked)
        stat(on ? "like" : "unlike", t, genres)
        return on
    }
    func stat(_ ev: String, _ t: Title, _ genres: [String]) {
        guard let url = URL(string: "\(Store.helper)/stat") else { return }
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.httpMethod = "POST"
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["ev": ev, "kind": t.kind.rawValue, "id": t.id, "name": t.name, "poster": t.poster ?? NSNull(), "genres": genres])
        URLSession.shared.dataTask(with: req).resume()
    }
    func rememberSearch(_ q: String) {
        let v = q.trimmingCharacters(in: .whitespaces)
        guard v.count >= 2 else { return }
        searches = Array(([v] + searches.filter { $0.lowercased() != v.lowercased() }).prefix(12))
        d.set(searches, forKey: "searches")
    }
    func forgetSearch(_ q: String?) {
        if let q { searches.removeAll { $0 == q } } else { searches = [] }
        d.set(searches, forKey: "searches")
    }

    // MARK: Sync between devices (a short link code, kept by Movio's helper)

    static let helper = "https://movio-helper.g41us-jarvis.workers.dev"
    @Published var syncCode: String = UserDefaults.standard.string(forKey: "syncCode") ?? "" { didSet { d.set(syncCode, forKey: "syncCode") } }
    @Published var kids: Bool = UserDefaults.standard.bool(forKey: "kids") { didSet { d.set(kids, forKey: "kids") } }
    private var ledger: [String: Double] = (UserDefaults.standard.dictionary(forKey: "syncAt") as? [String: Double]) ?? [:]
    private var pushTask: Task<Void, Never>?
    private var busy = false

    private struct Item: Codable { var at: Double; var del: Bool?; var t: Title?; var s: Int?; var e: Int?; var p: Double?; var d: Double? }
    private struct Prof: Codable { var at: Double; var name: String }
    private struct State: Codable { var list: [String: Item] = [:]; var cont: [String: Item] = [:]; var pos: [String: Item] = [:]; var prof: Prof? }
    /// When this device last synced.
    @Published var lastSync: Date? = UserDefaults.standard.object(forKey: "lastSync") as? Date
    private struct Reply: Codable { var ok: Bool; var state: State?; var code: String? }

    private var now: Double { (Date().timeIntervalSince1970 * 1000).rounded() }
    private func at(_ k: String) -> Double { abs(ledger[k] ?? 0) }

    private func mark(_ k: String, removed: Bool, quiet: Bool = false) {
        ledger[k] = removed ? -now : now
        d.set(ledger, forKey: "syncAt")
        guard !syncCode.isEmpty else { return }
        pushTask?.cancel()
        pushTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: quiet ? 15_000_000_000 : 1_500_000_000)
            if !Task.isCancelled { await self?.sync() }
        }
    }

    private func state() -> State {
        var st = State()
        for (i, t) in myList.enumerated() { st.list[t.key] = Item(at: at("l:\(t.key)") > 0 ? at("l:\(t.key)") : Double(1000 - i), t: t) }
        for p in recent.values {
            st.cont[p.title.key] = Item(at: max(at("c:\(p.title.key)"), p.updated.timeIntervalSince1970 * 1000), t: p.title, s: p.season, e: p.episode)
        }
        for (k, v) in positions where v.count == 2 {
            st.pos[k] = Item(at: at("p:\(k)") > 0 ? at("p:\(k)") : 1, p: v[0] > v[1] - 90 ? -1 : v[0], d: v[1])
        }
        for (k, v) in ledger where v < 0 {
            let rest = String(k.dropFirst(2))
            if k.hasPrefix("l:"), st.list[rest] == nil { st.list[rest] = Item(at: -v, del: true) }
            if k.hasPrefix("c:"), st.cont[rest] == nil { st.cont[rest] = Item(at: -v, del: true) }
        }
        // Your name travels too, so a newly linked device can greet you. A changed name counts as newer.
        let name = Profile.shared.name.trimmingCharacters(in: .whitespaces)
        if !name.isEmpty {
            if name != d.string(forKey: "nameLast") { d.set(name, forKey: "nameLast"); d.set(now, forKey: "nameAt") }
            st.prof = Prof(at: max(d.double(forKey: "nameAt"), 1), name: name)
        }
        return st
    }

    private func apply(_ st: State) {
        for (k, v) in st.list where v.at > at("l:\(k)") {
            myList.removeAll { $0.key == k }
            if v.del != true, let t = v.t { myList.append(t) }
            ledger["l:\(k)"] = v.del == true ? -v.at : v.at
        }
        myList.sort { at("l:\($0.key)") > at("l:\($1.key)") }
        save("myList", myList)
        for (k, v) in st.pos where v.at > at("p:\(k)") {
            guard let dur = v.d, let p = v.p else { continue }
            positions[k] = [p < 0 ? dur : p, dur]
            ledger["p:\(k)"] = v.at
        }
        save("positions", positions)
        for (k, v) in st.cont where v.at > at("c:\(k)") {
            if v.del == true { recent[k] = nil }
            else if let t = v.t {
                let s = v.s ?? 1, e = v.e ?? 1
                let pp = positions[Store.posKey(t, s, e)] ?? [0, 0]
                recent[k] = Progress(title: t, season: s, episode: e, position: pp.first ?? 0, duration: pp.last ?? 0, updated: Date(timeIntervalSince1970: v.at / 1000))
            }
            ledger["c:\(k)"] = v.del == true ? -v.at : v.at
        }
        save("recent", recent)
        d.set(ledger, forKey: "syncAt")
        if let p = st.prof, !p.name.isEmpty, p.at > d.double(forKey: "nameAt") {
            Profile.shared.name = p.name
            d.set(p.name, forKey: "nameLast"); d.set(p.at, forKey: "nameAt")
        }
        lastSync = Date(); d.set(lastSync, forKey: "lastSync")
    }

    /// Sends this device's lists, gets everyone's back, merged.
    @discardableResult
    func sync() async -> Bool {
        guard !syncCode.isEmpty, !busy, let url = URL(string: "\(Store.helper)/sync/\(syncCode)") else { return false }
        busy = true
        defer { busy = false }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = "PUT"
        req.httpBody = try? JSONEncoder().encode(state())
        guard let (data, res) = try? await URLSession.shared.data(for: req) else { return false }
        if (res as? HTTPURLResponse)?.statusCode == 404 { syncCode = ""; return false }
        guard let r = try? JSONDecoder().decode(Reply.self, from: data), r.ok, let st = r.state else { return false }
        apply(st)
        return true
    }

    func createCode() async -> String? {
        guard let url = URL(string: "\(Store.helper)/sync/new") else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = "POST"
        guard let (data, _) = try? await URLSession.shared.data(for: req), let r = try? JSONDecoder().decode(Reply.self, from: data), let c = r.code else { return nil }
        syncCode = c
        await sync()
        return c
    }

    func joinCode(_ raw: String) async -> Bool {
        let c = raw.uppercased().filter { $0.isLetter || $0.isNumber }
        guard c.count == 6, let url = URL(string: "\(Store.helper)/sync/\(c)"),
              let (_, res) = try? await URLSession.shared.data(from: url), (res as? HTTPURLResponse)?.statusCode == 200 else { return false }
        syncCode = c
        return await sync()
    }

    var continueWatching: [Progress] { recent.values.sorted { $0.updated > $1.updated } }

    func setSource(_ name: String) { sourceName = name; d.set(name, forKey: "source") }

    func play(_ t: Title, season: Int? = nil, episode: Int? = nil) {
        if t.kind == .tv, season == nil, let p = recent[t.key] {
            playing = PlayRequest(title: t, season: p.season, episode: p.episode); return
        }
        playing = PlayRequest(title: t, season: season ?? 1, episode: episode ?? 1)
    }
}
