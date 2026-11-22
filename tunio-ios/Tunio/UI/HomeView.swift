import SwiftUI

struct HomeView: View {
    @ObservedObject private var library = Library.shared
    @ObservedObject private var downloads = Downloads.shared
    @ObservedObject private var player = Player.shared
    @ObservedObject private var home = HomeServer.shared

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 18 ? "Good afternoon" : "Good evening"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                    QuickTile(title: "Liked songs", route: .liked) { Cover(size: 56, corner: 0, icon: "heart.fill", gradient: Theme.accentGradient) }
                    QuickTile(title: "Downloads", route: .downloads) {
                        Cover(size: 56, corner: 0, icon: "arrow.down.circle.fill", gradient: Theme.downloadsGradient)
                    }
                    ForEach(library.playlists.prefix(6)) { p in
                        QuickTile(title: p.name, route: .playlist(p.id)) { Cover(url: library.cover(of: p), size: 56, corner: 0) }
                    }
                }

                let recent = library.tracks(library.recent).prefix(15).map { $0 }
                if !recent.isEmpty {
                    Shelf(title: "Recently played", more: .recent) {
                        ForEach(Array(recent.enumerated()), id: \.element.id) { i, t in
                            Button { player.play(recent, at: i, shuffled: false, context: "Recently played") } label: {
                                CardLabel(cover: Cover(url: t.thumbnail, trackID: t.id, size: 140, corner: 8), title: t.title, subtitle: t.artist)
                            }.buttonStyle(.plain).contextMenu { TrackMenu(track: t) }
                        }
                    }
                }
                if !library.playlists.isEmpty {
                    Shelf(title: "Your playlists", more: nil) {
                        ForEach(library.playlists) { p in
                            NavigationLink(value: Route.playlist(p.id)) {
                                CardLabel(cover: Cover(url: library.cover(of: p), size: 140, corner: 8), title: p.name, subtitle: "Playlist · \(p.trackIds.count) songs")
                            }.buttonStyle(.plain)
                        }
                    }
                }
                if recent.isEmpty && library.playlists.isEmpty && library.liked.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Welcome to Tunio").font(.title3.bold())
                        Text("Search YouTube or SoundCloud, or paste a YouTube, SoundCloud or Spotify link (song, playlist or album) in Search.\n\nLong-press any song to download it for offline listening.")
                            .foregroundStyle(Theme.muted)
                        if case .notSetUp = home.status {
                            NavigationLink(value: Route.settings) { Label("Have a Tunio home server? Connect it", systemImage: "server.rack") }
                                .font(.subheadline.bold())
                        }
                    }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(Theme.c30, in: RoundedRectangle(cornerRadius: 14))
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .background(alignment: .top) {
            LinearGradient(colors: [Theme.accent.opacity(0.35), .clear], startPoint: .top, endPoint: .bottom).frame(height: 320).ignoresSafeArea()
        }
        .navigationTitle(greeting)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink(value: Route.settings) { Image(systemName: "gearshape") } } }
    }
}

struct QuickTile<C: View>: View {
    let title: String
    let route: Route
    @ViewBuilder var cover: () -> C
    var body: some View {
        NavigationLink(value: route) {
            HStack(spacing: 10) {
                cover().frame(width: 56, height: 56)
                Text(title).font(.subheadline.bold()).foregroundStyle(Theme.text).lineLimit(2).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        }.buttonStyle(.plain)
    }
}

struct Shelf<C: View>: View {
    let title: String
    let more: Route?
    @ViewBuilder var content: () -> C
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(.title2.bold())
                Spacer()
                if let more { NavigationLink(value: more) { Text("Show all").font(.footnote.bold()).foregroundStyle(Theme.muted) } }
            }
            ScrollView(.horizontal, showsIndicators: false) { LazyHStack(alignment: .top, spacing: 14) { content() } }
        }
    }
}

struct CardLabel: View {
    let cover: Cover
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            cover.shadow(color: .black.opacity(0.4), radius: 8, y: 4)
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
            Text(subtitle).font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
        }.frame(width: 140, alignment: .leading)
    }
}
