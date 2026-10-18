import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import UIKit

/// One player for the whole app. Keeps playing with the screen locked and
/// over AirPlay (background audio mode + a .playback audio session), shows
/// itself on the lock screen with working controls, saves progress, and
/// moves on to the next episode at the end.
///
/// It also looks after itself, like the TV app does: a film that doesn't
/// start (a dead link) moves to the next source instead of sitting on
/// "Loading from", and a live channel that stalls or fails reconnects.
@MainActor
final class PlayerModel: ObservableObject {
    static let shared = PlayerModel()
    private let tag = "Player"

    let player = AVPlayer()
    @Published private(set) var request: PlayRequest?
    @Published private(set) var liveChannel: LiveChannel?
    /// What the viewer should know right now ("Finding a source…"); empty while playing.
    @Published private(set) var status: String = ""
    /// True while `status` describes something still happening (show a spinner).
    @Published private(set) var working = false
    /// The stream as its source gave it.
    @Published private(set) var stream: StreamInfo?

    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var failObserver: NSObjectProtocol?
    private var itemStatus: NSKeyValueObservation?
    private var externalObserver: NSKeyValueObservation?
    private var watchdog: Task<Void, Never>?
    private var episodesCache: [String: [EpisodeInfo]] = [:]

    /// Bumped whenever what is playing changes, so late callbacks from an
    /// old item or an old lookup are ignored.
    private var generation = 0
    private var tried: Set<String> = []     // sources tried for this title
    private var retriedSame = false         // a source that was playing got one reload
    private var reconnects = 0              // live reconnects without a steady stretch between
    private var started = false             // this item has shown progress
    private var pendingSeek: Double?
    /// The item's address also works for an AirPlay receiver: no loopback
    /// address and no headers only this phone can send.
    private var viaLAN = false

    private static let vodStartLimit: Double = 30
    private static let liveStartLimit: Double = 75      // a cold channel can take this long
    private static let vodStallLimit: Double = 45
    private static let liveStallLimit: Double = 30
    private static let maxReconnects = 5

    private init() {
        player.allowsExternalPlayback = true                       // AirPlay video
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        player.automaticallyWaitsToMinimizeStalling = true
        configureAudioSession()
        configureRemoteCommands()
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 10, preferredTimescale: 1), queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.saveProgress(); self?.updateNowPlaying() }
        }
        externalObserver = player.observe(\.isExternalPlaybackActive, options: [.new]) { [weak self] p, _ in
            let active = p.isExternalPlaybackActive
            Task { @MainActor in self?.externalChanged(active) }
        }
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            AppLog.w(tag, "audio session: \(error)")
        }
    }

    /// Playing to an Apple TV (AirPlay, or screen mirroring).
    private var externalRoute: Bool {
        player.isExternalPlaybackActive ||
            AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .airPlay }
    }

    private func say(_ text: String, working: Bool) {
        status = text
        self.working = working && !text.isEmpty
    }

    // MARK: playing

    func play(_ req: PlayRequest, source only: String? = nil) {
        begin()
        request = req
        tried = []
        retriedSame = false
        findSource(req, only: only, resumeAt: Library.shared.position(for: req))
    }

    func play(channel: LiveChannel) {
        begin()
        liveChannel = channel
        reconnects = 0
        connect(channel)
    }

    private func begin() {
        saveProgress()
        generation += 1
        watchdog?.cancel()
        player.pause()
        request = nil
        liveChannel = nil
        stream = nil
        PlayerPresenter.shared.show(self)
    }

    private func findSource(_ req: PlayRequest, only: String?, resumeAt: Double?) {
        say(tried.isEmpty ? "Finding a source…" : "Trying another source…", working: true)
        let gen = generation
        Task {
            let s = await Sources.resolve(req, only: only, skipping: tried)
            guard gen == generation else { return }
            guard let s else {
                say(tried.isEmpty ? "No source has this right now. Try again later."
                                  : "No source could play this right now. Try again later.", working: false)
                AppLog.w(tag, "nothing played \(req.label); tried \(tried.sorted().joined(separator: ", "))")
                return
            }
            tried.insert(s.provider)
            stream = s
            var url = s.url
            var headers = s.headers
            viaLAN = headers.isEmpty
            if !viaLAN && externalRoute, let p = try? await LiveServer.shared.proxied(s.url, headers: s.headers) {
                guard gen == generation else { return }
                url = p; headers = [:]; viaLAN = true
            }
            load(url, headers: headers, live: false, resumeAt: resumeAt)
            say("Loading from \(s.provider)…", working: true)
            AppLog.i(tag, "playing \(req.label) from \(s.provider) (\(s.label), \(s.language))\(viaLAN && !s.headers.isEmpty ? " via this phone" : "")")
        }
    }

    private func connect(_ channel: LiveChannel, after delay: Double = 0) {
        say(reconnects == 0 ? "Connecting to \(channel.displayName)…" : "Reconnecting…", working: true)
        let gen = generation
        Task {
            if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
            guard gen == generation else { return }
            do {
                let url = try await LiveServer.shared.url(for: channel.id, external: externalRoute)
                guard gen == generation else { return }
                viaLAN = url.host != "127.0.0.1"
                stream = StreamInfo(url: url, headers: [:], provider: "Live", label: "Live", language: "", isLive: true)
                load(url, headers: [:], live: true, resumeAt: nil)
                if reconnects == 0 { Library.shared.noteChannel(channel) }
                AppLog.i(tag, "ch=\(channel.id) playing \(channel.name)\(viaLAN ? " (Wi-Fi address)" : "")")
            } catch {
                guard gen == generation else { return }
                AppLog.w(tag, "ch=\(channel.id): \(error)")
                if reconnects < 2 {
                    reconnects += 1
                    connect(channel, after: 3)
                } else {
                    say("This channel isn't available right now.", working: false)
                }
            }
        }
    }

    private func load(_ url: URL, headers: [String: String], live: Bool, resumeAt: Double?) {
        generation += 1
        let gen = generation
        started = false
        pendingSeek = resumeAt
        var options: [String: Any] = [:]
        if !headers.isEmpty { options["AVURLAssetHTTPHeaderFieldsKey"] = headers }
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: options))
        item.preferredForwardBufferDuration = live ? 20 : 30
        item.externalMetadata = metadata()
        itemStatus = item.observe(\.status) { [weak self] item, _ in
            let status = item.status
            let message = item.error?.localizedDescription ?? "?"
            Task { @MainActor in
                guard let self, gen == self.generation else { return }
                switch status {
                case .readyToPlay:
                    if let t = self.pendingSeek {
                        self.pendingSeek = nil
                        self.player.seek(to: CMTime(seconds: t, preferredTimescale: 600))
                    }
                case .failed:
                    self.recover("failed: \(message)", gen: gen)
                default: break
                }
            }
        }
        for o in [endObserver, failObserver].compactMap({ $0 }) { NotificationCenter.default.removeObserver(o) }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.finished(gen) } }
        failObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { [weak self] note in
            let e = (note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription ?? "?"
            Task { @MainActor in self?.recover("stopped: \(e)", gen: gen) }
        }
        player.replaceCurrentItem(with: item)
        player.play()
        updateNowPlaying()
        watch(gen, live: live)
    }

    /// Every 2 s: has it started, is it moving? Recover when it isn't.
    private func watch(_ gen: Int, live: Bool) {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            let begun = Date()
            var lastMove = Date()
            var lastTime = -1.0
            var steadySince: Date?
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self, gen == self.generation, !Task.isCancelled else { return }
                let now = Date()
                let t = self.player.currentTime().seconds
                if self.player.timeControlStatus == .paused {          // the viewer paused
                    lastMove = now; steadySince = nil
                    if t.isFinite { lastTime = t }
                    continue
                }
                let moving = self.player.timeControlStatus == .playing && lastTime >= 0 && t.isFinite && t > lastTime + 0.5
                if t.isFinite { lastTime = t }
                if moving {
                    lastMove = now
                    if !self.started {
                        self.started = true
                        AppLog.i(self.tag, "started after \(Int(now.timeIntervalSince(begun))) s")
                    }
                    if !self.status.isEmpty { self.say("", working: false) }
                    if steadySince == nil { steadySince = now }
                    if live, let s = steadySince, now.timeIntervalSince(s) > 90 { self.reconnects = 0 }
                    continue
                }
                steadySince = nil
                if !self.started {
                    let limit = live ? Self.liveStartLimit : Self.vodStartLimit
                    if now.timeIntervalSince(begun) > limit {
                        self.recover("didn't start in \(Int(limit)) s", gen: gen)
                        return
                    }
                } else {
                    let still = now.timeIntervalSince(lastMove)
                    let limit = live ? Self.liveStallLimit : Self.vodStallLimit
                    if still > limit {
                        self.recover("stalled for \(Int(limit)) s", gen: gen)
                        return
                    }
                    if still >= 4 && self.status.isEmpty { self.say("Buffering…", working: true) }
                }
            }
        }
    }

    /// The item failed, stopped, or isn't moving: reconnect a live channel;
    /// reload a film that was playing once, else move to the next source.
    private func recover(_ reason: String, gen: Int) {
        guard gen == generation else { return }
        generation += 1
        watchdog?.cancel()
        if let ch = liveChannel {
            reconnects += 1
            AppLog.w(tag, "ch=\(ch.id) \(reason); reconnect \(reconnects)")
            if reconnects > Self.maxReconnects {
                player.pause()
                say("This channel keeps dropping. Try another channel.", working: false)
                return
            }
            connect(ch, after: reconnects == 1 ? 0.5 : 3)
            return
        }
        guard let req = request else { return }
        let pos = player.currentTime().seconds
        let resume = started && pos.isFinite && pos > 30 ? pos : Library.shared.position(for: req)
        let provider = stream?.provider ?? "?"
        if started && !retriedSame {
            retriedSame = true
            tried.remove(provider)
            AppLog.w(tag, "\(provider) \(reason) during \(req.label); reloading it")
        } else {
            AppLog.w(tag, "\(provider) \(reason) for \(req.label); trying the next source")
        }
        player.pause()
        findSource(req, only: nil, resumeAt: resume)
    }

    /// AirPlay started: the Apple TV fetches the stream itself, and can't
    /// reach this phone's loopback address or send a source's headers.
    /// Hand it an address it can use, at the same spot.
    private func externalChanged(_ active: Bool) {
        AppLog.i(tag, active ? "playing on an AirPlay receiver" : "playing on this device")
        updateNowPlaying()
        guard active, !viaLAN, player.currentItem != nil else { return }
        if let ch = liveChannel {
            generation += 1
            watchdog?.cancel()
            connect(ch)
        } else if let req = request, let s = stream {
            let pos = player.currentTime().seconds
            generation += 1
            watchdog?.cancel()
            let gen = generation
            Task {
                guard let url = try? await LiveServer.shared.proxied(s.url, headers: s.headers),
                      gen == generation, request == req else { return }
                viaLAN = true
                load(url, headers: [:], live: false, resumeAt: pos.isFinite && pos > 1 ? pos : nil)
            }
        }
    }

    func stop() {
        saveProgress()
        generation += 1
        watchdog?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        request = nil
        liveChannel = nil
        stream = nil
        say("", working: false)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: episodes

    private func finished(_ gen: Int) {
        guard gen == generation else { return }
        if liveChannel != nil {          // a live playlist that ended: the feed restarted
            recover("ended", gen: gen)
            return
        }
        guard let req = request else { return }
        Library.shared.markWatched(req)
        guard let s = req.season, let e = req.episode else { return }
        Task {
            if let next = await nextEpisode(after: req.item, season: s, episode: e), request == req {
                AppLog.i(tag, "next: S\(next.0) E\(next.1)")
                play(PlayRequest(item: req.item, season: next.0, episode: next.1))
            }
        }
    }

    func nextEpisode(after item: MediaItem, season: Int, episode: Int) async -> (Int, Int)? {
        if let eps = try? await episodes(item.tmdbId, season), eps.contains(where: { $0.number == episode + 1 }) {
            return (season, episode + 1)
        }
        if let eps = try? await episodes(item.tmdbId, season + 1), !eps.isEmpty { return (season + 1, eps[0].number) }
        return nil
    }

    func episodes(_ tvId: Int, _ season: Int) async throws -> [EpisodeInfo] {
        let key = "\(tvId):\(season)"
        if let c = episodesCache[key] { return c }
        let eps = try await TMDB.episodes(tvId: tvId, season: season)
        episodesCache[key] = eps
        return eps
    }

    // MARK: progress

    private func saveProgress() {
        guard let req = request, started, let item = player.currentItem else { return }
        let pos = player.currentTime().seconds
        let dur = item.duration.seconds
        guard pos.isFinite, dur.isFinite, dur > 0 else { return }
        Library.shared.save(req, position: pos, duration: dur)
    }

    // MARK: titles, lock screen

    private var titleText: String { liveChannel?.displayName ?? request?.item.title ?? "" }

    private var subtitleText: String? {
        if let r = request, let s = r.season, let e = r.episode { return "Season \(s) · Episode \(e)" }
        return liveChannel != nil ? "Live TV" : nil
    }

    /// Shown by Apple's player and the AirPlay receiver.
    private func metadata() -> [AVMetadataItem] {
        func entry(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let m = AVMutableMetadataItem()
            m.identifier = id
            m.value = value as NSString
            m.extendedLanguageTag = "und"
            return m
        }
        var out = [entry(.commonIdentifierTitle, titleText)]
        if let sub = subtitleText { out.append(entry(.iTunesMetadataTrackSubTitle, sub)) }
        return out
    }

    private func updateNowPlaying() {
        guard request != nil || liveChannel != nil else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: titleText]
        if let sub = subtitleText { info[MPMediaItemPropertyArtist] = sub }
        if liveChannel != nil { info[MPNowPlayingInfoPropertyIsLiveStream] = true }
        if let item = player.currentItem {
            let d = item.duration.seconds
            if d.isFinite { info[MPMediaItemPropertyPlaybackDuration] = d }
        }
        let t = player.currentTime().seconds
        if t.isFinite { info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = t }
        info[MPNowPlayingInfoPropertyPlaybackRate] = player.rate
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func configureRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.player.play(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.player.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let p = self?.player else { return .commandFailed }
            if p.rate > 0 { p.pause() } else { p.play() }
            return .success
        }
        c.skipForwardCommand.preferredIntervals = [10]
        c.skipForwardCommand.addTarget { [weak self] _ in self?.skip(10); return .success }
        c.skipBackwardCommand.preferredIntervals = [10]
        c.skipBackwardCommand.addTarget { [weak self] _ in self?.skip(-10); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.player.seek(to: CMTime(seconds: e.positionTime, preferredTimescale: 600))
            return .success
        }
    }

    func skip(_ seconds: Double) {
        let t = player.currentTime().seconds + seconds
        player.seek(to: CMTime(seconds: max(0, t), preferredTimescale: 600))
    }
}
