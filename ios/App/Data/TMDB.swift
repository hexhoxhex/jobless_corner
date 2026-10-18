import Foundation

/// The Movie Database: catalogue, search, seasons and episodes. Every
/// source this app plays from is keyed by the TMDB id, so this is the one
/// place titles come from.
enum TMDB {
    private static let base = "https://api.themoviedb.org/3/"
    private static let image = "https://image.tmdb.org/t/p/"

    private static func call(_ path: String, _ query: [String: String] = [:]) async throws -> [String: Any] {
        var comps = URLComponents(string: base + path)!
        comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } +
            [URLQueryItem(name: "language", value: "en-US")]
        var headers = ["Accept": "application/json"]
        let token = Config.tmdbToken
        if !token.isEmpty { headers["Authorization"] = "Bearer " + token }
        let obj = try await Net.json(comps.url!, headers: headers)
        return obj as? [String: Any] ?? [:]
    }

    static func img(_ path: Any?, size: String) -> URL? {
        guard let p = path as? String, !p.isEmpty else { return nil }
        return URL(string: image + size + p)
    }

    private static func item(_ d: [String: Any], forceTV: Bool? = nil) -> MediaItem? {
        guard let id = d["id"] as? Int else { return nil }
        let media = d["media_type"] as? String
        if let m = media, m != "movie", m != "tv" { return nil }
        let isTV = forceTV ?? (media == "tv" || d["first_air_date"] != nil)
        let title = (d["title"] as? String) ?? (d["name"] as? String) ?? ""
        guard !title.isEmpty else { return nil }
        let date = (d["release_date"] as? String) ?? (d["first_air_date"] as? String) ?? ""
        return MediaItem(
            tmdbId: id, isTV: isTV, title: title,
            year: Int(date.prefix(4)),
            overview: d["overview"] as? String ?? "",
            poster: img(d["poster_path"], size: "w342"),
            backdrop: img(d["backdrop_path"], size: "w780"),
            rating: (d["vote_average"] as? Double).flatMap { $0 > 0 ? $0 : nil }
        )
    }

    private static func list(_ path: String, _ q: [String: String] = [:], forceTV: Bool? = nil) async throws -> [MediaItem] {
        let r = try await call(path, q)
        return (r["results"] as? [[String: Any]] ?? []).compactMap { item($0, forceTV: forceTV) }
    }

    static func trending() async throws -> [MediaItem] { try await list("trending/all/day") }
    static func popularMovies() async throws -> [MediaItem] { try await list("movie/popular", forceTV: false) }
    static func popularTV() async throws -> [MediaItem] { try await list("tv/popular", forceTV: true) }
    static func topRatedTV() async throws -> [MediaItem] { try await list("tv/top_rated", forceTV: true) }

    static func search(_ query: String) async throws -> [MediaItem] {
        try await list("search/multi", ["query": query, "include_adult": "false"])
    }

    /// Full details: fresh overview/backdrop and, for a series, its seasons.
    static func details(_ item: MediaItem) async throws -> (MediaItem, [SeasonInfo]) {
        let r = try await call((item.isTV ? "tv/" : "movie/") + String(item.tmdbId))
        let fresh = TMDB.item(r, forceTV: item.isTV) ?? item
        let seasons = (r["seasons"] as? [[String: Any]] ?? []).compactMap { s -> SeasonInfo? in
            guard let n = s["season_number"] as? Int, n > 0 else { return nil }
            let count = s["episode_count"] as? Int ?? 0
            return count > 0 ? SeasonInfo(number: n, episodeCount: count) : nil
        }
        return (fresh, seasons)
    }

    static func episodes(tvId: Int, season: Int) async throws -> [EpisodeInfo] {
        let r = try await call("tv/\(tvId)/season/\(season)")
        return (r["episodes"] as? [[String: Any]] ?? []).compactMap { e in
            guard let n = e["episode_number"] as? Int, n > 0 else { return nil }
            return EpisodeInfo(
                number: n,
                name: e["name"] as? String ?? "Episode \(n)",
                overview: e["overview"] as? String ?? "",
                still: img(e["still_path"], size: "w300"),
                runtimeMin: e["runtime"] as? Int
            )
        }
    }
}
