import SwiftUI

struct SearchView: View {
    @State private var query = ""
    @State private var results: [MediaItem] = []
    @State private var searching = false
    @State private var task: Task<Void, Never>?

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 12)]

    var body: some View {
        ScrollView {
            if results.isEmpty && !query.isEmpty && !searching {
                Text("Nothing found for \u{201C}\(query)\u{201D}.").foregroundStyle(Theme.muted).padding(.top, 40)
            }
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(results) { item in
                    NavigationLink(value: item) { PosterCard(item: item, width: 110) }
                        .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("Search")
        .navigationDestination(for: MediaItem.self) { DetailView(item: $0) }
        .searchable(text: $query, prompt: "Films and series")
        .onChange(of: query) { q in
            task?.cancel()
            task = Task {
                try? await Task.sleep(nanoseconds: 350_000_000)
                guard !Task.isCancelled else { return }
                await search(q)
            }
        }
        .overlay { if searching { ProgressView() } }
    }

    private func search(_ q: String) async {
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else { results = []; return }
        searching = true
        results = (try? await TMDB.search(trimmed)) ?? []
        searching = false
    }
}
