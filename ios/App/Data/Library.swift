import Foundation

/// Continue watching, watched episodes and recent channels, kept on the
/// device (UserDefaults, JSON).
@MainActor
final class Library: ObservableObject {
    static let shared = Library()

    struct Entry: Codable, Identifiable, Hashable {
        let request: PlayRequest
        var position: Double
        var duration: Double
        var updated: Date
        var id: String { request.item.id }
        var progress: Double { duration > 0 ? min(1, position / duration) : 0 }
    }

    struct RecentChannel: Codable, Identifiable, Hashable {
        let id: String
        let name: String
        let logo: URL?
        let group: String
    }

    @Published private(set) var entries: [Entry] = []
    @Published private(set) var channels: [RecentChannel] = []
    private var positions: [String: Double] = [:]      // per episode/film key
    private let defaults = UserDefaults.standard

    private init() {
        if let d = defaults.data(forKey: "library.entries"),
           let e = try? JSONDecoder().decode([Entry].self, from: d) { entries = e }
        if let d = defaults.data(forKey: "library.channels"),
           let c = try? JSONDecoder().decode([RecentChannel].self, from: d) { channels = c }
        positions = defaults.dictionary(forKey: "library.positions") as? [String: Double] ?? [:]
    }

    private func persist() {
        defaults.set(try? JSONEncoder().encode(entries), forKey: "library.entries")
        defaults.set(try? JSONEncoder().encode(channels), forKey: "library.channels")
        defaults.set(positions, forKey: "library.positions")
    }

    /// Where to resume `req`, if it was left partway through.
    func position(for req: PlayRequest) -> Double? {
        guard let p = positions[req.key], p > 30 else { return nil }
        return p
    }

    func save(_ req: PlayRequest, position: Double, duration: Double) {
        positions[req.key] = position > duration - 60 ? 0 : position
        // One card per title (a series shows its latest episode).
        entries.removeAll { $0.id == req.item.id }
        if position < duration - 60 {
            entries.insert(Entry(request: req, position: position, duration: duration, updated: Date()), at: 0)
        }
        if entries.count > 40 { entries.removeLast(entries.count - 40) }
        persist()
    }

    func markWatched(_ req: PlayRequest) {
        positions[req.key] = 0
        entries.removeAll { $0.id == req.item.id && $0.request.key == req.key }
        persist()
    }

    func remove(_ entry: Entry) {
        entries.removeAll { $0.id == entry.id }
        persist()
    }

    func noteChannel(_ c: LiveChannel) {
        channels.removeAll { $0.id == c.id }
        channels.insert(RecentChannel(id: c.id, name: c.displayName, logo: c.logo, group: c.group), at: 0)
        if channels.count > 12 { channels.removeLast(channels.count - 12) }
        persist()
    }

    func removeChannel(_ id: String) {
        channels.removeAll { $0.id == id }
        persist()
    }
}
