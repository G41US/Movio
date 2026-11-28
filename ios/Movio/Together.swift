import SwiftUI

/// Someone you watch with.
struct TgPerson: Identifiable, Hashable { let pid: String; let name: String; let kind: String; let online: Bool; var id: String { pid } }
struct TgInvite: Identifiable { let id: Int; let room: String; let from: String; let title: [String: Any] }

/// Together: the people you watch with (5-letter codes, two-way connections, invites) on Movio's helper.
/// You are one person across all your linked devices (your link code); otherwise this iPhone.
@MainActor
final class Tg: ObservableObject {
    static let shared = Tg()
    @Published var code = ""
    @Published var people: [TgPerson] = []
    @Published var invites: [TgInvite] = []
    private var dismissed = Set<Int>()

    private var who: (String, String?) {
        let d = UserDefaults.standard
        let sync = Store.shared.syncCode
        if d.string(forKey: "device_id") == nil { d.set(UUID().uuidString.lowercased(), forKey: "device_id") }
        let w = sync.isEmpty ? "d:" + (d.string(forKey: "device_id") ?? "") : "c:" + sync
        if let last = d.string(forKey: "tg_who"), last != w { d.set(last, forKey: "tg_prev") }
        d.set(w, forKey: "tg_who")
        return (w, d.string(forKey: "tg_prev"))
    }

    private func post(_ path: String, _ body: [String: Any]) async -> (Int, [String: Any]) {
        guard let url = URL(string: "\(Store.helper)\(path)") else { return (0, [:]) }
        var r = URLRequest(url: url, timeoutInterval: 12)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (data, res) = try? await URLSession.shared.data(for: r) else { return (0, [:]) }
        return ((res as? HTTPURLResponse)?.statusCode ?? 0, (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:])
    }

    /// Runs a Together action; returns an error message when it fails.
    @discardableResult
    func call(_ op: String, _ extra: [String: Any] = [:]) async -> String? {
        let (w, prev) = who
        var body: [String: Any] = ["who": w, "name": Profile.shared.name]
        if let prev { body["prev"] = prev }
        body.merge(extra) { $1 }
        let (status, o) = await post("/tg/\(op)", body)
        guard (200..<300).contains(status) else { return (o["error"] as? String) ?? I18n.t("Couldn't reach Movio. Try again.") }
        UserDefaults.standard.removeObject(forKey: "tg_prev")
        code = o["code"] as? String ?? ""
        people = (o["people"] as? [[String: Any]] ?? []).map {
            TgPerson(pid: $0["pid"] as? String ?? "", name: $0["name"] as? String ?? "", kind: $0["kind"] as? String ?? "friend", online: $0["online"] as? Bool ?? false)
        }
        invites = (o["invites"] as? [[String: Any]] ?? []).map {
            TgInvite(id: $0["id"] as? Int ?? 0, room: $0["room"] as? String ?? "", from: $0["from"] as? String ?? "", title: $0["title"] as? [String: Any] ?? [:])
        }.filter { !dismissed.contains($0.id) }
        return nil
    }
    func refresh() async { await call("me") }
    func dismiss(_ id: Int) { dismissed.insert(id); invites.removeAll { $0.id == id }; Task { await call("dismiss", ["id": id]) } }

    static func titleJSON(_ t: Title, _ s: Int, _ e: Int) -> [String: Any] {
        ["kind": t.kind.rawValue, "id": t.id, "name": t.name, "poster": t.poster ?? "", "backdrop": t.backdrop ?? "", "s": s, "e": e]
    }
    static func title(_ o: [String: Any]) -> Title {
        let p = o["poster"] as? String, b = o["backdrop"] as? String
        return Title(id: o["id"] as? Int ?? 0, kind: (o["kind"] as? String) == "tv" ? .tv : .movie, name: o["name"] as? String ?? "", overview: "",
                     poster: (p?.isEmpty ?? true) ? nil : p, backdrop: (b?.isEmpty ?? true) ? nil : b, year: nil, rating: 0)
    }

    /// A new watch party; invites the chosen people. Returns the 4-letter party code.
    func newParty(_ t: Title, _ s: Int, _ e: Int, invite: [String]) async -> String? {
        let title = Tg.titleJSON(t, s, e)
        let (status, o) = await post("/room/new", ["name": Profile.shared.name, "title": title])
        guard status == 200, let room = o["room"] as? String, room.count == 4 else { return nil }
        if !invite.isEmpty { await call("invite", ["to": invite, "room": room, "title": title]) }
        return room
    }

    /// What a party is watching, or nil if the code is wrong.
    func party(_ code: String) async -> [String: Any]? {
        guard let url = URL(string: "\(Store.helper)/room/\(code.uppercased())/info"),
              let (data, res) = try? await URLSession.shared.data(from: url), (res as? HTTPURLResponse)?.statusCode == 200,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return o["title"] as? [String: Any]
    }
}

/// The live link to a watch party: everyone follows one shared state (playing, position, episode).
@MainActor
final class PartyLink: ObservableObject {
    struct Member: Identifiable, Hashable { let id: String; let name: String; let waiting: Bool }
    struct State { let playing: Bool; let pos: Double; let at: Double; let s: Int; let e: Int }

    let code: String
    private let name: String
    var onMessage: ([String: Any]) -> Void = { _ in }
    @Published var members: [Member] = []
    @Published var you = ""
    var st: State?
    var skew: Double = 0
    private var task: URLSessionWebSocketTask?
    private var closed = false

    init(code: String, name: String) { self.code = code; self.name = name }

    var others: [Member] { members.filter { $0.id != you } }
    /// Where the party is now (seconds), from the last shared state.
    var expected: Double? { st.map { $0.pos + ($0.playing ? (Date().timeIntervalSince1970 * 1000 + skew - $0.at) / 1000 : 0) } }

    func open() {
        guard !closed, let url = URL(string: Store.helper.replacingOccurrences(of: "https://", with: "wss://") + "/room/\(code)/ws?name=" + (name.isEmpty ? "Friend" : name).addingPercentEncoding(withAllowedCharacters: .alphanumerics)!) else { return }
        let t = URLSession.shared.webSocketTask(with: url)
        task = t
        t.resume()
        receive(t)
    }
    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            Task { @MainActor in
                guard let self, !self.closed else { return }
                switch result {
                case .success(.string(let s)):
                    if let d = s.data(using: .utf8), let m = try? JSONSerialization.jsonObject(with: d) as? [String: Any] { self.handle(m) }
                    self.receive(t)
                case .success: self.receive(t)
                case .failure:
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    self.open()
                }
            }
        }
    }
    private func handle(_ m: [String: Any]) {
        if let now = m["now"] as? Double { skew = now - Date().timeIntervalSince1970 * 1000 }
        func setMembers(_ a: Any?) { members = (a as? [[String: Any]] ?? []).map { Member(id: $0["id"] as? String ?? "", name: $0["name"] as? String ?? "", waiting: $0["waiting"] as? Bool ?? false) } }
        func state(_ o: Any?) -> State? {
            guard let o = o as? [String: Any] else { return nil }
            return State(playing: o["playing"] as? Bool ?? false, pos: (o["pos"] as? NSNumber)?.doubleValue ?? 0, at: (o["at"] as? NSNumber)?.doubleValue ?? 0,
                         s: o["s"] as? Int ?? 1, e: o["e"] as? Int ?? 1)
        }
        switch m["t"] as? String {
        case "hello": you = m["you"] as? String ?? ""; setMembers(m["members"]); st = state(m["st"])
        case "members": setMembers(m["members"])
        case "state": st = state(m["st"])
        default: break
        }
        onMessage(m)
    }
    private func send(_ o: [String: Any]) {
        guard let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return }
        task?.send(.string(s)) { _ in }
    }
    func state(playing: Bool, pos: Double, s: Int, e: Int, why: String) { send(["t": "state", "playing": playing, "pos": pos, "s": s, "e": e, "why": why]) }
    func waiting(_ on: Bool) { send(["t": "wait", "on": on]) }
    func react(_ e: String) { send(["t": "react", "e": e]) }
    func chat(_ m: String) { send(["t": "chat", "m": m]) }
    func close() { closed = true; task?.cancel(with: .normalClosure, reason: nil) }
}

private let kinds: [(String, String, String)] = [("partner", "❤️", "Partner"), ("friend", "🤝", "Friend"), ("family", "🏠", "Family")]
private let avatarColors: [[Color]] = [[Color(red: 0.42, green: 0.49, blue: 1), Color(red: 0.71, green: 0.42, blue: 1)],
                                       [Color(red: 0.06, green: 0.73, blue: 0.51), Color(red: 0.2, green: 0.83, blue: 0.6)],
                                       [Color(red: 0.96, green: 0.45, blue: 0.71), Color(red: 0.98, green: 0.44, blue: 0.52)]]

struct TgAvatar: View {
    let name: String; var size: CGFloat = 56; var index = 0; var online = false; var heart = false; var selected = false
    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: avatarColors[index % avatarColors.count], startPoint: .topLeading, endPoint: .bottomTrailing))
            Text(String(name.first ?? "?").uppercased()).font(jakarta(size * 0.38, .extrabold)).foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .overlay(Circle().stroke(.white, lineWidth: selected ? 3 : 0).padding(-4))
        .overlay(alignment: .topLeading) { if online { Circle().fill(Color.green).frame(width: size * 0.2, height: size * 0.2).overlay(Circle().stroke(Color.ink, lineWidth: 2)) } }
        .overlay(alignment: .bottomTrailing) { if heart { Text("❤️").font(.system(size: size * 0.26)) } }
    }
}

/// The Together tab: the people you watch with, adding someone, and joining a party with its code.
struct TogetherView: View {
    @ObservedObject var tg = Tg.shared
    @EnvironmentObject var store: Store
    @State private var sel = ""
    @State private var adding = false
    @State private var code = ""
    @State private var err = ""

    var body: some View {
        let cur = tg.people.first { $0.pid == sel } ?? tg.people.first
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(Array(tg.people.enumerated()), id: \.element.pid) { i, p in
                            Button { sel = p.pid } label: {
                                VStack(spacing: 6) {
                                    TgAvatar(name: p.name, size: 62, index: i, online: p.online, heart: p.kind == "partner", selected: cur?.pid == p.pid)
                                    Text(p.name).font(jakarta(13, .bold)).foregroundStyle(.white).lineLimit(1)
                                    Text(kinds.first { $0.0 == p.kind }?.2 ?? "Friend").font(jakarta(11, .semibold)).foregroundStyle(Color.muted)
                                }.frame(width: 84)
                            }.buttonStyle(PressStyle())
                        }
                        Button { adding = true } label: {
                            VStack(spacing: 6) {
                                Circle().strokeBorder(Color.white.opacity(0.3), style: StrokeStyle(lineWidth: 2, dash: [5])).frame(width: 62, height: 62)
                                    .overlay(Image(systemName: "plus").font(.system(size: 22, weight: .semibold)).foregroundStyle(Color.soft))
                                Text("Add someone").font(jakarta(13, .bold)).foregroundStyle(.white)
                                Text("With a code").font(jakarta(11, .semibold)).foregroundStyle(Color.muted)
                            }.frame(width: 92)
                        }.buttonStyle(PressStyle())
                    }.padding(.horizontal, 18).padding(.vertical, 6)
                }
                Group {
                    if let cur {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(I18n.f("With {1}", cur.name)).font(jakarta(22, .extrabold))
                            HStack(spacing: 8) {
                                chip(cur.online ? "● " + I18n.t("On Movio now") : I18n.t("Not on Movio right now"), cur.online ? .green : Color.muted)
                                if let k = kinds.first(where: { $0.0 == cur.kind }) { chip("\(k.1) \(I18n.t(k.2))", .white) }
                            }
                            Text(I18n.f("To watch with {1}, open any film or episode and press Watch together. They'll get an invite.", cur.name))
                                .font(jakarta(14)).foregroundStyle(Color.soft)
                            Button(role: .destructive) { Task { await tg.call("remove", ["pid": cur.pid]); sel = "" } } label: {
                                Text("Remove").font(jakarta(14, .bold)).padding(.horizontal, 16).padding(.vertical, 10).background(Color.white.opacity(0.1), in: Capsule())
                            }
                        }
                    } else {
                        VStack(spacing: 10) {
                            Text("👥").font(.system(size: 40))
                            Text("Watch with the people you love").font(jakarta(20, .extrabold)).multilineTextAlignment(.center)
                            Text("Add your partner, friends or family with a code. Then watch films and shows together, in sync, wherever you are.")
                                .font(jakarta(14)).foregroundStyle(Color.soft).multilineTextAlignment(.center)
                            BigButton(label: "Add someone") { adding = true }
                        }.frame(maxWidth: .infinity)
                    }
                }
                .padding(18).background(Color.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 22, style: .continuous)).padding(.horizontal, 18)

                VStack(alignment: .leading, spacing: 10) {
                    Text("Join a watch party").font(jakarta(17, .bold))
                    Text("Got a 4-letter party code? Enter it here.").font(jakarta(13)).foregroundStyle(Color.muted)
                    HStack(spacing: 10) {
                        TextField("ABCD", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .font(jakarta(22, .extrabold)).kerning(6).padding(.horizontal, 14).frame(height: 50)
                            .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .onChange(of: code) { v in code = String(v.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(4)) }
                        Button { join() } label: {
                            Text("Join").font(jakarta(16, .bold)).foregroundStyle(Color.ink).padding(.horizontal, 22).frame(height: 50).background(.white, in: RoundedRectangle(cornerRadius: 14))
                        }
                    }
                    if !err.isEmpty { Text(err).font(jakarta(13, .bold)).foregroundStyle(.red) }
                }
                .padding(18).background(Color.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 22, style: .continuous)).padding(.horizontal, 18)
            }
            .padding(.bottom, 30)
        }
        .navigationTitle("Together")
        .task { await tg.refresh() }
        .refreshable { await tg.refresh() }
        .sheet(isPresented: $adding) { AddSomeoneSheet().presentationDetents([.large]) }
    }

    private func chip(_ s: String, _ c: Color) -> some View {
        Text(s).font(jakarta(12, .bold)).foregroundStyle(c).padding(.horizontal, 10).padding(.vertical, 6).background(Color.white.opacity(0.08), in: Capsule())
    }
    private func join() {
        guard code.count == 4 else { return }
        Task {
            guard let o = await tg.party(code) else { err = I18n.t("That party code wasn't found."); return }
            err = ""
            store.playing = PlayRequest(title: Tg.title(o), season: o["s"] as? Int ?? 1, episode: o["e"] as? Int ?? 1, party: code.uppercased())
        }
    }
}

/// Add someone: share your code, or enter theirs and say who they are.
struct AddSomeoneSheet: View {
    @ObservedObject var tg = Tg.shared
    @Environment(\.dismiss) private var dismiss
    @State private var kind = "partner"
    @State private var code = ""
    @State private var err = ""
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Add someone").font(jakarta(24, .extrabold))
                Text("Each person or group gets their own shared things.").font(jakarta(13.5)).foregroundStyle(Color.muted)
                Text("YOUR CODE FOR THEM").font(jakarta(11.5, .extrabold)).foregroundStyle(Color.muted).padding(.top, 6)
                HStack(spacing: 8) {
                    ForEach(Array((tg.code.isEmpty ? "·····" : tg.code).enumerated()), id: \.offset) { _, c in
                        Text(String(c)).font(jakarta(26, .extrabold)).frame(width: 50, height: 60).background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                    }
                }.frame(maxWidth: .infinity)
                ShareLink(item: I18n.f("Add me on Movio to watch together. My code is {1}", tg.code) + " — https://g41us.github.io/Movio/") {
                    Text("Share code").font(jakarta(15, .bold)).frame(maxWidth: .infinity, minHeight: 46).background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                }
                Text("OR ENTER THEIR CODE").font(jakarta(11.5, .extrabold)).foregroundStyle(Color.muted).padding(.top, 6)
                TextField("ABCDE", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .font(jakarta(22, .extrabold)).kerning(6).padding(.horizontal, 14).frame(height: 52)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .onChange(of: code) { v in code = String(v.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(5)); err = "" }
                Text("THEY ARE YOUR…").font(jakarta(11.5, .extrabold)).foregroundStyle(Color.muted).padding(.top, 6)
                HStack(spacing: 8) {
                    ForEach(kinds, id: \.0) { k in
                        Button { kind = k.0 } label: {
                            Text("\(k.1) \(I18n.t(k.2))").font(jakarta(14, .bold)).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 10)
                                .background(kind == k.0 ? AnyShapeStyle(LinearGradient(colors: [.violet, .pink], startPoint: .leading, endPoint: .trailing)) : AnyShapeStyle(Color.white.opacity(0.08)), in: Capsule())
                        }
                    }
                }
                if !err.isEmpty { Text(err).font(jakarta(13, .bold)).foregroundStyle(.red) }
                BigButton(label: busy ? "…" : "Connect") {
                    guard code.count == 5 else { err = I18n.t("Enter their 5-letter code."); return }
                    busy = true
                    Task {
                        if let e = await tg.call("connect", ["code": code, "kind": kind]) { err = e } else { dismiss() }
                        busy = false
                    }
                }
            }
            .padding(22)
        }
        .background(Color(red: 0.07, green: 0.07, blue: 0.1).ignoresSafeArea())
        .task { await tg.refresh() }
    }
}

/// From a title's page: choose who to invite, then start the party.
struct WatchTogetherSheet: View {
    let title: Title; let season: Int; let episode: Int
    @ObservedObject var tg = Tg.shared
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var chosen = Set<String>()
    @State private var busy = false
    @State private var err = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Watch together").font(jakarta(24, .extrabold))
            Text(verbatim: title.name + (title.kind == .tv ? " · S\(season) E\(episode)" : "")).font(jakarta(14)).foregroundStyle(Color.muted)
            if tg.people.isEmpty {
                Text("You'll get a code to share. Add people in the Together tab to invite them directly next time.").font(jakarta(14)).foregroundStyle(Color.soft)
            } else {
                Text("INVITE").font(jakarta(11.5, .extrabold)).foregroundStyle(Color.muted)
                ForEach(Array(tg.people.enumerated()), id: \.element.pid) { i, p in
                    Button { if chosen.contains(p.pid) { chosen.remove(p.pid) } else { chosen.insert(p.pid) } } label: {
                        HStack(spacing: 12) {
                            TgAvatar(name: p.name, size: 36, index: i, online: p.online)
                            Text(verbatim: p.name).font(jakarta(15, .bold)).foregroundStyle(.white)
                            Spacer()
                            if chosen.contains(p.pid) { Image(systemName: "checkmark").font(.system(size: 16, weight: .bold)).foregroundStyle(Color.violet) }
                        }.padding(10).background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
            if !err.isEmpty { Text(err).font(jakarta(13, .bold)).foregroundStyle(.red) }
            Spacer(minLength: 8)
            BigButton(label: busy ? "…" : "Start watch party") {
                busy = true
                Task {
                    let room = await tg.newParty(title, season, episode, invite: Array(chosen))
                    busy = false
                    guard let room else { err = I18n.t("Couldn't reach Movio. Try again."); return }
                    dismiss()
                    try? await Task.sleep(nanoseconds: 350_000_000)
                    store.playing = PlayRequest(title: title, season: season, episode: episode, party: room, host: true)
                }
            }
        }
        .padding(22)
        .background(Color(red: 0.07, green: 0.07, blue: 0.1).ignoresSafeArea())
        .task { await tg.refresh(); if chosen.isEmpty { chosen = Set(tg.people.filter { $0.kind == "partner" }.map(\.pid)) } }
    }
}

/// "Tobi wants to watch Street Fighter with you": shown over Movio when an invite comes in.
struct InviteBanner: View {
    @ObservedObject var tg = Tg.shared
    @EnvironmentObject var store: Store
    var body: some View {
        Group {
            if let inv = tg.invites.first, store.playing == nil {
                HStack(spacing: 12) {
                    if let p = inv.title["poster"] as? String, !p.isEmpty { Art(url: TMDB.img(p, "w154")).frame(width: 40, height: 60).clipShape(RoundedRectangle(cornerRadius: 8)) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: I18n.f("{1} wants to watch with you", inv.from)).font(jakarta(12, .bold)).foregroundStyle(Color.muted)
                        Text(verbatim: inv.title["name"] as? String ?? "").font(jakarta(15, .extrabold)).lineLimit(1)
                    }
                    Spacer()
                    Button {
                        tg.dismiss(inv.id)
                        store.playing = PlayRequest(title: Tg.title(inv.title), season: inv.title["s"] as? Int ?? 1, episode: inv.title["e"] as? Int ?? 1, party: inv.room)
                    } label: { Text("Join").font(jakarta(15, .bold)).foregroundStyle(Color.ink).padding(.horizontal, 16).frame(height: 38).background(.white, in: Capsule()) }
                    Button { tg.dismiss(inv.id) } label: { Image(systemName: "xmark").font(.system(size: 14, weight: .bold)).foregroundStyle(Color.soft).frame(width: 34, height: 34) }
                }
                .padding(10)
                .background(Color(red: 0.08, green: 0.08, blue: 0.12).opacity(0.97), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Color.white.opacity(0.1)))
                .padding(.horizontal, 12)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.4), value: tg.invites.first?.id)
        .task { while !Task.isCancelled { await tg.refresh(); try? await Task.sleep(nanoseconds: 20_000_000_000) } }
    }
}
