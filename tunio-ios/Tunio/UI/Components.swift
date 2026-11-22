import SwiftUI

enum Route: Hashable {
    case liked, downloads, recent, playlist(String), settings
}

/// A song or playlist cover. If the image fails to load, the song's cover is looked up again.
struct Cover: View {
    var url: String?
    var trackID: String? = nil
    var size: CGFloat? = nil
    var corner: CGFloat = 6
    var icon = "music.note"
    var gradient: LinearGradient? = nil

    var body: some View {
        ZStack {
            if let gradient {
                gradient
                Image(systemName: icon).font(.system(size: (size ?? 60) * 0.4, weight: .semibold)).foregroundStyle(.white)
            } else {
                Theme.c30hi
                if let u = url, let url = URL(string: u) {
                    AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                        switch phase {
                        case .success(let img): img.resizable().scaledToFill()
                        case .failure: placeholder.onAppear { if let id = trackID { Library.shared.coverFailed(id) } }
                        default: Color.clear
                        }
                    }
                } else { placeholder }
            }
        }
        .frame(width: size, height: size)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }

    private var placeholder: some View {
        Image(systemName: icon).font(.system(size: (size ?? 60) * 0.35)).foregroundStyle(Theme.muted)
    }
}

struct SourceBadge: View {
    let source: Source
    var body: some View {
        Text(source.short).font(.system(size: 9, weight: .black)).foregroundStyle(.black)
            .padding(.horizontal, 4).padding(.vertical, 1)
            .background(Theme.sourceColor(source), in: RoundedRectangle(cornerRadius: 3))
    }
}

/// The little moving bars next to the song that's playing.
struct PlayingBars: View {
    var playing: Bool
    var body: some View {
        TimelineView(.animation(paused: !playing)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<3) { i in
                    RoundedRectangle(cornerRadius: 1).fill(Theme.accentLight)
                        .frame(width: 3, height: playing ? 4 + 10 * abs(sin(t * 4 + Double(i) * 1.3)) : 4)
                }
            }.frame(width: 14, height: 14, alignment: .bottom)
        }
    }
}

struct OfflineMark: View {
    let track: Track
    @ObservedObject private var downloads = Downloads.shared
    var body: some View {
        if downloads.isOffline(track) {
            Image(systemName: "arrow.down.circle.fill").font(.system(size: 12)).foregroundStyle(Theme.good)
        } else if let p = downloads.progress[track.id] {
            ProgressView(value: p).progressViewStyle(.circular).scaleEffect(0.45).frame(width: 12, height: 12).tint(Theme.accentLight)
        }
    }
}

/// One song in a list. Tap plays it; the ⋯ menu (or long press) has the rest.
struct TrackRow: View {
    let track: Track
    var number: Int? = nil
    var playlistID: String? = nil
    var onTap: () -> Void
    @ObservedObject private var player = Player.shared

    var body: some View {
        let current = player.current?.id == track.id
        HStack(spacing: 0) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    if let number {
                        Group {
                            if current { PlayingBars(playing: player.isPlaying) } else { Text("\(number)").foregroundStyle(Theme.muted) }
                        }.font(.subheadline.monospacedDigit()).frame(width: 22)
                    }
                    Cover(url: track.thumbnail, trackID: track.id, size: 48)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title).font(.body.weight(.medium)).foregroundStyle(current ? Theme.accentLight : Theme.text).lineLimit(1)
                        HStack(spacing: 5) {
                            SourceBadge(source: track.source)
                            OfflineMark(track: track)
                            Text([track.artist, formatDuration(track.durationSec)].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu { TrackMenu(track: track, playlistID: playlistID) } label: {
                Image(systemName: "ellipsis").font(.body.weight(.semibold)).foregroundStyle(Theme.muted)
                    .frame(width: 36, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .contextMenu { TrackMenu(track: track, playlistID: playlistID) }
    }
}

struct TrackMenu: View {
    let track: Track
    var playlistID: String? = nil
    @ObservedObject private var library = Library.shared
    @ObservedObject private var downloads = Downloads.shared
    @EnvironmentObject private var ui: UIState

    var body: some View {
        Button { Player.shared.playNext(track) } label: { Label("Play next", systemImage: "text.insert") }
        Button { Player.shared.addToQueue(track) } label: { Label("Add to queue", systemImage: "text.append") }
        Divider()
        Button { library.toggleLike(track) } label: {
            Label(library.isLiked(track) ? "Remove from Liked songs" : "Save to Liked songs", systemImage: library.isLiked(track) ? "heart.slash" : "heart")
        }
        Button { ui.addToPlaylist = [track] } label: { Label("Add to playlist", systemImage: "text.badge.plus") }
        if downloads.isOffline(track) {
            Button(role: .destructive) { downloads.remove(track) } label: { Label("Remove download", systemImage: "trash") }
        } else {
            Button { downloads.download([track]) } label: { Label("Download", systemImage: "arrow.down.circle") }
        }
        if let playlistID {
            Divider()
            Button(role: .destructive) { library.remove(track.id, from: playlistID) } label: { Label("Remove from this playlist", systemImage: "minus.circle") }
        }
    }
}

/// The round accent play button (10 % colour).
struct PlayFab: View {
    var playing = false
    var size: CGFloat = 56
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: size * 0.42, weight: .bold)).foregroundStyle(.white)
                .frame(width: size, height: size).background(Theme.accentGradient, in: Circle())
                .shadow(color: .black.opacity(0.35), radius: 10, y: 6)
        }.buttonStyle(.plain)
    }
}

/// Shared UI state: sheets and dialogs opened from anywhere.
@MainActor
final class UIState: ObservableObject {
    @Published var showPlayer = false
    @Published var addToPlaylist: [Track]? = nil
    @Published var toast: String?
    /// A search asked for from outside (tunio://search?q=…)
    @Published var pendingSearch: String?
}

struct AddToPlaylistSheet: View {
    let tracks: [Track]
    @ObservedObject private var library = Library.shared
    @Environment(\.dismiss) private var dismiss
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("New playlist", text: $newName)
                        Button("Create") {
                            library.createPlaylist(newName.isEmpty ? "My playlist" : newName, tracks: tracks, cover: tracks.first?.thumbnail)
                            dismiss()
                        }.bold()
                    }
                }
                Section("Your playlists") {
                    ForEach(library.playlists) { p in
                        Button { library.add(tracks, to: p.id); dismiss() } label: {
                            HStack(spacing: 12) {
                                Cover(url: library.cover(of: p), size: 44)
                                VStack(alignment: .leading) { Text(p.name).foregroundStyle(Theme.text); Text("\(p.trackIds.count) songs").font(.footnote).foregroundStyle(Theme.muted) }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden).background(Theme.c60)
            .navigationTitle(tracks.count == 1 ? "Add to playlist" : "Add \(tracks.count) songs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}
