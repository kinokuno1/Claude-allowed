import SwiftUI

struct LibraryView: View {
    @ObservedObject private var library = Library.shared
    @ObservedObject private var downloads = Downloads.shared
    @State private var naming = false
    @State private var newName = ""

    var body: some View {
        List {
            if library.coversLeft > 0 {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Getting song covers… \(library.coversLeft) left").font(.footnote).foregroundStyle(Theme.muted)
                }
                .listRowBackground(Color.clear)
            }
            Section {
                NavigationLink(value: Route.liked) {
                    row(Cover(size: 56, corner: 6, icon: "heart.fill", gradient: Theme.accentGradient), "Liked songs", "Playlist · \(library.liked.count) songs")
                }
                NavigationLink(value: Route.downloads) {
                    row(Cover(size: 56, corner: 6, icon: "arrow.down.circle.fill", gradient: Theme.downloadsGradient), "Downloads", "\(downloads.offline.count) songs on this iPhone")
                }
                NavigationLink(value: Route.recent) {
                    row(Cover(size: 56, corner: 6, icon: "clock.fill", gradient: LinearGradient(colors: [Theme.c30hi, Theme.c30], startPoint: .top, endPoint: .bottom)),
                        "Recently played", "\(library.recent.count) songs")
                }
            }.listRowBackground(Color.clear)
            Section("Playlists") {
                ForEach(library.playlists) { p in
                    NavigationLink(value: Route.playlist(p.id)) {
                        row(Cover(url: library.cover(of: p), size: 56, corner: 6), p.name,
                            "Playlist · \(p.trackIds.count) songs" + (allOffline(p) ? " · offline" : ""))
                    }
                }
                .onDelete { idx in idx.map { library.playlists[$0].id }.forEach(library.deletePlaylist) }
                Button { newName = ""; naming = true } label: { Label("Create playlist", systemImage: "plus") }.foregroundStyle(Theme.accentLight)
            }.listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .navigationTitle("Your Library")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { newName = ""; naming = true } label: { Image(systemName: "plus") } } }
        .alert("Create playlist", isPresented: $naming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Create") { library.createPlaylist(newName.isEmpty ? "My playlist #\(library.playlists.count + 1)" : newName) }
        }
    }

    private func allOffline(_ p: Playlist) -> Bool { !p.trackIds.isEmpty && p.trackIds.allSatisfy { downloads.offline.contains($0) } }

    private func row(_ cover: Cover, _ title: String, _ sub: String) -> some View {
        HStack(spacing: 12) {
            cover
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.body.weight(.semibold)).lineLimit(1)
                Text(sub).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
            }
        }
    }
}

struct CollectionView: View {
    enum Kind: Equatable { case liked, downloads, recent, playlist(String) }
    let kind: Kind

    @ObservedObject private var library = Library.shared
    @ObservedObject private var downloads = Downloads.shared
    @ObservedObject private var player = Player.shared
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmDelete = false

    private var playlist: Playlist? { if case .playlist(let id) = kind { return library.playlist(id) }; return nil }
    private var title: String {
        switch kind {
        case .liked: return "Liked songs"
        case .downloads: return "Downloads"
        case .recent: return "Recently played"
        case .playlist: return playlist?.name ?? "Playlist"
        }
    }
    private var tracks: [Track] {
        switch kind {
        case .liked: return library.tracks(library.liked)
        case .recent: return library.tracks(library.recent)
        case .downloads: return library.tracks.values.filter { downloads.offline.contains($0.id) }.sorted { $0.title.lowercased() < $1.title.lowercased() }
        case .playlist: return library.tracks(playlist?.trackIds ?? [])
        }
    }
    private var coverURL: String? { playlist.flatMap { library.cover(of: $0) } }

    var body: some View {
        let list = tracks
        let isContext = player.context == title && player.current != nil
        List {
            VStack(spacing: 14) {
                Group {
                    switch kind {
                    case .liked: Cover(size: 200, corner: 10, icon: "heart.fill", gradient: Theme.accentGradient)
                    case .downloads: Cover(size: 200, corner: 10, icon: "arrow.down.circle.fill", gradient: Theme.downloadsGradient)
                    case .recent: Cover(size: 200, corner: 10, icon: "clock.fill", gradient: LinearGradient(colors: [Theme.c30hi, Theme.c30], startPoint: .top, endPoint: .bottom))
                    case .playlist: Cover(url: coverURL, size: 200, corner: 10)
                    }
                }.shadow(color: .black.opacity(0.5), radius: 20, y: 10)
                VStack(spacing: 4) {
                    Text(title).font(.title.bold()).multilineTextAlignment(.center)
                    Text("\(list.count) songs" + duration(list)).font(.footnote).foregroundStyle(Theme.muted)
                }
                HStack(spacing: 18) {
                    downloadButton(list)
                    if playlist != nil {
                        Menu {
                            Button { newName = title; renaming = true } label: { Label("Rename", systemImage: "pencil") }
                            Button(role: .destructive) { confirmDelete = true } label: { Label("Delete playlist", systemImage: "trash") }
                        } label: { Image(systemName: "ellipsis").font(.title3).frame(width: 40, height: 40) }
                    }
                    Spacer()
                    Button { if !list.isEmpty { player.play(list, at: Int.random(in: 0..<list.count), shuffled: true, context: title) } } label: {
                        Image(systemName: "shuffle").font(.title2).foregroundStyle(player.shuffle ? Theme.accentLight : Theme.text).frame(width: 44, height: 44)
                    }.buttonStyle(.plain)
                    PlayFab(playing: isContext && player.isPlaying) {
                        if isContext { player.toggle() } else if !list.isEmpty { player.play(list, at: 0, shuffled: false, context: title) }
                    }.disabled(list.isEmpty)
                }
            }
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear).listRowSeparator(.hidden).padding(.top, 8)

            if list.isEmpty {
                Text(emptyText).foregroundStyle(Theme.muted).listRowBackground(Color.clear)
            }
            ForEach(Array(list.enumerated()), id: \.element.id) { i, t in
                TrackRow(track: t, number: i + 1, playlistID: playlist?.id) { player.play(list, at: i, shuffled: false, context: title) }
                    .listRowBackground(Color.clear)
                    .swipeActions(edge: .leading) {
                        Button { player.playNext(t) } label: { Label("Play next", systemImage: "text.insert") }.tint(Theme.accent)
                    }
            }
            .onDelete(perform: deletable ? delete : nil)
            .onMove(perform: playlist != nil ? move : nil)
        }
        .listStyle(.plain)
        .background(alignment: .top) { header }
        .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        .toolbar { if playlist != nil && !list.isEmpty { EditButton() } }
        .alert("Rename playlist", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Save") { if let p = playlist { library.rename(p.id, newName) } }
        }
        .confirmationDialog("Delete “\(title)”?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete playlist", role: .destructive) { if let p = playlist { library.deletePlaylist(p.id); dismiss() } }
        } message: { Text("Downloaded songs stay in Downloads.") }
    }

    private var deletable: Bool { kind != .recent }

    private func move(_ from: IndexSet, _ to: Int) {
        if let p = playlist { library.move(in: p.id, from: from, to: to) }
    }

    private func delete(_ idx: IndexSet) {
        let list = tracks
        for i in idx where list.indices.contains(i) {
            let t = list[i]
            switch kind {
            case .liked: library.toggleLike(t)
            case .downloads: downloads.remove(t)
            case .playlist(let id): library.remove(t.id, from: id)
            case .recent: break
            }
        }
    }

    @ViewBuilder private var header: some View {
        if let c = coverURL, let u = URL(string: c) {
            AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                .frame(height: 420).blur(radius: 60).opacity(0.55).clipped()
                .overlay(LinearGradient(colors: [.clear, Theme.c60], startPoint: .top, endPoint: .bottom))
                .ignoresSafeArea()
        } else {
            LinearGradient(colors: [(kind == .liked ? Theme.likedColor : Theme.downloadsColor).opacity(0.45), .clear],
                           startPoint: .top, endPoint: .bottom).frame(height: 420).ignoresSafeArea()
        }
    }

    @ViewBuilder private func downloadButton(_ list: [Track]) -> some View {
        let missing = list.filter { !downloads.offline.contains($0.id) }
        let busy = list.filter { downloads.progress[$0.id] != nil }.count
        if kind == .downloads || list.isEmpty {
            EmptyView()
        } else if missing.isEmpty {
            Image(systemName: "arrow.down.circle.fill").font(.title2).foregroundStyle(Theme.good)
        } else if busy > 0 {
            HStack(spacing: 6) { ProgressView(); Text("\(busy)").font(.footnote).foregroundStyle(Theme.muted) }
        } else {
            Button { downloads.download(missing) } label: { Image(systemName: "arrow.down.circle").font(.title2).frame(width: 40, height: 40) }
                .buttonStyle(.plain).accessibilityLabel("Download \(missing.count) songs")
        }
    }

    private var emptyText: String {
        switch kind {
        case .liked: return "Songs you like appear here. Long-press a song → Save to Liked songs."
        case .downloads: return "Nothing downloaded yet. Long-press a song → Download, or tap ⤓ on a playlist."
        case .recent: return "Songs you play appear here."
        case .playlist: return "This playlist is empty. Search for songs, long-press → Add to playlist."
        }
    }

    private func duration(_ list: [Track]) -> String {
        let s = list.reduce(0) { $0 + $1.durationSec }
        guard s > 0 else { return "" }
        return s >= 3600 ? ", about \(s / 3600) h \(s % 3600 / 60) min" : ", about \(s / 60) min"
    }
}
