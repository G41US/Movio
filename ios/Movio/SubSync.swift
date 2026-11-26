import Foundation
import AVFoundation
import CommonCrypto

/// How a subtitle file's clock maps onto the film: film = sub × scale + offset.
struct SubFit: Equatable { var scale: Double = 1; var offset: Double = 0 }

/// iPhone can't tap the audio of a streaming film, so Movio fetches the stream's first
/// minutes itself (decrypting and unwrapping it like a player would), decodes the sound,
/// and keeps a "someone is talking" track: loudness every 100 ms.
enum StreamAudio {
    static let step = 0.1

    static func speech(stream: URL, headers: [String: String], minutes: Double = 8) async -> [Float]? {
        guard let media = await mediaPlaylist(stream, headers) else { return nil }
        let (base, text) = media
        var segs: [(url: URL, start: Double, key: URL?, iv: Data?)] = []
        var key: URL?; var iv: Data?; var seq = 0; var dur = 0.0; var t = 0.0; var map: URL?
        for raw in text.components(separatedBy: .newlines) {
            let l = raw.trimmingCharacters(in: .whitespaces)
            if l.hasPrefix("#EXT-X-MEDIA-SEQUENCE:") { seq = Int(l.dropFirst(22)) ?? 0 }
            else if l.hasPrefix("#EXT-X-KEY:") {
                key = nil; iv = nil
                if l.contains("METHOD=AES-128"), let u = attr(l, "URI") { key = URL(string: u, relativeTo: base)?.absoluteURL }
                if let h = attr(l, "IV") { iv = hex(h) }
            }
            else if l.hasPrefix("#EXT-X-MAP:"), let u = attr(l, "URI") { map = URL(string: u, relativeTo: base)?.absoluteURL }
            else if l.hasPrefix("#EXTINF:") { dur = Double(l.dropFirst(8).split(separator: ",").first ?? "") ?? 0 }
            else if !l.isEmpty && !l.hasPrefix("#"), let u = URL(string: l, relativeTo: base)?.absoluteURL {
                if t < minutes * 60 {
                    var ivData = iv
                    if key != nil && ivData == nil {
                        var n = UInt64(seq + segs.count).bigEndian
                        ivData = Data(count: 8) + Data(bytes: &n, count: 8)
                    }
                    segs.append((u, t, key, ivData))
                }
                t += dur
            }
        }
        if segs.isEmpty { return nil }
        var keys: [URL: Data] = [:]
        let dir = FileManager.default.temporaryDirectory
        if let map {
            // fMP4 stream: init segment + media segments make a normal MP4 file.
            guard var file = await fetch(map, headers) else { return nil }
            for s in segs {
                guard let d = await segment(s, headers, &keys) else { return nil }
                file.append(d)
            }
            let url = dir.appendingPathComponent("movio-sync.mp4")
            try? file.write(to: url)
            return await decode(url)
        }
        var aac = Data()
        for s in segs {
            guard let d = await segment(s, headers, &keys) else { return nil }
            guard let a = TS.audio(d) else { return nil }
            aac.append(a)
        }
        let url = dir.appendingPathComponent("movio-sync.aac")
        try? aac.write(to: url)
        return await decode(url)
    }

    private static func segment(_ s: (url: URL, start: Double, key: URL?, iv: Data?), _ h: [String: String], _ keys: inout [URL: Data]) async -> Data? {
        guard var d = await fetch(s.url, h) else { return nil }
        if let k = s.key {
            if keys[k] == nil { keys[k] = await fetch(k, h) }
            guard let kd = keys[k], let iv = s.iv, let plain = aes(d, kd, iv) else { return nil }
            d = plain
        }
        return d
    }

    /// Picks the audio-only rendition if there is one, else the smallest variant.
    private static func mediaPlaylist(_ url: URL, _ h: [String: String]) async -> (URL, String)? {
        guard let d = await fetch(url, h), let text = String(data: d, encoding: .utf8), text.hasPrefix("#EXTM3U") else { return nil }
        if !text.contains("#EXT-X-STREAM-INF") { return (url, text) }
        let lines = text.components(separatedBy: .newlines)
        if let audio = lines.first(where: { $0.hasPrefix("#EXT-X-MEDIA:") && $0.contains("TYPE=AUDIO") && $0.contains("URI=") }),
           let u = attr(audio, "URI"), let au = URL(string: u, relativeTo: url)?.absoluteURL {
            return await mediaPlaylist(au, h)
        }
        var best: (Int, URL)?
        for (i, l) in lines.enumerated() where l.hasPrefix("#EXT-X-STREAM-INF") && i + 1 < lines.count {
            let bw = Int(attr(l, "BANDWIDTH") ?? "") ?? Int.max
            if let u = URL(string: lines[i + 1].trimmingCharacters(in: .whitespaces), relativeTo: url)?.absoluteURL, best == nil || bw < best!.0 { best = (bw, u) }
        }
        guard let b = best else { return nil }
        return await mediaPlaylist(b.1, h)
    }

    private static func attr(_ line: String, _ name: String) -> String? {
        guard let r = line.range(of: name + "=") else { return nil }
        let rest = line[r.upperBound...]
        if rest.hasPrefix("\"") { return rest.dropFirst().split(separator: "\"", maxSplits: 1).first.map(String.init) }
        return rest.split(separator: ",", maxSplits: 1).first.map(String.init)
    }

    private static func hex(_ s: String) -> Data? {
        var h = s.lowercased().hasPrefix("0x") ? String(s.dropFirst(2)) : s
        while h.count < 32 { h = "0" + h }
        var d = Data()
        var i = h.startIndex
        while i < h.endIndex {
            let j = h.index(i, offsetBy: 2)
            guard let b = UInt8(h[i..<j], radix: 16) else { return nil }
            d.append(b); i = j
        }
        return d
    }

    static func fetch(_ url: URL, _ h: [String: String]) async -> Data? {
        var r = URLRequest(url: url, timeoutInterval: 20)
        h.forEach { r.setValue($0.value, forHTTPHeaderField: $0.key) }
        guard let res = try? await URLSession.shared.data(for: r), ((res.1 as? HTTPURLResponse)?.statusCode ?? 200) < 400 else { return nil }
        return res.0
    }

    private static func aes(_ data: Data, _ key: Data, _ iv: Data) -> Data? {
        var out = Data(count: data.count + kCCBlockSizeAES128)
        let outCap = out.count
        var moved = 0
        let st = out.withUnsafeMutableBytes { o in
            data.withUnsafeBytes { i in
                key.withUnsafeBytes { k in
                    iv.withUnsafeBytes { v in
                        CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                                k.baseAddress, key.count, v.baseAddress, i.baseAddress, data.count, o.baseAddress, outCap, &moved)
                    }
                }
            }
        }
        guard st == CCCryptorStatus(kCCSuccess) else { return nil }
        return out.prefix(moved)
    }

    /// Decodes any audio file to loudness per 100 ms (speech band: differences drop bass and rumble).
    private static func decode(_ url: URL) async -> [Float]? {
        await Task.detached(priority: .utility) { () -> [Float]? in
            guard let f = try? AVAudioFile(forReading: url) else { return nil }
            let fmt = f.processingFormat
            let per = max(1, Int(fmt.sampleRate * step))
            guard let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(per * 20)) else { return nil }
            var out: [Float] = []
            var acc: Double = 0; var n = 0; var prev: Float = 0
            let ch = Int(fmt.channelCount)
            while (try? f.read(into: buf)) != nil, buf.frameLength > 0 {
                guard let data = buf.floatChannelData else { return nil }
                for i in 0..<Int(buf.frameLength) {
                    var m: Float = 0
                    for c in 0..<ch { m += data[c][i] }
                    m /= Float(ch)
                    let d = m - prev; prev = m
                    acc += Double(d * d); n += 1
                    if n >= per { out.append(Float(log(acc / Double(n) * 1e6 + 1))); acc = 0; n = 0 }
                }
            }
            return out.count > 600 ? out : nil
        }.value
    }
}

/// Pulls the audio (ADTS AAC) out of MPEG-TS segments, including ones disguised with image headers.
enum TS {
    static func audio(_ d: Data) -> Data? {
        let b = [UInt8](d)
        guard b.count > 376 else { return nil }
        var o = -1
        for i in 0..<min(b.count - 376, 4096) where b[i] == 0x47 && b[i + 188] == 0x47 && b[i + 376] == 0x47 { o = i; break }
        if o < 0 { return nil }
        var pmt = -1, apid = -1
        var out = Data()
        var p = o
        while p + 188 <= b.count {
            defer { p += 188 }
            if b[p] != 0x47 { continue }
            let pid = (Int(b[p + 1] & 0x1f) << 8) | Int(b[p + 2])
            let pusi = b[p + 1] & 0x40 != 0
            let af = (b[p + 3] >> 4) & 3
            var i = p + 4
            if af == 2 || af == 3 { i += 1 + Int(b[p + 4]) }
            if af == 2 || i >= p + 188 { continue }
            let end = p + 188
            if pid == 0 && pusi {
                let s = i + 1 + Int(b[i])
                if s + 11 < end { pmt = (Int(b[s + 10] & 0x1f) << 8) | Int(b[s + 11]) }
            } else if pid == pmt && pusi && apid < 0 {
                let s = i + 1 + Int(b[i])
                guard s + 11 < end else { continue }
                let sl = (Int(b[s + 1] & 0x0f) << 8) | Int(b[s + 2])
                let pil = (Int(b[s + 10] & 0x0f) << 8) | Int(b[s + 11])
                var j = s + 12 + pil
                let last = min(s + 3 + sl - 4, end)
                while j + 4 < last {
                    let st = b[j]
                    let ep = (Int(b[j + 1] & 0x1f) << 8) | Int(b[j + 2])
                    let el = (Int(b[j + 3] & 0x0f) << 8) | Int(b[j + 4])
                    if st == 0x0f && apid < 0 { apid = ep }
                    j += 5 + el
                }
            } else if pid == apid {
                if pusi && i + 9 < end && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
                    let start = i + 9 + Int(b[i + 8])
                    if start < end { out.append(contentsOf: b[start..<end]) }
                } else {
                    out.append(contentsOf: b[i..<end])
                }
            }
        }
        return out.isEmpty ? nil : out
    }
}

/// Finds the scale/offset that puts subtitle lines on speech. Tested on a real film:
/// with these limits it never picked a wrong fix.
enum SubAligner {
    struct Eval { let fit: SubFit; let quality: Double; let sure: Bool }
    static let scales: [Double] = [1.0, 25 / 23.976, 23.976 / 25, 24 / 23.976, 23.976 / 24, 25.0 / 24, 24.0 / 25]

    static func evaluate(_ cues: [Cue], _ e: [Float]) -> Eval? {
        guard e.count > 600, cues.count >= 20 else { return nil }
        let thr = e.sorted()[e.count * 55 / 100]
        var pre = [Double](repeating: 0, count: e.count + 1)
        for i in 0..<e.count { pre[i + 1] = pre[i] + (e[i] > thr ? 1 : -1) }
        let n = e.count
        var best = SubFit(); var bestScore = -Double.infinity; var bestCov = 0; var bestLines = 0
        var all: [(SubFit, Double)] = []
        all.reserveCapacity(3000)
        for s in scales {
            var o = -20.0
            while o <= 20.0001 {
                var t = 0.0, cov = 0, lines = 0
                for c in cues {
                    let a = min(n, max(0, Int((c.start * s + o) / StreamAudio.step)))
                    let b = min(n, max(0, Int((c.end * s + o) / StreamAudio.step)))
                    if b > a { t += pre[b] - pre[a]; cov += b - a; lines += 1 }
                }
                let v = t - abs(o) - (s == 1 ? 0 : 8)
                let f = SubFit(scale: s, offset: (o * 10).rounded() / 10)
                all.append((f, v))
                if v > bestScore { bestScore = v; best = f; bestCov = cov; bestLines = lines }
                o += 0.1
            }
        }
        guard bestCov >= 150 else { return nil }
        let runnerUp = all.filter { abs($0.0.scale - best.scale) > 0.01 || abs($0.0.offset - best.offset) >= 2 }.map(\.1).max() ?? -Double.infinity
        let sure = bestLines >= 40 && bestCov >= 600 && bestScore - runnerUp >= max(25, 0.1 * bestScore)
        return Eval(fit: best, quality: bestScore / Double(bestCov), sure: sure)
    }
}
