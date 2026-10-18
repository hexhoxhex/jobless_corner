import AVFoundation
import XCTest
@testable import VijanaBaruBaru

/// Tests against the real services. A service that is down or refuses the
/// build machine skips its test (and says why in the log) rather than
/// failing the build; live TV sources don't serve cloud machines at all,
/// so live is only tested up to the published channel list.
final class OnlineTests: XCTestCase {

    private func skipWithoutToken() throws {
        if Config.tmdbToken.isEmpty { throw XCTSkip("no TMDB token in this build") }
    }

    func testTMDBLists() async throws {
        try skipWithoutToken()
        let trending = try await TMDB.trending()
        XCTAssertFalse(trending.isEmpty)
        XCTAssertNotNil(trending.first?.poster)
    }

    func testSeriesHasSeasonsAndEpisodes() async throws {
        try skipWithoutToken()
        // The Big Bang Theory
        let item = MediaItem(tmdbId: 1418, isTV: true, title: "The Big Bang Theory", year: 2007,
                             overview: "", poster: nil, backdrop: nil, rating: nil)
        let (_, seasons) = try await TMDB.details(item)
        XCTAssertGreaterThanOrEqual(seasons.count, 12)
        let eps = try await TMDB.episodes(tvId: 1418, season: 1)
        XCTAssertEqual(eps.first?.number, 1)
        XCTAssertGreaterThanOrEqual(eps.count, 17)
    }

    /// A source finds a film, its playlist loads, and Apple's player
    /// accepts it.
    private func checkPlays(_ name: String, _ req: PlayRequest) async throws {
        guard let s = await Sources.resolve(req, only: name) else {
            throw XCTSkip("\(name) had nothing for \(req.label) from this machine")
        }
        XCTAssertEqual(s.provider, name)
        let asset = AVURLAsset(url: s.url, options: ["AVURLAssetHTTPHeaderFieldsKey": s.headers])
        let playable = try await asset.load(.isPlayable)
        XCTAssertTrue(playable, "\(name): the player refused \(s.url.host ?? "?")")
        print("\(name): \(req.label) -> \(s.url.host ?? "?") \(s.label) \(s.language)")
    }

    private let fightClub = PlayRequest(
        item: MediaItem(tmdbId: 550, isTV: false, title: "Fight Club", year: 1999,
                        overview: "", poster: nil, backdrop: nil, rating: nil),
        season: nil, episode: nil)

    func testSevenMoviesPlaysAFilm() async throws {
        try await checkPlays("7Movies", fightClub)
    }

    func testVixSrcPlaysAFilm() async throws {
        try await checkPlays("VixSrc", fightClub)
    }

    func testSevenMoviesPlaysAnEpisode() async throws {
        let ep = PlayRequest(
            item: MediaItem(tmdbId: 1418, isTV: true, title: "The Big Bang Theory", year: 2007,
                            overview: "", poster: nil, backdrop: nil, rating: nil),
            season: 1, episode: 1)
        try await checkPlays("7Movies", ep)
    }

    /// A film stream through the phone's own server (what an AirPlay
    /// receiver uses) still loads in Apple's player.
    func testFilmThroughThisPhonePlays() async throws {
        guard let s = await Sources.resolve(fightClub) else { throw XCTSkip("no source answered from this machine") }
        let url = try await LiveServer.shared.proxied(s.url, headers: s.headers)
        let (data, _) = try await Net.get(url, timeout: 20)
        XCTAssertTrue(data.starts(with: Data("#EXTM3U".utf8)))
        let body = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(body.contains("/p/"), "playlist addresses weren't pointed at the phone")
        let playable = try await AVURLAsset(url: url).load(.isPlayable)
        XCTAssertTrue(playable)
    }

    func testChannelListAndSchedule() async throws {
        let channels = try await LiveCatalog.channels()
        XCTAssertGreaterThan(channels.count, 20)
        _ = try await LiveCatalog.schedule()
    }

    /// The local server answers over HTTP at all.
    func testLocalServerAnswers() async throws {
        let port = try await LiveServer.shared.start()
        let url = URL(string: "http://127.0.0.1:\(port)/nothing")!
        do {
            _ = try await Net.get(url, timeout: 5)
            XCTFail("expected a 404")
        } catch let e as Net.HTTPError {
            XCTAssertEqual(e.status, 404)
        }
    }
}
