import Foundation

/// Everything the app asks for (search, links, audio, autoplay): through the home server when it answers,
/// otherwise on the phone.
@MainActor
enum Engine {
    enum SearchSource: String, CaseIterable, Identifiable { case youtube = "YouTube", soundcloud = "SoundCloud"; var id: String { rawValue } }

    private static var recent: [String: ([Track], Date)] = [:]

    static func search(_ query: String, in source: SearchSource) async throws -> [Track] {
        let key = "\(source.rawValue)|\(query.lowercased())"
        if case let (hit, at)? = recent[key], Date().timeIntervalSince(at) < 600 { return hit }
        let out: [Track]
        switch source {
        case .youtube: out = try await searchYouTube(query)
        case .soundcloud:
            if let j = await HomeServer.shared.flat("scsearch25:\(query)") { out = FlatParser.tracks(j).tracks }
            else { out = try await SoundCloud.search(query) }
        }
        recent[key] = (out, Date())
        return out
    }

    static func searchYouTube(_ query: String, limit: Int = 25) async throws -> [Track] {
        do { return try await YT.search(query, limit: limit) }
        catch {
            if let j = await HomeServer.shared.flat("ytsearch\(limit):\(query)") { return FlatParser.tracks(j).tracks }
            throw error
        }
    }

    static func isLink(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.range(of: #"https?://\S+"#, options: .regularExpression) != nil || t.hasPrefix("spotify:")
            || t.range(of: #"^(www\.|m\.|vm\.)?[a-z0-9-]+\.(com|be|link|fi)/\S+"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// A pasted link (song, playlist, album, mix) or several pasted Spotify song links.
    static func resolve(_ text: String) async throws -> TrackList {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.contains("://") && !t.hasPrefix("spotify:") { t = "https://" + t }
        // several Spotify songs pasted at once (select songs in the Spotify app, copy, paste)
        let spotifyTracks = SoundCloud.matches(#"open\.spotify\.com/track/[A-Za-z0-9]+"#, in: t)
        if spotifyTracks.count > 1 {
            var tracks: [Track] = []
            await withTaskGroup(of: (Int, Track?).self) { g in
                for (i, link) in spotifyTracks.enumerated() {
                    g.addTask { (i, try? await Spotify.resolve("https://" + link).tracks.first) }
                }
                var got: [(Int, Track)] = []
                for await (i, tr) in g { if let tr { got.append((i, tr)) } }
                tracks = got.sorted { $0.0 < $1.0 }.map(\.1)
            }
            guard !tracks.isEmpty else { throw TunioError("Couldn't read these Spotify songs") }
            return TrackList(title: "Pasted Spotify songs", tracks: tracks, cover: tracks.first?.thumbnail)
        }
        if Spotify.isLink(t) { return try await Spotify.resolve(t) }
        if let server = await HomeServer.shared.flat(t), !FlatParser.tracks(server).tracks.isEmpty {
            var r = FlatParser.tracks(server); r.sourceUrl = r.sourceUrl ?? t; return r
        }
        if t.contains("youtu") {
            if let list = YouTubeLink.playlistID(t), YouTubeLink.videoID(t) == nil || list.hasPrefix("PL") || list.hasPrefix("OL") {
                return try await YT.playlist(list)
            }
            if let id = YouTubeLink.videoID(t) { return TrackList(title: nil, tracks: [try await YT.video(id)]) }
        }
        if t.contains("soundcloud.com") || t.contains("on.soundcloud") { return try await SoundCloud.resolve(t) }
        if t.contains("tiktok.com") { throw TunioError("TikTok links need your Tunio home server (Settings → Home server).") }
        throw TunioError("Tunio can't open this link. It works with YouTube, SoundCloud and Spotify links.")
    }

    enum Audio {
        case remote(URL, headers: [String: String])
        case file(URL)
    }

    /// Where a song's audio comes from. `matched` is the track with its YouTube match (Spotify songs).
    static func audio(for track: Track) async throws -> (Audio, Track) {
        if let f = Downloads.shared.file(for: track) { return (.file(f), track) }
        var t = track
        if t.source == .spotify { t = try await Spotify.matchOnYouTube(t) }
        guard let target = t.playTarget else { throw TunioError("No link to play “\(t.title)”") }
        let home = HomeServer.shared
        if await home.check() {
            return (.remote(home.audioURL(target: target, track: t), headers: home.authHeader), t)
        }
        if let id = t.youtubeID ?? YouTubeLink.videoID(target) {
            return (.remote(try await YT.audioURL(videoID: id), headers: [:]), t)
        }
        if t.source == .soundcloud || target.contains("soundcloud.com") {
            return (.remote(try await SoundCloud.audioURL(target).url, headers: [:]), t)
        }
        throw TunioError("“\(t.title)” needs your Tunio home server to play")
    }

    /// Songs like this one (autoplay): YouTube's Mix for the song.
    static func similar(to t: Track, excluding: Set<String>, max: Int = 10) async -> [Track] {
        var seed = t
        if seed.source == .spotify { seed = (try? await Spotify.matchOnYouTube(seed)) ?? seed }
        var id = seed.youtubeID
        if id == nil { id = try? await searchYouTube(t.searchQuery, limit: 1).first?.youtubeID }
        guard let vid = id else { return [] }
        var items: [Track] = []
        if let j = await HomeServer.shared.flat("https://www.youtube.com/watch?v=\(vid)&list=RD\(vid)", items: "1:40") {
            items = FlatParser.tracks(j).tracks
        } else {
            items = (try? await YT.mix(videoID: vid, limit: 40)) ?? []
        }
        let seenTitles = Set(excluding)
        return Array(items.filter { !seenTitles.contains($0.id) && $0.youtubeID != vid && $0.durationSec < 900 }.prefix(max))
    }
}
