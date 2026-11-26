import SwiftUI
import AVKit
import WebKit

/// Forwards page messages without the web view keeping the player alive.
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: PlayerModel?
    init(_ t: PlayerModel) { target = t }
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let d = m.body as? [String: Any], let t = d["t"] as? String, let u = d["u"] as? String else { return }
        let page = d["p"] as? String ?? ""
        Task { @MainActor [weak target] in target?.onMessage(t, u, page) }
    }
}

/// Finds a stream for a title (hidden web page), plays it in Apple's player, draws subtitles.
@MainActor
final class PlayerModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    enum Phase { case finding, playing, web, failed }

    let title: Title
    @Published var season: Int
    @Published var episode: Int
    @Published var phase: Phase = .finding
    @Published var status = "Loading"
    @Published var sourceIndex = 0
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var isPlaying = false
    @Published var buffering = false
    @Published var caption = ""
    @Published var tracks: [SubTrack] = []
    @Published var selected: SubTrack?
    @Published var offset: Double = 0
    @Published var fill = false
    @Published var episodes: [Episode] = []

    let player = AVPlayer()
    let web: WKWebView
    var pip: AVPictureInPictureController?

    private var cues: [Cue] = []
    private var candidates: [(url: URL, page: String, hls: Bool)] = []
    private var rejected = Set<String>()
    private var trying: (url: URL, page: String)?
    private var attempt = 0
    private var failedSources = Set<Int>()
    private var autoTried = 0
    private var auto = true
    private var pageHost = ""
    private var loadedOnce = false
    private var runtime: Double?
    private var reconnects = 0
    private var startedAt = Date()
    private var statusObs: NSKeyValueObservation?
    private var timeObs: Any?
    private var ticks = 0
    private var stuck = 0
    private var resumeAt: Double?
    private var ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private let store = Store.shared
    private var observers: [NSObjectProtocol] = []

    init(_ req: PlayRequest) {
        title = req.title
        season = req.season
        episode = req.episode
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.userContentController.addUserScript(WKUserScript(source: sniffJS, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        web = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        cfg.userContentController.add(WeakHandler(self), name: "movio")
        web.navigationDelegate = self
        web.uiDelegate = self
        web.isOpaque = false
        web.backgroundColor = .black
        web.customUserAgent = ua
        player.allowsExternalPlayback = true

        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] n in
            Task { @MainActor in
                guard let self, (n.object as? AVPlayerItem) === self.player.currentItem else { return }
                if self.title.kind == .tv { self.next() }
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main) { [weak self] n in
            Task { @MainActor in
                guard let self, (n.object as? AVPlayerItem) === self.player.currentItem else { return }
                self.onFailed()
            }
        })

        let perTitle = UserDefaults.standard.string(forKey: "src:\(title.key)")
        let first = perTitle ?? store.sourceName ?? Sources.fastest.first.map { Sources.all[$0].name }
        Task {
            runtime = await TMDB.runtime(title.kind, title.id, season, episode)
            if title.kind == .tv { episodes = (try? await TMDB.episodes(title.id, season)) ?? [] }
        }
        loadSubtitles()
        start(Sources.index(first), automatic: true)
    }

    var label: String { title.kind == .tv ? "S\(season) · E\(episode)" : (title.year ?? "") }
    var source: StreamSource { Sources.all[sourceIndex] }
    var hasNext: Bool { title.kind == .tv }

    // MARK: Finding a stream

    func start(_ index: Int, automatic: Bool) {
        if phase == .playing { savePosition() }
        if !automatic { autoTried = 0; store.setSource(Sources.all[index].name) }
        auto = automatic
        sourceIndex = index
        attempt += 1
        let token = attempt
        startedAt = Date()
        candidates = []; rejected = []; trying = nil; loadedOnce = false
        statusObs = nil
        player.replaceCurrentItem(with: nil)
        phase = .finding
        status = autoTried > 0 ? "Finding the best stream" : "Loading"
        let src = Sources.all[index]
        let url = src.url(title.kind, title.id, season, episode)
        pageHost = url.host ?? ""
        var r = URLRequest(url: url)
        r.setValue("https://braflix.at/", forHTTPHeaderField: "Referer")
        web.load(r)
        if src.web { showPage(); return }
        Task {
            try? await Task.sleep(nanoseconds: 13_000_000_000)
            if token == attempt && phase == .finding && trying == nil { sourceFailed() }
        }
    }

    func pick(_ index: Int) {
        failedSources = []
        UserDefaults.standard.set(Sources.all[index].name, forKey: "src:\(title.key)")
        start(index, automatic: false)
    }

    fileprivate func onMessage(_ t: String, _ u: String, _ page: String) {
        guard let url = URL(string: u) else { return }
        if t == "sub" {
            if !tracks.contains(where: { $0.url == url }) {
                tracks.append(SubTrack(label: "Source \(tracks.filter { $0.lang == "und" }.count + 1)", lang: "und", url: url))
            }
            return
        }
        guard phase == .finding, !rejected.contains(u), !candidates.contains(where: { $0.url == url }) else { return }
        candidates.append((url, page, t == "stream"))
        if trying == nil { tryNext() }
    }

    private func tryNext() {
        guard let next = candidates.sorted(by: { $0.hls && !$1.hls }).first(where: { !rejected.contains($0.url.absoluteString) }) else { trying = nil; return }
        trying = (next.url, next.page)
        status = "Almost ready"
        Task { await play(next.url, page: next.page) }
    }

    private func play(_ url: URL, page: String) async {
        let token = attempt
        var headers: [String: String] = ["User-Agent": ua]
        if let p = URL(string: page), let scheme = p.scheme, let host = p.host {
            headers["Referer"] = page
            headers["Origin"] = "\(scheme)://\(host)"
        }
        let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
        guard token == attempt else { return }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers, AVURLAssetHTTPCookiesKey: cookies])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 20
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            Task { @MainActor in self?.itemStatus(it, token) }
        }
        player.replaceCurrentItem(with: item)
    }

    private func itemStatus(_ item: AVPlayerItem, _ token: Int) {
        guard token == attempt, item === player.currentItem else { return }
        switch item.status {
        case .readyToPlay: ready(item)
        case .failed: onFailed()
        default: break
        }
    }

    private func ready(_ item: AVPlayerItem) {
        if let r = resumeAt { resumeAt = nil; player.seek(to: CMTime(seconds: r, preferredTimescale: 600)); player.play(); phase = .playing; return }
        guard phase == .finding, let t = trying else { return }
        let d = item.duration.seconds
        if d.isFinite && d > 0 {
            // Short clips are ads; far from the real runtime means the wrong title.
            let wrongLength = d < 150 || (runtime.map { $0 > 0 && abs(d - $0) / $0 > 0.3 } ?? false)
            if wrongLength {
                rejected.insert(t.url.absoluteString); trying = nil
                player.replaceCurrentItem(with: nil)
                if candidates.contains(where: { !rejected.contains($0.url.absoluteString) }) { tryNext() } else { sourceFailed() }
                return
            }
        }
        phase = .playing
        reconnects = 0
        Sources.score(sourceIndex, Date().timeIntervalSince(startedAt))
        failedSources.remove(sourceIndex)
        if let s = savedPosition(), s.0 > 30, s.1 <= 0 || s.0 < s.1 - 60 {
            player.seek(to: CMTime(seconds: s.0, preferredTimescale: 600))
        }
        player.play()
        stopPage()
    }

    private func savedPosition() -> (Double, Double)? { store.position(title, season, episode) }

    private func onFailed() {
        if phase == .playing {
            // Mid-film: reconnect twice, then move to another source at the same spot.
            let pos = player.currentTime().seconds
            if reconnects < 2, let t = trying {
                reconnects += 1
                status = "Reconnecting…"
                resumeAt = pos.isFinite ? pos : nil
                Task { await play(t.url, page: t.page) }
                return
            }
            savePosition()
            auto = true; autoTried = 0
            sourceFailed()
            return
        }
        if let t = trying { rejected.insert(t.url.absoluteString) }
        trying = nil
        if candidates.contains(where: { !rejected.contains($0.url.absoluteString) }) { tryNext() }
    }

    private func sourceFailed() {
        failedSources.insert(sourceIndex)
        Sources.score(sourceIndex, 30)
        if auto && autoTried < 5, let next = Sources.fastest.first(where: { !failedSources.contains($0) }) {
            autoTried += 1
            start(next, automatic: true)
            return
        }
        showPage()
    }

    /// Show the source's own page (its player) when Movio couldn't catch the stream.
    func showPage() {
        player.pause()
        phase = .web
        web.evaluateJavaScript("window.__show=1;document.querySelectorAll('video').forEach(function(v){v.muted=false});document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage('movio-show','*')}catch(e){}})")
    }

    private func stopPage() {
        web.stopLoading()
        web.load(URLRequest(url: URL(string: "about:blank")!))
    }

    // MARK: Page guard: no pop-ups, no redirects to ad sites

    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction, windowFeatures f: WKWindowFeatures) -> WKWebView? { nil }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        let main = navigationAction.targetFrame?.isMainFrame ?? true
        if main, loadedOnce, let h = navigationAction.request.url?.host, !h.isEmpty, h != pageHost { return .cancel }
        return .allow
    }

    func webView(_ w: WKWebView, didFinish n: WKNavigation!) { loadedOnce = true }

    // MARK: Playing

    private func tick() {
        let t = player.currentTime().seconds
        if t.isFinite { position = t }
        if let d = player.currentItem?.duration.seconds, d.isFinite { duration = d }
        isPlaying = player.timeControlStatus == .playing
        buffering = phase == .playing && player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        let now = position - offset
        let lines = cues.filter { now >= $0.start && now < $0.end }.map(\.text).joined(separator: "\n")
        if lines != caption { caption = lines }
        ticks += 1
        if ticks % 20 == 0 && phase == .playing { savePosition() }
        // Stuck loading for 20 s without an error: treat it as a failure.
        if ticks % 4 == 0 {
            stuck = buffering ? stuck + 1 : 0
            if stuck >= 20 { stuck = 0; onFailed() }
        }
    }

    func savePosition() {
        guard phase == .playing, duration > 0 else { return }
        store.savePosition(title, season, episode, position, duration)
    }

    func toggle() { isPlaying ? player.pause() : player.play() }
    func seek(by s: Double) { seek(to: position + s) }
    func seek(to s: Double) {
        let v = max(0, min(duration > 0 ? duration - 1 : s, s))
        player.seek(to: CMTime(seconds: v, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        position = v
    }

    func next() {
        savePosition()
        let count = episodes.count
        if count > 0 && episode >= count {
            season += 1; episode = 1
            Task { episodes = (try? await TMDB.episodes(title.id, season)) ?? [] }
        } else { episode += 1 }
        failedSources = []; autoTried = 0
        Task { runtime = await TMDB.runtime(title.kind, title.id, season, episode) }
        loadSubtitles()
        start(sourceIndex, automatic: true)
    }

    func play(episode e: Int) {
        savePosition()
        episode = e
        failedSources = []; autoTried = 0
        Task { runtime = await TMDB.runtime(title.kind, title.id, season, episode) }
        loadSubtitles()
        start(sourceIndex, automatic: true)
    }

    // MARK: Subtitles

    private func loadSubtitles() {
        tracks = []; cues = []; selected = nil; caption = ""
        let (s, e) = (season, episode)
        Task {
            let found = await Subtitles.find(title.kind, title.id, s, e)
            guard s == season && e == episode else { return }
            tracks = found + tracks.filter { $0.lang == "und" }
            if selected == nil, let en = found.first(where: { $0.lang == "en" }) { select(en) }
        }
    }

    func select(_ t: SubTrack?) {
        selected = t
        cues = []; caption = ""
        guard let t else { return }
        Task {
            let c = await Subtitles.load(t.url)
            if selected == t { cues = c }
        }
    }

    func close() {
        savePosition()
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let o = timeObs { player.removeTimeObserver(o); timeObs = nil }
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        web.stopLoading()
        web.configuration.userContentController.removeScriptMessageHandler(forName: "movio")
    }
}

// MARK: - Views

struct VideoSurface: UIViewRepresentable {
    let model: PlayerModel
    final class V: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var pl: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> V {
        let v = V()
        v.backgroundColor = .black
        v.pl.player = model.player
        if AVPictureInPictureController.isPictureInPictureSupported() {
            let p = AVPictureInPictureController(playerLayer: v.pl)
            p?.canStartPictureInPictureAutomaticallyFromInline = true
            model.pip = p
        }
        return v
    }
    func updateUIView(_ v: V, context: Context) { v.pl.videoGravity = model.fill ? .resizeAspectFill : .resizeAspect }
}

struct WebSurface: UIViewRepresentable {
    let web: WKWebView
    func makeUIView(context: Context) -> WKWebView { web }
    func updateUIView(_ v: WKWebView, context: Context) {}
}

struct PlayerScreen: View {
    @StateObject private var m: PlayerModel
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var controls = true
    @State private var lastTouch = Date()
    @State private var scrubbing = false
    @State private var scrub: Double = 0

    init(request: PlayRequest) { _m = StateObject(wrappedValue: PlayerModel(request)) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            WebSurface(web: m.web).ignoresSafeArea().opacity(m.phase == .web ? 1 : 0.01).allowsHitTesting(m.phase == .web)
            if m.phase == .playing || m.phase == .finding {
                VideoSurface(model: m).ignoresSafeArea().opacity(m.phase == .playing ? 1 : 0)
            }
            if m.phase == .playing {
                Color.clear.contentShape(Rectangle()).ignoresSafeArea()
                    .onTapGesture { withAnimation(.easeOut(duration: 0.2)) { controls.toggle() }; lastTouch = Date() }
                captionView
                if controls { controlsView.transition(.opacity) }
                if m.buffering { ProgressView().tint(.white).scaleEffect(1.4) }
            }
            if m.phase == .finding { findingView }
            if m.phase == .web { webBar }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear { rotate(landscape: true) }
        .onDisappear { m.close(); rotate(landscape: false) }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if controls && m.isPlaying && !scrubbing && Date().timeIntervalSince(lastTouch) > 3.5 { withAnimation { controls = false } }
            }
        }
    }

    private func rotate(landscape: Bool) {
        AppDelegate.orientations = landscape ? .landscape : .allButUpsideDown
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: landscape ? .landscapeRight : .portrait)) { _ in }
        scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    private func close() { store.playing = nil; dismiss() }
    private func touch() { lastTouch = Date() }

    // Subtitles sit low, and lift above the controls when those show.
    private var captionView: some View {
        VStack {
            Spacer()
            if !m.caption.isEmpty {
                Text(m.caption)
                    .font(jakarta(20, .bold)).foregroundStyle(.white).multilineTextAlignment(.center)
                    .shadow(color: .black, radius: 3).shadow(color: .black.opacity(0.8), radius: 8)
                    .padding(.horizontal, 40)
            }
        }
        .padding(.bottom, controls ? 92 : 26)
        .allowsHitTesting(false)
        .animation(.easeOut(duration: 0.2), value: controls)
    }

    private var controlsView: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea().allowsHitTesting(false)
            VStack {
                HStack(spacing: 18) {
                    icon("xmark") { close() }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.title.name).font(jakarta(17, .extrabold)).lineLimit(1)
                        Text(m.label).font(jakarta(12, .semibold)).foregroundStyle(Color.soft)
                    }
                    Spacer()
                    subtitlesMenu
                    sourcesMenu
                    if m.title.kind == .tv { episodesMenu }
                    icon(m.fill ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right") { m.fill.toggle(); touch() }
                    if m.pip != nil { icon("pip.enter") { m.pip?.startPictureInPicture() } }
                }
                Spacer()
                HStack(spacing: 56) {
                    icon("gobackward.10", 30) { m.seek(by: -10); touch() }
                    icon(m.isPlaying ? "pause.fill" : "play.fill", 44) { m.toggle(); touch() }
                    icon("goforward.10", 30) { m.seek(by: 10); touch() }
                }
                Spacer()
                HStack(spacing: 12) {
                    Text(clock(scrubbing ? scrub : m.position)).font(jakarta(12, .semibold)).monospacedDigit()
                    Slider(value: Binding(get: { scrubbing ? scrub : m.position }, set: { scrub = $0 }),
                           in: 0...max(1, m.duration),
                           onEditingChanged: { editing in
                               if editing { scrub = m.position; scrubbing = true } else { m.seek(to: scrub); scrubbing = false }
                               touch()
                           })
                        .tint(.violet)
                    Text("-" + clock(max(0, m.duration - (scrubbing ? scrub : m.position)))).font(jakarta(12, .semibold)).monospacedDigit()
                    if m.hasNext {
                        Button { m.next() } label: {
                            Label("Next", systemImage: "forward.end.fill").font(jakarta(13, .bold))
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background(.ultraThinMaterial, in: Capsule())
                        }
                    }
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 24).padding(.vertical, 16)
        }
    }

    private func icon(_ name: String, _ size: CGFloat = 20, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: name).font(.system(size: size, weight: .semibold)).frame(minWidth: 36, minHeight: 36) }
            .foregroundStyle(.white)
    }

    @ViewBuilder private func choice(_ text: String, _ on: Bool) -> some View {
        if on { Label(text, systemImage: "checkmark") } else { Text(text) }
    }

    private var subtitlesMenu: some View {
        Menu {
            Button { m.select(nil) } label: { choice("Off", m.selected == nil) }
            ForEach(m.tracks) { t in
                Button { m.select(t) } label: { choice(t.label, m.selected == t) }
            }
            Section("Timing: " + (m.offset == 0 ? "no change" : String(format: "%+.1fs", m.offset))) {
                Button { m.offset -= 0.5 } label: { Label("Show earlier", systemImage: "minus.circle") }
                Button { m.offset += 0.5 } label: { Label("Show later", systemImage: "plus.circle") }
                if m.offset != 0 { Button { m.offset = 0 } label: { Label("Reset", systemImage: "arrow.counterclockwise") } }
            }
        } label: { Image(systemName: m.selected == nil ? "captions.bubble" : "captions.bubble.fill").font(.system(size: 20, weight: .semibold)).frame(minWidth: 36, minHeight: 36) }
            .onTapGesture { touch() }
    }

    private var sourcesMenu: some View {
        Menu {
            ForEach(Array(Sources.all.enumerated()), id: \.offset) { i, s in
                Button { m.pick(i) } label: { choice(s.name + (s.web ? " (web)" : ""), i == m.sourceIndex) }
            }
        } label: { Image(systemName: "server.rack").font(.system(size: 19, weight: .semibold)).frame(minWidth: 36, minHeight: 36) }
    }

    private var episodesMenu: some View {
        Menu {
            ForEach(m.episodes) { e in
                Button { m.play(episode: e.number) } label: { choice("\(e.number). \(e.name)", e.number == m.episode) }
            }
        } label: { Image(systemName: "list.bullet").font(.system(size: 20, weight: .semibold)).frame(minWidth: 36, minHeight: 36) }
    }

    private var findingView: some View {
        ZStack {
            Art(url: TMDB.img(m.title.backdrop, "w1280")).ignoresSafeArea().blur(radius: 30).overlay(Color.black.opacity(0.55))
            VStack(spacing: 14) {
                Text(m.title.name).font(jakarta(24, .extrabold)).multilineTextAlignment(.center)
                if m.title.kind == .tv { Text(m.label).font(jakarta(14, .semibold)).foregroundStyle(Color.soft) }
                ProgressView().tint(.white).scaleEffect(1.3).padding(.top, 6)
                Text(m.status).font(jakarta(13, .semibold)).foregroundStyle(Color.soft)
            }
            .foregroundStyle(.white)
            VStack {
                HStack {
                    icon("xmark") { close() }
                    Spacer()
                    sourcesMenu.foregroundStyle(.white)
                }
                Spacer()
            }
            .padding(20)
        }
    }

    private var webBar: some View {
        VStack {
            HStack(spacing: 12) {
                icon("xmark") { close() }
                    .background(.ultraThinMaterial, in: Circle())
                Spacer()
                sourcesMenu.foregroundStyle(.white).background(.ultraThinMaterial, in: Circle())
            }
            Spacer()
        }
        .padding(14)
    }
}

func clock(_ s: Double) -> String {
    guard s.isFinite, s > 0 else { return "0:00" }
    let t = Int(s)
    return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
}
