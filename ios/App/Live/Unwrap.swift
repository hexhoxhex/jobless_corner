import Compression
import Foundation

/// Live chunks arrive disguised as PNG images on an image CDN: the picture's
/// pixel bytes are an 8-byte marker, a 4-byte length, then a gzip of the real
/// MPEG-TS. The site's player decodes them in JavaScript (pngRGB ->
/// unwrapPixels -> gunzip); this does the same (TV app: DisguisedSegment.kt).
enum Unwrap {
    private static let sync: UInt8 = 0x47
    private static let packet = 188
    private static let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    static func isPNG(_ d: Data) -> Bool { d.count >= 8 && Array(d.prefix(8)) == pngSignature }

    /// MPEG-TS from a chunk: unwrapped when it is a disguised PNG, cleaned of
    /// any bytes between packets otherwise. Nil when it holds no stream.
    static func transportStream(_ chunk: Data) -> Data? {
        if !isPNG(chunk) {
            let ts = resync([UInt8](chunk))
            return ts.count >= packet * 20 ? Data(ts) : nil
        }
        guard let pixels = pngPixels([UInt8](chunk)) else { return nil }
        guard let gz = gzipSlice(pixels), let raw = gunzip(gz) else {
            let ts = resync(pixels)
            return ts.count >= packet * 20 ? Data(ts) : nil
        }
        let ts = resync(raw)
        return ts.count >= packet * 20 ? Data(ts) : nil
    }

    // MARK: PNG

    private static func u32(_ b: [UInt8], _ o: Int) -> Int {
        Int(b[o]) << 24 | Int(b[o + 1]) << 16 | Int(b[o + 2]) << 8 | Int(b[o + 3])
    }

    /// The image's pixel bytes (RGB; alpha dropped), rows unfiltered.
    static func pngPixels(_ png: [UInt8]) -> [UInt8]? {
        var width = 0, height = 0, depth = 0, colour = 0
        var idat: [UInt8] = []
        var i = 8
        while i + 12 <= png.count {
            let len = u32(png, i)
            guard len >= 0, i + 12 + len <= png.count else { break }
            let type = String(bytes: png[(i + 4)..<(i + 8)], encoding: .ascii) ?? ""
            if type == "IHDR" {
                width = u32(png, i + 8); height = u32(png, i + 12)
                depth = Int(png[i + 16]); colour = Int(png[i + 17])
            } else if type == "IDAT" {
                idat.append(contentsOf: png[(i + 8)..<(i + 8 + len)])
            } else if type == "IEND" {
                break
            }
            i += 12 + len
        }
        guard width > 0, height > 0, depth == 8, idat.count > 2 else { return nil }
        let bpp: Int
        switch colour {
        case 0, 3: bpp = 1
        case 2: bpp = 3
        case 4: bpp = 2
        case 6: bpp = 4
        default: return nil
        }
        let row = width * bpp
        let stride = row + 1
        let expected = stride * height
        guard expected <= 64 * 1024 * 1024 else { return nil }
        // IDAT is a zlib stream: 2-byte header, then raw DEFLATE.
        guard let raw = inflate(Array(idat.dropFirst(2)), capacity: expected), raw.count >= stride else { return nil }
        let rows = raw.count / stride
        var out = [UInt8](repeating: 0, count: rows * row)
        var prev = [UInt8](repeating: 0, count: row)
        var cur = [UInt8](repeating: 0, count: row)
        for r in 0..<rows {
            let filter = raw[r * stride]
            let src = r * stride + 1
            for x in 0..<row {
                let v = Int(raw[src + x])
                let a = x >= bpp ? Int(cur[x - bpp]) : 0
                let b = Int(prev[x])
                let c = x >= bpp ? Int(prev[x - bpp]) : 0
                let value: Int
                switch filter {
                case 0: value = v
                case 1: value = v + a
                case 2: value = v + b
                case 3: value = v + ((a + b) >> 1)
                case 4: value = v + paeth(a, b, c)
                default: return nil
                }
                cur[x] = UInt8(truncatingIfNeeded: value)
            }
            out.replaceSubrange((r * row)..<((r + 1) * row), with: cur)
            swap(&prev, &cur)
        }
        if colour == 6 {
            // RGBA -> RGB, as the site's decoder does.
            var rgb = [UInt8]()
            rgb.reserveCapacity(out.count / 4 * 3)
            var k = 0
            while k + 3 < out.count { rgb.append(out[k]); rgb.append(out[k + 1]); rgb.append(out[k + 2]); k += 4 }
            return rgb
        }
        return out
    }

    private static func paeth(_ a: Int, _ b: Int, _ c: Int) -> Int {
        let p = a + b - c
        let pa = abs(p - a), pb = abs(p - b), pc = abs(p - c)
        if pa <= pb && pa <= pc { return a }
        return pb <= pc ? b : c
    }

    // MARK: gzip

    /// The gzip member inside the pixels: after the 8-byte marker and the
    /// 4-byte big-endian length when they are there, else found by its magic.
    static func gzipSlice(_ px: [UInt8]) -> [UInt8]? {
        let limit = min(64, px.count - 10)
        guard limit > 0 else { return nil }
        for s in 0..<limit where px[s] == 0x1f && px[s + 1] == 0x8b && px[s + 2] == 0x08 {
            if s >= 4 {
                let n = u32(px, s - 4)
                if n > 18, s + n <= px.count { return Array(px[s..<(s + n)]) }
            }
            return Array(px[s...])
        }
        return nil
    }

    static func gunzip(_ gz: [UInt8]) -> [UInt8]? {
        guard gz.count > 18, gz[0] == 0x1f, gz[1] == 0x8b, gz[2] == 8 else { return nil }
        let flags = gz[3]
        var p = 10
        if flags & 0x04 != 0, p + 2 <= gz.count { p += 2 + (Int(gz[p]) | Int(gz[p + 1]) << 8) }   // FEXTRA
        if flags & 0x08 != 0 { while p < gz.count && gz[p] != 0 { p += 1 }; p += 1 }              // FNAME
        if flags & 0x10 != 0 { while p < gz.count && gz[p] != 0 { p += 1 }; p += 1 }              // FCOMMENT
        if flags & 0x02 != 0 { p += 2 }                                                           // FHCRC
        guard p < gz.count - 8 else { return nil }
        let n = gz.count
        let isize = Int(gz[n - 4]) | Int(gz[n - 3]) << 8 | Int(gz[n - 2]) << 16 | Int(gz[n - 1]) << 24
        let capacity = isize > 0 && isize < 64 * 1024 * 1024 ? isize : (n - p) * 8
        return inflate(Array(gz[p..<(n - 8)]), capacity: capacity)
    }

    /// Raw DEFLATE (Apple's COMPRESSION_ZLIB is RFC 1951 without a header).
    static func inflate(_ src: [UInt8], capacity: Int) -> [UInt8]? {
        guard !src.isEmpty, capacity > 0 else { return nil }
        var dst = [UInt8](repeating: 0, count: capacity)
        let written = dst.withUnsafeMutableBufferPointer { d in
            src.withUnsafeBufferPointer { s in
                compression_decode_buffer(d.baseAddress!, capacity, s.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0 else { return nil }
        return Array(dst.prefix(written))
    }

    // MARK: TS

    /// Whole 188-byte packets only, dropping anything between runs.
    static func resync(_ b: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(b.count)
        var r = 0
        let len = b.count
        func runStarts(_ o: Int) -> Bool {
            o + packet * 2 < len && b[o] == sync && b[o + packet] == sync && b[o + packet * 2] == sync
        }
        while r < len {
            if runStarts(r) {
                while r + packet <= len && b[r] == sync {
                    out.append(contentsOf: b[r..<(r + packet)])
                    r += packet
                }
            } else {
                r += 1
            }
        }
        return out
    }
}
