import Foundation
import UIKit

/// Shared network helpers. One URLSession with sane timeouts; every request
/// says what it is for in the log when it fails.
enum Net {
    static let desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 60
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpMaximumConnectionsPerHost = 6
        return URLSession(configuration: c)
    }()

    struct HTTPError: Error, CustomStringConvertible {
        let status: Int
        let url: URL
        let bodyPreview: String
        var description: String { "HTTP \(status) from \(url.host ?? "?"): \(bodyPreview)" }
    }

    /// GET returning the body; throws on a non-2xx answer.
    static func get(_ url: URL, headers: [String: String] = [:],
                    timeout: TimeInterval = 20) async throws -> (Data, HTTPURLResponse) {
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.setValue(desktopUA, forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard (200..<300).contains(http.statusCode) else {
            let preview = String(data: data.prefix(160), encoding: .utf8) ?? ""
            throw HTTPError(status: http.statusCode, url: url, bodyPreview: preview)
        }
        return (data, http)
    }

    static func text(_ url: URL, headers: [String: String] = [:], timeout: TimeInterval = 20) async throws -> String {
        let (data, _) = try await get(url, headers: headers, timeout: timeout)
        return String(decoding: data, as: UTF8.self)
    }

    static func json(_ url: URL, headers: [String: String] = [:], timeout: TimeInterval = 20) async throws -> Any {
        let (data, _) = try await get(url, headers: headers, timeout: timeout)
        return try JSONSerialization.jsonObject(with: data)
    }
}

enum Config {
    /// TMDB v4 read token, put into Info.plist by the build (see project.yml).
    static var tmdbToken: String {
        (Bundle.main.object(forInfoDictionaryKey: "TMDBToken") as? String)?
            .trimmingCharacters(in: .whitespaces) ?? ""
    }
    static let appRepo = "hexhoxhex/jobless_corner"
    static let dataRepo = "hexhoxhex/mkurugenzi_viewer"
    static let dataBase = "https://raw.githubusercontent.com/hexhoxhex/mkurugenzi_viewer/main/data/"
}

enum DeviceInfo {
    static var model: String { UIDevice.current.model }
    static var systemVersion: String { UIDevice.current.systemVersion }
}

extension String {
    /// First capture group of `pattern`, or nil.
    func firstMatch(_ pattern: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(startIndex..., in: self)
        guard let m = re.firstMatch(in: self, range: range) else { return nil }
        let r = m.numberOfRanges > 1 ? m.range(at: 1) : m.range
        guard let sr = Range(r, in: self) else { return nil }
        return String(self[sr])
    }

    /// Every match of `pattern`, as arrays of capture groups.
    func allMatches(_ pattern: String) -> [[String]] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        let range = NSRange(startIndex..., in: self)
        return re.matches(in: self, range: range).map { m in
            (0..<m.numberOfRanges).map { i in
                Range(m.range(at: i), in: self).map { String(self[$0]) } ?? ""
            }
        }
    }

    var urlEncoded: String {
        addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? self
    }
}
