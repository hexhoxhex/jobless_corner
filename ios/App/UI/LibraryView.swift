import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var library: Library
    @EnvironmentObject var player: PlayerModel

    var body: some View {
        List {
            Section("Continue watching") {
                if library.entries.isEmpty {
                    Text("Nothing yet. Films and episodes you start show up here.")
                        .font(.subheadline).foregroundStyle(Theme.muted)
                }
                ForEach(library.entries) { e in
                    Button { player.play(e.request) } label: {
                        HStack(spacing: 12) {
                            AsyncImage(url: e.request.item.poster) { img in
                                img.resizable().scaledToFill()
                            } placeholder: { Theme.surface }
                            .frame(width: 48, height: 72)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            VStack(alignment: .leading, spacing: 4) {
                                Text(e.request.item.title).font(.subheadline.bold()).foregroundStyle(.white)
                                Text(e.request.season.map { "Season \($0) · Episode \(e.request.episode ?? 1)" } ?? "Film")
                                    .font(.caption).foregroundStyle(Theme.muted)
                                ProgressView(value: e.progress).tint(Theme.accent).frame(maxWidth: 160)
                            }
                        }
                    }
                    .swipeActions { Button("Remove", role: .destructive) { library.remove(e) } }
                }
            }
            .listRowBackground(Theme.background)

            Section("Channels you watched") {
                if library.channels.isEmpty {
                    Text("Live channels you open show up here.").font(.subheadline).foregroundStyle(Theme.muted)
                }
                ForEach(library.channels) { c in
                    Button {
                        player.play(channel: LiveChannel(id: c.id, name: c.name, logo: c.logo, group: c.group))
                    } label: {
                        HStack(spacing: 12) {
                            ChannelLogo(url: c.logo, name: c.name)
                            Text(c.name).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                        }
                    }
                    .swipeActions { Button("Remove", role: .destructive) { library.removeChannel(c.id) } }
                }
            }
            .listRowBackground(Theme.background)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle("Library")
    }
}
