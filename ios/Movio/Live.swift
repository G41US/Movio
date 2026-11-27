import SwiftUI
import AVKit

/// A free-to-air channel (from iptv-org; Movio's lists keep only ones that answered in the last check).
struct Channel: Codable, Identifiable, Hashable {
    let id: String
    let n: String
    let l: String?
    let u: String
    let c: [String]
    var r: String?
    var a: String?
}

enum Live {
    static let base = "https://g41us.github.io/Movio/live"
    struct Country: Codable, Hashable { let code: String; let name: String; let n: Int }
    struct Cat: Codable, Hashable { let id: String; let name: String; let n: Int }
    struct Index: Codable { let total: Int; let countries: [Country]; let categories: [Cat] }

    static func get<T: Decodable>(_ path: String) async -> T? {
        guard let u = URL(string: "\(base)/\(path)") else { return nil }
        var r = URLRequest(url: u, timeoutInterval: 15); r.cachePolicy = .reloadRevalidatingCacheData
        guard let (d, resp) = try? await URLSession.shared.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }

    static func myCountry() -> String {
        let tz = TimeZone.current.identifier
        let map = ["Africa/Lagos": "NG", "Africa/Accra": "GH", "Africa/Nairobi": "KE", "Africa/Johannesburg": "ZA", "Europe/London": "UK", "Europe/Paris": "FR"]
        return map[tz] ?? (Locale.current.region?.identifier ?? "US").uppercased()
    }
    static func flag(_ cc: String) -> URL? { URL(string: "https://flagcdn.com/w40/\(cc == "UK" ? "gb" : cc.lowercased()).png") }

    // Favourites, kept on this device.
    static var favs: [Channel] {
        get { (UserDefaults.standard.data(forKey: "liveFav")).flatMap { try? JSONDecoder().decode([Channel].self, from: $0) } ?? [] }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: "liveFav") }
    }
    @discardableResult static func toggleFav(_ ch: Channel) -> Bool {
        var f = favs
        let on = !f.contains { $0.id == ch.id }
        if on { f.insert(ch, at: 0) } else { f.removeAll { $0.id == ch.id } }
        favs = f
        return on
    }
}

/// Home's way in to Live TV.
struct LiveCard: View {
    var body: some View {
        NavigationLink { LiveView() } label: {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(LinearGradient(colors: [Color(red: 1, green: 0.23, blue: 0.36), .pink], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 50, height: 50)
                    .overlay(Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 22, weight: .semibold)).foregroundStyle(.white))
                VStack(alignment: .leading, spacing: 3) {
                    Text("Live TV").font(jakarta(17, .extrabold)).foregroundStyle(.white)
                    Text("News, sport, music and kids' channels, free.").font(jakarta(13, .semibold)).foregroundStyle(Color.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward").font(.system(size: 14, weight: .bold)).foregroundStyle(Color.muted)
            }
            .padding(14)
            .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Color.white.opacity(0.08)))
            .padding(.horizontal, 16)
        }
        .buttonStyle(PressStyle())
    }
}

private let liveCats = ["news", "sports", "movies", "entertainment", "music", "kids", "documentary", "religious", "general"]

struct LiveView: View {
    @AppStorage("liveCc") private var cc = Live.myCountry()
    @AppStorage("liveCat") private var cat = ""
    @State private var index: Live.Index?
    @State private var failed = false
    @State private var list: [Channel]?
    @State private var query = ""
    @State private var favs = Live.favs
    @State private var picking = false
    @State private var playing: LiveRequest?
    private let grid = [GridItem(.adaptive(minimum: 150), spacing: 12)]

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 12) {
                if let idx = index {
                    HStack(alignment: .lastTextBaseline) {
                        Text("Live TV").font(jakarta(30, .extrabold))
                        Spacer()
                        Text(verbatim: I18n.f("{1} channels", idx.total)).font(jakarta(13, .bold)).foregroundStyle(Color.muted)
                    }
                    .padding(.horizontal, 16)
                    chips(idx)
                    if !favs.isEmpty && query.isEmpty {
                        Text("Favourites").font(jakarta(19, .extrabold)).padding(.horizontal, 16).padding(.top, 4)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(Array(favs.enumerated()), id: \.element.id) { i, ch in tile(ch, fav: true) { playing = LiveRequest(list: favs, i: i) }.frame(width: 150) }
                            }
                            .padding(.horizontal, 16)
                        }
                    }
                    if let l = list {
                        let q = query.lowercased()
                        let shown = l.filter { (cat.isEmpty || $0.c.contains(cat) || (cat == "general" && $0.c.isEmpty)) && (q.isEmpty || $0.n.lowercased().contains(q)) }
                        if shown.isEmpty {
                            VStack(spacing: 8) {
                                Text("No channels here").font(jakarta(17, .bold))
                                Text("Try another country or category.").font(jakarta(14)).foregroundStyle(Color.muted)
                            }.frame(maxWidth: .infinity).padding(.top, 50)
                        }
                        LazyVGrid(columns: grid, spacing: 16) {
                            ForEach(Array(shown.enumerated()), id: \.element.id) { i, ch in
                                tile(ch, fav: favs.contains { $0.id == ch.id }) { playing = LiveRequest(list: shown, i: i) }
                                    .contextMenu {
                                        Button { Live.toggleFav(ch); favs = Live.favs } label: {
                                            Label(favs.contains { $0.id == ch.id } ? L("Remove from Favourites") : L("Add to Favourites"), systemImage: "star")
                                        }
                                    }
                            }
                        }
                        .padding(.horizontal, 16)
                    } else { ProgressView().tint(.violet).frame(maxWidth: .infinity).padding(.top, 40) }
                } else if failed {
                    VStack(spacing: 8) {
                        Text("Live TV is getting ready").font(jakarta(17, .bold))
                        Text("Try again in a few minutes.").font(jakarta(14)).foregroundStyle(Color.muted)
                    }.frame(maxWidth: .infinity).padding(.top, 120)
                } else { ProgressView().tint(.violet).frame(maxWidth: .infinity).padding(.top, 120) }
            }
            .padding(.vertical, 8)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: L("Find a channel"))
        .task { if index == nil { index = await Live.get("index.json"); failed = index == nil } }
        .task(id: "\(cc)|\(cat)|\(index != nil)") {
            guard let idx = index else { return }
            if cc != "ALL" && !idx.countries.contains(where: { $0.code == cc }) { cc = "US" }
            list = nil
            list = await Live.get(cc == "ALL" ? "cat/\(cat.isEmpty ? "news" : cat).json" : "c/\(cc.lowercased()).json") ?? []
        }
        .sheet(isPresented: $picking) { countryPicker }
        .fullScreenCover(item: $playing) { LivePlayerView(request: $0) { favs = Live.favs } }
    }

    private func chips(_ idx: Live.Index) -> some View {
        let top = [cc, Live.myCountry(), "NG", "GH", "KE", "ZA", "US", "UK", "FR"].reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
            .filter { c in idx.countries.contains { $0.code == c } }
        return VStack(alignment: .leading, spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(top, id: \.self) { c in
                        chip(idx.countries.first { $0.code == c }?.name ?? c, on: cc == c, flag: c) { cc = c }
                    }
                    chip("🌍 " + L("Worldwide"), on: cc == "ALL") { cc = "ALL"; if cat.isEmpty { cat = "news" } }
                    chip(L("All countries") + " ›", on: false) { picking = true }
                }
                .padding(.horizontal, 16)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(L("All"), on: cat.isEmpty) { cat = cc == "ALL" ? "news" : "" }
                    ForEach(liveCats.filter { c in idx.categories.contains { $0.id == c } }, id: \.self) { c in
                        chip(L(c.prefix(1).uppercased() + c.dropFirst()), on: cat == c) { cat = c }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func chip(_ label: String, on: Bool, flag: String? = nil, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack(spacing: 7) {
                if let flag { AsyncImage(url: Live.flag(flag)) { $0.resizable().scaledToFill() } placeholder: { Color.clear }.frame(width: 18, height: 13).clipShape(RoundedRectangle(cornerRadius: 3)) }
                Text(verbatim: label).font(jakarta(13, .bold))
            }
            .foregroundStyle(on ? Color.ink : .white).padding(.horizontal, 14).frame(height: 32)
            .background(on ? Color.white : Color.white.opacity(0.12), in: Capsule())
        }
        .buttonStyle(PressStyle())
    }

    private func tile(_ ch: Channel, fav: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(RadialGradient(colors: [Color(red: 0.16, green: 0.17, blue: 0.23), Color(red: 0.08, green: 0.09, blue: 0.12)], center: .topLeading, startRadius: 10, endRadius: 260))
                    if let l = ch.l, let u = URL(string: l) {
                        AsyncImage(url: u) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                            .padding(.horizontal, 30).padding(.vertical, 22).shadow(color: .black.opacity(0.4), radius: 6, y: 3)
                    } else {
                        Text(verbatim: String(ch.n.prefix(2)).uppercased()).font(jakarta(24, .extrabold)).foregroundStyle(Color.soft)
                    }
                }
                .aspectRatio(16 / 10, contentMode: .fit)
                .overlay(alignment: .topLeading) { LiveBadge().padding(8) }
                .overlay(alignment: .topTrailing) { if fav { Text("★").foregroundStyle(Color(red: 1, green: 0.82, blue: 0.37)).padding(8) } }
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.07)))
                Text(verbatim: ch.n).font(jakarta(14, .bold)).foregroundStyle(.white).lineLimit(1)
                Text(verbatim: L((ch.c.first ?? "general").prefix(1).uppercased() + (ch.c.first ?? "general").dropFirst())).font(jakarta(12, .semibold)).foregroundStyle(Color.muted)
            }
        }
        .buttonStyle(PressStyle())
    }

    private var countryPicker: some View {
        NavigationStack {
            List(index?.countries ?? [], id: \.code) { c in
                Button { cc = c.code; picking = false } label: {
                    HStack(spacing: 12) {
                        AsyncImage(url: Live.flag(c.code)) { $0.resizable().scaledToFill() } placeholder: { Color.clear }.frame(width: 24, height: 17).clipShape(RoundedRectangle(cornerRadius: 3))
                        Text(verbatim: c.name).foregroundStyle(c.code == cc ? Color.violet : .white)
                        Spacer()
                        Text(verbatim: "\(c.n)").foregroundStyle(Color.muted)
                    }
                }
            }
            .navigationTitle("Country").navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
    }
}

struct LiveBadge: View {
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(.white).frame(width: 5, height: 5)
            Text(verbatim: "LIVE").font(.system(size: 10, weight: .heavy)).kerning(0.6).foregroundStyle(.white)
        }
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(Color(red: 1, green: 0.23, blue: 0.36).opacity(0.9), in: RoundedRectangle(cornerRadius: 6))
    }
}

struct LiveRequest: Identifiable { let id = UUID(); let list: [Channel]; let i: Int }

@MainActor
final class LivePlayer: ObservableObject {
    let list: [Channel]
    @Published var i: Int
    @Published var state = "tuning"  // tuning · playing · off
    let player = AVPlayer()
    private var token = 0
    private var obs: NSKeyValueObservation?
    private var viaHelper = false

    init(_ r: LiveRequest) { list = r.list; i = r.i; tune() }
    var channel: Channel { list[i] }

    func step(_ d: Int) { i = (i + d + list.count) % list.count; tune() }

    func tune(helper: Bool = false) {
        token += 1
        let t = token, ch = channel
        viaHelper = helper
        state = "tuning"
        player.replaceCurrentItem(with: nil)
        Task {
            var url: URL?
            var headers: [String: String] = [:]
            if !helper {
                url = URL(string: ch.u)
                if let r = ch.r { headers["Referer"] = r }
                if let a = ch.a { headers["User-Agent"] = a }
            } else {
                let q = { (s: String?) in (s ?? "").addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "" }
                if let u = URL(string: "\(Store.helper)/live?u=\(q(ch.u))&r=\(q(ch.r))&a=\(q(ch.a))"),
                   let (d, _) = try? await URLSession.shared.data(from: u),
                   let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let s = o["url"] as? String { url = URL(string: s) }
            }
            guard t == token else { return }
            guard let url else { state = "off"; return }
            let item = AVPlayerItem(asset: AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers]))
            obs = item.observe(\.status) { [weak self] it, _ in
                Task { @MainActor in
                    guard let self, t == self.token else { return }
                    if it.status == .readyToPlay { self.state = "playing" } else if it.status == .failed { self.failed() }
                }
            }
            player.replaceCurrentItem(with: item)
            player.play()
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            if t == token && state == "tuning" { failed() }
        }
    }

    private func failed() {
        // Straight from the channel didn't work: try once through Movio's helper, then say it's off air.
        if !viaHelper { tune(helper: true) } else { state = "off" }
    }
}

struct LivePlayerView: View {
    @StateObject private var m: LivePlayer
    var onClose: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var controls = true
    @State private var fav = false
    @State private var touched = Date()

    init(request: LiveRequest, onClose: @escaping () -> Void) {
        _m = StateObject(wrappedValue: LivePlayer(request))
        self.onClose = onClose
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            LiveVideo(player: m.player).ignoresSafeArea()
            if m.state == "tuning" {
                VStack(spacing: 12) { ProgressView().tint(.white).scaleEffect(1.3); Text("Tuning in…").font(jakarta(14, .semibold)).foregroundStyle(Color.soft) }
            }
            if m.state == "off" {
                VStack(spacing: 14) {
                    Text("This channel is off air right now").font(jakarta(17, .bold))
                    Button { m.step(1) } label: {
                        Text("Next channel").font(jakarta(15, .bold)).foregroundStyle(Color.ink).padding(.horizontal, 24).padding(.vertical, 11).background(.white, in: Capsule())
                    }
                }
            }
            if controls { bars.transition(.opacity) }
        }
        .contentShape(Rectangle())
        .onTapGesture { withAnimation { controls.toggle() }; touched = Date() }
        .gesture(DragGesture(minimumDistance: 30).onEnded { g in
            if abs(g.translation.height) > abs(g.translation.width) { m.step(g.translation.height < 0 ? 1 : -1) }
        })
        .onAppear { fav = Live.favs.contains { $0.id == m.channel.id } }
        .onChange(of: m.i) { _ in fav = Live.favs.contains { $0.id == m.channel.id }; controls = true; touched = Date() }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if controls && m.state == "playing" && Date().timeIntervalSince(touched) > 4 { withAnimation { controls = false } }
            }
        }
        .onDisappear { m.player.pause(); onClose() }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
    }

    private var bars: some View {
        VStack {
            HStack(spacing: 14) {
                Button { dismiss() } label: { Image(systemName: "chevron.backward").font(.system(size: 18, weight: .bold)).frame(width: 44, height: 44).background(.ultraThinMaterial, in: Circle()) }
                if let l = m.channel.l, let u = URL(string: l) {
                    AsyncImage(url: u) { $0.resizable().scaledToFit() } placeholder: { Color.clear }.frame(width: 52, height: 36)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: m.channel.n).font(jakarta(17, .bold)).lineLimit(1)
                    LiveBadge()
                }
                Spacer()
                Button { fav = Live.toggleFav(m.channel) } label: {
                    Image(systemName: fav ? "star.fill" : "star").font(.system(size: 20, weight: .semibold)).foregroundStyle(fav ? Color(red: 1, green: 0.82, blue: 0.37) : .white)
                        .frame(width: 44, height: 44).background(.ultraThinMaterial, in: Circle())
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 30)
            .background(LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
            Spacer()
            HStack(spacing: 14) {
                pill("chevron.backward", L("Previous")) { m.step(-1) }
                Button { if m.player.timeControlStatus == .paused { m.player.play() } else { m.player.pause() }; touched = Date() } label: {
                    Image(systemName: "playpause.fill").font(.system(size: 22, weight: .bold)).frame(width: 56, height: 56).background(.ultraThinMaterial, in: Circle())
                }
                pill("chevron.forward", L("Next")) { m.step(1) }
            }
            .foregroundStyle(.white)
            .padding(.top, 30).padding(.bottom, 18)
            .frame(maxWidth: .infinity)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        }
    }

    private func pill(_ icon: String, _ label: String, _ tap: @escaping () -> Void) -> some View {
        Button(action: { tap(); touched = Date() }) {
            Label(label, systemImage: icon).font(jakarta(14, .bold)).padding(.horizontal, 18).frame(height: 44).background(.ultraThinMaterial, in: Capsule())
        }
    }
}

/// The video itself (no system controls: Movio draws its own).
struct LiveVideo: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let c = AVPlayerViewController()
        c.player = player; c.showsPlaybackControls = false; c.videoGravity = .resizeAspect
        c.allowsPictureInPicturePlayback = true; c.canStartPictureInPictureAutomaticallyFromInline = true
        return c
    }
    func updateUIViewController(_ c: AVPlayerViewController, context: Context) {}
}
