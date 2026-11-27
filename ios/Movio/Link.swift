import SwiftUI

/// The gradient tile with the two-devices icon (green tick once linked).
struct LinkOrb: View {
    var ok = false
    var size: CGFloat = 60
    var body: some View {
        let colors: [Color] = ok ? [Color(red: 0.24, green: 0.86, blue: 0.59), Color(red: 0.13, green: 0.69, blue: 0.49)] : [.violet, .pink]
        RoundedRectangle(cornerRadius: size * 0.32, style: .continuous)
            .fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(Image(systemName: ok ? "checkmark" : "laptopcomputer.and.iphone")
                .font(.system(size: size * (ok ? 0.4 : 0.36), weight: ok ? .heavy : .medium)).foregroundStyle(.white))
            .shadow(color: colors[0].opacity(0.55), radius: 18, y: 10)
    }
}

/// Six letter tiles; [live] = being typed, with a blinking cursor on the next empty tile.
struct CodeTiles: View {
    let code: String
    var live = false
    var focused = false
    @State private var blink = false
    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<6, id: \.self) { i in
                let ch = i < code.count ? String(Array(code)[i]) : nil
                let cur = live && focused && i == min(code.count, 5)
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(live ? Color.black.opacity(0.28) : Color.violet.opacity(0.16))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(cur ? Color.violet : !live ? Color.violet.opacity(0.4) : ch != nil ? Color.white.opacity(0.28) : Color.white.opacity(0.1), lineWidth: cur ? 1.5 : 1))
                    .overlay {
                        if let ch { Text(verbatim: ch).font(jakarta(26, .extrabold)).foregroundStyle(.white) }
                        else if cur { Capsule().fill(Color.violet).frame(width: 2, height: 26).opacity(blink ? 0 : 1) }
                    }
                    .frame(height: 58)
            }
        }
        .environment(\.layoutDirection, .leftToRight)  // codes read left to right, even in Arabic
        .onAppear { withAnimation(.easeInOut(duration: 0.5).repeatForever()) { blink = true } }
    }
}

/// The code entry: an invisible text field over the tiles.
struct CodeField: View {
    @Binding var code: String
    var autoFocus = false
    var onFull: () -> Void
    @FocusState private var focused: Bool
    var body: some View {
        ZStack {
            CodeTiles(code: code, live: true, focused: focused)
            TextField("", text: $code)
                .textInputAutocapitalization(.characters).autocorrectionDisabled().keyboardType(.asciiCapable)
                .textContentType(.oneTimeCode)
                .focused($focused)
                .foregroundStyle(.clear).tint(.clear).accentColor(.clear)
                .frame(maxWidth: .infinity, minHeight: 58).opacity(0.02)
                .onChange(of: code) { v in
                    let clean = String(v.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(6))
                    if clean != v { code = clean }
                    if clean.count == 6 { onFull() }
                }
        }
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
        .task { if autoFocus { try? await Task.sleep(nanoseconds: 350_000_000); focused = true } }
    }
}

private struct Caps: View {
    let text: String
    var body: some View { Text(verbatim: L(text).uppercased()).font(jakarta(12, .bold)).kerning(1.2).foregroundStyle(Color.muted) }
}

private struct LinkPanel<C: View>: View {
    @ViewBuilder var content: C
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(18)
            .background(LinearGradient(colors: [.white.opacity(0.075), .white.opacity(0.035)], startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Color.white.opacity(0.09)))
    }
}

private func errorNote(_ s: String) -> some View {
    Text(verbatim: s).font(jakarta(13, .semibold)).foregroundStyle(Color(red: 1, green: 0.56, blue: 0.64))
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Color(red: 1, green: 0.37, blue: 0.48).opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.top, 12)
}

struct BigButton: View {
    let label: String
    var white = true
    var busy = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if busy { ProgressView().tint(white ? Color.ink : .white).scaleEffect(0.8) }
                Text(verbatim: L(label)).font(jakarta(16, .bold))
            }
            .foregroundStyle(white ? Color.ink : .white).frame(maxWidth: .infinity, minHeight: 52)
            .background(white ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressStyle()).disabled(busy)
    }
}

/// Link devices (from the profile button): your code, or type one from another device.
struct LinkView: View {
    @ObservedObject private var store = Store.shared
    @State private var typed = ""
    @State private var err: String?
    @State private var busy = ""
    @State private var copied = false

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                LinkOrb().padding(.bottom, 20)
                Text("Link devices").font(jakarta(30, .extrabold)).foregroundStyle(.white)
                Text("My List, Continue watching and where you stopped follow you on every device with the same code.")
                    .font(jakarta(15)).foregroundStyle(Color.muted).padding(.top, 8).padding(.bottom, 22)
                if !store.syncCode.isEmpty { linked } else { unlinked }
            }
            .padding(.horizontal, 24).padding(.vertical, 20)
            .frame(maxWidth: 460).frame(maxWidth: .infinity)
        }
        .background(Color.ink.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
    }

    private var linked: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinkPanel {
                HStack {
                    HStack(spacing: 7) {
                        Circle().fill(Color(red: 0.24, green: 0.86, blue: 0.59)).frame(width: 7, height: 7)
                        Text("Linked").font(jakarta(13, .bold)).foregroundStyle(Color(red: 0.37, green: 0.9, blue: 0.67))
                    }
                    .padding(.horizontal, 12).frame(height: 28)
                    .background(Color(red: 0.24, green: 0.86, blue: 0.59).opacity(0.13), in: Capsule())
                    Spacer()
                    Text(verbatim: ago(store.lastSync)).font(jakarta(12, .semibold)).foregroundStyle(Color.muted)
                }
                .padding(.bottom, 16)
                HStack {
                    Caps(text: "This device's code")
                    Spacer()
                    Button { UIPasteboard.general.string = store.syncCode; copied = true } label: {
                        Text(verbatim: L(copied ? "Copied" : "Copy")).font(jakarta(13, .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 12).frame(height: 26).background(Color.white.opacity(0.1), in: Capsule())
                    }
                }
                .padding(.bottom, 10)
                CodeTiles(code: store.syncCode)
                Text("On your other device, open Link devices and type this code.").font(jakarta(13)).foregroundStyle(Color.muted).padding(.top, 14)
            }
            HStack(spacing: 10) {
                BigButton(label: "Unlink", white: false) { store.syncCode = ""; err = nil; typed = "" }
                BigButton(label: busy == "sync" ? "Syncing…" : "Sync now", busy: busy == "sync") {
                    busy = "sync"
                    Task { let ok = await store.sync(); busy = ""; err = ok ? nil : L("Couldn't sync") }
                }
                .layoutPriority(1)
            }
            .padding(.top, 14)
            if let err { errorNote(err) }
        }
    }

    private var unlinked: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinkPanel {
                Caps(text: "Type the code").padding(.bottom, 10)
                CodeField(code: $typed) { join() }
                if let err { errorNote(err) }
                BigButton(label: busy == "link" ? "Linking…" : "Link", busy: busy == "link") { join() }.padding(.top, 16)
            }
            HStack(spacing: 12) {
                Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1)
                Caps(text: "or")
                Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1)
            }
            .padding(.vertical, 20)
            LinkPanel {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("No code yet?").font(jakarta(16, .bold)).foregroundStyle(.white)
                        Text("Start a new link from this device.").font(jakarta(13)).foregroundStyle(Color.muted)
                    }
                    Spacer()
                    Button {
                        busy = "new"; err = nil
                        Task { if await store.createCode() == nil { err = L("Couldn't reach the server. Try again.") }; busy = "" }
                    } label: {
                        HStack(spacing: 6) {
                            if busy == "new" { ProgressView().tint(.white).scaleEffect(0.75) }
                            Text(verbatim: L("Get a code")).font(jakarta(15, .bold)).foregroundStyle(.white)
                        }
                        .padding(.horizontal, 16).frame(height: 44)
                        .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(PressStyle())
                }
            }
        }
    }

    private func join() {
        guard busy.isEmpty else { return }
        guard typed.count == 6 else { err = L("Type the code"); return }
        busy = "link"; err = nil
        Task {
            let ok = await store.joinCode(typed)
            busy = ""
            if ok { typed = "" } else { err = L("That code wasn't found. Check it and try again.") }
        }
    }

    private func ago(_ d: Date?) -> String {
        guard let d else { return L("Not synced yet") }
        let m = Int(Date().timeIntervalSince(d) / 60)
        let when = m < 1 ? L("just now") : m < 60 ? I18n.f("{1} min ago", m) : d.formatted(date: .omitted, time: .shortened)
        return I18n.f("Last synced {1}", when)
    }
}

/// First-time setup: type a code from another device.
struct SetupLinkStep: View {
    @ObservedObject private var store = Store.shared
    var onBack: () -> Void
    var onLinked: (String, Int, Int) -> Void
    @State private var typed = ""
    @State private var err: String?
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinkOrb().padding(.bottom, 20)
            Text("Link with a code").font(jakarta(30, .extrabold)).foregroundStyle(.white)
            Text("Enter the code shown on your other device.").font(jakarta(15)).foregroundStyle(Color.muted).padding(.top, 8).padding(.bottom, 22)
            LinkPanel {
                Caps(text: "Type the code").padding(.bottom, 10)
                CodeField(code: $typed, autoFocus: true) { join() }
                if let err { errorNote(err) }
                BigButton(label: busy ? "Linking…" : "Link", busy: busy) { join() }.padding(.top, 16)
            }
            BigButton(label: "Back", white: false) { onBack() }.padding(.top, 14)
        }
    }

    private func join() {
        guard !busy else { return }
        guard typed.count == 6 else { err = L("Type the code"); return }
        busy = true; err = nil
        Task {
            let ok = await store.joinCode(typed)
            busy = false
            if ok { onLinked(Profile.shared.name, store.myList.count, store.recent.count) }
            else { err = L("That code wasn't found. Check it and try again.") }
        }
    }
}

/// After linking during setup: "Welcome back, Marvelous" and what came across.
struct WelcomeBackStep: View {
    let name: String
    let list: Int
    let cont: Int
    var onNext: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinkOrb(ok: true).padding(.bottom, 20)
            Text(verbatim: name.isEmpty ? L("You're linked") : I18n.f("Welcome back, {1}", name)).font(jakarta(30, .extrabold)).foregroundStyle(.white)
            Text("Your list and progress are here.").font(jakarta(15)).foregroundStyle(Color.muted).padding(.top, 8).padding(.bottom, 22)
            HStack(spacing: 10) {
                stat(list, "My List"); stat(cont, "Continue watching")
            }
            BigButton(label: "Continue") { onNext() }.padding(.top, 24)
        }
    }
    private func stat(_ n: Int, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: "\(n)").font(jakarta(30, .extrabold)).foregroundStyle(.white)
            Text(label).font(jakarta(13, .semibold)).foregroundStyle(Color.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.white.opacity(0.08)))
    }
}
