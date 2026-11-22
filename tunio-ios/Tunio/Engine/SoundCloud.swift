import Foundation

/// SoundCloud through its public web API (the same one soundcloud.com uses). Its "client_id" is read from the
/// website's scripts and remembered.
enum SoundCloud {
    private static let api = "https://api-v2.soundcloud.com"
    private static var clientID: String? = UserDefaults.standard.string(forKey: "sc.clientID")

    private static func key(refresh: Bool = false) async throws -> String {
        if let id = clientID, !refresh { return id }
        let home = try await Net.text(URL(string: "https://soundcloud.com")!)
        let scripts = matches(#"https://a-v2\.sndcdn\.com/assets/[^"]+\.js"#, in: home)
        for s in scripts.reversed() {
            guard let u = URL(string: s), let js = try? await Net.text(u) else { continue }
            if let m = matches(#"client_id\s*[:=]\s*"([A-Za-z0-9]{32})""#, in: js, group: 1).first {
                clientID = m
                UserDefaults.standard.set(m, forKey: "sc.clientID")
                return m
            }
        }
        throw TunioError("Couldn't connect to SoundCloud")
    }

    private static func get(_ path: String, _ query: [String: String] = [:]) async throws -> J {
        var q = query
        q["client_id"] = try await key()
        if let r = try? await Net.json(Net.url(api + path, q)) { return r }
        q["client_id"] = try await key(refresh: true) // the key changes now and then
        return try await Net.json(Net.url(api + path, q))
    }

    static func matches(_ pattern: String, in s: String, group: Int = 0) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap {
            Range($0.range(at: group), in: s).map { String(s[$0]) }
        }
    }

    private static func track(_ j: J) -> Track? {
        guard let id = j["id"].string, let title = j["title"].string else { return nil }
        let art = (j["artwork_url"].string ?? j["user"]["avatar_url"].string)?.replacingOccurrences(of: "-large.", with: "-t500x500.")
        return Track(id: Track.makeId(.soundcloud, id), source: .soundcloud, title: title,
                     artist: j["publisher_metadata"]["artist"].string ?? j["user"]["username"].string ?? "",
                     durationSec: (j["full_duration"].int ?? j["duration"].int ?? 0) / 1000, thumbnail: art,
                     url: j["permalink_url"].string)
    }

    static func search(_ query: String, limit: Int = 25) async throws -> [Track] {
        try await get("/search/tracks", ["q": query, "limit": String(limit)])["collection"].array.compactMap(track)
    }

    /// A SoundCloud link: one song, or a playlist / album.
    static func resolve(_ link: String) async throws -> TrackList {
        let r = try await get("/resolve", ["url": link])
        if r["kind"].string == "track", let t = track(r) { return TrackList(title: nil, tracks: [t], cover: t.thumbnail) }
        var items = r["tracks"].array
        // long playlists only include the first few songs in full; the rest are fetched by id
        let missing = items.filter { $0["title"].string == nil }.compactMap { $0["id"].string }
        if !missing.isEmpty {
            var full: [String: J] = [:]
            for chunk in stride(from: 0, to: missing.count, by: 50).map({ Array(missing[$0..<min($0 + 50, missing.count)]) }) {
                for t in try await get("/tracks", ["ids": chunk.joined(separator: ",")]).array {
                    if let id = t["id"].string { full[id] = t }
                }
            }
            items = items.map { $0["title"].string == nil ? (full[$0["id"].string ?? ""] ?? $0) : $0 }
        }
        let tracks = items.compactMap(track)
        guard !tracks.isEmpty else { throw TunioError("Nothing playable in this SoundCloud link") }
        return TrackList(title: r["title"].string, tracks: tracks,
                         cover: r["artwork_url"].string?.replacingOccurrences(of: "-large.", with: "-t500x500.") ?? tracks.first?.thumbnail,
                         sourceUrl: link)
    }

    /// The audio of a song: an MP3 file if SoundCloud has one, otherwise a (non-encrypted) HLS stream.
    static func audioURL(_ permalink: String) async throws -> (url: URL, isFile: Bool) {
        let t = try await get("/resolve", ["url": permalink])
        let codings = t["media"]["transcodings"].array
        let auth = t["track_authorization"].string ?? ""
        let progressive = codings.first { $0["format"]["protocol"].string == "progressive" }
        let hls = codings.first { $0["format"]["protocol"].string == "hls" && ($0["format"]["mime_type"].string ?? "").contains("mpeg") }
            ?? codings.first { $0["format"]["protocol"].string == "hls" }
        guard let pick = progressive ?? hls, let base = pick["url"].string else {
            throw TunioError("SoundCloud doesn't allow playing this song outside its app")
        }
        let r = try await Net.json(Net.url(base, ["client_id": try await key(), "track_authorization": auth]))
        guard let s = r["url"].string, let u = URL(string: s) else { throw TunioError("SoundCloud didn't give a link") }
        return (u, progressive != nil)
    }
}
