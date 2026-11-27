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
    private var counted = false
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
    @Published var segments: [Segment] = []
    @Published var countdown: Int?
    @Published var syncStatus = "Waiting for the film"
    @Published var fit = SubFit()
    let offline: DownloadItem?
    let nowPlaying = NowPlaying()

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
    private var fromCache = false
    private var countdownEnd: Date?
    private var countdownCancelled = false
    private var headers: [String: String] = [:]
    private var cookies: [HTTPCookie] = []
    private var speech: [Float]?
    private var speechFor: URL?
    private var cueCache: [URL: [Cue]] = [:]
    private var posKey: String { Store.posKey(title, season, episode) }

    init(_ req: PlayRequest) {
        title = req.title
        season = req.season
        episode = req.episode
        offline = req.offline.flatMap { id in Downloads.shared.item(id) }
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
        // Shows: your pick for this show, else Club first. Films: your pick, your default, else the fastest.
        let first = perTitle ?? (req.title.kind == .tv ? nil : store.sourceName) ?? Sources.order(req.title.kind).first.map { Sources.all[$0].name }
        Task {
            runtime = await TMDB.runtime(title.kind, title.id, season, episode)
            if title.kind == .tv { episodes = (try? await TMDB.episodes(title.id, season)) ?? [] }
        }
        nowPlaying.attach(self)
        loadSegments()
        if let off = offline, let file = off.fileURL {
            // Saved on the phone: no searching, no network.
            sourceIndex = 0
            if let sub = off.subtitleURL { let t = SubTrack(label: "Saved subtitles", lang: "en", url: sub); tracks = [t]; select(t) }
            trying = (file, "")
            status = "Opening download"
            let item = AVPlayerItem(asset: AVURLAsset(url: file))
            statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
                Task { @MainActor in self?.itemStatus(it, 0) }
            }
            player.replaceCurrentItem(with: item)
            return
        }
        loadSubtitles()
        start(Sources.index(first), automatic: true)
    }

    private func loadSegments() {
        segments = []; countdown = nil; countdownEnd = nil; countdownCancelled = false
        let (s, e) = (season, episode)
        Task {
            let found = await Segments.find(title.kind, title.id, s, e)
            if s == season && e == episode { segments = found }
        }
    }

    var label: String { title.kind == .tv ? "S\(season) · E\(episode)" : (title.year ?? "") }
    var source: StreamSource { Sources.all[sourceIndex] }
    var hasNext: Bool { title.kind == .tv }

    // MARK: Finding a stream

    func start(_ index: Int, automatic: Bool, useCache: Bool = true) {
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
        if useCache, !src.web, let link = Links.get(posKey, src.name) {
            // Played this before on this source: its stream link usually still works.
            fromCache = true
            status = "Resuming"
            trying = link
            Task { await play(link.url, page: link.page) }
            return
        }
        fromCache = false
        // Club, Peach, Mist and Main: Movio's helper hands over the stream itself, so it plays in Movio's
        // own player (never the source's web page with its ads). If it doesn't have the title, the next source is tried.
        if Sources.helped.contains(src.name) {
            pageHost = ""
            Task {
                let got = await Sources.viaHelper(src.name, title.kind, title.id, season, episode)
                guard token == attempt else { return }
                guard let (url, subs) = got else { sourceFailed(); return }
                for t in subs where !tracks.contains(where: { $0.url == t.url }) { tracks.append(t) }
                trying = (url, "")
                status = "Almost ready"
                await play(url, page: "")
            }
            return
        }
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
        self.headers = headers
        self.cookies = cookies
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers, AVURLAssetHTTPCookiesKey: cookies])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 20
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            Task { @MainActor in self?.itemStatus(it, token) }
        }
        player.replaceCurrentItem(with: item)
    }

    private func itemStatus(_ item: AVPlayerItem, _ token: Int) {
        guard token == attempt || offline != nil, item === player.currentItem else { return }
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
            if wrongLength && fromCache { retryFresh(); return }
            if wrongLength {
                rejected.insert(t.url.absoluteString); trying = nil
                player.replaceCurrentItem(with: nil)
                if candidates.contains(where: { !rejected.contains($0.url.absoluteString) }) { tryNext() } else { sourceFailed() }
                return
            }
        }
        phase = .playing
        reconnects = 0
        if offline == nil && !counted { counted = true; Store.shared.stat("play", title, []) }
        if offline == nil {
            Sources.score(sourceIndex, Date().timeIntervalSince(startedAt))
            failedSources.remove(sourceIndex)
            Links.save(posKey, source.name, url: t.url, page: t.page)
            syncSubtitles()
        }
        if let s = savedPosition(), s.0 > 30, s.1 <= 0 || s.0 < s.1 - 60 {
            player.seek(to: CMTime(seconds: s.0, preferredTimescale: 600))
        }
        player.play()
        Haptic.soft()
        if offline == nil { stopPage() }
        nowPlaying.update(self)
    }

    /// The remembered link expired: forget it and find a fresh one.
    private func retryFresh() {
        fromCache = false
        Links.drop(posKey, source.name)
        start(sourceIndex, automatic: auto, useCache: false)
    }

    private func savedPosition() -> (Double, Double)? { store.position(title, season, episode) }

    private func onFailed() {
        if offline != nil { status = "This download can't be played"; phase = .failed; return }
        if fromCache && phase != .playing { retryFresh(); return }
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
            Links.drop(posKey, source.name)
            auto = true; autoTried = 0
            sourceFailed()
            return
        }
        if let t = trying { rejected.insert(t.url.absoluteString) }
        trying = nil
        if Sources.helped.contains(source.name) { sourceFailed(); return }
        if candidates.contains(where: { !rejected.contains($0.url.absoluteString) }) { tryNext() }
    }

    private func sourceFailed() {
        failedSources.insert(sourceIndex)
        Sources.score(sourceIndex, 30)
        if auto && autoTried < 6, let next = Sources.order(title.kind).first(where: { !failedSources.contains($0) }) {
            autoTried += 1
            start(next, automatic: true)
            return
        }
        // A helper source has no page worth showing (it would only bring the source's ads).
        if Sources.helped.contains(source.name) { status = "No stream found right now. Try another source."; phase = .failed; return }
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
        let now = (position - offset - fit.offset) / fit.scale
        let lines = cues.filter { now >= $0.start && now < $0.end }.map(\.text).joined(separator: "\n")
        if lines != caption { caption = lines }
        ticks += 1
        if ticks % 20 == 0 && phase == .playing { savePosition(); nowPlaying.update(self); prepareNext() }
        updateCountdown()
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

    func toggle() {
        isPlaying ? player.pause() : player.play()
        Haptic.tap()
        Task { @MainActor in try? await Task.sleep(nanoseconds: 300_000_000); nowPlaying.update(self) }
    }
    func seek(by s: Double) { seek(to: position + s, learn: false) }
    func seek(to s: Double, learn: Bool = true) {
        let v = max(0, min(duration > 0 ? duration - 1 : s, s))
        // You jumped past the opening of an episode: remember it as the show's intro.
        if learn, title.kind == .tv, phase == .playing, !segments.contains(where: { $0.type == "intro" }) {
            Segments.learn(title.id, from: position, to: v)
        }
        if v < position { countdownCancelled = false; countdownEnd = nil; countdown = nil }
        player.seek(to: CMTime(seconds: v, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        position = v
        nowPlaying.update(self)
    }

    /// The skip button for wherever we are (credits on a show are handled by the Next episode card).
    var activeSegment: Segment? {
        segments.first { $0.contains(position) && !($0.type == "credits" && hasNext) }
    }

    func skip(_ seg: Segment) {
        Haptic.soft()
        if let end = seg.end { seek(to: end, learn: false) } else if hasNext { next() } else { seek(to: duration - 1, learn: false) }
    }

    /// Next episode in 10 s once the credits start (or the last 25 s when nobody has timed them).
    private func updateCountdown() {
        guard hasNext, phase == .playing, duration > 60, !countdownCancelled else { countdown = nil; return }
        let creditsAt = segments.first { $0.type == "credits" }?.start ?? (duration - 25)
        if position >= creditsAt {
            if countdownEnd == nil { countdownEnd = Date().addingTimeInterval(10) }
            let left = Int(ceil(countdownEnd!.timeIntervalSinceNow))
            countdown = max(0, left)
            if left <= 0 { next() }
        } else { countdown = nil; countdownEnd = nil }
    }

    func cancelCountdown() { countdownCancelled = true; countdown = nil }

    // MARK: Download

    var downloadState: DownloadItem? { Downloads.shared.item(posKey) }

    func download() {
        guard offline == nil, phase == .playing, let t = trying else { return }
        Haptic.tap()
        Downloads.shared.start(title: title, season: season, episode: episode, url: t.url, headers: headers, cookies: cookies,
                               hls: t.url.absoluteString.lowercased().contains("m3u8") || candidates.first { $0.url == t.url }?.hls == true,
                               subtitle: selected?.url)
        objectWillChange.send()
    }

    /// Near the end of an episode, the next one's subtitles are fetched so they're there from its first second.
    private var preparedNext = ""
    private func prepareNext() {
        guard title.kind == .tv, offline == nil, duration > 300, position > duration * 0.75 else { return }
        let (s, e) = !episodes.isEmpty && episode >= episodes.count ? (season + 1, 1) : (season, episode + 1)
        guard preparedNext != "\(s):\(e)" else { return }
        preparedNext = "\(s):\(e)"
        Subtitles.prepare(title.kind, title.id, s, e)
    }

    func next() {
        savePosition()
        countdownEnd = nil; countdown = nil
        let count = episodes.count
        if count > 0 && episode >= count {
            season += 1; episode = 1
            Task { episodes = (try? await TMDB.episodes(title.id, season)) ?? [] }
        } else { episode += 1 }
        failedSources = []; autoTried = 0
        Task { runtime = await TMDB.runtime(title.kind, title.id, season, episode) }
        loadSubtitles()
        loadSegments()
        start(sourceIndex, automatic: true)
    }

    func play(episode e: Int) {
        savePosition()
        episode = e
        failedSources = []; autoTried = 0
        Task { runtime = await TMDB.runtime(title.kind, title.id, season, episode) }
        loadSubtitles()
        loadSegments()
        start(sourceIndex, automatic: true)
    }

    // MARK: Subtitles

    private func loadSubtitles() {
        tracks = []; cues = []; selected = nil; caption = ""
        let (s, e) = (season, episode)
        // Two lookups at once; whichever answers first gets subtitles on screen.
        Task { add(await Subtitles.find(title.kind, title.id, s, e), s, e) }
        Task { add(await Subtitles.party(title.kind, title.id, s, e), s, e) }
    }

    private func add(_ found: [SubTrack], _ s: Int, _ e: Int) {
        guard s == season && e == episode, !found.isEmpty else { return }
        let fresh = found.filter { f in !tracks.contains { $0.url == f.url } }
        // Keep languages grouped: English first, then the rest, then ones caught from the page.
        tracks = (tracks + fresh).enumerated().sorted { (rank($0.element), $0.offset) < (rank($1.element), $1.offset) }.map(\.element)
        // The subtitle language picked at setup (none if "No subtitles").
        let want = I18n.subLang
        if selected == nil, !userOff, want != "off", let first = tracks.first(where: { $0.lang == want }) { select(first) }
        // Sync already listened: test the new versions too.
        if let sp = speech { Task { await evaluate(sp) } }
    }

    private var userOff = false
    func turnOff() { userOff = true; select(nil) }

    private func rank(_ t: SubTrack) -> Int { t.lang == I18n.subLang ? 0 : t.lang == "en" ? 1 : t.lang == "und" ? 3 : 2
    }

    func select(_ t: SubTrack?, fit newFit: SubFit? = nil) {
        selected = t
        cues = []; caption = ""
        fit = newFit ?? SubFit()
        guard let t else { return }
        Task {
            let c = await cuesFor(t)
            if selected == t {
                cues = c
                if newFit == nil, let sp = speech { await check(t, sp) }
            }
        }
    }

    private func cuesFor(_ t: SubTrack) async -> [Cue] {
        if let c = cueCache[t.url] { return c }
        let c = await Subtitles.load(t.url)
        if !c.isEmpty { cueCache[t.url] = c }
        return c
    }

    /// Listens to the first minutes of the stream, tries every English version against it,
    /// and keeps the one that lines up best with frame-rate/delay mismatches fixed.
    private func syncSubtitles() {
        guard let t = trying, offline == nil, speechFor != t.url else { return }
        speechFor = t.url
        speech = nil
        syncStatus = "Listening to the film…"
        let (h, url) = (headers, t.url)
        Task {
            var hh = h
            if !cookies.isEmpty, let c = HTTPCookie.requestHeaderFields(with: cookies)["Cookie"] { hh["Cookie"] = c }
            let sp = await StreamAudio.speech(stream: url, headers: hh)
            guard speechFor == url else { return }
            guard let sp else { syncStatus = "Can't check this stream · use Timing"; return }
            speech = sp
            await evaluate(sp)
        }
    }

    /// Tries every English version against what the film sounds like; keeps the best fit.
    private func evaluate(_ sp: [Float]) async {
        let url = speechFor
        do {
            let cands = tracks.filter { $0.lang == (I18n.subLang == "off" ? "en" : I18n.subLang) || $0.lang == "und" || $0 == selected }.prefix(8)
            var results: [(SubTrack, SubAligner.Eval)] = []
            for c in cands {
                let cues = await cuesFor(c)
                if let ev = await Task.detached(priority: .utility, operation: { SubAligner.evaluate(cues, sp) }).value { results.append((c, ev)) }
            }
            guard speechFor == url else { return }
            let current = results.first { $0.0 == selected }?.1
            if let best = results.filter({ $0.1.sure }).max(by: { $0.1.quality < $1.1.quality }),
               best.0 != selected, best.1.quality > (current?.quality ?? -1) + 0.08 {
                select(best.0, fit: best.1.fit)
                syncStatus = "Switched to a better-matching version · " + describe(best.1.fit)
                return
            }
            if let c = current, c.sure { fit = c.fit; syncStatus = describe(c.fit) }
            else if tracks.isEmpty { syncStatus = "No subtitles found yet" }
            else { syncStatus = "Looks fine · use Timing if needed" }
        }
    }

    private func check(_ t: SubTrack, _ sp: [Float]) async {
        let cues = await cuesFor(t)
        guard let ev = await Task.detached(priority: .utility, operation: { SubAligner.evaluate(cues, sp) }).value else { return }
        if selected == t && ev.sure { fit = ev.fit; syncStatus = describe(ev.fit) }
    }

    private func describe(_ f: SubFit) -> String {
        var parts: [String] = []
        if abs(f.scale - 1) > 0.0005 { parts.append(abs(f.scale - 1) > 0.03 ? "frame rate fixed" : "slow drift fixed") }
        if abs(f.offset) >= 0.3 { parts.append(String(format: "%+.1fs", f.offset)) }
        return parts.isEmpty ? "In sync" : "In sync (" + parts.joined(separator: ", ") + ")"
    }

    func close() {
        savePosition()
        nowPlaying.detach()
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
    @ObservedObject private var downloads = Downloads.shared
    @Environment(\.dismiss) private var dismiss
    @State private var controls = true
    @State private var lastTouch = Date()
    @State private var scrubbing = false
    @State private var scrub: Double = 0
    @State private var ripple: (left: Bool, id: Int)?
    @State private var hud: (icon: String, value: Double)?
    @State private var dragStart: Double?
    @State private var panel: Panel?
    @State private var slow = false
    private enum Panel { case subtitles, sources, episodes }

    init(request: PlayRequest) { _m = StateObject(wrappedValue: PlayerModel(request)) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VolumeHook().frame(width: 1, height: 1).opacity(0.01)
            WebSurface(web: m.web).ignoresSafeArea().opacity(m.phase == .web ? 1 : 0.01).allowsHitTesting(m.phase == .web)
            if m.phase == .playing || m.phase == .finding {
                VideoSurface(model: m).ignoresSafeArea().opacity(m.phase == .playing ? 1 : 0)
            }
            if m.phase == .playing {
                gestureLayer
                captionView
                if controls { controlsView.transition(.opacity) }
                if m.buffering { ProgressView().tint(.white).scaleEffect(1.4).allowsHitTesting(false) }
                rippleView
                hudView
                skipAndNext
            }
            if m.phase == .finding { findingView }
            if m.phase == .web { webBar }
            if m.phase == .failed { failedView }
            if let p = panel { sidePanel(p).transition(.move(edge: .trailing).combined(with: .opacity)) }
        }
        .animation(.easeOut(duration: 0.22), value: panel)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear { rotate(landscape: true) }
        .onDisappear { m.close(); rotate(landscape: false) }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if controls && panel == nil && m.isPlaying && !scrubbing && Date().timeIntervalSince(lastTouch) > 3.5 { withAnimation { controls = false } }
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

    // MARK: Gestures: tap = controls, double-tap a side = ±10 s, swipe up/down left = brightness, right = volume

    private var gestureLayer: some View {
        GeometryReader { g in
            Color.clear.contentShape(Rectangle())
                .gesture(
                    SpatialTapGesture(count: 2).onEnded { e in
                        let left = e.location.x < g.size.width / 2
                        m.seek(by: left ? -10 : 10)
                        Haptic.tap()
                        ripple = (left, (ripple?.id ?? 0) + 1)
                        let id = ripple!.id
                        Task { try? await Task.sleep(nanoseconds: 700_000_000); if ripple?.id == id { withAnimation { ripple = nil } } }
                    }
                    .exclusively(before: TapGesture().onEnded {
                        withAnimation(.easeOut(duration: 0.2)) { controls.toggle() }
                        touch()
                    })
                )
                .simultaneousGesture(
                    DragGesture(minimumDistance: 14)
                        .onChanged { v in
                            guard abs(v.translation.height) > abs(v.translation.width) else { return }
                            let left = v.startLocation.x < g.size.width / 2
                            if dragStart == nil { dragStart = left ? Double(UIScreen.main.brightness) : Double(VolumeHook.volume) }
                            let value = max(0, min(1, dragStart! - Double(v.translation.height / (g.size.height * 0.8))))
                            if left { UIScreen.main.brightness = CGFloat(value) } else { VolumeHook.volume = Float(value) }
                            hud = (left ? "sun.max.fill" : (value == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill"), value)
                        }
                        .onEnded { _ in
                            dragStart = nil
                            Task { try? await Task.sleep(nanoseconds: 600_000_000); withAnimation { hud = nil } }
                        }
                )
        }
        .ignoresSafeArea()
    }

    private var rippleView: some View {
        HStack {
            if let r = ripple, r.left { seekBadge("gobackward.10") }
            Spacer()
            if let r = ripple, !r.left { seekBadge("goforward.10") }
        }
        .padding(.horizontal, 70)
        .allowsHitTesting(false)
    }

    private func seekBadge(_ icon: String) -> some View {
        Image(systemName: icon).font(.system(size: 30, weight: .semibold)).foregroundStyle(.white)
            .frame(width: 84, height: 84).background(.ultraThinMaterial, in: Circle())
            .transition(.scale.combined(with: .opacity))
    }

    @ViewBuilder private var hudView: some View {
        if let h = hud {
            HStack(spacing: 12) {
                Image(systemName: h.icon).font(.system(size: 16, weight: .semibold))
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.25))
                        Capsule().fill(.white).frame(width: g.size.width * h.value)
                    }
                }
                .frame(width: 140, height: 5)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 18).padding(.vertical, 12)
            .background(.ultraThinMaterial, in: Capsule())
            .frame(maxHeight: .infinity, alignment: .top).padding(.top, 24)
            .allowsHitTesting(false)
        }
    }

    // MARK: Skip intro / Next episode

    @ViewBuilder private var skipAndNext: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                if let n = m.countdown {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Next episode in \(n)").font(jakarta(15, .extrabold))
                        HStack(spacing: 8) {
                            Button { Haptic.tap(); m.next() } label: {
                                Label("Play now", systemImage: "play.fill").font(jakarta(14, .bold)).foregroundStyle(Color.ink)
                                    .padding(.horizontal, 14).padding(.vertical, 9).background(.white, in: Capsule())
                            }
                            Button { m.cancelCountdown() } label: {
                                Text("Watch credits").font(jakarta(14, .semibold)).foregroundStyle(.white)
                                    .padding(.horizontal, 14).padding(.vertical, 9).background(.ultraThinMaterial, in: Capsule())
                            }
                        }
                    }
                    .padding(16)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                } else if let seg = m.activeSegment {
                    Button { m.skip(seg) } label: {
                        Label(seg.label, systemImage: "forward.fill").font(jakarta(15, .bold)).foregroundStyle(Color.ink)
                            .padding(.horizontal, 18).padding(.vertical, 12).background(.white, in: Capsule())
                    }
                    .transition(.opacity)
                }
            }
        }
        .padding(.trailing, 28)
        .padding(.bottom, controls ? 86 : 30)
        .animation(.easeOut(duration: 0.25), value: m.countdown)
        .animation(.easeOut(duration: 0.25), value: m.activeSegment)
    }

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
                HStack(spacing: 14) {
                    icon("xmark") { close() }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.title.name).font(jakarta(17, .extrabold)).lineLimit(1)
                        Text(m.label).font(jakarta(12, .semibold)).foregroundStyle(Color.soft)
                    }
                    Spacer(minLength: 8)
                    subtitlesMenu
                    if m.offline == nil { sourcesMenu }
                    if m.title.kind == .tv { episodesMenu }
                    if m.offline == nil { downloadButton }
                    AirPlayButton().frame(width: 36, height: 36)
                    icon(m.fill ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right") { m.fill.toggle(); Haptic.tap(); touch() }
                    if m.pip != nil { icon("pip.enter") { m.pip?.startPictureInPicture() } }
                }
                Spacer()
                HStack(spacing: 56) {
                    icon("gobackward.10", 30) { m.seek(by: -10); Haptic.tap(); touch() }
                    icon(m.isPlaying ? "pause.fill" : "play.fill", 44) { m.toggle(); touch() }
                    icon("goforward.10", 30) { m.seek(by: 10); Haptic.tap(); touch() }
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
                        Button { Haptic.tap(); m.next() } label: {
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

    @ViewBuilder private var downloadButton: some View {
        let d = downloads.items.first { $0.id == Store.posKey(m.title, m.season, m.episode) }
        switch d?.state {
        case .done?: icon("checkmark.circle.fill") {}
        case .downloading?:
            ZStack {
                Circle().stroke(.white.opacity(0.3), lineWidth: 3)
                Circle().trim(from: 0, to: d!.progress).stroke(Color.violet, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90))
            }
            .frame(width: 22, height: 22).frame(width: 36, height: 36)
        default: icon("arrow.down.circle") { m.download(); touch() }
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
        icon(m.selected == nil ? "captions.bubble" : "captions.bubble.fill") { panel = .subtitles; touch() }
    }

    private var sourcesMenu: some View {
        icon("server.rack") { panel = .sources; touch() }
    }

    private var episodesMenu: some View {
        icon("list.bullet") { panel = .episodes; touch() }
    }

    // MARK: Side panel: scrolls, so long lists (subtitles, sources, episodes) always fit

    private func sidePanel(_ p: Panel) -> some View {
        ZStack(alignment: .trailing) {
            Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { panel = nil }
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(p == .subtitles ? "Subtitles" : p == .sources ? "Sources" : "Season \(m.season)").font(jakarta(18, .extrabold))
                    Spacer()
                    icon("xmark", 16) { panel = nil }
                }
                .padding(.horizontal, 18).padding(.top, 14).padding(.bottom, 6)
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 2) {
                        switch p {
                        case .subtitles: subtitleRows
                        case .sources:
                            ForEach(Array(Sources.all.enumerated()), id: \.offset) { i, src in
                                row(src.name + (src.web ? " (web)" : ""), on: i == m.sourceIndex) { m.pick(i); panel = nil }
                            }
                        case .episodes:
                            ForEach(m.episodes) { e in
                                row("\(e.number). \(e.name)", on: e.number == m.episode) { m.play(episode: e.number); panel = nil }
                            }
                        }
                    }
                    .padding(.horizontal, 10).padding(.bottom, 16)
                }
            }
            .frame(width: 340)
            .frame(maxHeight: .infinity)
            .background(.ultraThinMaterial)
            .foregroundStyle(.white)
        }
        .ignoresSafeArea(edges: .vertical)
    }

    @ViewBuilder private var subtitleRows: some View {
        Text("Auto-sync: " + m.syncStatus).font(jakarta(12, .semibold)).foregroundStyle(Color.soft).padding(.horizontal, 8).padding(.bottom, 6)
        row("Off", on: m.selected == nil) { m.turnOff() }
        ForEach(m.tracks) { t in row(t.label, on: m.selected == t) { m.select(t) } }
        Text("Timing: " + (m.offset == 0 ? "no change" : String(format: "%+.1fs", m.offset)))
            .font(jakarta(12, .semibold)).foregroundStyle(Color.soft).padding(.horizontal, 8).padding(.top, 14).padding(.bottom, 6)
        HStack(spacing: 8) {
            timingButton("Earlier", "minus") { m.offset -= 0.5 }
            timingButton("Later", "plus") { m.offset += 0.5 }
            if m.offset != 0 { timingButton("Reset", "arrow.counterclockwise") { m.offset = 0 } }
        }
        .padding(.horizontal, 8)
    }

    private func row(_ text: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 10) {
                Image(systemName: "checkmark").font(.system(size: 14, weight: .bold)).foregroundStyle(Color.violet).opacity(on ? 1 : 0)
                Text(text).font(jakarta(15, on ? .bold : .semibold)).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10).padding(.vertical, 12)
            .background(on ? Color.white.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .foregroundStyle(.white)
    }

    private func timingButton(_ text: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            Label(text, systemImage: icon).font(jakarta(13, .bold)).padding(.horizontal, 12).padding(.vertical, 9)
                .background(.white.opacity(0.12), in: Capsule())
        }
        .foregroundStyle(.white)
    }

    private var findingView: some View {
        ZStack {
            Art(url: TMDB.img(m.title.backdrop, "w1280")).ignoresSafeArea().blur(radius: 30).overlay(Color.black.opacity(0.55))
            VStack(spacing: 14) {
                Text(m.title.name).font(jakarta(24, .extrabold)).multilineTextAlignment(.center)
                if m.title.kind == .tv { Text(m.label).font(jakarta(14, .semibold)).foregroundStyle(Color.soft) }
                ProgressView().tint(.white).scaleEffect(1.3).padding(.top, 6)
                Text(m.status).font(jakarta(13, .semibold)).foregroundStyle(Color.soft)
                // Never just spin: after 20 s, offer the source list.
                if slow && m.offline == nil {
                    Text("This is taking longer than usual").font(jakarta(15, .bold)).padding(.top, 14)
                    Button { panel = .sources; touch() } label: {
                        Text("Pick a source").font(jakarta(15, .bold)).foregroundStyle(Color.ink).padding(.horizontal, 24).padding(.vertical, 11).background(.white, in: Capsule())
                    }
                }
            }
            .task { slow = false; try? await Task.sleep(nanoseconds: 20_000_000_000); slow = true }
            .foregroundStyle(.white)
            .padding(.horizontal, 40)
            VStack {
                HStack {
                    icon("xmark") { close() }
                    Spacer()
                    if m.offline == nil { sourcesMenu.foregroundStyle(.white) }
                }
                Spacer()
            }
            .padding(20)
        }
    }

    private var failedView: some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 34))
            Text(m.status).font(jakarta(16, .bold)).multilineTextAlignment(.center)
            Button { close() } label: {
                Text("Close").font(jakarta(15, .bold)).foregroundStyle(Color.ink).padding(.horizontal, 28).padding(.vertical, 11).background(.white, in: Capsule())
            }
        }
        .foregroundStyle(.white)
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
