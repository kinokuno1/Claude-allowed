import Foundation

/// Spotify links: song names are read from Spotify's public embed pages (no account), and the songs play from
/// YouTube, matched by artist, title and length.
enum Spotify {
    /// ("playlist" | "album" | "track", id) for a Spotify link.
    static func link(_ s: String) -> (String, String)? {
        if let r = s.range(of: #"spotify:(track|album|playlist):([A-Za-z0-9]+)"#, options: .regularExpression) {
            let p = s[r].split(separator: ":"); return (String(p[1]), String(p[2]))
        }
        if let r = s.range(of: #"open\.spotify\.com/(?:intl-[a-z]+/)?(track|album|playlist)/([A-Za-z0-9]+)"#, options: .regularExpression) {
            let p = s[r].split(separator: "/"); return (String(p[p.count - 2]), String(p[p.count - 1]))
        }
        return nil
    }

    static func isLink(_ s: String) -> Bool { link(s) != nil || s.contains("spotify.link/") || s.contains("spoti.fi/") }

    static func resolve(_ text: String) async throws -> TrackList {
        var l = link(text)
        if l == nil, let short = SoundCloud.matches(#"https?://(spotify\.link|spoti\.fi)/\S+"#, in: text).first, let u = URL(string: short) {
            let (_, resp) = try await Net.data(Net.request(u)) // follows the redirect
            l = resp.url.flatMap { link($0.absoluteString) }
        }
        guard let (kind, id) = l else { throw TunioError("Not a Spotify song, album or playlist link") }
        return try await embed(kind, id)
    }

    private static func embed(_ kind: String, _ id: String) async throws -> TrackList {
        let page = "https://open.spotify.com/\(kind)/\(id)"
        let html = try await Net.text(URL(string: "https://open.spotify.com/embed/\(kind)/\(id)")!)
        guard let jsonText = SoundCloud.matches(#"<script[^>]*id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>"#, in: html, group: 1).first,
              let obj = try? JSONSerialization.jsonObject(with: Data(jsonText.utf8)) else {
            if kind == "track" { return TrackList(title: nil, tracks: [try await oembedTrack(id)], sourceUrl: page) }
            throw TunioError("Couldn't read this Spotify \(kind). If it's private, make it public in Spotify.")
        }
        let root = J(obj)
        var entity = root["props"]["pageProps"]["state"]["data"]["entity"]
        if !entity.exists { entity = root.first("entity") }
        let cover = image(entity)
        let list = entity["trackList"].array
        if !list.isEmpty {
            let tracks: [Track] = list.compactMap { t in
                guard let title = t["title"].string ?? t["name"].string else { return nil }
                let tid = (t["uri"].string ?? "").split(separator: ":").last.map(String.init) ?? UUID().uuidString
                return Track(id: Track.makeId(.spotify, tid), source: .spotify, title: title,
                             artist: cleanArtists(t["subtitle"].string ?? ""), durationSec: (t["duration"].int ?? 0) / 1000,
                             thumbnail: image(t) ?? cover, url: "https://open.spotify.com/track/\(tid)")
            }
            return TrackList(title: entity["name"].string ?? entity["title"].string, tracks: tracks, cover: cover, sourceUrl: page)
        }
        guard let title = entity["name"].string ?? entity["title"].string else { throw TunioError("Couldn't read this Spotify link") }
        let artists = entity["artists"].array.compactMap { $0["name"].string }.joined(separator: ", ")
        let t = Track(id: Track.makeId(.spotify, id), source: .spotify, title: title,
                      artist: cleanArtists(artists.isEmpty ? (entity["subtitle"].string ?? "") : artists),
                      durationSec: (entity["duration"]["totalMilliseconds"].int ?? entity["duration"].int ?? 0) / 1000,
                      thumbnail: cover, url: page)
        return TrackList(title: nil, tracks: [t], cover: cover, sourceUrl: page)
    }

    private static func oembedTrack(_ id: String) async throws -> Track {
        let page = "https://open.spotify.com/track/\(id)"
        let o = try await Net.json(Net.url("https://open.spotify.com/oembed", ["url": page]))
        return Track(id: Track.makeId(.spotify, id), source: .spotify, title: o["title"].string ?? "Spotify song",
                     thumbnail: o["thumbnail_url"].string, url: page)
    }

    /// The song's own cover (album art), from Spotify's public oEmbed.
    static func cover(_ t: Track) async -> String? {
        guard let u = t.url, u.contains("open.spotify.com/track/") else { return nil }
        return try? await Net.json(Net.url("https://open.spotify.com/oembed", ["url": u]))["thumbnail_url"].string
    }

    private static func image(_ j: J) -> String? {
        for key in ["coverArt", "visualIdentity", "images", "image"] {
            let v = j[key]
            if let u = v["sources"].array.max(by: { ($0["width"].int ?? 0) < ($1["width"].int ?? 0) })?["url"].string { return u }
            if let u = v["image"].array.max(by: { ($0["maxWidth"].int ?? 0) < ($1["maxWidth"].int ?? 0) })?["url"].string { return u }
            if let u = v.array.first?["url"].string { return u }
        }
        return nil
    }

    private static func cleanArtists(_ s: String) -> String {
        s.replacingOccurrences(of: "\u{00A0}", with: " ").replacingOccurrences(of: "E,", with: "").trimmingCharacters(in: .whitespaces)
    }

    /// The YouTube video that plays a Spotify song: the first result of similar length.
    static func matchOnYouTube(_ t: Track) async throws -> Track {
        var m = t
        if m.matchedUrl != nil { return m }
        let results = try await Engine.searchYouTube("\(t.searchQuery) audio", limit: 8)
        let pick = results.first { t.durationSec == 0 || $0.durationSec == 0 || abs($0.durationSec - t.durationSec) <= 15 } ?? results.first
        guard let p = pick else { throw TunioError("Couldn't find “\(t.title)” on YouTube") }
        m.matchedUrl = p.url
        if m.durationSec == 0 { m.durationSec = p.durationSec }
        return m
    }
}
