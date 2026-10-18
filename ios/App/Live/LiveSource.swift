import Foundation

/// Finds a live channel's playlist and keeps its token, the way the site's
/// own player does (TV app: LiveResolver + LiveStreamProxy, 2026-10-18).
///
/// `<wrapper>/stream/stream-<id>.php` frames an embed page
/// (`https://dembed.st/premiumtv/daddy.php?id=<id>` today) which declares
/// `const EDGE = "https://edge..."` and `const CHANNEL = "premium<id>"`.
/// The playlist is `<EDGE>/<CHANNEL>/index.m3u8`, and since 2026-10-18 the
/// edge wants `t=<token>` on it, from `<EDGE>/api/playlist-token?slug=<CHANNEL>`
/// (`{"token": ..., "ttl": 120}`, bound to the viewer's address).
///
/// The wrapper page is 644 KB trickled out at ~13 KB/s with the frame at the
/// very end (15 s and more), so the embed page is asked for directly first,
/// on the host the last wrapper page named, and each channel's playlist is
/// remembered so a reconnect fetches no page at all.
enum LiveSource {
    private static let tag = "Live"
    static let headers = ["Origin": "https://dlhd.st", "Referer": "https://dlhd.st/"]

    private static var wrapperBase: String?
    private static var tokens: [String: (value: String, renewAt: Date, validUntil: Date)] = [:]
    private static var found: [String: (url: URL, at: Date)] = [:]
    private static let foundFor: TimeInterval = 6 * 3600
    private static let lock = NSLock()

    private static let embedHostKey = "live.embedHost"
    private static var embedHost: String {
        get { UserDefaults.standard.string(forKey: embedHostKey) ?? "dembed.st" }
        set { UserDefaults.standard.set(newValue, forKey: embedHostKey) }
    }

    /// The wrapper site moves between domains; follow the redirect once.
    private static func wrapper() async -> String {
        if let b = wrapperBase { return b }
        var base = "https://dlive.sx"
        if let url = URL(string: "https://dlhd.st/"),
           let result = try? await Net.get(url, timeout: 15),
           let final = result.1.url, let host = final.host {
            base = "https://" + host
        }
        wrapperBase = base
        return base
    }

    /// The channel's playlist (without a token).
    static func playlist(for id: String) async throws -> URL {
        lock.lock()
        let known = found[id]
        lock.unlock()
        if let k = known, Date().timeIntervalSince(k.at) < foundFor { return k.url }
        let url: URL
        if let direct = URL(string: "https://\(embedHost)/premiumtv/daddy.php?id=\(id.urlEncoded)"),
           let u = try? await fromEmbed(direct, referer: (wrapperBase ?? "https://dlive.sx") + "/", id: id) {
            url = u
        } else {
            url = try await fromWrapper(id)
        }
        lock.lock(); found[id] = (url, Date()); lock.unlock()
        return url
    }

    /// Stop trusting a channel's remembered playlist (it stopped answering).
    static func forget(_ id: String) {
        lock.lock(); found[id] = nil; lock.unlock()
    }

    private static func fromWrapper(_ id: String) async throws -> URL {
        let base = await wrapper()
        guard let wrapperURL = URL(string: "\(base)/stream/stream-\(id).php") else { throw URLError(.badURL) }
        let page = try await Net.text(wrapperURL, headers: ["Referer": "\(base)/watch.php?id=\(id)"], timeout: 30)
        let frames = page.allMatches(#"<iframe[^>]+src="([^"]+)""#).map { $0[1] }
            .filter { !$0.contains("dlhd.") && !$0.contains("dlive.") }
        guard let frame = frames.first, let frameURL = URL(string: frame) else {
            AppLog.w(tag, "ch=\(id): no player frame on the wrapper page")
            throw URLError(.cannotParseResponse)
        }
        if let host = frameURL.host, frameURL.path.contains("/premiumtv/"), host != embedHost {
            AppLog.i(tag, "player host is now \(host)")
            embedHost = host
        }
        return try await fromEmbed(frameURL, referer: wrapperURL.absoluteString, id: id)
    }

    private static func fromEmbed(_ frameURL: URL, referer: String, id: String) async throws -> URL {
        let embed = try await Net.text(frameURL, headers: ["Referer": referer], timeout: 30)
        if let edge = embed.firstMatch(#"const\s+EDGE\s*=\s*"([^"]+)""#),
           let channel = embed.firstMatch(#"const\s+CHANNEL\s*=\s*"([^"]+)""#),
           let url = URL(string: "\(edge)/\(channel)/index.m3u8") {
            AppLog.i(tag, "ch=\(id) -> \(url.host ?? "?") \(channel)")
            return url
        }
        let unescaped = embed.replacingOccurrences(of: "\\/", with: "/")
        if let m3u8 = unescaped.firstMatch(#"(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"#), let url = URL(string: m3u8) {
            AppLog.i(tag, "ch=\(id) -> \(url.host ?? "?") (literal)")
            return url
        }
        AppLog.w(tag, "ch=\(id): no stream on the embed page")
        throw URLError(.cannotParseResponse)
    }

    /// `url` with the edge's playlist token, when it hands one out.
    static func tokenized(_ url: URL) async -> URL {
        guard let host = url.host,
              let slug = url.pathComponents.dropFirst().first, !slug.isEmpty else { return url }
        let key = host + "|" + slug
        let now = Date()
        lock.lock()
        let cached = tokens[key]
        lock.unlock()
        var token: String? = cached.flatMap { now < $0.renewAt ? $0.value : nil }
        if token == nil, let tokenURL = URL(string: "https://\(host)/api/playlist-token?slug=\(slug.urlEncoded)") {
            if let obj = try? await Net.json(tokenURL, headers: headers, timeout: 15) as? [String: Any],
               let value = obj["token"] as? String, !value.isEmpty {
                let ttl = max(20, (obj["ttl"] as? Double) ?? 120)
                lock.lock()
                tokens[key] = (value, now.addingTimeInterval(ttl * 0.5), now.addingTimeInterval(ttl * 0.9))
                lock.unlock()
                token = value
            } else if let c = cached, now < c.validUntil {
                token = c.value
            }
        }
        guard let t = token, var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        var items = (comps.queryItems ?? []).filter { $0.name != "t" && $0.name != "_" }
        items.append(URLQueryItem(name: "t", value: t))
        comps.queryItems = items
        return comps.url ?? url
    }
}

/// The published channel list and match schedule (mkurugenzi_viewer).
enum LiveCatalog {
    static func channels() async throws -> [LiveChannel] {
        let url = URL(string: Config.dataBase + "channels.json")!
        let arr = try await Net.json(url, timeout: 30) as? [[String: Any]] ?? []
        return arr.compactMap { c in
            guard let id = c["id"] as? String, let name = c["name"] as? String else { return nil }
            if let status = c["status"] as? String, status != "ok" { return nil }
            return LiveChannel(id: id, name: name,
                               logo: (c["logo"] as? String).flatMap(URL.init(string:)),
                               group: (c["group"] as? String) ?? "Other")
        }
    }

    static func schedule() async throws -> [ScheduleEvent] {
        let url = URL(string: Config.dataBase + "schedule.json")!
        let arr = try await Net.json(url, timeout: 30) as? [[String: Any]] ?? []
        return arr.compactMap { e in
            guard let title = e["title"] as? String else { return nil }
            let chans = (e["channels"] as? [[String: Any]] ?? []).compactMap { ch -> ScheduleChannel? in
                guard let id = ch["id"] as? String else { return nil }
                return ScheduleChannel(id: id, name: ch["name"] as? String ?? id)
            }
            let start = (e["start_unix"] as? Double).map { Date(timeIntervalSince1970: $0) }
            return ScheduleEvent(title: cleanTitle(title), category: sport(e["category"] as? String ?? ""),
                                 start: start, channels: chans)
        }
        .sorted { ($0.start ?? .distantFuture) < ($1.start ?? .distantFuture) }
    }

    private static func isPictograph(_ u: Unicode.Scalar) -> Bool {
        u.properties.isEmojiPresentation || (0x1F000...0x1FAFF).contains(u.value) ||
            (0x2600...0x27BF).contains(u.value) || (0xFE00...0xFE0F).contains(u.value) ||
            u.value == 0x200D
    }

    /// The schedule's text sometimes arrives with its emoji decoded the
    /// wrong way ("ðŸŽ¾" for a tennis ball: UTF-8 bytes read as Windows-1252).
    /// Undo that, or failing that drop the broken sequences.
    static func repairMojibake(_ s: String) -> String {
        guard s.contains("\u{00F0}\u{0178}") || s.contains("\u{00E2}\u{20AC}") else { return s }
        if let bytes = s.data(using: .windowsCP1252), let fixed = String(data: bytes, encoding: .utf8) { return fixed }
        return s.replacingOccurrences(of: "\u{00F0}\u{0178}..", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\u{00E2}\u{20AC}.", with: "", options: .regularExpression)
    }

    /// A schedule title without flags, emoji or doubled spaces.
    static func cleanTitle(_ s: String) -> String {
        let kept = repairMojibake(s).unicodeScalars.filter { !isPictograph($0) }
        return String(String.UnicodeScalarView(kept))
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    /// The sport from a schedule category: "Tennis <ball> ATP - Singles: Lyon
    /// (France)" is "Tennis"; "Am. Football (NFL" is "Am. Football (NFL)".
    static func sport(_ category: String) -> String {
        let repaired = repairMojibake(category)
        let head = String(String.UnicodeScalarView(repaired.unicodeScalars.prefix { !isPictograph($0) }))
        var out = cleanTitle(head)
        if out.filter({ $0 == "(" }).count > out.filter({ $0 == ")" }).count { out += ")" }
        return out
    }
}
