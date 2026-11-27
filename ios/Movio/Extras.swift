import SwiftUI
import AVKit
import MediaPlayer

// MARK: - Skip intro / recap / credits

/// A part of a title that can be skipped. end nil = to the end.
struct Segment: Hashable {
    let type: String
    let start: Double
    let end: Double?
    func contains(_ t: Double) -> Bool { t >= start && (end == nil || t < end!) }
    var label: String {
        switch type { case "recap": return "Skip recap"; case "preview": return "Skip preview"; case "credits": return "Skip credits"; default: return "Skip intro" }
    }
}

enum Segments {
    /// Times from two community databases matched up by Movio's helper, plus intros you skipped at the same place twice.
    static func find(_ kind: Kind, _ id: Int, _ s: Int, _ e: Int) async -> [Segment] {
        let q = kind == .tv ? "tmdb_id=\(id)&season=\(s)&episode=\(e)" : "tmdb_id=\(id)"
        var out: [Segment] = []
        // Movio's helper (two databases matched) plus TheIntroDB asked directly, in case it was too busy for the helper.
        func get(_ u: String) async -> [String: Any] {
            guard let url = URL(string: u), let (d, _) = try? await URLSession.shared.data(from: url) else { return [:] }
            return (try? JSONSerialization.jsonObject(with: d) as? [String: Any]) ?? [:]
        }
        async let wq = get("\(Store.helper)/intro?\(q)")
        async let dq = get("https://api.theintrodb.org/v4/media?\(q)")
        let w = await wq, d = await dq
        func ms(_ x: [String: Any], _ k: String) -> Double? { (x[k] as? NSNumber)?.doubleValue }
        for type in ["recap", "intro", "credits", "preview"] {
            let a = (w[type] as? [[String: Any]])?.first, b = (d[type] as? [[String: Any]])?.first
            var x: (Double, Double?)? = a.map { (ms($0, "start_ms") ?? 0, ms($0, "end_ms")) }
            if let b, a == nil || (a?["src"] as? String) == "introdb" {
                let bs = ms(b, "start_ms") ?? 0, be = ms(b, "end_ms")
                if let a {
                    let s0 = ms(a, "start_ms") ?? 0, e0 = ms(a, "end_ms")
                    if abs(s0 - bs) < 4000 && abs((e0 ?? 1e12) - (be ?? 1e12)) < 4000 {
                        x = ((s0 + bs) / 2, (e0 != nil && be != nil) ? (e0! + be!) / 2 : (e0 ?? be))
                    } else if (ms(a, "sure") ?? 0) < 0.75 { x = (bs, be) }
                } else { x = (bs, be) }
            }
            if let (st, en) = x, en == nil || en! > st { out.append(Segment(type: type, start: st / 1000, end: en.map { $0 / 1000 })) }
        }
        // One skip alone isn't trusted (cold opens differ): only when two agree (start within 20 s, length within 8 s).
        if kind == .tv, !out.contains(where: { $0.type == "intro" }),
           let seen = UserDefaults.standard.array(forKey: "intro2:\(id)") as? [[Double]], let last = seen.last {
            let agree = seen.filter { abs($0[0] - last[0]) < 20 && abs(($0[1] - $0[0]) - (last[1] - last[0])) < 8 }
            if agree.count >= 2 { out.append(Segment(type: "learned", start: agree.map { $0[0] }.sorted()[agree.count / 2], end: agree.map { $0[1] }.sorted()[agree.count / 2])) }
        }
        return out.sorted { $0.start < $1.start }
    }

    /// You skipped ahead early in an episode: remember it as this show's intro.
    static func learn(_ id: Int, from: Double, to: Double) {
        let jump = to - from
        guard from < 360, jump >= 20, jump <= 150 else { return }
        var seen = (UserDefaults.standard.array(forKey: "intro2:\(id)") as? [[Double]]) ?? []
        seen.append([max(0, from - 3), to])
        UserDefaults.standard.set(Array(seen.suffix(3)), forKey: "intro2:\(id)")
    }
}

// MARK: - Remembered stream links

/// A stream that worked, reused for 6 hours so playback starts at once.
enum Links {
    private static func key(_ pos: String, _ src: String) -> String { "link:\(pos)|\(src)" }

    static func save(_ pos: String, _ src: String, url: URL, page: String) {
        UserDefaults.standard.set(["u": url.absoluteString, "p": page, "t": Date().timeIntervalSince1970], forKey: key(pos, src))
    }

    static func get(_ pos: String, _ src: String) -> (url: URL, page: String)? {
        guard let d = UserDefaults.standard.dictionary(forKey: key(pos, src)),
              let u = (d["u"] as? String).flatMap(URL.init(string:)), let p = d["p"] as? String,
              let t = d["t"] as? Double, Date().timeIntervalSince1970 - t < 6 * 3600 else { return nil }
        return (u, p)
    }

    static func drop(_ pos: String, _ src: String) { UserDefaults.standard.removeObject(forKey: key(pos, src)) }
}

// MARK: - Haptics

enum Haptic {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func soft() { UIImpactFeedbackGenerator(style: .soft).impactOccurred() }
    static func done() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
}

// MARK: - Volume (for the swipe gesture) and AirPlay

/// A hidden system volume slider: setting it changes the phone volume without the system pop-up.
struct VolumeHook: UIViewRepresentable {
    final class Box { weak var slider: UISlider? }
    static let box = Box()
    func makeUIView(context: Context) -> MPVolumeView {
        let v = MPVolumeView(frame: CGRect(x: -200, y: -200, width: 10, height: 10))
        v.alpha = 0.01
        VolumeHook.box.slider = v.subviews.compactMap { $0 as? UISlider }.first
        return v
    }
    func updateUIView(_ v: MPVolumeView, context: Context) {
        if VolumeHook.box.slider == nil { VolumeHook.box.slider = v.subviews.compactMap { $0 as? UISlider }.first }
    }
    static var volume: Float {
        get { box.slider?.value ?? AVAudioSession.sharedInstance().outputVolume }
        set { box.slider?.setValue(max(0, min(1, newValue)), animated: false) }
    }
}

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = UIColor(red: 1.0, green: 0.302, blue: 0.180, alpha: 1)
        v.prioritizesVideoDevices = true
        return v
    }
    func updateUIView(_ v: AVRoutePickerView, context: Context) {}
}

// MARK: - Lock screen / Control Center

/// Title, artwork and controls on the lock screen and in Control Center.
@MainActor
final class NowPlaying {
    private var targets: [Any] = []
    private var art: MPMediaItemArtwork?

    func attach(_ m: PlayerModel) {
        let c = MPRemoteCommandCenter.shared()
        targets.append(c.playCommand.addTarget { [weak m] _ in m?.player.play(); return .success })
        targets.append(c.pauseCommand.addTarget { [weak m] _ in m?.player.pause(); return .success })
        targets.append(c.togglePlayPauseCommand.addTarget { [weak m] _ in m?.toggle(); return .success })
        c.skipForwardCommand.preferredIntervals = [10]
        c.skipBackwardCommand.preferredIntervals = [10]
        targets.append(c.skipForwardCommand.addTarget { [weak m] _ in m?.seek(by: 10); return .success })
        targets.append(c.skipBackwardCommand.addTarget { [weak m] _ in m?.seek(by: -10); return .success })
        targets.append(c.changePlaybackPositionCommand.addTarget { [weak m] e in
            if let e = e as? MPChangePlaybackPositionCommandEvent { m?.seek(to: e.positionTime) }
            return .success
        })
        c.nextTrackCommand.isEnabled = m.title.kind == .tv
        if m.title.kind == .tv { targets.append(c.nextTrackCommand.addTarget { [weak m] _ in m?.next(); return .success }) }
        Task {
            if let u = TMDB.img(m.title.poster, "w342"), let d = try? await URLSession.shared.data(from: u).0, let img = UIImage(data: d) {
                art = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
                update(m)
            }
        }
    }

    func update(_ m: PlayerModel) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: m.title.kind == .tv ? "\(m.title.name) · \(m.label)" : m.title.name,
            MPMediaItemPropertyArtist: "Movio",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: m.position,
            MPMediaItemPropertyPlaybackDuration: m.duration,
            MPNowPlayingInfoPropertyPlaybackRate: m.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if let art { info[MPMediaItemPropertyArtwork] = art }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func detach() {
        let c = MPRemoteCommandCenter.shared()
        for t in targets {
            [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.skipForwardCommand, c.skipBackwardCommand,
             c.changePlaybackPositionCommand, c.nextTrackCommand].forEach { $0.removeTarget(t) }
        }
        targets = []
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
