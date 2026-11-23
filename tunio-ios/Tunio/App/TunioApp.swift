import SwiftUI

@main
struct TunioApp: App {
    @StateObject private var ui = UIState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(ui)
                .preferredColorScheme(.dark)
                .tint(Theme.accentLight)
                .task {
                    await HomeServer.shared.check(force: true)
                    Library.shared.fillArtwork()
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var ui: UIState
    @ObservedObject private var player = Player.shared
    @ObservedObject private var downloads = Downloads.shared
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView().withRoutes() }
                .safeAreaInset(edge: .bottom) { mini }
                .tabItem { Label("Home", systemImage: tab == 0 ? "house.fill" : "house") }.tag(0)
            NavigationStack { SearchView().withRoutes() }
                .safeAreaInset(edge: .bottom) { mini }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(1)
            NavigationStack { LibraryView().withRoutes() }
                .safeAreaInset(edge: .bottom) { mini }
                .tabItem { Label("Library", systemImage: tab == 2 ? "books.vertical.fill" : "books.vertical") }.tag(2)
        }
        .background(Theme.c60)
        .fullScreenCover(isPresented: $ui.showPlayer) { PlayerView() }
        .sheet(isPresented: Binding(get: { ui.addToPlaylist != nil }, set: { if !$0 { ui.addToPlaylist = nil } })) {
            AddToPlaylistSheet(tracks: ui.addToPlaylist ?? []).presentationDetents([.medium, .large])
        }
        .overlay(alignment: .top) { toast }
        .onChange(of: player.message) { _, m in if let m { ui.toast = m; player.message = nil } }
        .onOpenURL(perform: open)
        .task {
            // automated tests: Tunio --open tunio://search?q=…
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "--open"), i + 1 < args.count, let u = URL(string: args[i + 1]) { open(u) }
        }
        .onChange(of: downloads.lastError) { _, m in if let m { ui.toast = m; downloads.lastError = nil } }
    }

    /// tunio://pair?u=…&k=… (home server QR code), tunio://search?q=…, tunio://play?q=… (plays the top YouTube result;
    /// handy for Shortcuts and Siri).
    private func open(_ url: URL) {
        guard url.scheme == "tunio" else { return }
        let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "q" }?.value ?? ""
        switch url.host {
        case "pair":
            HomeServer.shared.pair(url)
            ui.toast = "Home server added"
        case "search":
            ui.showPlayer = false
            tab = 1
            ui.pendingSearch = q
        case "play" where !q.isEmpty:
            Task {
                do {
                    guard let first = try await Engine.search(q, in: .youtube).first else { ui.toast = "Nothing found for “\(q)”"; return }
                    player.play([first], context: "Search")
                    ui.showPlayer = true
                } catch { ui.toast = error.localizedDescription }
            }
        default: break
        }
    }

    @ViewBuilder private var mini: some View {
        if player.current != nil { MiniPlayer().padding(.horizontal, 8).padding(.bottom, 4) }
    }

    @ViewBuilder private var toast: some View {
        if let t = ui.toast {
            Text(t).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.c60).multilineTextAlignment(.center)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(Theme.text, in: Capsule()).padding(.horizontal, 24).padding(.top, 6)
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: t) {
                    try? await Task.sleep(nanoseconds: 3_200_000_000)
                    withAnimation { if ui.toast == t { ui.toast = nil } }
                }
                .onTapGesture { withAnimation { ui.toast = nil } }
        }
    }
}

extension View {
    func withRoutes() -> some View {
        navigationDestination(for: Route.self) { r in
            switch r {
            case .liked: CollectionView(kind: .liked)
            case .downloads: CollectionView(kind: .downloads)
            case .recent: CollectionView(kind: .recent)
            case .playlist(let id): CollectionView(kind: .playlist(id))
            case .settings: SettingsView()
            }
        }
        .background(Theme.c60.ignoresSafeArea())
        .scrollContentBackground(.hidden)
    }
}

struct MiniPlayer: View {
    @ObservedObject private var player = Player.shared
    @ObservedObject private var clock = Clock.shared
    @EnvironmentObject private var ui: UIState

    var body: some View {
        if let t = player.current {
            HStack(spacing: 10) {
                Cover(url: t.thumbnail, trackID: t.id, size: 44, corner: 8)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(player.isLoading ? "Loading…" : t.artist).font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                }
                Spacer(minLength: 4)
                if player.isLoading {
                    ProgressView().frame(width: 40, height: 40)
                } else {
                    Button { player.toggle() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title3).contentTransition(.symbolEffect(.replace)).frame(width: 40, height: 40)
                    }
                }
                Button { player.next() } label: { Image(systemName: "forward.fill").font(.title3).frame(width: 40, height: 40) }
            }
            .foregroundStyle(Theme.text)
            .padding(8)
            .overlay(alignment: .bottom) {
                GeometryReader { g in
                    Capsule().fill(Theme.text)
                        .frame(width: g.size.width * (clock.duration > 0 ? min(1, clock.time / clock.duration) : 0), height: 2)
                }.frame(height: 2).padding(.horizontal, 14)
            }
            .glassy(cornerRadius: 18)
            .contentShape(Rectangle())
            .onTapGesture { ui.showPlayer = true }
            .gesture(DragGesture(minimumDistance: 20).onEnded { v in
                if v.translation.height < -30 { ui.showPlayer = true }
                else if v.translation.width < -60 { player.next() }
                else if v.translation.width > 60 { player.previous() }
            })
        }
    }
}
