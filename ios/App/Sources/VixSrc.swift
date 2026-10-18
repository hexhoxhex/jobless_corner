import Foundation

/// VixSrc (vixsrc.to): `/api/{movie|tv}/<tmdb>[/s/e]` names an embed page
/// whose script carries a token, its expiry and the playlist; together they
/// make an adaptive HLS master. Ported from the TV app's net/VixSrc.kt.
enum VixSrc {
    private static let base = "https://vixsrc.to"
    private static let tag = "VixSrc"

    static func resolve(_ req: PlayRequest) async -> StreamInfo? {
        let api: String
        if let s = req.season, let e = req.episode {
            api = "\(base)/api/tv/\(req.item.tmdbId)/\(s)/\(e)"
        } else {
            api = "\(base)/api/movie/\(req.item.tmdbId)"
        }
        do {
            guard let apiURL = URL(string: api),
                  let obj = try await Net.json(apiURL, headers: ["Referer": base + "/"], timeout: 12) as? [String: Any],
                  let src = obj["src"] as? String, !src.isEmpty,
                  let embedURL = URL(string: src.hasPrefix("http") ? src : base + src) else { return nil }
            // token/expires/url must come from the same page.
            let html = try await Net.text(embedURL, headers: ["Referer": base + "/"], timeout: 12)
            guard let token = html.firstMatch(#"token["']\s*:\s*["']([^"']+)"#),
                  let expires = html.firstMatch(#"expires["']\s*:\s*["']([^"']+)"#),
                  let playlist = html.firstMatch(#"url\s*:\s*["']([^"']+)"#) else {
                AppLog.i(tag, "no token/playlist on the embed page")
                return nil
            }
            if let e = Double(expires), Date(timeIntervalSince1970: e) < Date() { return nil }
            let sep = playlist.contains("?") ? "&" : "?"
            guard let master = URL(string: "\(playlist)\(sep)token=\(token)&expires=\(expires)&h=1") else { return nil }
            let headers = ["Referer": embedURL.absoluteString]
            let manifest = try await Net.text(master, headers: headers, timeout: 12)
            guard manifest.hasPrefix("#EXTM3U") else { return nil }
            AppLog.i(tag, "resolved tmdb=\(req.item.tmdbId) s=\(req.season ?? 0) e=\(req.episode ?? 0)")
            return StreamInfo(url: master, headers: headers, provider: "VixSrc", label: "Auto",
                              language: "Original", isLive: false)
        } catch {
            AppLog.i(tag, "tmdb=\(req.item.tmdbId): \(error)")
            return nil
        }
    }
}

/// Every source in order of preference; the first that plays wins.
enum Sources {
    static let names = ["7Movies", "VixSrc"]

    /// The first source with a stream, skipping the ones already tried
    /// (a link that answered but never started playing).
    static func resolve(_ req: PlayRequest, only: String? = nil, skipping: Set<String> = []) async -> StreamInfo? {
        let all: [(String, (PlayRequest) async -> StreamInfo?)] = [
            ("7Movies", SevenMovies.resolve),
            ("VixSrc", VixSrc.resolve),
        ]
        for (name, fn) in all where (only == nil || only == name) && !skipping.contains(name) {
            if let s = await fn(req) { return s }
            AppLog.i("Sources", "\(name) has nothing for \(req.label)")
        }
        return nil
    }
}
