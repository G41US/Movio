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

    init() { _ = Downloads.shared }  // reconnects to downloads that carried on in the background

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .tint(.violet)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: Store

    var body: some View {
        TabView {
            tab(HomeView(), "Home", "house.fill")
            tab(BrowseView(kind: .movie), "Movies", "film.fill")
            tab(BrowseView(kind: .tv), "TV Shows", "tv.fill")
            tab(SearchView(), "Search", "magnifyingglass")
            tab(MyListView(), "My List", "bookmark.fill")
        }
        .fullScreenCover(item: $store.playing) { req in
            PlayerScreen(request: req).environmentObject(store)
        }
    }

    private func tab<V: View>(_ v: V, _ label: String, _ icon: String) -> some View {
        NavigationStack {
            v.navigationDestination(for: Title.self) { DetailsView(title: $0) }
                .background(Color.ink.ignoresSafeArea())
        }
        .tabItem { Label(label, systemImage: icon) }
    }
}
