import Foundation

enum Kind: String, Codable, Hashable { case movie, tv }

struct Title: Codable, Hashable, Identifiable {
    let id: Int
    let kind: Kind
    let name: String
    let overview: String
    let poster: String?
    let backdrop: String?
    let year: String?
    let rating: Double

    var key: String { "\(kind.rawValue):\(id)" }
}

struct Season: Hashable { let number: Int; let name: String; let episodeCount: Int }
struct Episode: Hashable, Identifiable { let number: Int; let name: String; let overview: String; let still: String?; let runtime: Int?; var id: Int { number } }
struct Person: Hashable { let id: Int; let name: String; let role: String; let photo: String? }

struct Details {
    let title: Title
    let tagline: String
    let runtime: Int?
    let genres: [String]
    let seasons: [Season]
    let cast: [Person]
    let similar: [Title]
    let logo: String?
}

/// A person and everything they've been in.
struct PersonDetails {
    let person: Person
    let facts: String
    let bio: String
    let credits: [Credit]
}

struct Credit: Hashable { let title: Title; let role: String }

struct Row: Identifiable {
    let name: String
    let items: [Title]
    var ranked = false
    var id: String { name }
}

/// TMDB catalogue (the same one the Android app uses).
enum TMDB {
    private static let api = "https://api.themoviedb.org/3/"
    private static var cache: [String: (Date, [String: Any])] = [:]
    private static let lock = NSLock()

    static func img(_ path: String?, _ size: String = "w500") -> URL? {
        guard let p = path else { return nil }
        return URL(string: "https://image.tmdb.org/t/p/\(size)\(p)")
    }

    static func get(_ path: String, _ params: [String: String] = [:]) async throws -> [String: Any] {
        var c = URLComponents(string: api + path)!
        c.queryItems = (params.merging(["api_key": Keys.tmdb, "language": I18n.tmdb]) { a, _ in a }).map { URLQueryItem(name: $0.key, value: $0.value) }
        let url = c.url!.absoluteString
        lock.lock(); let hit = cache[url]; lock.unlock()
        if let hit, Date().timeIntervalSince(hit.0) < 6 * 3600 { return hit.1 }
        let (data, resp) = try await URLSession.shared.data(from: c.url!)
        guard (resp as? HTTPURLResponse)?.statusCode ?? 0 < 300,
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw URLError(.badServerResponse) }
        lock.lock(); cache[url] = (Date(), o); lock.unlock()
        return o
    }

    static func parse(_ o: [String: Any], _ forced: Kind?) -> Title? {
        let kind: Kind
        if let f = forced { kind = f } else {
            switch o["media_type"] as? String { case "movie": kind = .movie; case "tv": kind = .tv; default: return nil }
        }
        guard let id = o["id"] as? Int else { return nil }
        let name = (o["title"] as? String) ?? (o["name"] as? String) ?? ""
        if name.isEmpty { return nil }
        let date = (o["release_date"] as? String) ?? (o["first_air_date"] as? String) ?? ""
        return Title(id: id, kind: kind, name: name, overview: o["overview"] as? String ?? "",
                     poster: o["poster_path"] as? String, backdrop: o["backdrop_path"] as? String,
                     year: date.count >= 4 ? String(date.prefix(4)) : nil, rating: (o["vote_average"] as? Double) ?? 0)
    }

    /// Kids mode: only family, animated and kids titles anywhere in the catalog.
    static var kids: Bool { UserDefaults.standard.bool(forKey: "kids") }
    private static let kidsGenres: Set<Int> = [16, 10751, 10762]
    private static func kidsOK(_ o: [String: Any]) -> Bool {
        guard kids else { return true }
        if (o["adult"] as? Bool) == true { return false }
        return ((o["genre_ids"] as? [Int]) ?? []).contains { kidsGenres.contains($0) }
    }

    // MARK: Search filters (same categories as the web and Android apps)

    struct Cat: Identifiable {
        let id: String; let name: String; let kinds: [Kind]; let q: [String: String]; var few = false
        let ok: ([String: Any]) -> Bool
    }
    private static func lang(_ o: [String: Any]) -> String { o["original_language"] as? String ?? "" }
    static let cats: [Cat] = [
        Cat(id: "hollywood", name: "Hollywood", kinds: [.movie], q: ["with_origin_country": "US", "with_original_language": "en"]) { lang($0) == "en" },
        Cat(id: "nollywood", name: "Nollywood", kinds: [.movie, .tv], q: ["with_origin_country": "NG"], few: true) { (($0["origin_country"] as? [String]) ?? []).contains("NG") },
        Cat(id: "kdrama", name: "K-drama", kinds: [.tv], q: ["with_original_language": "ko", "with_genres": "18"]) { lang($0) == "ko" },
        Cat(id: "anime", name: "Anime", kinds: [.tv, .movie], q: ["with_original_language": "ja", "with_genres": "16"]) { lang($0) == "ja" && (($0["genre_ids"] as? [Int]) ?? []).contains(16) },
        Cat(id: "chinese", name: "Chinese", kinds: [.tv, .movie], q: ["with_original_language": "zh"]) { ["zh", "cn"].contains(lang($0)) },
        Cat(id: "bollywood", name: "Bollywood", kinds: [.movie], q: ["with_original_language": "hi"]) { lang($0) == "hi" },
        Cat(id: "korean", name: "Korean movies", kinds: [.movie], q: ["with_original_language": "ko"]) { lang($0) == "ko" },
        Cat(id: "turkish", name: "Turkish", kinds: [.tv], q: ["with_original_language": "tr"]) { lang($0) == "tr" },
        Cat(id: "spanish", name: "Spanish", kinds: [.tv, .movie], q: ["with_original_language": "es"]) { lang($0) == "es" },
    ]
    static var years: [String] { let y = Calendar.current.component(.year, from: Date()); return (0..<12).map { "\(y - $0)" } + ["2010s", "2000s", "1990s", "Older"] }
    private static func yearRange(_ y: String?) -> ClosedRange<Int>? {
        guard let y, !y.isEmpty else { return nil }
        if y == "Older" { return 1900...1989 }
        if y.hasSuffix("s"), let d = Int(y.prefix(4)) { return d...(d + 9) }
        return Int(y).map { $0...$0 }
    }

    /// Search, or browse by category and year when there's no text.
    static func find(_ q: String, cat: Cat?, year: String?) async -> [Title] {
        let yr = yearRange(year)
        func ofYear(_ o: [String: Any]) -> Bool {
            guard let yr else { return true }
            let d = (o["release_date"] as? String) ?? (o["first_air_date"] as? String) ?? ""
            return Int(d.prefix(4)).map { yr.contains($0) } ?? false
        }
        var raw: [([String: Any], Kind?)] = []
        if q.count >= 2 {
            for pg in [1, 2] {
                let o = (try? await get("search/multi", ["query": q, "include_adult": "false", "page": "\(pg)"])) ?? [:]
                for x in (o["results"] as? [[String: Any]]) ?? [] {
                    guard let mt = x["media_type"] as? String, let k = Kind(rawValue: mt) else { continue }
                    if let cat, !(cat.kinds.contains(k) && cat.ok(x)) { continue }
                    if ofYear(x) { raw.append((x, nil)) }
                }
            }
        } else if cat != nil || yr != nil {
            await withTaskGroup(of: [([String: Any], Kind?)].self) { g in
                for k in cat?.kinds ?? [.movie, .tv] { for pg in [1, 2] { g.addTask {
                    var p = ["sort_by": "popularity.desc", "page": "\(pg)", "vote_count.gte": cat?.few == true ? "0" : "5"]
                    cat?.q.forEach { p[$0.key] = $0.value }
                    if let yr {
                        let (f, t) = k == .movie ? ("primary_release_date.gte", "primary_release_date.lte") : ("first_air_date.gte", "first_air_date.lte")
                        p[f] = "\(yr.lowerBound)-01-01"; p[t] = "\(yr.upperBound)-12-31"
                    }
                    let o = (try? await get("discover/\(k.rawValue)", p)) ?? [:]
                    return ((o["results"] as? [[String: Any]]) ?? []).map { ($0, k) }
                } } }
                for await part in g { raw += part }
            }
            raw.sort { (($0.0["popularity"] as? Double) ?? 0) > (($1.0["popularity"] as? Double) ?? 0) }
        } else { return (try? await list("trending/all/week", nil)) ?? [] }
        var seen = Set<String>()
        return raw.filter { kidsOK($0.0) }.compactMap { parse($0.0, $0.1) }.filter { $0.poster != nil && seen.insert($0.key).inserted }
    }

    static func list(_ path: String, _ kind: Kind?, _ params: [String: String] = [:]) async throws -> [Title] {
        let o = try await get(path, params)
        return ((o["results"] as? [[String: Any]]) ?? []).filter(kidsOK).compactMap { parse($0, kind) }.filter { $0.poster != nil }
    }

    static func kidsRows(_ only: Kind? = nil) async -> [Row] {
        let pg = ["certification_country": "US", "certification.lte": "PG"]
        var specs: [(String, () async throws -> [Title])] = []
        if only != .tv {
            specs.append(("Family movies", { try await list("discover/movie", .movie, ["with_genres": "10751", "sort_by": "popularity.desc"].merging(pg) { a, _ in a }) }))
            specs.append(("Animated movies", { try await list("discover/movie", .movie, ["with_genres": "16", "sort_by": "popularity.desc"].merging(pg) { a, _ in a }) }))
        }
        if only != .movie {
            specs.append(("Kids shows", { try await list("discover/tv", .tv, ["with_genres": "10762", "sort_by": "popularity.desc"]) }))
            specs.append(("Animated shows", { try await list("discover/tv", .tv, ["with_genres": "16,10751", "sort_by": "popularity.desc"]) }))
        }
        if only != .tv {
            specs.append(("Top rated family movies", { try await list("discover/movie", .movie, ["with_genres": "10751", "sort_by": "vote_average.desc", "vote_count.gte": "500"].merging(pg) { a, _ in a }) }))
        }
        return await rows(specs)
    }

    /// Loads all rows at once; rows that fail are dropped.
    static func rows(_ specs: [(String, () async throws -> [Title])], ranked: String? = nil) async -> [Row] {
        await withTaskGroup(of: (Int, Row?).self) { g in
            for (i, s) in specs.enumerated() {
                g.addTask {
                    let items = (try? await s.1()) ?? []
                    return (i, items.isEmpty ? nil : Row(name: s.0, items: items, ranked: s.0 == ranked))
                }
            }
            var out: [(Int, Row)] = []
            for await (i, r) in g { if let r { out.append((i, r)) } }
            return out.sorted { $0.0 < $1.0 }.map { $0.1 }
        }
    }

    static func home() async -> [Row] {
        if kids { return await kidsRows() }
        return await rows([
            ("Top 10 today", { Array(try await list("trending/all/day", nil).prefix(10)) }),
            ("New in cinemas", { try await list("movie/now_playing", .movie) }),
            ("Trending this week", { try await list("trending/all/week", nil) }),
            ("Popular movies", { try await list("movie/popular", .movie) }),
            ("Popular shows", { try await list("tv/popular", .tv) }),
            ("Top rated movies", { try await list("movie/top_rated", .movie) }),
            ("Top rated shows", { try await list("tv/top_rated", .tv) }),
        ], ranked: "Top 10 today")
    }

    static func browse(_ kind: Kind) async -> [Row] {
        if kids { return await kidsRows(kind) }
        if kind == .movie {
            return await rows([
                ("Top 10 movies", { Array(try await list("trending/movie/day", .movie).prefix(10)) }),
                ("Now playing", { try await list("movie/now_playing", .movie) }),
                ("Trending movies", { try await list("trending/movie/week", .movie) }),
                ("Popular", { try await list("movie/popular", .movie) }),
                ("Coming soon", { try await list("movie/upcoming", .movie) }),
                ("Top rated", { try await list("movie/top_rated", .movie) }),
            ], ranked: "Top 10 movies")
        }
        return await rows([
            ("Top 10 shows", { Array(try await list("trending/tv/day", .tv).prefix(10)) }),
            ("Airing today", { try await list("tv/airing_today", .tv) }),
            ("Trending shows", { try await list("trending/tv/week", .tv) }),
            ("Popular", { try await list("tv/popular", .tv) }),
            ("On the air", { try await list("tv/on_the_air", .tv) }),
            ("Top rated", { try await list("tv/top_rated", .tv) }),
        ], ranked: "Top 10 shows")
    }

    static func search(_ q: String) async throws -> [Title] {
        try await list("search/multi", nil, ["query": q, "include_adult": "false"])
    }

    static func details(_ t: Title) async throws -> Details {
        let o = try await get("\(t.kind.rawValue)/\(t.id)", ["append_to_response": "credits,similar,images", "include_image_language": "\(I18n.lang),en,null"])
        let title = parse(o, t.kind) ?? t
        let runtime = (o["runtime"] as? Int) ?? ((o["episode_run_time"] as? [Int])?.first)
        let genres = ((o["genres"] as? [[String: Any]]) ?? []).compactMap { $0["name"] as? String }
        let seasons = ((o["seasons"] as? [[String: Any]]) ?? []).compactMap { s -> Season? in
            guard let n = s["season_number"] as? Int, n > 0 else { return nil }
            return Season(number: n, name: s["name"] as? String ?? "Season \(n)", episodeCount: s["episode_count"] as? Int ?? 0)
        }
        let cast = (((o["credits"] as? [String: Any])?["cast"] as? [[String: Any]]) ?? []).prefix(15).compactMap { c -> Person? in
            guard let id = c["id"] as? Int, let n = c["name"] as? String else { return nil }
            return Person(id: id, name: n, role: c["character"] as? String ?? "", photo: c["profile_path"] as? String)
        }
        let similar = (((o["similar"] as? [String: Any])?["results"] as? [[String: Any]]) ?? []).compactMap { parse($0, t.kind) }.filter { $0.poster != nil }
        let logos = ((o["images"] as? [String: Any])?["logos"] as? [[String: Any]]) ?? []
        let logo = (logos.first { ($0["iso_639_1"] as? String) == I18n.lang } ?? logos.first { ($0["iso_639_1"] as? String) == "en" } ?? logos.first)?["file_path"] as? String
        return Details(title: title, tagline: o["tagline"] as? String ?? "", runtime: runtime, genres: genres,
                       seasons: seasons, cast: Array(cast), similar: similar, logo: logo)
    }

    static func person(_ id: Int) async throws -> PersonDetails {
        let o = try await get("person/\(id)", ["append_to_response": "combined_credits"])
        let dept = o["known_for_department"] as? String ?? ""
        let cc = o["combined_credits"] as? [String: Any] ?? [:]
        // Actors: the parts they played. Directors, writers…: their work behind the camera too.
        var raw = cc["cast"] as? [[String: Any]] ?? []
        if !dept.isEmpty && dept != "Acting" { raw += cc["crew"] as? [[String: Any]] ?? [] }
        raw = raw.filter { c in
            // chat shows, news and award-show guest spots crowd out the real work
            guard (c["media_type"] as? String) == "tv" else { return true }
            let g = c["genre_ids"] as? [Int] ?? []
            let ch = (c["character"] as? String ?? "").lowercased()
            return !(g.contains(10767) || g.contains(10763) || ch.hasPrefix("self") || ch.hasPrefix("himself") || ch.hasPrefix("herself"))
        }
        raw.sort { ($0["popularity"] as? Double ?? 0) > ($1["popularity"] as? Double ?? 0) }
        var seen = Set<String>()
        var credits: [Credit] = []
        for c in raw {
            guard let t = parse(c, nil), t.poster != nil, seen.insert(t.key).inserted else { continue }
            let role = (c["character"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (c["job"] as? String ?? "")
            credits.append(Credit(title: t, role: role))
        }
        let born = (o["birthday"] as? String).flatMap { $0.count >= 4 ? $0 : nil }
        let died = (o["deathday"] as? String).flatMap { $0.count >= 4 ? $0 : nil }
        var facts: [String] = []
        if !dept.isEmpty { facts.append(I18n.t(dept)) }
        if let b = born {
            if let d = died { facts.append("\(b.prefix(4))–\(d.prefix(4))") }
            else {
                let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
                if let date = f.date(from: b), let age = Calendar.current.dateComponents([.year], from: date, to: Date()).year {
                    facts.append(I18n.f("Born {1} · {2}", b.prefix(4), age))
                } else { facts.append(I18n.f("Born {1}", b.prefix(4))) }
            }
        }
        if let place = o["place_of_birth"] as? String, !place.isEmpty { facts.append(place) }
        let person = Person(id: id, name: o["name"] as? String ?? "", role: dept, photo: o["profile_path"] as? String)
        return PersonDetails(person: person, facts: facts.joined(separator: "  ·  "), bio: o["biography"] as? String ?? "", credits: credits)
    }

    static func episodes(_ id: Int, _ season: Int) async throws -> [Episode] {
        let o = try await get("tv/\(id)/season/\(season)")
        return ((o["episodes"] as? [[String: Any]]) ?? []).compactMap { e in
            guard let n = e["episode_number"] as? Int else { return nil }
            return Episode(number: n, name: e["name"] as? String ?? "Episode \(n)", overview: e["overview"] as? String ?? "",
                           still: e["still_path"] as? String, runtime: e["runtime"] as? Int)
        }
    }

    /// Runtime in seconds, used to spot a source playing the wrong title.
    static func runtime(_ kind: Kind, _ id: Int, _ season: Int, _ episode: Int) async -> Double? {
        if kind == .movie { return (try? await get("movie/\(id)"))?["runtime"].flatMap { $0 as? Int }.map { Double($0) * 60 } }
        let eps = (try? await episodes(id, season)) ?? []
        return eps.first { $0.number == episode }?.runtime.map { Double($0) * 60 }
    }
}
