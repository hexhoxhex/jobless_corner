import Foundation

/// A film or series, keyed by its TMDB id (what every source looks titles up by).
struct MediaItem: Identifiable, Hashable, Codable {
    let tmdbId: Int
    let isTV: Bool
    let title: String
    let year: Int?
    let overview: String
    let poster: URL?
    let backdrop: URL?
    let rating: Double?

    var id: String { (isTV ? "tv:" : "movie:") + String(tmdbId) }
}

struct SeasonInfo: Identifiable, Hashable {
    let number: Int
    let episodeCount: Int
    var id: Int { number }
}

struct EpisodeInfo: Identifiable, Hashable {
    let number: Int
    let name: String
    let overview: String
    let still: URL?
    let runtimeMin: Int?
    var id: Int { number }
}

/// What to play: a URL the player can open, the headers its server wants,
/// and where it came from.
struct StreamInfo {
    let url: URL
    let headers: [String: String]
    let provider: String
    let label: String          // "1080P", "Auto", "Live"
    let language: String       // "Original", "English", ...
    let isLive: Bool
}

/// What is being played — enough to save progress and find the next episode.
struct PlayRequest: Hashable, Codable {
    let item: MediaItem
    let season: Int?
    let episode: Int?

    var key: String {
        if let s = season, let e = episode { return "\(item.id):s\(s)e\(e)" }
        return item.id
    }
    var label: String {
        if let s = season, let e = episode { return "S\(s) E\(e) · \(item.title)" }
        return item.title
    }
}

/// A live channel from the published catalogue (mkurugenzi_viewer).
struct LiveChannel: Identifiable, Hashable {
    let id: String
    let name: String
    let logo: URL?
    let group: String

    /// "tnt sports 1 uk" -> "TNT Sports 1 UK".
    var displayName: String {
        let upper: Set<String> = ["uk", "usa", "us", "tv", "nz", "hd", "bt", "tnt", "espn", "cnn",
                                  "nbc", "abc", "cbs", "hbo", "bbc", "itv", "mena", "dazn", "tsn"]
        return name.split(separator: " ").map { w -> String in
            let s = String(w)
            if upper.contains(s.lowercased()) { return s.uppercased() }
            return s.prefix(1).uppercased() + s.dropFirst()
        }.joined(separator: " ")
    }
}

/// A channel named by the schedule for one event.
struct ScheduleChannel: Hashable, Identifiable {
    let id: String
    let name: String
}

/// A match or show from the published schedule.
struct ScheduleEvent: Identifiable, Hashable {
    let title: String
    let category: String
    let start: Date?
    let channels: [ScheduleChannel]

    var id: String { title + (start.map { String($0.timeIntervalSince1970) } ?? "") }
}
