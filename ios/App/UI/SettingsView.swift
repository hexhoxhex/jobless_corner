import SwiftUI

struct SettingsView: View {
    var body: some View {
        List {
            Section {
                NavigationLink { LogsView() } label: { Label("App logs", systemImage: "doc.text") }
                NavigationLink { AboutView() } label: { Label("About, and how to fix things", systemImage: "info.circle") }
            }
            Section("Watching") {
                Label("Keeps playing with the screen locked", systemImage: "lock")
                Label("AirPlay: tap the AirPlay button in the player to send to an Apple TV", systemImage: "airplayvideo")
                Label("Picture in picture when you leave the app", systemImage: "pip")
            }
            .font(.subheadline)
            .foregroundStyle(Theme.muted)
            Section("This app") {
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")
                LabeledContent("Device", value: "\(DeviceInfo.model), iOS \(DeviceInfo.systemVersion)")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle("Settings")
    }
}

struct LogsView: View {
    @State private var problemsOnly = true
    @State private var lines: [String] = []
    @State private var file: URL?

    var body: some View {
        VStack(spacing: 12) {
            Text("What the app has been doing. When something goes wrong, share this with us and say what you were watching and when. Links, tokens, passwords and IP addresses are removed; it does include the names of what was played.")
                .font(.footnote).foregroundStyle(Theme.muted)
                .padding(.horizontal)
            HStack {
                Picker("Show", selection: $problemsOnly) {
                    Text("Problems").tag(true)
                    Text("Everything").tag(false)
                }
                .pickerStyle(.segmented)
                if let file {
                    ShareLink(item: file) { Label("Share", systemImage: "square.and.arrow.up") }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal)
            ScrollView {
                Text(lines.isEmpty ? (problemsOnly ? "No problems logged." : "Nothing logged yet.") : lines.joined(separator: "\n"))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding()
            }
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
        }
        .background(Theme.background)
        .navigationTitle("App logs")
        .onAppear(perform: refresh)
        .onChange(of: problemsOnly) { _ in refresh() }
    }

    private func refresh() {
        lines = AppLog.recent(600, problemsOnly: problemsOnly)
        file = AppLog.exportFile()
    }
}

struct AboutView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                section("Where the code lives") {
                    repo("hexhoxhex/jobless_corner", "The Android TV app, this iPhone app (the ios folder), the phone remote and the Windows app")
                    repo("hexhoxhex/mkurugenzi_viewer", "Live channels, the sports schedule and the source list the apps download")
                }
                section("How the iPhone app is built") {
                    para("It is a SwiftUI app with Apple's own player (AVPlayer). There is no Xcode project in the repo: ios/project.yml describes it and XcodeGen generates it.")
                    para("GitHub's Mac machines build it (.github/workflows/ios.yml): generate the project, run the tests in the iPhone simulator, and produce an unsigned .ipa. Install it with Sideloadly (Windows or Mac) or AltStore using your Apple ID; a free Apple ID needs a re-install every 7 days.")
                }
                section("How it works") {
                    para("Films and series: titles come from TMDB, then the sources are asked in order (7Movies, then VixSrc). When 7Movies has dubbed copies of a title, its original-language copy is chosen.")
                    para("Live TV: the channel's player page names the stream; the stream needs a short token the app fetches and renews; the video pieces are disguised as images and the app unwraps them. A small server inside the app hands Apple's player the clean stream, on your Wi-Fi address so AirPlay works too.")
                    para("It keeps playing with the screen locked and over AirPlay, and shows on the lock screen.")
                }
                section("How to fix something") {
                    para("1. Share the log (App logs > Share) and say what happened and when.")
                    para("2. Sources: ios/App/Sources. Live TV: ios/App/Live. The player: ios/App/Player. Screens: ios/App/UI.")
                    para("3. Push a change; the iOS workflow builds and tests it and leaves the .ipa as a download on the run.")
                    para("When a source stops working it is nearly always the site that changed: a new address, a renamed field, a new token step. Compare what the site sends now with what the code expects. The Android app usually meets the same change first; its fix (app/src/main/java) shows what to port.")
                }
                section("Fixing it with an AI agent") {
                    para("Coding agents such as Claude Code can read this code, compare it with the Android app, and drive the GitHub build. Describe the problem in plain words with the log, ask it to find the cause before changing anything, and to change as little as it can. Read its changes before you install them, and never give it passwords or tokens.")
                }
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("About")
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased()).font(.caption.bold()).foregroundStyle(Theme.muted)
            content()
        }
    }

    private func para(_ s: String) -> some View {
        Text(s).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
    }

    private func repo(_ name: String, _ what: String) -> some View {
        Link(destination: URL(string: "https://github.com/\(name)")!) {
            VStack(alignment: .leading, spacing: 3) {
                Text(name).font(.system(.subheadline, design: .monospaced).bold()).foregroundStyle(Theme.accent)
                Text(what).font(.caption).foregroundStyle(Theme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
