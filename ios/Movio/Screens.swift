import SwiftUI

// MARK: - Home

struct HomeView: View {
    @EnvironmentObject var store: Store
    @State private var rows: [Row] = []
    @State private var loaded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                if let trending = (rows.first { $0.name.hasPrefix("Trending") } ?? rows.first) {
                    Hero(items: Array(trending.items.filter { $0.backdrop != nil && !$0.overview.isEmpty }.prefix(6)))
                }
                if !store.continueWatching.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Continue watching").font(jakarta(19, .extrabold)).padding(.horizontal, 18)
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 12) { ForEach(store.continueWatching, id: \.title.key) { ContinueCard(p: $0) } }
                                .padding(.horizontal, 18)
                        }
                    }
                }
                if !loaded { Loading() }
                ForEach(rows) { RowView(row: $0) }
            }
            .padding(.bottom, 30)
        }
        .ignoresSafeArea(edges: .top)
        .background(Color.ink.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { if rows.isEmpty { rows = await TMDB.home(); loaded = true } }
        .refreshable { rows = await TMDB.home() }
    }
}

/// Big swipeable banner at the top of Home.
struct Hero: View {
    let items: [Title]
    @EnvironmentObject var store: Store
    @State private var page = 0

    var body: some View {
        TabView(selection: $page) {
            ForEach(Array(items.enumerated()), id: \.element.key) { i, t in
                ZStack(alignment: .bottomLeading) {
                    Color.clear
                    Art(url: TMDB.img(t.backdrop, "w1280"))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    LinearGradient(stops: [.init(color: .ink.opacity(0.45), location: 0), .init(color: .clear, location: 0.25),
                                           .init(color: .clear, location: 0.45), .init(color: .ink, location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                    VStack(alignment: .leading, spacing: 10) {
                        Text(t.name).font(jakarta(32, .extrabold)).lineLimit(2).shadow(radius: 8)
                        Text([t.year, t.kind == .tv ? "Series" : "Movie", String(format: "★ %.1f", t.rating)].compactMap { $0 }.joined(separator: "  ·  "))
                            .font(jakarta(13, .semibold)).foregroundStyle(Color.soft)
                        HStack(spacing: 10) {
                            Button { store.play(t) } label: {
                                Label("Play", systemImage: "play.fill").font(jakarta(16, .bold))
                                    .foregroundStyle(Color.ink).frame(width: 130, height: 46)
                                    .background(.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            Button { store.toggle(t) } label: {
                                Label("My List", systemImage: store.inList(t) ? "checkmark" : "plus").font(jakarta(15, .semibold))
                                    .foregroundStyle(.white).frame(width: 120, height: 46)
                                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            NavigationLink(value: t) {
                                Image(systemName: "info.circle").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white)
                                    .frame(width: 46, height: 46).background(.ultraThinMaterial, in: Circle())
                            }
                        }
                        .buttonStyle(PressStyle())
                    }
                    .padding(.horizontal, 18).padding(.bottom, 40)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxWidth: .infinity)
                .clipped()
                .tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .frame(height: 500)
        .task(id: items.count) {
            while !Task.isCancelled && items.count > 1 {
                try? await Task.sleep(nanoseconds: 7_000_000_000)
                withAnimation(.easeInOut(duration: 0.6)) { page = (page + 1) % items.count }
            }
        }
    }
}

// MARK: - Movies / TV Shows

struct BrowseView: View {
    let kind: Kind
    @State private var rows: [Row] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                if rows.isEmpty { Loading() }
                ForEach(rows) { RowView(row: $0) }
            }
            .padding(.vertical, 12)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationTitle(kind == .movie ? "Movies" : "TV Shows")
        .task { if rows.isEmpty { rows = await TMDB.browse(kind) } }
    }
}

// MARK: - Search

struct SearchView: View {
    @State private var query = ""
    @State private var results: [Title] = []
    @State private var trending: [Title] = []
    private let grid = [GridItem(.adaptive(minimum: 104), spacing: 10)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: grid, spacing: 12) {
                ForEach(query.isEmpty ? trending : results, id: \.key) { PosterCard(title: $0, width: 108) }
            }
            .padding(16)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Movies, shows…")
        .task { if trending.isEmpty { trending = (try? await TMDB.list("trending/all/week", nil)) ?? [] } }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { results = []; return }
            try? await Task.sleep(nanoseconds: 350_000_000)
            if Task.isCancelled { return }
            results = (try? await TMDB.search(q)) ?? []
        }
    }
}

// MARK: - My List

struct MyListView: View {
    @EnvironmentObject var store: Store
    @State private var showDownloads = false
    private let grid = [GridItem(.adaptive(minimum: 104), spacing: 10)]

    var body: some View {
        ScrollView {
            Picker("", selection: $showDownloads) {
                Text("Saved").tag(false)
                Text("Downloads").tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16).padding(.top, 6)
            if showDownloads {
                DownloadsList()
            } else if store.myList.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "bookmark").font(.system(size: 40)).foregroundStyle(Color.muted)
                    Text("Nothing saved yet").font(jakarta(17, .bold))
                    Text("Tap My List on any title to keep it here.").font(jakarta(14)).foregroundStyle(Color.muted)
                }
                .frame(maxWidth: .infinity).padding(.top, 120)
            } else {
                LazyVGrid(columns: grid, spacing: 12) {
                    ForEach(store.myList, id: \.key) { PosterCard(title: $0, width: 108) }
                }
                .padding(16)
            }
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationTitle("My List")
    }
}

// MARK: - Details

struct DetailsView: View {
    let title: Title
    @EnvironmentObject var store: Store
    @State private var d: Details?
    @State private var season = 1
    @State private var episodes: [Episode] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ZStack(alignment: .bottomLeading) {
                    Art(url: TMDB.img(title.backdrop ?? title.poster, "w1280")).frame(maxWidth: .infinity, maxHeight: .infinity)
                    LinearGradient(colors: [.clear, .clear, .ink], startPoint: .top, endPoint: .bottom)
                    Group {
                        if let logo = d?.logo {
                            AsyncImage(url: TMDB.img(logo, "w500")) { img in img.resizable().scaledToFit() } placeholder: { Color.clear }
                                .frame(maxWidth: 240, maxHeight: 90, alignment: .bottomLeading)
                        } else {
                            Text(title.name).font(jakarta(30, .extrabold)).lineLimit(2)
                        }
                    }
                    .padding(.horizontal, 18)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 300)
                .clipped()

                VStack(alignment: .leading, spacing: 14) {
                    Text(meta).font(jakarta(13, .semibold)).foregroundStyle(Color.soft)
                    HStack(spacing: 10) {
                        Button { play() } label: {
                            Label(playLabel, systemImage: "play.fill").font(jakarta(16, .bold)).foregroundStyle(Color.ink)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .background(.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        Button { store.toggle(title) } label: {
                            Image(systemName: store.inList(title) ? "checkmark" : "plus").font(.system(size: 18, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 52, height: 48)
                                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .buttonStyle(PressStyle())
                    if let tag = d?.tagline, !tag.isEmpty { Text(tag).font(jakarta(15, .bold)) }
                    Text(d?.title.overview ?? title.overview).font(jakarta(14)).foregroundStyle(Color.soft).lineSpacing(3)
                    if let g = d?.genres, !g.isEmpty { Text(g.joined(separator: " · ")).font(jakarta(12, .semibold)).foregroundStyle(Color.muted) }
                }
                .padding(.horizontal, 18)

                if title.kind == .tv, let seasons = d?.seasons, !seasons.isEmpty { episodeList(seasons) }

                if let cast = d?.cast, !cast.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Cast").font(jakarta(19, .extrabold)).padding(.horizontal, 18)
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 14) {
                                ForEach(cast, id: \.id) { p in
                                    VStack(spacing: 6) {
                                        Art(url: TMDB.img(p.photo, "w185")).frame(width: 72, height: 72).clipShape(Circle())
                                        Text(p.name).font(jakarta(11, .bold)).multilineTextAlignment(.center).lineLimit(2)
                                        Text(p.role).font(jakarta(10)).foregroundStyle(Color.muted).lineLimit(1)
                                    }
                                    .frame(width: 84)
                                }
                            }
                            .padding(.horizontal, 18)
                        }
                    }
                }
                if let similar = d?.similar, !similar.isEmpty { RowView(row: Row(name: "More like this", items: similar)) }
            }
            .padding(.bottom, 30)
        }
        .ignoresSafeArea(edges: .top)
        .background(Color.ink.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if d == nil { d = try? await TMDB.details(title) }
            if title.kind == .tv {
                if let p = store.recent[title.key] { season = p.season }
                else if let first = d?.seasons.first?.number { season = first }
            }
        }
        .task(id: season) { if title.kind == .tv { episodes = (try? await TMDB.episodes(title.id, season)) ?? [] } }
    }

    private var meta: String {
        var parts: [String] = []
        if let y = (d?.title.year ?? title.year) { parts.append(y) }
        if title.rating > 0 { parts.append(String(format: "★ %.1f", title.rating)) }
        if title.kind == .movie, let r = d?.runtime, r > 0 { parts.append(r >= 60 ? "\(r / 60)h \(r % 60)m" : "\(r)m") }
        if title.kind == .tv, let n = d?.seasons.count, n > 0 { parts.append(n == 1 ? "1 season" : "\(n) seasons") }
        return parts.joined(separator: "  ·  ")
    }

    private var playLabel: String {
        if title.kind == .tv, let p = store.recent[title.key] { return "Resume S\(p.season) E\(p.episode)" }
        if let p = store.position(title, 1, 1), p.0 > 30 { return "Resume" }
        return "Play"
    }

    private func play() { store.play(title) }

    @ViewBuilder private func episodeList(_ seasons: [Season]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Menu {
                ForEach(seasons, id: \.number) { s in Button(s.name) { season = s.number } }
            } label: {
                HStack(spacing: 6) {
                    Text(seasons.first { $0.number == season }?.name ?? "Season \(season)").font(jakarta(18, .extrabold))
                    Image(systemName: "chevron.down").font(.system(size: 13, weight: .bold))
                }
                .foregroundStyle(.white)
            }
            .padding(.horizontal, 18)
            LazyVStack(spacing: 6) {
                ForEach(episodes) { e in
                    Button { store.play(title, season: season, episode: e.number) } label: {
                        HStack(alignment: .top, spacing: 12) {
                            ZStack(alignment: .bottom) {
                                Art(url: TMDB.img(e.still, "w300")).frame(width: 132, height: 74)
                                if let p = store.position(title, season, e.number), p.1 > 0 {
                                    GeometryReader { g in
                                        Capsule().fill(brand).frame(width: g.size.width * min(1, p.0 / p.1), height: 3)
                                    }
                                    .frame(height: 3)
                                }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            VStack(alignment: .leading, spacing: 4) {
                                Text("\(e.number). \(e.name)").font(jakarta(14, .bold)).foregroundStyle(.white).lineLimit(2)
                                if let r = e.runtime { Text("\(r)m").font(jakarta(11, .semibold)).foregroundStyle(Color.muted) }
                                Text(e.overview).font(jakarta(12)).foregroundStyle(Color.muted).lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle())
                }
            }
            .padding(.horizontal, 10)
        }
    }
}
