import SwiftUI

extension Color {
    static let ink = Color(red: 0.027, green: 0.031, blue: 0.051)
    static let panel = Color(red: 0.082, green: 0.090, blue: 0.133)
    static let violet = Color(red: 0.608, green: 0.427, blue: 1.0)
    static let pink = Color(red: 1.0, green: 0.373, blue: 0.635)
    static let muted = Color(red: 0.62, green: 0.63, blue: 0.70)
    static let soft = Color(red: 0.855, green: 0.859, blue: 0.898)
}

let brand = LinearGradient(colors: [.violet, .pink], startPoint: .leading, endPoint: .trailing)

enum Weight { case regular, semibold, bold, extrabold }

/// Plus Jakarta Sans, the same type as the Android app.
func jakarta(_ size: CGFloat, _ w: Weight = .regular) -> Font {
    let name: String
    switch w {
    case .regular: name = "MovioJakarta-Regular"
    case .semibold: name = "MovioJakarta-SemiBold"
    case .bold: name = "MovioJakarta-Bold"
    case .extrabold: name = "MovioJakarta-ExtraBold"
    }
    return .custom(name, size: size)
}

/// Network image with a soft placeholder (cached through the shared URL cache).
struct Art: View {
    let url: URL?
    var body: some View {
        // The box takes whatever size it's given; the picture fills it and is cropped,
        // so a wide image can never push the page wider than the screen.
        Color.panel
            .overlay {
                AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
                    if case .success(let img) = phase { img.resizable().scaledToFill() } else { Color.panel }
                }
            }
            .clipped()
    }
}

struct PosterCard: View {
    let title: Title
    var width: CGFloat = 116
    var body: some View {
        NavigationLink(value: title) {
            Art(url: TMDB.img(title.poster, "w342"))
                .frame(width: width, height: width * 1.5)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(PressStyle())
    }
}

/// iOS-style press: a quick, slight shrink.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

struct RowView: View {
    let row: Row
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(row.name).font(jakarta(19, .extrabold)).foregroundStyle(.white).padding(.horizontal, 18)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: row.ranked ? 2 : 10) {
                    ForEach(Array(row.items.enumerated()), id: \.element.key) { i, t in
                        if row.ranked { RankedCard(rank: i + 1, title: t) } else { PosterCard(title: t) }
                    }
                }
                .padding(.horizontal, 18)
            }
        }
    }
}

/// Top 10: a heavy chrome numeral tucked under the poster.
struct RankedCard: View {
    let rank: Int
    let title: Title
    var body: some View {
        NavigationLink(value: title) {
            HStack(alignment: .bottom, spacing: -14) {
                Text("\(rank)")
                    .font(jakarta(110, .extrabold))
                    .tracking(-6)
                    .foregroundStyle(LinearGradient(colors: [.white, Color(red: 0.89, green: 0.87, blue: 1), Color(red: 0.56, green: 0.53, blue: 0.76)], startPoint: .top, endPoint: .bottom))
                    .shadow(color: .black.opacity(0.5), radius: 6, y: 2)
                    .offset(y: 14)
                Art(url: TMDB.img(title.poster, "w342"))
                    .frame(width: 108, height: 162)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
        .buttonStyle(PressStyle())
    }
}

struct ContinueCard: View {
    let p: Progress
    @EnvironmentObject var store: Store
    var body: some View {
        Button { store.play(p.title, season: p.season, episode: p.episode) } label: {
            ZStack(alignment: .bottomLeading) {
                Art(url: TMDB.img(p.title.backdrop ?? p.title.poster, "w500"))
                    .frame(width: 236, height: 133)
                LinearGradient(colors: [.clear, .clear, .ink.opacity(0.95)], startPoint: .top, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 3) {
                    Text(p.title.name).font(jakarta(14, .extrabold)).foregroundStyle(.white).lineLimit(1)
                    Text(sub).font(jakarta(11, .semibold)).foregroundStyle(Color.soft).lineLimit(1)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.22))
                            Capsule().fill(brand).frame(width: g.size.width * p.fraction)
                        }
                    }
                    .frame(height: 4)
                    .padding(.top, 4)
                }
                .padding(11)
            }
            .frame(width: 236, height: 133)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressStyle())
        .contextMenu {
            Button(role: .destructive) { store.remove(p.title) } label: { Label("Remove from row", systemImage: "xmark.circle") }
        }
    }

    private var sub: String {
        let left = max(1, Int((p.duration - p.position) / 60))
        let time = left >= 60 ? "\(left / 60)h \(left % 60)m left" : "\(left)m left"
        return p.title.kind == .tv ? "S\(p.season) · E\(p.episode)  ·  \(time)" : time
    }
}

struct Loading: View {
    var body: some View { ProgressView().tint(.violet).frame(maxWidth: .infinity, minHeight: 200) }
}
