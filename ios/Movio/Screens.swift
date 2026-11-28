import SwiftUI

// MARK: - Home

struct HomeView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var profile: Profile
    @State private var rows: [Row] = []
    @State private var loaded = false
    @State private var showProfile = false

    var body: some View {
        ScrollView(showsIndicators: false) {
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
                if loaded && !store.kids { PlatformsRow(); LiveCard() }
                ForEach(rows) { RowView(row: $0) }
            }
            .padding(.bottom, 30)
        }
        .ignoresSafeArea(edges: .top)
        .background(Color.ink.ignoresSafeArea())
        .overlay(alignment: .topTrailing) {
            // Profile: your initial, iOS style. Opens name, language and subtitle settings.
            Button { showProfile = true } label: {
                Text(verbatim: profile.name.first.map { String($0).uppercased() } ?? "")
                    .font(jakarta(16, .extrabold)).foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(profile.name.isEmpty ? AnyShapeStyle(.ultraThinMaterial) : AnyShapeStyle(brand), in: Circle())
                    .overlay { if profile.name.isEmpty { Image(systemName: "person.fill").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white) } }
            }
            .buttonStyle(PressStyle())
            .padding(.trailing, 16).padding(.top, 4)
        }
        .sheet(isPresented: $showProfile) { ProfileSheet().environmentObject(profile) }
        .toolbar(.hidden, for: .navigationBar)
        .task { if rows.isEmpty { rows = await TMDB.home(); loaded = true } }
        .refreshable { rows = await TMDB.home() }
    }
}

/// Netflix, Prime Video…: tap one to see what's on it.
/// Platform tiles: a quiet edge at rest; the Sunset glow only while pressed.
struct GlowPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let on = configuration.isPressed
        return configuration.label
            .overlay(alignment: .top) {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(on ? Color.violet.opacity(0.8) : Color.white.opacity(0.08), lineWidth: 1)
                    .frame(width: 150, height: 84)
                    .shadow(color: on ? Color.violet.opacity(0.6) : .clear, radius: 10)
            }
            .scaleEffect(on ? 0.96 : 1)
            .animation(.easeOut(duration: 0.15), value: on)
    }
}

struct PlatformsRow: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Platforms").font(jakarta(19, .extrabold)).padding(.horizontal, 18)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(TMDB.platforms) { p in
                        NavigationLink { PlatformView(platform: p) } label: {
                            // Wide dark tiles with the logo and one soft Sunset glow (the same for every service).
                            VStack(alignment: .leading, spacing: 8) {
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .fill(LinearGradient(colors: [Color(red: 0.10, green: 0.106, blue: 0.145), Color(red: 0.06, green: 0.063, blue: 0.09)], startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: 150, height: 84)
                                    .overlay(
                                        AsyncImage(url: TMDB.img(p.logo, "w154")) { $0.resizable().scaledToFill() } placeholder: { Color.white.opacity(0.08) }
                                            .frame(width: 46, height: 46).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                            .shadow(color: .black.opacity(0.45), radius: 8, y: 5))
                                Text(verbatim: p.name).font(jakarta(13, .bold)).foregroundStyle(Color.soft).lineLimit(1)
                            }
                            .frame(width: 150, alignment: .leading)
                        }
                        .buttonStyle(GlowPress())
                    }
                }
                .padding(.horizontal, 18).padding(.vertical, 8)
            }
        }
    }
}

struct PlatformView: View {
    let platform: TMDB.Platform
    @AppStorage("platKind") private var kind = "tv"
    @AppStorage("platSort") private var sort = "popular"
    @State private var items: [Title] = []
    @State private var page = 1
    @State private var more = true
    @State private var loading = false
    private let grid = [GridItem(.adaptive(minimum: 104), spacing: 10)]

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) {
                    AsyncImage(url: TMDB.img(platform.logo, "w154")) { $0.resizable().scaledToFill() } placeholder: { Color.white.opacity(0.08) }
                        .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: platform.name).font(jakarta(28, .extrabold))
                        Text("Everything here plays in Movio.").font(jakarta(13, .semibold)).foregroundStyle(Color.muted)
                    }
                }
                .padding(.horizontal, 16)
                chips([("tv", "Shows"), ("movie", "Movies")], $kind)
                chips([("popular", "Popular"), ("new", "New"), ("top", "Top rated")], $sort)
                LazyVGrid(columns: grid, spacing: 12) {
                    ForEach(items, id: \.key) { t in
                        PosterCard(title: t, width: 108).onAppear { if t.key == items.last?.key { Task { await load() } } }
                    }
                }
                .padding(.horizontal, 16)
                if loading { ProgressView().tint(.violet).frame(maxWidth: .infinity).padding(.vertical, 20) }
            }
            .padding(.vertical, 8)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(kind)|\(sort)") { items = []; page = 1; more = true; await load() }
    }

    private func load() async {
        guard more, !loading else { return }
        loading = true
        let (got, has) = await TMDB.platform(platform.id, kind: kind == "movie" ? .movie : .tv, sort: sort, page: page)
        let seen = Set(items.map(\.key))
        items += got.filter { !seen.contains($0.key) }
        more = has && !got.isEmpty; page += 1; loading = false
    }

    private func chips(_ list: [(String, String)], _ sel: Binding<String>) -> some View {
        HStack(spacing: 8) {
            ForEach(list, id: \.0) { id, label in
                let on = sel.wrappedValue == id
                Button { sel.wrappedValue = id } label: {
                    Text(verbatim: L(label)).font(jakarta(13, .bold)).foregroundStyle(on ? Color.ink : .white)
                        .padding(.horizontal, 14).frame(height: 32)
                        .background(on ? Color.white : Color.white.opacity(0.12), in: Capsule())
                }
                .buttonStyle(PressStyle())
            }
        }
        .padding(.horizontal, 16)
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
        ScrollView(showsIndicators: false) {
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
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var results: [Title] = []
    @State private var cat = ""
    @State private var year = ""
    @State private var loading = false
    private let grid = [GridItem(.adaptive(minimum: 104), spacing: 10)]
    private var filtering: Bool { !cat.isEmpty || !year.isEmpty }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 12) {
                chips([("", L("All"))] + TMDB.cats.map { ($0.id, L($0.name)) }, $cat)
                chips([("", L("Any year"))] + TMDB.years.map { ($0, L($0)) }, $year)
                if query.trimmingCharacters(in: .whitespaces).isEmpty && !store.searches.isEmpty { recent }
                if loading && results.isEmpty { ProgressView().tint(.violet).frame(maxWidth: .infinity).padding(.top, 40) }
                else if results.isEmpty && (filtering || query.count >= 2) {
                    VStack(spacing: 8) {
                        Text("No results").font(jakarta(17, .bold))
                        Text(filtering ? "Try another year or category." : "Try a different title.").font(jakarta(14)).foregroundStyle(Color.muted)
                    }.frame(maxWidth: .infinity).padding(.top, 60)
                }
                LazyVGrid(columns: grid, spacing: 12) {
                    ForEach(results, id: \.key) { t in
                        PosterCard(title: t, width: 108).simultaneousGesture(TapGesture().onEnded { store.rememberSearch(query) })
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.top, 8).padding(.bottom, 16)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Movies, shows…")
        .onSubmit(of: .search) { store.rememberSearch(query) }
        .task(id: "\(query)|\(cat)|\(year)") {
            let q = query.trimmingCharacters(in: .whitespaces)
            if q.count >= 2 { try? await Task.sleep(nanoseconds: 350_000_000) }
            if Task.isCancelled { return }
            loading = true
            let r = await TMDB.find(q.count >= 2 ? q : "", cat: TMDB.cats.first { $0.id == cat }, year: year.isEmpty ? nil : year)
            if !Task.isCancelled { results = r; loading = false }
        }
    }

    private var recent: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Recent searches").font(jakarta(17, .bold))
                Spacer()
                Button(L("Clear")) { store.forgetSearch(nil) }.font(jakarta(14, .bold)).foregroundStyle(Color.violet)
            }
            .padding(.bottom, 4)
            ForEach(store.searches, id: \.self) { q in
                HStack(spacing: 12) {
                    Button { query = q; store.rememberSearch(q) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "clock.arrow.circlepath").foregroundStyle(Color.muted)
                            Text(verbatim: q).font(jakarta(15, .semibold)).foregroundStyle(.white)
                            Spacer()
                        }.contentShape(Rectangle())
                    }
                    Button { store.forgetSearch(q) } label: { Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Color.muted).padding(8) }
                }
                .padding(.vertical, 8)
                Divider().overlay(Color.white.opacity(0.07))
            }
        }
        .padding(.horizontal, 16)
    }

    private func chips(_ items: [(String, String)], _ sel: Binding<String>) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(items, id: \.0) { id, label in
                    let on = sel.wrappedValue == id
                    Button { sel.wrappedValue = id } label: {
                        Text(verbatim: label).font(jakarta(13, .bold)).foregroundStyle(on ? Color.ink : .white)
                            .padding(.horizontal, 14).frame(height: 32)
                            .background(on ? Color.white : Color.white.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(PressStyle())
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

// MARK: - My List

struct MyListView: View {
    @EnvironmentObject var store: Store
    @State private var tab = 0
    private let grid = [GridItem(.adaptive(minimum: 104), spacing: 10)]

    var body: some View {
        ScrollView(showsIndicators: false) {
            Picker("", selection: $tab) {
                Text("Saved").tag(0)
                Text("Liked").tag(1)
                Text("Downloads").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16).padding(.top, 6)
            if tab == 2 {
                DownloadsList()
            } else if tab == 1 {
                if store.liked.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "heart").font(.system(size: 40)).foregroundStyle(Color.muted)
                        Text("Nothing liked yet").font(jakarta(17, .bold))
                        Text("Tap ♥ Like on any title to keep it here.").font(jakarta(14)).foregroundStyle(Color.muted)
                    }
                    .frame(maxWidth: .infinity).padding(.top, 120)
                } else {
                    LazyVGrid(columns: grid, spacing: 12) { ForEach(store.liked, id: \.key) { PosterCard(title: $0, width: 108) } }.padding(16)
                }
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
    @State private var together = false

    var body: some View {
        ScrollView(showsIndicators: false) {
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
                    }
                    .buttonStyle(PressStyle())
                    // Icon-over-label actions.
                    HStack(spacing: 0) {
                        action(store.inList(title) ? "checkmark" : "plus", "My List", on: store.inList(title), tint: .violet) { store.toggle(title) }
                        action(store.isLiked(title) ? "heart.fill" : "heart", store.isLiked(title) ? "Liked" : "Like", on: store.isLiked(title), tint: .pink) {
                            store.toggleLike(title, genres: d?.genres ?? [])
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        }
                        action("person.2.fill", "Together", on: false, tint: .white) { together = true }
                        ShareLink(item: URL(string: "https://g41us.github.io/Movio/#/title/\(title.kind.rawValue)/\(title.id)")!,
                                  subject: Text(verbatim: title.name), message: Text(verbatim: I18n.f("Watch {1} on Movio", title.name))) {
                            actionLabel("square.and.arrow.up", "Share", on: false, tint: .white)
                        }
                        .buttonStyle(PressStyle())
                    }
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
                                    NavigationLink(value: p) {
                                        VStack(spacing: 6) {
                                            Art(url: TMDB.img(p.photo, "w185")).frame(width: 72, height: 72).clipShape(Circle())
                                            Text(p.name).font(jakarta(11, .bold)).foregroundStyle(.white).multilineTextAlignment(.center).lineLimit(2)
                                            Text(p.role).font(jakarta(10)).foregroundStyle(Color.muted).lineLimit(1)
                                        }
                                        .frame(width: 84)
                                    }
                                    .buttonStyle(PressStyle())
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
        .sheet(isPresented: $together) {
            let p = store.recent[title.key]
            WatchTogetherSheet(title: title, season: title.kind == .tv ? p?.season ?? 1 : 1, episode: title.kind == .tv ? p?.episode ?? 1 : 1)
                .environmentObject(store).presentationDetents([.medium, .large])
        }
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

    private func actionLabel(_ icon: String, _ label: String, on: Bool, tint: Color) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 21, weight: .semibold)).foregroundStyle(on ? tint : .white).frame(height: 24)
            Text(label).font(jakarta(12, .bold)).foregroundStyle(on ? Color.white : Color.soft)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 8).contentShape(Rectangle())
    }
    private func action(_ icon: String, _ label: String, on: Bool, tint: Color, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) { actionLabel(icon, label, on: on, tint: tint) }.buttonStyle(PressStyle())
    }

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

// MARK: - Person

/// Everything a person has been in, newest hits first, with Movies / Shows filters.
struct PersonView: View {
    let person: Person
    @State private var d: PersonDetails?
    @State private var failed = false
    @State private var more = false
    @State private var filter = 0 // 0 all, 1 movies, 2 shows
    private let grid = [GridItem(.adaptive(minimum: 104), spacing: 12, alignment: .top)]

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 14) {
                Art(url: TMDB.img(d?.person.photo ?? person.photo, "h632")).frame(width: 130, height: 130).clipShape(Circle())
                Text(person.name).font(jakarta(26, .extrabold)).multilineTextAlignment(.center)
                if let f = d?.facts, !f.isEmpty { Text(f).font(jakarta(13, .semibold)).foregroundStyle(Color.muted).multilineTextAlignment(.center) }
                if let bio = d?.bio, !bio.isEmpty {
                    Text(bio).font(jakarta(14)).foregroundStyle(Color.soft).lineSpacing(3).lineLimit(more ? nil : 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .onTapGesture { withAnimation(.easeOut(duration: 0.2)) { more.toggle() } }
                }
                if let d {
                    let movies = d.credits.filter { $0.title.kind == .movie }.count
                    let shows = d.credits.count - movies
                    if movies > 0 && shows > 0 {
                        Picker("", selection: $filter) {
                            Text("All \(d.credits.count)").tag(0)
                            Text("Movies \(movies)").tag(1)
                            Text("Shows \(shows)").tag(2)
                        }
                        .pickerStyle(.segmented)
                        .padding(.top, 4)
                    }
                    let shown = d.credits.filter { filter == 0 || (filter == 1 ? $0.title.kind == .movie : $0.title.kind == .tv) }
                    if shown.isEmpty {
                        Text("No titles found.").font(jakarta(14)).foregroundStyle(Color.muted).padding(.top, 40)
                    }
                    LazyVGrid(columns: grid, spacing: 18) {
                        ForEach(shown, id: \.title.key) { c in
                            NavigationLink(value: c.title) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Color.clear.aspectRatio(2 / 3, contentMode: .fit)
                                        .overlay { Art(url: TMDB.img(c.title.poster, "w342")) }
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        .padding(.bottom, 5)
                                    Text(c.title.name).font(jakarta(13, .semibold)).foregroundStyle(.white).lineLimit(1)
                                    Text([c.title.year, c.role.isEmpty ? nil : c.role].compactMap { $0 }.joined(separator: " · "))
                                        .font(jakarta(11)).foregroundStyle(Color.muted).lineLimit(1)
                                }
                            }
                            .buttonStyle(PressStyle())
                        }
                    }
                } else if failed {
                    Button("Try again") { failed = false; Task { await load() } }.font(jakarta(15, .bold)).padding(.top, 40)
                } else {
                    ProgressView().tint(.violet).padding(.top, 40)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 30)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task { if d == nil { await load() } }
    }

    private func load() async {
        do { d = try await TMDB.person(person.id) } catch { failed = true }
    }
}
