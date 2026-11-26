import Foundation
import SwiftUI

/// What to play: a film, or one episode of a show.
struct PlayRequest: Identifiable, Hashable {
    let title: Title
    var season: Int = 1
    var episode: Int = 1
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
        if inList(t) { myList.removeAll { $0.key == t.key } } else { myList.insert(t, at: 0) }
        save("myList", myList)
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
        if done && t.kind == .movie { recent[t.key] = nil }
        else { recent[t.key] = Progress(title: t, season: s, episode: e, position: pos, duration: dur, updated: Date()) }
        save("recent", recent)
    }

    func remove(_ t: Title) { recent[t.key] = nil; save("recent", recent) }

    var continueWatching: [Progress] { recent.values.sorted { $0.updated > $1.updated } }

    func setSource(_ name: String) { sourceName = name; d.set(name, forKey: "source") }

    func play(_ t: Title, season: Int? = nil, episode: Int? = nil) {
        if t.kind == .tv, season == nil, let p = recent[t.key] {
            playing = PlayRequest(title: t, season: p.season, episode: p.episode); return
        }
        playing = PlayRequest(title: t, season: season ?? 1, episode: episode ?? 1)
    }
}
