import SwiftUI
import AVFoundation

/// Something saved for offline viewing.
struct DownloadItem: Codable, Identifiable, Hashable {
    enum State: String, Codable { case downloading, done, failed }
    let id: String
    let title: Title
    let season: Int
    let episode: Int
    var state: State
    var progress: Double
    var path: String?
    var hls: Bool
    var subtitle: String?

    var label: String { title.kind == .tv ? "S\(season) · E\(episode)" : (title.year ?? "Movie") }

    /// Where the file lives now (iOS can move the app's home folder between launches, so paths are relative).
    var fileURL: URL? {
        guard let p = path else { return nil }
        return hls ? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(p) : Downloads.folder.appendingPathComponent(p)
    }
    var subtitleURL: URL? { subtitle.map { Downloads.folder.appendingPathComponent($0) } }
}

/// Saves streams to the phone: HLS through Apple's downloader, plain video files directly.
@MainActor
final class Downloads: NSObject, ObservableObject, AVAssetDownloadDelegate, URLSessionDownloadDelegate {
    static let shared = Downloads()
    nonisolated static var folder: URL {
        let u = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    @Published private(set) var items: [DownloadItem] = []
    private var hlsSession: AVAssetDownloadURLSession!
    private var fileSession: URLSession!

    override private init() {
        super.init()
        if let d = UserDefaults.standard.data(forKey: "downloads"), let v = try? JSONDecoder().decode([DownloadItem].self, from: d) { items = v }
        hlsSession = AVAssetDownloadURLSession(configuration: .background(withIdentifier: "com.g41us.movio.hls"), assetDownloadDelegate: self, delegateQueue: .main)
        fileSession = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
        // Anything that was mid-download when the app closed and didn't finish is marked failed.
        hlsSession.getAllTasks { tasks in
            let live = Set(tasks.compactMap(\.taskDescription))
            Task { @MainActor in
                for i in self.items.indices where self.items[i].state == .downloading && !live.contains(self.items[i].id) { self.items[i].state = .failed }
                self.save()
            }
        }
    }

    private func save() { if let d = try? JSONEncoder().encode(items) { UserDefaults.standard.set(d, forKey: "downloads") } }

    func item(_ id: String) -> DownloadItem? { items.first { $0.id == id } }
    private func update(_ id: String, _ f: (inout DownloadItem) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        f(&items[i]); save()
    }

    func start(title: Title, season: Int, episode: Int, url: URL, headers: [String: String], cookies: [HTTPCookie], hls: Bool, subtitle: URL?) {
        let id = Store.posKey(title, season, episode)
        if let old = item(id), old.state != .failed { return }
        items.removeAll { $0.id == id }
        let safe = id.replacingOccurrences(of: ":", with: "_")
        var it = DownloadItem(id: id, title: title, season: season, episode: episode, state: .downloading, progress: 0, path: nil, hls: hls, subtitle: nil)
        items.insert(it, at: 0); save()
        if let subtitle {
            Task {
                if let d = await StreamAudio.fetch(subtitle, [:]) {
                    let name = "\(safe).srt"
                    try? d.write(to: Downloads.folder.appendingPathComponent(name))
                    update(id) { $0.subtitle = name }
                }
            }
        }
        if hls {
            let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers, AVURLAssetHTTPCookiesKey: cookies])
            guard let task = hlsSession.makeAssetDownloadTask(asset: asset, assetTitle: title.name, assetArtworkData: nil,
                                                              options: [AVAssetDownloadTaskMinimumRequiredMediaBitrateKey: 2_000_000]) else {
                it.state = .failed; update(id) { $0.state = .failed }; return
            }
            task.taskDescription = id
            task.resume()
        } else {
            var r = URLRequest(url: url)
            headers.forEach { r.setValue($0.value, forHTTPHeaderField: $0.key) }
            r.setValue(HTTPCookie.requestHeaderFields(with: cookies)["Cookie"], forHTTPHeaderField: "Cookie")
            let task = fileSession.downloadTask(with: r)
            task.taskDescription = id
            task.resume()
        }
    }

    func delete(_ it: DownloadItem) {
        hlsSession.getAllTasks { tasks in tasks.filter { $0.taskDescription == it.id }.forEach { $0.cancel() } }
        fileSession.getAllTasks { tasks in tasks.filter { $0.taskDescription == it.id }.forEach { $0.cancel() } }
        if let u = it.fileURL { try? FileManager.default.removeItem(at: u) }
        if let s = it.subtitleURL { try? FileManager.default.removeItem(at: s) }
        items.removeAll { $0.id == it.id }; save()
    }

    // HLS progress and finish
    nonisolated func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didLoad timeRange: CMTimeRange,
                                totalTimeRangesLoaded loaded: [NSValue], timeRangeExpectedToLoad expected: CMTimeRange) {
        let done = loaded.map { $0.timeRangeValue.duration.seconds }.reduce(0, +)
        let total = expected.duration.seconds
        let id = assetDownloadTask.taskDescription ?? ""
        Task { @MainActor in self.update(id) { $0.progress = total > 0 ? min(1, done / total) : 0 } }
    }

    nonisolated func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        let id = assetDownloadTask.taskDescription ?? ""
        let rel = location.relativePath
        Task { @MainActor in self.update(id) { $0.path = rel } }
    }

    // Plain file progress and finish
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                                totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let id = downloadTask.taskDescription ?? ""
        let p = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0
        Task { @MainActor in self.update(id) { $0.progress = p } }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let id = downloadTask.taskDescription ?? ""
        let name = id.replacingOccurrences(of: ":", with: "_") + ".mp4"
        let dest = Downloads.folder.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        let ok = (try? FileManager.default.moveItem(at: location, to: dest)) != nil
        Task { @MainActor in self.update(id) { $0.path = ok ? name : nil } }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let id = task.taskDescription ?? ""
        Task { @MainActor in
            self.update(id) { it in
                if error == nil && it.path != nil { it.state = .done; it.progress = 1 } else { it.state = .failed }
            }
            if error == nil { Haptic.done() }
        }
    }
}

struct DownloadsList: View {
    @EnvironmentObject var store: Store
    @ObservedObject private var dl = Downloads.shared

    var body: some View {
        if dl.items.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "arrow.down.circle").font(.system(size: 40)).foregroundStyle(Color.muted)
                Text("No downloads yet").font(jakarta(17, .bold))
                Text("While a film plays, tap ⬇︎ to save it for offline.").font(jakarta(14)).foregroundStyle(Color.muted).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity).padding(.top, 120).padding(.horizontal, 30)
        } else {
            LazyVStack(spacing: 10) {
                ForEach(dl.items) { it in
                    HStack(spacing: 12) {
                        Art(url: TMDB.img(it.title.poster, "w185")).frame(width: 58, height: 87)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        VStack(alignment: .leading, spacing: 5) {
                            Text(it.title.name).font(jakarta(15, .bold)).lineLimit(1)
                            Text(it.label).font(jakarta(12, .semibold)).foregroundStyle(Color.muted)
                            switch it.state {
                            case .downloading:
                                ProgressView(value: it.progress).tint(.violet)
                                Text("\(Int(it.progress * 100))%").font(jakarta(11, .semibold)).foregroundStyle(Color.muted)
                            case .failed: Text("Couldn't download · this source may block saving").font(jakarta(11, .semibold)).foregroundStyle(Color.pink)
                            case .done: Label("Ready to watch offline", systemImage: "checkmark.circle.fill").font(jakarta(11, .semibold)).foregroundStyle(Color.violet)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if it.state == .done {
                            Image(systemName: "play.circle.fill").font(.system(size: 30)).foregroundStyle(.white)
                        }
                    }
                    .padding(10)
                    .background(Color.panel, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .contentShape(Rectangle())
                    .onTapGesture { if it.state == .done { store.playing = PlayRequest(title: it.title, season: it.season, episode: it.episode, offline: it.id) } }
                    .contextMenu { Button(role: .destructive) { dl.delete(it) } label: { Label("Delete download", systemImage: "trash") } }
                }
            }
            .padding(16)
        }
    }
}
