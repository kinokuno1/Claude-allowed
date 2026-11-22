import SwiftUI

struct SearchView: View {
    @ObservedObject private var player = Player.shared
    @ObservedObject private var library = Library.shared
    @State private var query = ""
    @State private var source: Engine.SearchSource = .youtube
    @State private var result: TrackList?
    @State private var fromLink = false
    @State private var loading = false
    @State private var error: String?
    @State private var coversLeft = 0
    @State private var token = 0
    @EnvironmentObject private var ui: UIState

    var body: some View {
        List {
            if result == nil && !loading && error == nil {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Search YouTube or SoundCloud", systemImage: "magnifyingglass").font(.headline)
                        Text("Or paste a link: YouTube, SoundCloud or Spotify (song, playlist or album). Spotify songs play from YouTube.\n\nPrivate Spotify playlist? In the Spotify app, select the songs, copy their links and paste them here.")
                            .font(.subheadline).foregroundStyle(Theme.muted)
                        PasteButton(payloadType: String.self) { strings in
                            guard let s = strings.first else { return }
                            Task { @MainActor in query = s; run() }
                        }
                        .buttonBorderShape(.capsule).labelStyle(.titleAndIcon).tint(Theme.c30hi).padding(.top, 4)
                    }.padding(.vertical, 6)
                }.listRowBackground(Theme.c30)
            }
            if loading {
                HStack { Spacer(); ProgressView(fromLink ? "Reading link…" : "Searching…"); Spacer() }.listRowBackground(Color.clear).padding(.vertical, 30)
            }
            if let error {
                Text(error).foregroundStyle(Color(red: 1, green: 0.79, blue: 0.79)).listRowBackground(Color.clear)
            }
            if let r = result, !r.tracks.isEmpty {
                if fromLink && r.tracks.count > 1 {
                    Section {
                        HStack(spacing: 14) {
                            Cover(url: r.cover ?? r.tracks.first?.thumbnail, size: 96, corner: 8)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Imported list").font(.caption.bold()).foregroundStyle(Theme.muted)
                                Text(r.title ?? "Imported list").font(.title3.bold()).lineLimit(2)
                                Text("\(r.tracks.count) songs" + (coversLeft > 0 ? " · getting covers (\(coversLeft) left)…" : "")).font(.footnote).foregroundStyle(Theme.muted)
                            }
                        }
                        HStack(spacing: 14) {
                            PlayFab(size: 50) { player.play(r.tracks, at: 0, shuffled: false, context: r.title) }
                            Button { player.play(r.tracks, at: Int.random(in: 0..<r.tracks.count), shuffled: true, context: r.title) } label: {
                                Image(systemName: "shuffle").font(.title3).frame(width: 44, height: 44)
                            }.buttonStyle(.plain)
                            Spacer()
                            Button {
                                let p = library.createPlaylist(r.title ?? "Imported", tracks: r.tracks, sourceUrl: r.sourceUrl, cover: r.cover)
                                ui.toast = "Saved as playlist “\(p.name)”"
                            } label: { Label("Save as playlist", systemImage: "text.badge.plus").font(.subheadline.bold()) }
                                .buttonStyle(.bordered).tint(Theme.text)
                        }
                    }.listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
                Section {
                    ForEach(Array(r.tracks.enumerated()), id: \.element.id) { i, t in
                        TrackRow(track: t, number: fromLink && r.tracks.count > 1 ? i + 1 : nil) {
                            if fromLink { player.play(r.tracks, at: i, shuffled: false, context: r.title ?? "Search") }
                            else { player.play([t], context: "Search") } // one song; autoplay adds similar ones
                        }
                    }
                }.listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Songs, artists, or paste a link")
        .searchScopes($source) { ForEach(Engine.SearchSource.allCases) { Text($0.rawValue).tag($0) } }
        .onSubmit(of: .search) { run() }
        .onChange(of: query) { old, new in
            // a pasted link opens right away
            if new.count - old.count > 8, Engine.isLink(new) { run() }
            if new.isEmpty { result = nil; error = nil }
        }
        .onChange(of: source) { _, _ in if !query.isEmpty && !Engine.isLink(query) { run() } }
        .onChange(of: ui.pendingSearch) { _, _ in takePending() }
        .onAppear { takePending() }
    }

    private func takePending() {
        guard let q = ui.pendingSearch, !q.isEmpty else { return }
        ui.pendingSearch = nil
        query = q
        run()
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        token += 1
        let my = token
        fromLink = Engine.isLink(q)
        loading = true; error = nil; result = nil; coversLeft = 0
        Task {
            do {
                let r: TrackList
                if fromLink { r = try await Engine.resolve(q) } else { r = TrackList(title: nil, tracks: try await Engine.search(q, in: source)) }
                guard my == token else { return }
                result = r
                if r.tracks.isEmpty { error = "Nothing found." }
                // get the top result ready while you read the list
                if let first = r.tracks.first { Task.detached(priority: .utility) { _ = try? await Engine.audio(for: first) } }
                if fromLink { await fillCovers(my) }
            } catch {
                if my == token { self.error = error.localizedDescription }
            }
            if my == token { loading = false }
        }
    }

    /// Spotify lists only name their songs: look up each song's own cover, a few at a time.
    private func fillCovers(_ my: Int) async {
        loading = false
        guard let r = result else { return }
        let need = r.tracks.indices.filter { r.tracks[$0].source == .spotify }
        coversLeft = need.count
        for chunk in stride(from: 0, to: need.count, by: 8).map({ Array(need[$0..<min($0 + 8, need.count)]) }) {
            let found: [(Int, String?)] = await withTaskGroup(of: (Int, String?).self) { g in
                for i in chunk { let t = r.tracks[i]; g.addTask { (i, await Spotify.cover(t)) } }
                var out: [(Int, String?)] = []
                for await x in g { out.append(x) }
                return out
            }
            guard my == token, var cur = result else { return }
            for (i, url) in found where cur.tracks.indices.contains(i) {
                if let url { cur.tracks[i].thumbnail = url }
                cur.tracks[i].artChecked = true
            }
            result = cur
            coversLeft = max(0, coversLeft - chunk.count)
        }
    }
}
