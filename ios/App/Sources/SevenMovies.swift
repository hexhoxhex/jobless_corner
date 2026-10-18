import Foundation

/// 7Movies (7movies.ac) streams through the embed.vidrift.net API:
/// `GET /api/boot/<path>` gives a playback token, then
/// `GET /api/source/<path>?token=&provider=<server>` gives each server's
/// streams. Ported from the TV app's net/SevenMovies.kt.
///
/// Servers: Vela (1080p, names no language), Titan (720p, ONE stream with
/// several audio tracks — the player only hears the first, often a dub),
/// Orion (one stream per language: "Original", "Hindi", ... ; HEVC).
/// When Orion lists dubbed copies the title has dubs going round and Vela
/// can't be trusted (VisionQuest S1E1 played a dub from Vela on 2026-10-18),
/// so Orion's Original goes first.
enum SevenMovies {
    static let base = "https://embed.vidrift.net"
    static let streamHeaders = ["Referer": base + "/", "Origin": base]
    private static let tag = "SevenMovies"
    private static let apiHeaders = ["Referer": base + "/embed2/play", "Accept": "application/json"]

    private static var tokens: [String: (String, Date)] = [:]
    private static let lock = NSLock()

    static func path(_ tmdbId: Int, _ season: Int?, _ episode: Int?) -> String {
        if let s = season, let e = episode { return "tv/\(tmdbId)/\(s)/\(e)" }
        return "movie/\(tmdbId)"
    }

    private static func playbackToken(_ path: String) async -> String? {
        lock.lock()
        if let cached = tokens[path], Date().timeIntervalSince(cached.1) < 3600 { lock.unlock(); return cached.0 }
        lock.unlock()
        guard let url = URL(string: "\(base)/api/boot/\(path)"),
              let obj = try? await Net.json(url, headers: apiHeaders, timeout: 12) as? [String: Any],
              let meta = obj["meta"] as? [String: Any],
              let token = meta["playbackToken"] as? String, !token.isEmpty else {
            AppLog.w(tag, "\(path): no playback token")
            return nil
        }
        lock.lock(); tokens[path] = (token, Date()); lock.unlock()
        return token
    }

    private static func source(_ path: String, token: String, server: String) async -> [String: Any]? {
        let s = "\(base)/api/source/\(path)?token=\(token.urlEncoded)&provider=\(server)&hevc=1"
        guard let url = URL(string: s) else { return nil }
        do {
            let obj = try await Net.json(url, headers: apiHeaders, timeout: 12)
            return obj as? [String: Any]
        } catch {
            AppLog.i(tag, "\(path) \(server): \(error)")
            return nil
        }
    }

    private struct Entry { let url: URL; let name: String }

    private static func entries(_ data: [String: Any]?) -> [Entry] {
        guard let data, data["success"] as? Bool == true,
              let streams = data["streams"] as? [[String: Any]] else { return [] }
        return streams.compactMap { s in
            let raw = (s["url"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (s["proxyUrl"] as? String ?? "")
            guard !raw.isEmpty, let url = URL(string: raw.hasPrefix("/") ? base + raw : raw) else { return nil }
            return Entry(url: url, name: s["name"] as? String ?? "")
        }
    }

    /// A named language other than the original/English one.
    private static func isDub(_ name: String) -> Bool {
        let n = name.lowercased()
        if n.isEmpty { return false }
        return !(n.contains("original") || n.contains("english") || n.contains("multi"))
    }

    private static func language(_ name: String) -> String {
        let after = name.components(separatedBy: "·").last?.trimmingCharacters(in: .whitespaces) ?? ""
        return after.isEmpty ? "Original" : after
    }

    /// Does the playlist answer? (The server's own headers.)
    static func plays(_ url: URL) async -> Bool {
        guard let body = try? await Net.text(url, headers: streamHeaders, timeout: 10) else { return false }
        return body.hasPrefix("#EXTM3U")
    }

    static func resolve(_ req: PlayRequest) async -> StreamInfo? {
        let started = Date()
        let p = path(req.item.tmdbId, req.season, req.episode)
        guard let token = await playbackToken(p) else { return nil }
        async let velaData = source(p, token: token, server: "vela")
        async let titanData = source(p, token: token, server: "titan")
        async let orionData = source(p, token: token, server: "orion")
        let (vela, titan, orion) = await (velaData, titanData, orionData)

        let orionEntries = entries(orion)
        let dubs = orionEntries.map(\.name).filter(isDub).map(language)
        var order: [(server: String, entry: Entry)] = []
        let orionOriginal = orionEntries.filter { !isDub($0.name) }
        let velaEntries = entries(vela)
        // Vela lists its renditions highest first; start at 720p on a phone.
        let velaPick = velaEntries.count > 1 ? Array(velaEntries.dropFirst()) + [velaEntries[0]] : velaEntries
        if !dubs.isEmpty {
            AppLog.i(tag, "\(p): dubs exist (\(dubs.joined(separator: ", "))) — Orion's original first")
            order += orionOriginal.map { ("orion", $0) }
            order += velaPick.map { ("vela", $0) }
        } else {
            order += velaPick.map { ("vela", $0) }
            order += entries(titan).filter { !isDub($0.name) }.map { ("titan", $0) }
            order += orionOriginal.map { ("orion", $0) }
        }
        for (server, entry) in order {
            if await plays(entry.url) {
                let ms = Int(Date().timeIntervalSince(started) * 1000)
                AppLog.i(tag, "\(p) via \(server) (\(entry.name.isEmpty ? "unnamed" : entry.name)) in \(ms) ms")
                return StreamInfo(url: entry.url, headers: streamHeaders, provider: "7Movies",
                                  label: server == "vela" ? "HD" : "Auto",
                                  language: language(entry.name), isLive: false)
            }
            AppLog.w(tag, "\(p) \(server): the stream refused us")
        }
        AppLog.i(tag, "\(p): nothing plays")
        return nil
    }
}
