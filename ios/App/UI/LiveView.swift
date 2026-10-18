import SwiftUI

struct LiveView: View {
    enum Mode: String, CaseIterable { case now = "On now", schedule = "Schedule", channels = "Channels" }

    @EnvironmentObject var player: PlayerModel
    @State private var mode: Mode = .now
    @State private var channels: [LiveChannel] = []
    @State private var events: [ScheduleEvent] = []
    @State private var query = ""
    @State private var loading = true
    @State private var error: String?
    @State private var expanded: String?

    var body: some View {
        VStack(spacing: 0) {
            Picker("Show", selection: $mode) {
                ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding()

            if loading {
                ProgressView().frame(maxHeight: .infinity)
            } else if let error {
                Text(error).foregroundStyle(Theme.muted).padding().frame(maxHeight: .infinity)
            } else {
                switch mode {
                case .now: eventList(onNow)
                case .schedule: eventList(upcoming)
                case .channels: channelList
                }
            }
        }
        .background(Theme.background)
        .navigationTitle("Live TV")
        .searchable(text: $query, prompt: mode == .channels ? "Channels" : "Matches and teams")
        .task { await load() }
        .refreshable { await load() }
    }

    // MARK: data

    private func load() async {
        do {
            async let c = LiveCatalog.channels()
            async let e = LiveCatalog.schedule()
            channels = try await c
            events = try await e
            error = nil
        } catch {
            self.error = "Couldn't load the channel list. Pull to try again."
            AppLog.w("Live", "catalogue: \(error)")
        }
        loading = false
    }

    private func matches(_ e: ScheduleEvent) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty || e.title.lowercased().contains(q) || e.category.lowercased().contains(q)
    }

    private var onNow: [ScheduleEvent] {
        let now = Date()
        return events.filter { e in
            guard let s = e.start else { return false }
            return s <= now.addingTimeInterval(15 * 60) && s >= now.addingTimeInterval(-3 * 3600) && matches(e)
        }
    }

    private var upcoming: [ScheduleEvent] {
        let now = Date()
        return events.filter { e in
            guard let s = e.start else { return false }
            return s > now && s < now.addingTimeInterval(36 * 3600) && matches(e)
        }
    }

    private func channel(_ id: String, _ name: String) -> LiveChannel {
        channels.first { $0.id == id } ?? LiveChannel(id: id, name: name, logo: nil, group: "Live event")
    }

    // MARK: views

    private static let time: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "EEE HH:mm"; return f
    }()

    private func eventList(_ list: [ScheduleEvent]) -> some View {
        List {
            if list.isEmpty {
                Text(mode == .now ? "Nothing on right now." : "Nothing scheduled.").foregroundStyle(Theme.muted)
            }
            ForEach(list) { e in
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        withAnimation { expanded = expanded == e.id ? nil : e.id }
                    } label: {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(e.title).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                                Text([e.start.map { Self.time.string(from: $0) }, e.category.isEmpty ? nil : e.category]
                                    .compactMap { $0 }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(Theme.muted)
                            }
                            Spacer()
                            Text("\(e.channels.count)").font(.caption.bold())
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Theme.surface, in: Capsule())
                        }
                    }
                    .buttonStyle(.plain)
                    if expanded == e.id {
                        ForEach(e.channels) { ch in
                            Button {
                                player.play(channel: channel(ch.id, ch.name))
                            } label: {
                                Label(channel(ch.id, ch.name).displayName, systemImage: "play.circle")
                                    .font(.subheadline)
                            }
                            .padding(.leading, 6)
                        }
                    }
                }
                .listRowBackground(Theme.background)
            }
        }
        .listStyle(.plain)
    }

    private var channelList: some View {
        let q = query.lowercased()
        let shown = channels.filter { q.isEmpty || $0.name.lowercased().contains(q) || $0.group.lowercased().contains(q) }
        let groups = Dictionary(grouping: shown, by: \.group).sorted { $0.key < $1.key }
        return List {
            ForEach(groups, id: \.key) { entry in
                Section(entry.key) {
                    ForEach(entry.value) { c in
                        Button { player.play(channel: c) } label: { ChannelRow(channel: c) }
                            .listRowBackground(Theme.background)
                    }
                }
            }
        }
        .listStyle(.plain)
    }
}

struct ChannelRow: View {
    let channel: LiveChannel

    var body: some View {
        HStack(spacing: 12) {
            ChannelLogo(url: channel.logo, name: channel.displayName)
            Text(channel.displayName).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
            Spacer()
            Image(systemName: "play.fill").font(.caption).foregroundStyle(Theme.muted)
        }
    }
}

struct ChannelLogo: View {
    let url: URL?
    let name: String
    var width: CGFloat = 64

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(Color(white: 0.95))
            AsyncImage(url: url) { img in
                img.resizable().scaledToFit()
                    .shadow(color: .black.opacity(0.5), radius: 0.5)   // white logos stay visible
                    .padding(6)
            } placeholder: {
                Text(String(name.split(separator: " ").prefix(2).compactMap(\.first)).uppercased())
                    .font(.caption.bold()).foregroundStyle(.gray)
            }
        }
        .frame(width: width, height: width * 0.62)
    }
}
