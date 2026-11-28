import SwiftUI
import AVFoundation

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// The player turns this to landscape while it's open.
    static var orientations: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientations
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 400 << 20)
        // Sound plays even with the silent switch on, and keeps going in Picture in Picture.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        let bar = UITabBarAppearance()
        bar.configureWithDefaultBackground()
        bar.backgroundEffect = UIBlurEffect(style: .systemChromeMaterialDark)
        UITabBar.appearance().standardAppearance = bar
        UITabBar.appearance().scrollEdgeAppearance = bar
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        nav.largeTitleTextAttributes = [.font: UIFont(name: "MovioJakarta-ExtraBold", size: 32) ?? .boldSystemFont(ofSize: 32), .foregroundColor: UIColor.white]
        nav.titleTextAttributes = [.font: UIFont(name: "MovioJakarta-Bold", size: 17) ?? .boldSystemFont(ofSize: 17), .foregroundColor: UIColor.white]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        return true
    }
}

@main
struct MovioApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var store = Store.shared
    @StateObject private var profile = Profile.shared

    init() {
        _ = Downloads.shared  // reconnects to downloads that carried on in the background
        // No scroll bars anywhere (lists, menus, rows).
        UIScrollView.appearance().showsVerticalScrollIndicator = false
        UIScrollView.appearance().showsHorizontalScrollIndicator = false
    }

    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(profile)
                .environment(\.layoutDirection, profile.rtl ? .rightToLeft : .leftToRight)
                .preferredColorScheme(.dark)
                .tint(.violet)
                // Linked devices: bring in changes when Movio opens, send ours when it goes to the background.
                .onChange(of: phase) { p in if p != .inactive { Task { await store.sync() } } }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var profile: Profile

    var body: some View {
        // First launch: name, language and subtitles before anything loads, so it all comes in the right language.
        Group {
            if let url = required { ForceUpdateView(ipa: url) }
            else if !profile.setupDone { SetupView().transition(.opacity) } else { tabs }
        }
        // Every new version is required: if one is out, Movio waits on the update screen.
        .task { Ping.open(); required = await ForceUpdateView.newer() }
    }
    @State private var required: URL?

    private var tabs: some View {
        TabView {
            tab(HomeView(), "Home", "house.fill")
            tab(BrowseView(kind: .movie), "Movies", "film.fill")
            tab(BrowseView(kind: .tv), "TV Shows", "tv.fill")
            tab(SearchView(), "Search", "magnifyingglass")
            tab(MyListView(), "My List", "bookmark.fill")
        }
        .id("\(profile.lang)\(store.kids)")  // a new language or kids mode reloads every screen
        .overlay(alignment: .top) { GreetingPill(text: profile.greeting).padding(.top, 6) }
        .fullScreenCover(item: $store.playing) { req in
            PlayerScreen(request: req).environmentObject(store).environmentObject(profile)
        }
    }

    private func tab<V: View>(_ v: V, _ label: String, _ icon: String) -> some View {
        NavigationStack {
            v.navigationDestination(for: Title.self) { DetailsView(title: $0) }
                .navigationDestination(for: Person.self) { PersonView(person: $0) }
                .background(Color.ink.ignoresSafeArea())
        }
        .tabItem { Label(label, systemImage: icon) }
    }
}

/// Shown instead of the app when a newer iPhone version is out. No release notes, just the way to get it.
struct ForceUpdateView: View {
    let ipa: URL
    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            RoundedRectangle(cornerRadius: 22, style: .continuous).fill(LinearGradient(colors: [.violet, .pink], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 84, height: 84)
                .overlay(Image(systemName: "arrow.down").font(.system(size: 34, weight: .bold)).foregroundStyle(.white))
            Text(verbatim: L("Update required")).font(jakarta(26, .extrabold)).foregroundStyle(.white).padding(.top, 22)
            Text(verbatim: L("A new version of Movio is out. Update to keep watching.")).font(jakarta(15)).foregroundStyle(Color.soft)
                .multilineTextAlignment(.center).padding(.top, 8)
            BigButton(label: "Get the update") {
                // AltStore or Scarlet installs it straight away; otherwise the file downloads in Safari.
                let enc = ipa.absoluteString.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
                let alt = URL(string: "altstore://install?url=\(enc)")!, sc = URL(string: "scarlet://install=\(ipa.absoluteString)")!
                UIApplication.shared.open(alt) { ok in
                    if ok { return }
                    UIApplication.shared.open(sc) { ok in if !ok { UIApplication.shared.open(ipa) } }
                }
            }
            .padding(.top, 26)
            Spacer()
        }
        .padding(28).frame(maxWidth: 440).frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.ink.ignoresSafeArea())
    }

    /// The newest iPhone release's .ipa, when it's newer than this build.
    static func newer() async -> URL? {
        guard let api = URL(string: "https://api.github.com/repos/G41US/Movio/releases?per_page=15") else { return nil }
        var req = URLRequest(url: api, timeoutInterval: 10)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        let mine = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        for r in list {
            guard let tag = r["tag_name"] as? String, tag.hasPrefix("ios-v") else { continue }
            let v = String(tag.dropFirst(5))
            guard v.compare(mine, options: .numeric) == .orderedDescending else { return nil }
            let assets = r["assets"] as? [[String: Any]] ?? []
            let link = assets.compactMap { $0["browser_download_url"] as? String }.first { $0.hasSuffix(".ipa") }
            return link.flatMap(URL.init(string:))
        }
        return nil
    }
}
