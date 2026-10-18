import Foundation
import Network

/// A tiny HTTP server inside the app that turns a live channel into a stream
/// Apple's player can play:
///
///   GET /live/<id>/index.m3u8     the channel's playlist, fetched with its
///                                 token, chunk addresses pointed back here
///   GET /live/<id>/seg/<b64>.ts   one chunk, fetched and unwrapped to MPEG-TS
///
///   GET /p/<set>/<b64>.<ext>      any HLS file of a film, fetched with the
///                                 headers its source wants (header set
///                                 <set>), playlists rewritten to point here
///
/// It listens on every interface, so with AirPlay the Apple TV can fetch the
/// same addresses from this device over the home Wi-Fi; the /p/ route is
/// for AirPlay too, since a receiver can't send a source's headers (TV app
/// equivalent: LiveStreamProxy).
final class LiveServer {
    static let shared = LiveServer()
    private let tag = "LiveServer"
    private let queue = DispatchQueue(label: "LiveServer", qos: .userInitiated)
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    private var playlists: [String: URL] = [:]
    private var headerSets: [[String: String]] = []
    private let lock = NSLock()

    /// Start (once) and return the port: 47110 when free, else any.
    func start() async throws -> UInt16 {
        if let l = listener, l.state == .ready { return port }
        if let p = try? await listen(on: NWEndpoint.Port(rawValue: 47110)!) { return p }
        return try await listen(on: .any)
    }

    private func listen(on requested: NWEndpoint.Port) async throws -> UInt16 {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let l = try NWListener(using: params, on: requested)
        l.newConnectionHandler = { [weak self] c in self?.serve(c) }
        listener = l
        return try await withCheckedThrowingContinuation { cont in
            var resumed = false
            l.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.port = l.port?.rawValue ?? 0
                    AppLog.i(self.tag, "listening on port \(self.port)")
                    if !resumed { resumed = true; cont.resume(returning: self.port) }
                case .failed(let e):
                    AppLog.e(self.tag, "listener on \(requested) failed: \(e)")
                    l.cancel()
                    self.listener = nil
                    if !resumed { resumed = true; cont.resume(throwing: e) }
                default: break
                }
            }
            l.start(queue: queue)
        }
    }

    /// The address the player opens for a channel: on the loopback address,
    /// or on this phone's Wi-Fi address when an AirPlay receiver will fetch it.
    func url(for channelId: String, external: Bool) async throws -> URL {
        let port = try await start()
        let source = try await LiveSource.playlist(for: channelId)
        lock.lock(); playlists[channelId] = source; lock.unlock()
        await warm(channelId, source)
        let host = external ? (Self.wifiAddress() ?? "127.0.0.1") : "127.0.0.1"
        return URL(string: "http://\(host):\(port)/live/\(channelId.urlEncoded)/index.m3u8")!
    }

    /// A channel nobody is watching takes the edge 12 s, sometimes over a
    /// minute, to start; ask for the playlist before the player does so its
    /// first request finds the channel running.
    private func warm(_ id: String, _ source: URL) async {
        let begun = Date()
        for attempt in 1...2 {
            let url = await LiveSource.tokenized(source)
            if let body = try? await Net.text(url, headers: LiveSource.headers, timeout: 35), body.hasPrefix("#EXTM3U") {
                let s = Int(Date().timeIntervalSince(begun))
                if s >= 5 { AppLog.i(tag, "ch=\(id) edge took \(s) s to start") }
                return
            }
            AppLog.i(tag, "ch=\(id) edge not ready (attempt \(attempt))")
        }
    }

    /// A film's stream through this phone, for an AirPlay receiver: the
    /// receiver asks here and this adds the headers the source wants.
    func proxied(_ url: URL, headers: [String: String]) async throws -> URL {
        let port = try await start()
        lock.lock()
        let set: Int
        if let i = headerSets.firstIndex(of: headers) {
            set = i
        } else {
            headerSets.append(headers)
            set = headerSets.count - 1
        }
        lock.unlock()
        let host = Self.wifiAddress() ?? "127.0.0.1"
        return URL(string: "http://\(host):\(port)" + Self.proxyPath(url, set: set))!
    }

    static func b64(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func unb64(_ s: String) -> String? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func proxyPath(_ url: URL, set: Int) -> String {
        let ext = url.pathExtension.isEmpty ? "bin" : url.pathExtension
        return "/p/\(set)/\(b64(url.absoluteString)).\(ext)"
    }

    // MARK: HTTP

    private func serve(_ c: NWConnection) {
        c.start(queue: queue)
        receiveHeader(c, buffer: Data())
    }

    private func receiveHeader(_ c: NWConnection, buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, done, error in
            guard let self else { return }
            var buf = buffer
            if let data { buf.append(data) }
            if let end = buf.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buf[..<end.lowerBound], as: UTF8.self)
                let lines = head.components(separatedBy: "\r\n")
                let parts = (lines.first ?? "").split(separator: " ")
                let path = parts.count >= 2 ? String(parts[1]) : "/"
                let range = lines.dropFirst().first { $0.lowercased().hasPrefix("range:") }
                    .map { String($0.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
                Task { await self.respond(c, path: path, range: range) }
            } else if done || error != nil || buf.count > 64 * 1024 {
                c.cancel()
            } else {
                self.receiveHeader(c, buffer: buf)
            }
        }
    }

    private func send(_ c: NWConnection, status: Int, type: String, body: Data, extra: [String: String] = [:]) {
        let reasons = [200: "OK", 206: "Partial Content", 404: "Not Found", 416: "Range Not Satisfiable"]
        var head = "HTTP/1.1 \(status) \(reasons[status] ?? "Bad Gateway")\r\n"
        head += "Content-Type: \(type)\r\nContent-Length: \(body.count)\r\n"
        for (k, v) in extra { head += "\(k): \(v)\r\n" }
        head += "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        var out = Data(head.utf8)
        out.append(body)
        c.send(content: out, completion: .contentProcessed { _ in c.cancel() })
    }

    private func respond(_ c: NWConnection, path: String, range: String?) async {
        let parts = path.split(separator: "?").first.map(String.init)?
            .split(separator: "/").map(String.init) ?? []
        // ["p", set, "<b64>.<ext>"]
        if parts.count == 3, parts[0] == "p", let set = Int(parts[1]) {
            let name = parts[2]
            let b64 = name.lastIndex(of: ".").map { String(name[..<$0]) } ?? name
            await serveProxied(c, set: set, encoded: b64, range: range)
            return
        }
        // ["live", id, "index.m3u8"] or ["live", id, "seg", "<b64>.ts"]
        guard parts.count >= 3, parts[0] == "live" else {
            send(c, status: 404, type: "text/plain", body: Data("not found".utf8)); return
        }
        let id = parts[1].removingPercentEncoding ?? parts[1]
        if parts[2] == "index.m3u8" {
            await servePlaylist(c, id: id)
        } else if parts.count >= 4, parts[2] == "seg" {
            let b64 = String(parts[3].dropLast(3))     // ".ts"
            await serveSegment(c, id: id, encoded: b64)
        } else {
            send(c, status: 404, type: "text/plain", body: Data("not found".utf8))
        }
    }

    private func servePlaylist(_ c: NWConnection, id: String) async {
        lock.lock(); var source = playlists[id]; lock.unlock()
        if source == nil {
            source = try? await LiveSource.playlist(for: id)
            if let s = source { lock.lock(); playlists[id] = s; lock.unlock() }
        }
        guard let src = source else {
            send(c, status: 502, type: "text/plain", body: Data("no stream".utf8)); return
        }
        do {
            let url = await LiveSource.tokenized(src)
            // A channel nobody is watching takes the edge ~12 s to start.
            let body = try await Net.text(url, headers: LiveSource.headers, timeout: 30)
            guard body.hasPrefix("#EXTM3U") else { throw URLError(.cannotParseResponse) }
            send(c, status: 200, type: "application/vnd.apple.mpegurl", body: Data(rewrite(body, base: src).utf8))
        } catch {
            AppLog.w(tag, "ch=\(id) playlist: \(error)")
            // Forget the address: the next request finds the stream again.
            lock.lock(); playlists[id] = nil; lock.unlock()
            LiveSource.forget(id)
            send(c, status: 502, type: "text/plain", body: Data("playlist failed".utf8))
        }
    }

    /// Point every chunk at this server; keep everything else.
    func rewrite(_ body: String, base: URL) -> String {
        body.components(separatedBy: "\n").map { raw in
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty, !line.hasPrefix("#"),
                  let abs = URL(string: line, relativeTo: base)?.absoluteURL else { return line }
            return "seg/\(Self.b64(abs.absoluteString)).ts"
        }.joined(separator: "\n")
    }

    private static let uriAttribute = try! NSRegularExpression(pattern: #"URI="([^"]+)""#)

    /// A film playlist with every address in it (file lines, and URI="..."
    /// attributes: audio and subtitle playlists, keys, init segments)
    /// pointed back at this server.
    static func rewriteProxied(_ body: String, base: URL, set: Int) -> String {
        func mapped(_ ref: String) -> String {
            guard let abs = URL(string: ref, relativeTo: base)?.absoluteURL else { return ref }
            return proxyPath(abs, set: set)
        }
        return body.components(separatedBy: "\n").map { raw -> String in
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { return line }
            if !line.hasPrefix("#") { return mapped(line) }
            guard line.contains("URI=\"") else { return line }
            let ns = line as NSString
            var out = ""
            var last = 0
            for m in uriAttribute.matches(in: line, range: NSRange(location: 0, length: ns.length)) {
                let r = m.range(at: 1)
                out += ns.substring(with: NSRange(location: last, length: r.location - last))
                out += mapped(ns.substring(with: r))
                last = r.location + r.length
            }
            out += ns.substring(from: last)
            return out
        }.joined(separator: "\n")
    }

    private func serveProxied(_ c: NWConnection, set: Int, encoded: String, range: String?) async {
        lock.lock()
        let headers = set < headerSets.count ? headerSets[set] : nil
        lock.unlock()
        guard let headers, let str = Self.unb64(encoded), let url = URL(string: str) else {
            send(c, status: 404, type: "text/plain", body: Data("not found".utf8)); return
        }
        var h = headers
        if let range { h["Range"] = range }
        do {
            let (data, resp) = try await Net.get(url, headers: h, timeout: 20)
            if data.starts(with: Data("#EXTM3U".utf8)) {
                let body = Self.rewriteProxied(String(decoding: data, as: UTF8.self), base: resp.url ?? url, set: set)
                send(c, status: 200, type: "application/vnd.apple.mpegurl", body: Data(body.utf8))
                return
            }
            var extra = ["Accept-Ranges": "bytes"]
            if let cr = resp.value(forHTTPHeaderField: "Content-Range") { extra["Content-Range"] = cr }
            send(c, status: resp.statusCode == 206 ? 206 : 200,
                 type: resp.mimeType ?? "application/octet-stream", body: data, extra: extra)
        } catch {
            AppLog.w(tag, "proxied \(url.host ?? "?"): \(error)")
            let status = (error as? Net.HTTPError)?.status == 416 ? 416 : 502
            send(c, status: status, type: "text/plain", body: Data("fetch failed".utf8))
        }
    }

    private func serveSegment(_ c: NWConnection, id: String, encoded: String) async {
        guard let str = Self.unb64(encoded), let url = URL(string: str) else {
            send(c, status: 404, type: "text/plain", body: Data("bad chunk".utf8)); return
        }
        let started = Date()
        for attempt in 1...2 {
            do {
                let (chunk, _) = try await Net.get(url, headers: LiveSource.headers, timeout: attempt == 1 ? 12 : 10)
                guard let ts = Unwrap.transportStream(chunk) else {
                    AppLog.w(tag, "ch=\(id) chunk \(chunk.count) bytes held no stream")
                    send(c, status: 502, type: "text/plain", body: Data("no stream".utf8)); return
                }
                let ms = Int(Date().timeIntervalSince(started) * 1000)
                if ms > 5000 { AppLog.i(tag, "ch=\(id) slow chunk: \(ms) ms") }
                send(c, status: 200, type: "video/mp2t", body: ts)
                return
            } catch {
                AppLog.w(tag, "ch=\(id) chunk attempt \(attempt): \(error)")
            }
        }
        send(c, status: 502, type: "text/plain", body: Data("chunk failed".utf8))
    }

    // MARK: address

    /// This device's Wi-Fi IPv4 address, so an AirPlay receiver can reach us.
    static func wifiAddress() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var found: String?
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name == "en0" else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                           nil, 0, NI_NUMERICHOST) == 0 {
                found = String(cString: host)
            }
        }
        return found
    }
}
