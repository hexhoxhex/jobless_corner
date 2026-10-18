import SwiftUI

struct DetailView: View {
    let item: MediaItem
    @EnvironmentObject var player: PlayerModel
    @EnvironmentObject var library: Library
    @State private var full: MediaItem?
    @State private var seasons: [SeasonInfo] = []
    @State private var season: Int = 1
    @State private var episodes: [EpisodeInfo] = []
    @State private var loadingEpisodes = false

    private var shown: MediaItem { full ?? item }
    private var resume: Library.Entry? { library.entries.first { $0.id == item.id } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                AsyncImage(url: shown.backdrop ?? shown.poster) { img in
                    img.resizable().scaledToFill()
                } placeholder: { Theme.surface }
                .frame(height: 220)
                .frame(maxWidth: .infinity)
                .clipped()
                .overlay(LinearGradient(colors: [.clear, Theme.background], startPoint: .center, endPoint: .bottom))

                VStack(alignment: .leading, spacing: 10) {
                    Text(shown.title).font(.title.bold())
                    HStack(spacing: 6) {
                        Text(shown.isTV ? "Series" : "Film")
                        if let y = shown.year { Text("· \(String(y))") }
                        if let r = shown.rating {
                            Text("·")
                            Image(systemName: "star.fill").foregroundStyle(.yellow).font(.caption)
                            Text(String(format: "%.1f", r))
                        }
                    }
                    .font(.subheadline).foregroundStyle(Theme.muted)
                    Text(shown.overview).font(.body).foregroundStyle(.secondary)

                    playButtons
                }
                .padding(.horizontal)

                if shown.isTV { episodeSection }
            }
            .padding(.bottom, 30)
        }
        .background(Theme.background)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    @ViewBuilder private var playButtons: some View {
        VStack(spacing: 10) {
            Button {
                if let r = resume { player.play(r.request) }
                else if shown.isTV { player.play(PlayRequest(item: shown, season: season, episode: episodes.first?.number ?? 1)) }
                else { player.play(PlayRequest(item: shown, season: nil, episode: nil)) }
            } label: {
                Label(playTitle, systemImage: "play.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)

            if !shown.isTV {
                Menu {
                    ForEach(Sources.names, id: \.self) { name in
                        Button("Play from \(name)") {
                            player.play(PlayRequest(item: shown, season: nil, episode: nil), source: name)
                        }
                    }
                } label: {
                    Label("Choose a source", systemImage: "list.bullet")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.top, 6)
    }

    private var playTitle: String {
        if let r = resume {
            if let s = r.request.season, let e = r.request.episode { return "Resume S\(s) E\(e)" }
            return "Resume"
        }
        return shown.isTV ? "Play S\(season) E1" : "Play"
    }

    @ViewBuilder private var episodeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !seasons.isEmpty {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(seasons) { s in
                                Button("Season \(s.number)") {
                                    season = s.number
                                    Task { await loadEpisodes() }
                                }
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(s.number == season ? Color.white : Theme.surface, in: Capsule())
                                .foregroundStyle(s.number == season ? Color.black : Color.white)
                                .id(s.number)
                            }
                        }
                        .padding(.horizontal)
                    }
                    .onAppear { proxy.scrollTo(season, anchor: .center) }
                }
            }
            if loadingEpisodes { ProgressView().frame(maxWidth: .infinity) }
            ForEach(episodes) { ep in
                Button {
                    player.play(PlayRequest(item: shown, season: season, episode: ep.number))
                } label: {
                    EpisodeRow(ep: ep, playing: resume?.request.season == season && resume?.request.episode == ep.number)
                }
                .buttonStyle(.plain)
                .padding(.horizontal)
            }
        }
    }

    private func load() async {
        if let result = try? await TMDB.details(item) {
            full = result.0
            seasons = result.1
            if let r = resume, let rs = r.request.season { season = rs }
            else if let first = result.1.first { season = first.number }
        }
        if item.isTV { await loadEpisodes() }
    }

    private func loadEpisodes() async {
        loadingEpisodes = true
        episodes = (try? await player.episodes(item.tmdbId, season)) ?? []
        loadingEpisodes = false
    }
}

struct EpisodeRow: View {
    let ep: EpisodeInfo
    let playing: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AsyncImage(url: ep.still) { img in
                img.resizable().scaledToFill()
            } placeholder: {
                ZStack { Theme.surface; Text("\(ep.number)").font(.title3.bold()).foregroundStyle(Theme.muted) }
            }
            .frame(width: 132, height: 74)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 4) {
                Text("\(ep.number). \(ep.name)").font(.subheadline.weight(.bold)).lineLimit(1)
                HStack(spacing: 8) {
                    if playing { Text("CONTINUE").font(.caption2.bold()).foregroundStyle(Theme.accent) }
                    if let m = ep.runtimeMin { Text("\(m) min").font(.caption).foregroundStyle(Theme.muted) }
                }
                Text(ep.overview).font(.caption).foregroundStyle(Theme.muted).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(8)
        .background(playing ? Theme.surface : Color.clear, in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
    }
}
