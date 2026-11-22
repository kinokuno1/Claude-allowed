import Foundation

/// Liked songs, playlists and recently played. Saved as library.json (same format as Tunio for Windows).
@MainActor
final class Library: ObservableObject {
    static let shared = Library()

    private struct Store: Codable {
        var tracks: [String: Track] = [:]
        var liked: [String] = []
        var playlists: [Playlist] = []
        var recent: [String] = []
    }

    @Published private(set) var tracks: [String: Track] = [:]
    @Published private(set) var liked: [String] = []
    @Published private(set) var playlists: [Playlist] = []
    @Published private(set) var recent: [String] = []

    private let file: URL
    private var saveTask: Task<Void, Never>?

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Tunio")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent("library.json")
        if let raw = try? Data(contentsOf: file), let d = try? JSONDecoder().decode(Store.self, from: raw) {
            tracks = d.tracks; liked = d.liked; playlists = d.playlists; recent = d.recent
        }
    }

    private func save() {
        saveTask?.cancel()
        let snapshot = Store(tracks: tracks, liked: liked, playlists: playlists, recent: recent)
        let file = self.file
        saveTask = Task.detached(priority: .utility) {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            if let raw = try? JSONEncoder().encode(snapshot) { try? raw.write(to: file, options: .atomic) }
        }
    }

    func track(_ id: String) -> Track? { tracks[id] }
    func tracks(_ ids: [String]) -> [Track] { ids.compactMap { tracks[$0] } }
    func isLiked(_ t: Track) -> Bool { liked.contains(t.id) }
    func playlist(_ id: String) -> Playlist? { playlists.first { $0.id == id } }

    func remember(_ list: [Track]) {
        for t in list {
            var n = t
            if let old = tracks[t.id] {
                if n.matchedUrl == nil { n.matchedUrl = old.matchedUrl }
                if old.artChecked == true { n.artChecked = true; n.thumbnail = old.thumbnail ?? n.thumbnail }
            }
            tracks[t.id] = n
        }
        save()
    }
    func remember(_ t: Track) { remember([t]) }

    func toggleLike(_ t: Track) {
        if let i = liked.firstIndex(of: t.id) { liked.remove(at: i) } else { remember(t); liked.insert(t.id, at: 0) }
        save()
    }

    func addRecent(_ t: Track) {
        remember(t)
        recent.removeAll { $0 == t.id }
        recent.insert(t.id, at: 0)
        if recent.count > 50 { recent = Array(recent.prefix(50)) }
        save()
    }

    @discardableResult
    func createPlaylist(_ name: String, tracks list: [Track] = [], sourceUrl: String? = nil, cover: String? = nil) -> Playlist {
        remember(list)
        var ids: [String] = []
        for t in list where !ids.contains(t.id) { ids.append(t.id) }
        let p = Playlist(id: UUID().uuidString.lowercased(), name: name.isEmpty ? "My playlist" : name, trackIds: ids, sourceUrl: sourceUrl, cover: cover)
        playlists.append(p)
        save()
        fillArtwork()
        return p
    }

    func add(_ list: [Track], to pid: String) {
        guard let i = playlists.firstIndex(where: { $0.id == pid }) else { return }
        remember(list)
        for t in list where !playlists[i].trackIds.contains(t.id) { playlists[i].trackIds.append(t.id) }
        save()
        fillArtwork()
    }

    func remove(_ tid: String, from pid: String) {
        guard let i = playlists.firstIndex(where: { $0.id == pid }) else { return }
        playlists[i].trackIds.removeAll { $0 == tid }
        save()
    }

    func move(in pid: String, from: IndexSet, to: Int) {
        guard let i = playlists.firstIndex(where: { $0.id == pid }) else { return }
        playlists[i].trackIds.move(fromOffsets: from, toOffset: to)
        save()
    }

    func rename(_ pid: String, _ name: String) {
        guard let i = playlists.firstIndex(where: { $0.id == pid }), !name.isEmpty else { return }
        playlists[i].name = name
        save()
    }

    func deletePlaylist(_ pid: String) {
        playlists.removeAll { $0.id == pid }
        save()
    }

    func cover(of p: Playlist) -> String? { p.cover ?? p.trackIds.first.flatMap { tracks[$0]?.thumbnail } }

    // MARK: covers

    private var artBusy = false

    /// Looks up covers for songs without one, and for Spotify songs that only had their playlist's cover.
    func fillArtwork() {
        guard !artBusy else { return }
        let todo = tracks.values.filter { $0.artChecked != true && ($0.thumbnail == nil || $0.source == .spotify) }
        guard !todo.isEmpty else { return }
        artBusy = true
        Task {
            for chunk in stride(from: 0, to: todo.count, by: 6).map({ Array(todo[$0..<min($0 + 6, todo.count)]) }) {
                let found: [(String, String?)] = await withTaskGroup(of: (String, String?).self) { g in
                    for t in chunk { g.addTask { (t.id, await Artwork.lookup(t)) } }
                    var out: [(String, String?)] = []
                    for await r in g { out.append(r) }
                    return out
                }
                for (id, url) in found {
                    guard var t = tracks[id] else { continue }
                    if let url { t.thumbnail = url }
                    t.artChecked = true
                    tracks[id] = t
                }
                save()
            }
            artBusy = false
        }
    }

    /// A cover didn't load (expired link…): look it up again.
    func coverFailed(_ id: String) {
        guard var t = tracks[id], t.artChecked != true || t.thumbnail != nil else { return }
        let broken = t.thumbnail
        t.artChecked = true
        tracks[id] = t
        Task {
            if let u = await Artwork.lookup(t), u != broken, var cur = tracks[id] { cur.thumbnail = u; tracks[id] = cur; save() }
        }
    }
}
