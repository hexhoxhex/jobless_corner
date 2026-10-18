import Foundation

/// The app's own log, kept so a viewer can send it when something breaks
/// (Settings > App logs > Share). Lines are cleaned before they are stored:
/// links are cut to their host, and tokens, cookies, keys, IP addresses and
/// e-mail addresses are blanked — the same rules as the TV app's AppLog.
enum AppLog {
    private static let queue = DispatchQueue(label: "AppLog")
    private static var lines: [String] = []
    private static let maxLines = 4000
    private static let fileLimit = 1_000_000

    private static var dir: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private static var current: URL { dir.appendingPathComponent("app.log") }
    private static var previous: URL { dir.appendingPathComponent("app.1.log") }

    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    static func i(_ tag: String, _ message: String) { write("I", tag, message) }
    static func w(_ tag: String, _ message: String) { write("W", tag, message) }
    static func e(_ tag: String, _ message: String) { write("E", tag, message) }

    private static func write(_ level: String, _ tag: String, _ message: String) {
        let line = "\(stamp.string(from: Date())) \(level)/\(tag): \(clean(message))"
        #if DEBUG
        print(line)
        #endif
        queue.async {
            lines.append(line)
            if lines.count > maxLines { lines.removeFirst(lines.count - maxLines) }
            append(line + "\n")
        }
    }

    private static func append(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        let fm = FileManager.default
        if let size = (try? fm.attributesOfItem(atPath: current.path))?[.size] as? Int, size > fileLimit {
            try? fm.removeItem(at: previous)
            try? fm.moveItem(at: current, to: previous)
        }
        if let h = try? FileHandle(forWritingTo: current) {
            h.seekToEndOfFile()
            h.write(data)
            try? h.close()
        } else {
            try? data.write(to: current)
        }
    }

    /// The most recent lines; with `problemsOnly`, warnings, errors and lines
    /// that name a failure.
    static func recent(_ n: Int, problemsOnly: Bool) -> [String] {
        queue.sync {
            let picked = problemsOnly ? lines.filter(isProblem) : lines
            return Array(picked.suffix(n))
        }
    }

    private static let problemWords = ["fail", "error", "stall", "freez", "broken", "timeout",
                                       "timed out", "not available", "no source", "giving up",
                                       "refus", "rebuffer", "403", "404", "500", "502", "503"]

    private static func isProblem(_ line: String) -> Bool {
        if line.contains(" W/") || line.contains(" E/") { return true }
        let msg = line.lowercased()
        return problemWords.contains { msg.contains($0) }
    }

    /// The whole log as a text file for sharing.
    static func exportFile() -> URL {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        var text = "Vijana BaruBaru log (iOS)\nMade: \(Date())\nApp: v\(version)\n"
        text += "Device: \(DeviceInfo.model), iOS \(DeviceInfo.systemVersion)\n"
        text += "Links, tokens, cookies, keys and IP addresses are removed.\n\n"
        queue.sync {
            let saved = [previous, current].compactMap { try? String(contentsOf: $0, encoding: .utf8) }
            text += saved.isEmpty ? lines.joined(separator: "\n") : saved.joined()
        }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd-HHmm"
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("vijanabarubaru-ios-log-v\(version)-\(f.string(from: Date())).txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    // MARK: cleaning

    private static let rules: [(NSRegularExpression, String)] = [
        (#"\b(https?)://([^/\s"'<>?#]+)[^\s"'<>]*"#, "$1://$2/…"),
        (#"eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}"#, "<token>"),
        (#"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{6,}"#, "Bearer <token>"),
        (#"(?i)\b(token|playbacktoken|t|auth|authorization|cookie|password|secret|signature|sig|key|apikey|api_key|session)(\s*[=:]\s*)("[^"]*"|[^\s,;&"'\]})]+)"#, "$1$2<removed>"),
        (#"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"#, "<email>"),
        (#"\b(?:\d{1,3}\.){3}\d{1,3}\b"#, "<ip>"),
    ].compactMap { pattern, template in
        (try? NSRegularExpression(pattern: pattern)).map { ($0, template) }
    }

    static func clean(_ s: String) -> String {
        var out = s
        for (re, template) in rules {
            let range = NSRange(out.startIndex..., in: out)
            out = re.stringByReplacingMatches(in: out, range: range, withTemplate: template)
        }
        return out
    }
}
