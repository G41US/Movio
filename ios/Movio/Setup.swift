import SwiftUI

/// First launch: your name, the app's language and the subtitle language. Asked once; changeable from the profile button.
struct SetupView: View {
    @EnvironmentObject var profile: Profile
    @State private var step = 0
    @State private var name = Profile.shared.name
    @FocusState private var typing: Bool

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(verbatim: "Movio").font(jakarta(24, .extrabold)).foregroundStyle(brand)
                    Spacer()
                    HStack(spacing: 6) {
                        ForEach(0..<3) { i in
                            Capsule().fill(i <= step ? Color.white : Color.white.opacity(0.25)).frame(width: i == step ? 22 : 6, height: 6)
                        }
                    }
                }
                .padding(.bottom, 34)
                Group {
                    switch step {
                    case 0: nameStep
                    case 1: languageStep
                    default: subtitleStep
                    }
                }
                .transition(.opacity)
            }
            .frame(maxWidth: 460)
            .padding(.horizontal, 24).padding(.vertical, 30)
            .frame(maxWidth: .infinity)
        }
        .background(Color.ink.ignoresSafeArea())
        .animation(.easeInOut(duration: 0.22), value: step)
        .id(profile.lang)  // redraw in the chosen language straight away
    }

    private var nameStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            heading("Welcome to Movio", "Let's set things up. It only takes a moment.")
            Text("What should we call you?").font(jakarta(15, .semibold)).foregroundStyle(Color.soft).padding(.top, 28).padding(.bottom, 10)
            TextField("", text: $name, prompt: Text(verbatim: L("Your name")).foregroundColor(.muted))
                .font(jakarta(17, .semibold)).foregroundStyle(.white).tint(.violet)
                .textInputAutocapitalization(.words).autocorrectionDisabled().submitLabel(.next)
                .focused($typing).onSubmit { next() }
                .padding(.horizontal, 16).frame(height: 56)
                .background(Color.white.opacity(0.09), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.14)))
                .onChange(of: name) { v in if v.count > 24 { name = String(v.prefix(24)) } }
            primary("Continue") { next() }.padding(.top, 26)
        }
        .onAppear { typing = true }
    }

    private var languageStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            heading("App language", "Menus, buttons and film descriptions will use this language.").padding(.bottom, 14)
            // Each language written in itself, so anyone can find theirs.
            ForEach(I18n.langs, id: \.code) { l in
                choice(l.native, l.code == "en" || L(l.english) == l.native ? nil : L(l.english), profile.lang == l.code) {
                    profile.lang = l.code
                    profile.subLang = l.code
                }
            }
            buttons(back: { step = 0 }, next: "Continue") { step = 2 }.padding(.top, 14)
        }
    }

    private var subtitleStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            heading("Subtitle language", "Movio will turn these subtitles on by default.").padding(.bottom, 14)
            ForEach(I18n.langs, id: \.code) { l in
                choice(L(l.english), L(l.english) == l.native ? nil : l.native, profile.subLang == l.code) { profile.subLang = l.code }
            }
            choice(L("No subtitles"), nil, profile.subLang == "off") { profile.subLang = "off" }
            buttons(back: { step = 1 }, next: "Start watching") {
                profile.name = name.trimmingCharacters(in: .whitespaces)
                withAnimation { profile.setupDone = true }
            }
            .padding(.top, 14)
        }
    }

    private func next() { profile.name = name.trimmingCharacters(in: .whitespaces); typing = false; step = 1 }

    private func heading(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(jakarta(30, .extrabold)).foregroundStyle(.white)
            Text(body).font(jakarta(15)).foregroundStyle(Color.muted)
        }
    }

    private func choice(_ label: String, _ sub: String?, _ on: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: label).font(jakarta(17, .bold)).foregroundStyle(.white)
                    if let sub { Text(verbatim: sub).font(jakarta(13)).foregroundStyle(Color.muted) }
                }
                Spacer()
                ZStack {
                    Circle().stroke(on ? Color.violet : Color.white.opacity(0.3), lineWidth: 2).frame(width: 24, height: 24)
                    if on { Circle().fill(Color.violet).frame(width: 24, height: 24); Image(systemName: "checkmark").font(.system(size: 12, weight: .heavy)).foregroundStyle(.white) }
                }
            }
            .padding(.horizontal, 18).padding(.vertical, 15)
            .background(on ? Color.violet.opacity(0.2) : Color.white.opacity(0.09), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? Color.violet : .clear, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }

    private func primary(_ label: String, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text(label).font(jakarta(16, .bold)).foregroundStyle(Color.ink).frame(maxWidth: .infinity, minHeight: 52)
                .background(.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressStyle())
    }

    private func buttons(back: @escaping () -> Void, next: String, _ go: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            Button(action: back) {
                Text("Back").font(jakarta(15, .semibold)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                    .background(Color.white.opacity(0.09), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PressStyle())
            primary(next, go).frame(maxWidth: .infinity).layoutPriority(1)
        }
    }
}

/// The round button on Home: name, app language and subtitle language.
struct ProfileSheet: View {
    @EnvironmentObject var profile: Profile
    @ObservedObject private var store = Store.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name = Profile.shared.name
    @State private var typed = ""
    @State private var msg: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section(L("Name")) {
                    TextField("", text: $name, prompt: Text(verbatim: L("Your name")))
                        .textInputAutocapitalization(.words).autocorrectionDisabled()
                        .onChange(of: name) { v in profile.name = String(v.prefix(24)) }
                }
                Section(L("App language")) {
                    ForEach(I18n.langs, id: \.code) { l in
                        row(l.native, profile.lang == l.code) { profile.lang = l.code }
                    }
                }
                Section(L("Subtitle language")) {
                    ForEach(I18n.langs, id: \.code) { l in row(L(l.english), profile.subLang == l.code) { profile.subLang = l.code } }
                    row(L("No subtitles"), profile.subLang == "off") { profile.subLang = "off" }
                }
                Section {
                    if store.syncCode.isEmpty {
                        Button(L("Get a code")) { run { if await store.createCode() == nil { msg = L("Couldn't reach the server. Try again.") } } }
                        HStack {
                            TextField("", text: $typed, prompt: Text(verbatim: L("Type the code")))
                                .textInputAutocapitalization(.characters).autocorrectionDisabled()
                                .font(.system(size: 18, weight: .bold, design: .rounded))
                                .onChange(of: typed) { v in typed = String(v.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(6)) }
                            Button(L("Link")) { run { msg = L(await store.joinCode(typed) ? "Linked" : "That code wasn't found. Check it and try again.") } }
                                .disabled(typed.count != 6)
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("This device's code").font(.footnote).foregroundStyle(Color.muted)
                            Text(verbatim: store.syncCode).font(.system(size: 34, weight: .heavy, design: .rounded)).kerning(6).foregroundStyle(brand)
                            Text("On your other device, open Link devices and type this code.").font(.footnote).foregroundStyle(Color.muted)
                        }
                        .padding(.vertical, 4)
                        Button(L("Sync now")) { run { msg = L(await store.sync() ? "Synced" : "Couldn't sync") } }
                        Button(L("Unlink"), role: .destructive) { store.syncCode = ""; msg = nil }
                    }
                    if working { ProgressView() }
                    if let msg { Text(verbatim: msg).font(.footnote).foregroundStyle(Color.soft) }
                } header: { Text("Link devices") } footer: {
                    Text("My List, Continue watching and where you stopped follow you on every device with the same code.")
                }
                Section {
                    Toggle(isOn: $store.kids) { Text("Kids mode") }.tint(.violet)
                } footer: { Text("Only family and animated titles.") }
            }
            .scrollContentBackground(.hidden)
            .background(Color.ink)
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
        .presentationDetents([.large])
    }

    private func run(_ work: @escaping () async -> Void) {
        working = true; msg = nil
        Task { await work(); working = false }
    }

    private func row(_ label: String, _ on: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack {
                Text(verbatim: label).foregroundStyle(.white)
                Spacer()
                if on { Image(systemName: "checkmark").foregroundStyle(Color.violet).font(.system(size: 15, weight: .bold)) }
            }
        }
    }
}

/// "Good evening, Marvelous": shown for a moment when Movio opens.
struct GreetingPill: View {
    @State private var shown = false
    let text: String?
    var body: some View {
        if let text {
            Text(verbatim: text).font(jakarta(15, .bold)).foregroundStyle(.white)
                .padding(.horizontal, 18).padding(.vertical, 10)
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.1)))
                .opacity(shown ? 1 : 0).offset(y: shown ? 0 : -16)
                .allowsHitTesting(false)
                .task {
                    try? await Task.sleep(nanoseconds: 600_000_000)
                    withAnimation(.spring(response: 0.4)) { shown = true }
                    try? await Task.sleep(nanoseconds: 3_200_000_000)
                    withAnimation(.easeOut(duration: 0.4)) { shown = false }
                }
        }
    }
}
