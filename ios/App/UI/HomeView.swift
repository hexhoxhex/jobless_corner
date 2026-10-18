import SwiftUI

struct HomeView: View {
    @EnvironmentObject var library: Library
    @State private var rows: [(String, [MediaItem])] = []
    @State private var error: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if !library.entries.isEmpty {
                    ContinueRow(entries: Array(library.entries.prefix(12)))
                }
                ForEach(rows, id: \.0) { title, items in
                    PosterRow(title: title, items: items)
                }
                if rows.isEmpty && error == nil {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 60)
                }
                if let error {
                    Text(error).foregroundStyle(Theme.muted).padding()
                }
            }
            .padding(.vertical)
        }
        .background(Theme.background)
        .navigationTitle("Vijana BaruBaru")
        .navigationDestination(for: MediaItem.self) { DetailView(item: $0) }
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        do {
            async let t = TMDB.trending()
            async let m = TMDB.popularMovies()
            async let s = TMDB.popularTV()
            async let r = TMDB.topRatedTV()
            rows = try await [("Trending today", t), ("Popular films", m), ("Popular series", s), ("Top rated series", r)]
            error = nil
        } catch {
            self.error = "Couldn't load the catalogue. Pull to try again."
            AppLog.w("Home", "load: \(error)")
        }
    }
}

struct PosterRow: View {
    let title: String
    let items: [MediaItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title3.bold()).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(items) { item in
                        NavigationLink(value: item) { PosterCard(item: item) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

struct PosterCard: View {
    let item: MediaItem
    var width: CGFloat = 120

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            AsyncImage(url: item.poster) { img in
                img.resizable().scaledToFill()
            } placeholder: {
                ZStack {
                    Theme.surface
                    Text(item.title).font(.caption).foregroundStyle(Theme.muted).padding(6)
                }
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(item.title).font(.caption.weight(.semibold)).lineLimit(1)
            Text([item.isTV ? "Series" : "Film", item.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                .font(.caption2).foregroundStyle(Theme.muted)
        }
        .frame(width: width)
    }
}

struct ContinueRow: View {
    let entries: [Library.Entry]
    @EnvironmentObject var player: PlayerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Continue watching").font(.title3.bold()).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(entries) { e in
                        Button { player.play(e.request) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                ZStack(alignment: .bottomLeading) {
                                    AsyncImage(url: e.request.item.backdrop ?? e.request.item.poster) { img in
                                        img.resizable().scaledToFill()
                                    } placeholder: { Theme.surface }
                                    .frame(width: 200, height: 112)
                                    .clipped()
                                    GeometryReader { g in
                                        Rectangle().fill(Theme.accent)
                                            .frame(width: g.size.width * e.progress, height: 3)
                                            .frame(maxHeight: .infinity, alignment: .bottom)
                                    }
                                }
                                .frame(width: 200, height: 112)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                Text(e.request.item.title).font(.caption.weight(.semibold)).lineLimit(1)
                                if let s = e.request.season, let ep = e.request.episode {
                                    Text("Season \(s) · Episode \(ep)").font(.caption2).foregroundStyle(Theme.muted)
                                }
                            }
                            .frame(width: 200)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}
