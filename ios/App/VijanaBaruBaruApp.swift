import SwiftUI

@main
struct VijanaBaruBaruApp: App {
    @StateObject private var player = PlayerModel.shared
    @StateObject private var library = Library.shared

    init() {
        AppLog.i("App", "start v\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")")
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(player)
                .environmentObject(library)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

enum Theme {
    static let accent = Color(red: 0.03, green: 0.72, blue: 0.31)       // #07B84E
    static let background = Color(red: 0.06, green: 0.07, blue: 0.08)   // #101114
    static let surface = Color(red: 0.10, green: 0.12, blue: 0.17)      // #191F2B
    static let muted = Color(red: 0.61, green: 0.63, blue: 0.66)
}

struct RootView: View {
    @EnvironmentObject var player: PlayerModel
    /// `-startTab live` on the launch command opens that tab (the build's
    /// screenshots use it).
    @State private var tab = UserDefaults.standard.string(forKey: "startTab") ?? "home"

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView() }
                .tabItem { Label("Home", systemImage: "house") }.tag("home")
            NavigationStack { LiveView() }
                .tabItem { Label("Live", systemImage: "tv") }.tag("live")
            NavigationStack { SearchView() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }.tag("search")
            NavigationStack { LibraryView() }
                .tabItem { Label("Library", systemImage: "square.stack") }.tag("library")
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }.tag("settings")
        }
    }
}
