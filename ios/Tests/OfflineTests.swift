import Compression
import XCTest
@testable import VijanaBaruBaru

/// Tests that need no network: unwrapping disguised live chunks, playlist
/// rewriting, log cleaning.
final class OfflineTests: XCTestCase {

    // MARK: a disguised chunk, built the way the live CDN builds them

    /// 40 MPEG-TS packets with a recognisable payload.
    private func transportStream() -> [UInt8] {
        var ts = [UInt8]()
        for i in 0..<40 {
            var p = [UInt8](repeating: UInt8(truncatingIfNeeded: i * 7), count: 188)
            p[0] = 0x47
            p[1] = 0x40; p[2] = 0x11; p[3] = UInt8(0x10 | (i & 0x0F))
            ts += p
        }
        return ts
    }

    private func deflate(_ src: [UInt8]) -> [UInt8] {
        var dst = [UInt8](repeating: 0, count: src.count + 1024)
        let n = dst.withUnsafeMutableBufferPointer { d in
            src.withUnsafeBufferPointer { s in
                compression_encode_buffer(d.baseAddress!, d.count, s.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        return Array(dst.prefix(n))
    }

    private func be32(_ v: Int) -> [UInt8] { [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
    private func le32(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 24 & 0xFF)] }

    private func adler32(_ b: [UInt8]) -> Int {
        var a = 1, s = 0
        for x in b { a = (a + Int(x)) % 65521; s = (s + a) % 65521 }
        return s << 16 | a
    }

    /// gzip member (the CRC isn't checked by the decoder, as in the site's player).
    private func gzip(_ raw: [UInt8]) -> [UInt8] {
        [0x1f, 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 0xff] + deflate(raw) + [0, 0, 0, 0] + le32(raw.count)
    }

    private func chunk(_ type: String, _ data: [UInt8]) -> [UInt8] {
        be32(data.count) + Array(type.utf8) + data + [0, 0, 0, 0]
    }

    private func paeth(_ a: Int, _ b: Int, _ c: Int) -> Int {
        let p = a + b - c
        let pa = abs(p - a), pb = abs(p - b), pc = abs(p - c)
        if pa <= pb && pa <= pc { return a }
        return pb <= pc ? b : c
    }

    /// An RGB PNG whose pixels are `payload`, each row using a different
    /// filter so every unfilter path is exercised.
    private func png(_ payload: [UInt8], width: Int) -> [UInt8] {
        let row = width * 3
        let height = (payload.count + row - 1) / row
        let px = payload + [UInt8](repeating: 0, count: height * row - payload.count)
        var filtered = [UInt8]()
        var prev = [UInt8](repeating: 0, count: row)
        for r in 0..<height {
            let cur = Array(px[(r * row)..<((r + 1) * row)])
            let f = r % 5
            filtered.append(UInt8(f))
            for x in 0..<row {
                let a = x >= 3 ? Int(cur[x - 3]) : 0
                let b = Int(prev[x])
                let c = x >= 3 ? Int(prev[x - 3]) : 0
                let pred: Int
                switch f {
                case 1: pred = a
                case 2: pred = b
                case 3: pred = (a + b) >> 1
                case 4: pred = paeth(a, b, c)
                default: pred = 0
                }
                filtered.append(UInt8(truncatingIfNeeded: Int(cur[x]) - pred))
            }
            prev = cur
        }
        let idat = [0x78, 0x9C] + deflate(filtered) + be32(adler32(filtered))
        let ihdr = be32(width) + be32(height) + [8, 2, 0, 0, 0]
        return [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
            + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", [])
    }

    func testDisguisedChunkUnwrapsToTheStream() throws {
        let ts = transportStream()
        let gz = gzip(ts)
        let payload = [UInt8](repeating: 0xAB, count: 8) + be32(gz.count) + gz
        let image = png(payload, width: 64)
        XCTAssertTrue(Unwrap.isPNG(Data(image)))
        let out = try XCTUnwrap(Unwrap.transportStream(Data(image)))
        XCTAssertEqual([UInt8](out), ts)
    }

    func testPlainStreamKeepsWholePacketsOnly() throws {
        let ts = transportStream()
        let junk: [UInt8] = [1, 2, 3, 4, 5]
        let out = try XCTUnwrap(Unwrap.transportStream(Data(junk + ts + [9, 9])))
        XCTAssertEqual([UInt8](out), ts)
    }

    func testNotAStreamIsNil() {
        XCTAssertNil(Unwrap.transportStream(Data("<html>blocked</html>".utf8)))
    }

    // MARK: playlists

    func testLivePlaylistPointsChunksHere() throws {
        let base = URL(string: "https://edge.example/premium51/index.m3u8?t=abc")!
        let body = """
        #EXTM3U
        #EXT-X-TARGETDURATION:6
        #EXTINF:6.0,
        https://cdn.example/a/1.png?x=1
        #EXTINF:6.0,
        seg2.png
        """
        let out = LiveServer.shared.rewrite(body, base: base).components(separatedBy: "\n")
        XCTAssertEqual(out[0], "#EXTM3U")
        XCTAssertEqual(out[2], "#EXTINF:6.0,")
        let first = try XCTUnwrap(out[3].firstMatch(#"^seg/([A-Za-z0-9_-]+)\.ts$"#))
        XCTAssertEqual(LiveServer.unb64(first), "https://cdn.example/a/1.png?x=1")
        let second = try XCTUnwrap(out[5].firstMatch(#"^seg/([A-Za-z0-9_-]+)\.ts$"#))
        XCTAssertEqual(LiveServer.unb64(second), "https://edge.example/premium51/seg2.png")
    }

    func testFilmPlaylistPointsEveryAddressHere() throws {
        let base = URL(string: "https://cdn.example/hls/master.m3u8")!
        let body = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Original",URI="audio/orig.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="a"
        720/index.m3u8
        #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1"
        """
        let out = LiveServer.rewriteProxied(body, base: base, set: 3).components(separatedBy: "\n")
        func decoded(_ path: String) -> String? {
            path.firstMatch(#"/p/3/([A-Za-z0-9_-]+)\.[a-z0-9]+"#).flatMap(LiveServer.unb64)
        }
        XCTAssertTrue(out[1].hasPrefix("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Original\",URI=\"/p/3/"))
        XCTAssertEqual(decoded(out[1]), "https://cdn.example/hls/audio/orig.m3u8")
        XCTAssertEqual(out[2], "#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"a\"")
        XCTAssertEqual(decoded(out[3]), "https://cdn.example/hls/720/index.m3u8")
        XCTAssertEqual(decoded(out[4]), "https://keys.example/k1")
    }

    // MARK: text

    func testLogHidesLinksTokensAndAddresses() {
        let line = "GET https://edge.example/premium51/index.m3u8?t=SECRET from 192.168.1.20 token=abc123 Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
        let out = AppLog.clean(line)
        XCTAssertFalse(out.contains("SECRET"))
        XCTAssertFalse(out.contains("premium51"))
        XCTAssertFalse(out.contains("192.168.1.20"))
        XCTAssertFalse(out.contains("abc123"))
        XCTAssertFalse(out.contains("eyJhbGci"))
        XCTAssertTrue(out.contains("https://edge.example/"))
    }

    func testScheduleTitlesLoseFlagsAndEmoji() {
        XCTAssertEqual(LiveCatalog.cleanTitle("\u{1F1EC}\u{1F1E7} Arsenal vs Chelsea \u{26BD}"), "Arsenal vs Chelsea")
    }

    func testChannelNamesReadWell() {
        let c = LiveChannel(id: "1", name: "tnt sports 1 uk", logo: nil, group: "UK")
        XCTAssertEqual(c.displayName, "TNT Sports 1 UK")
    }
}
