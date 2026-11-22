import Foundation

struct LyricsResult: Codable {
    var synced: [LyricLine]?
    var plain: String?
    var instrumental = false
    var found: Bool { synced != nil || plain != nil || instrumental }
}

struct LyricLine: Codable, Hashable, Identifiable {
    var time: Double
    var text: String
    var id: Double { time }
}

/// Lyrics from LRCLIB (https://lrclib.net), a free community-made lyrics library. Most songs have synced lyrics.
enum Lyrics {
    private static let api = "https://lrclib.net/api"
    private static var cache: [String: LyricsResult] = [:]
    private static let lock = NSLock()

    static func get(_ t: Track) async -> LyricsResult? {
        lock.lock(); if let hit = cache[t.id] { lock.unlock(); return hit }; lock.unlock()
        var result = LyricsResult()
        for (artist, title) in guesses(t).prefix(4) {
            var q = ["artist_name": artist, "track_name": title]
            if t.durationSec > 0 { q["duration"] = String(t.durationSec) }
            if let r = try? await Net.json(Net.url(api + "/get", q), headers: ["Lrclib-Client": "Tunio iOS"]), r["id"].exists {
                result = parse(r); if result.found { break }
            }
            if let list = try? await Net.json(Net.url(api + "/search", ["track_name": title, "artist_name": artist])) {
                let close = list.array.first { t.durationSec == 0 || abs(($0["duration"].int ?? 0) - t.durationSec) <= 6 }
                if let c = close { result = parse(c); if result.found { break } }
            }
        }
        lock.lock(); cache[t.id] = result; lock.unlock()
        return result
    }

    private static func parse(_ r: J) -> LyricsResult {
        var out = LyricsResult()
        out.instrumental = r["instrumental"].bool ?? false
        out.plain = r["plainLyrics"].string
        if let s = r["syncedLyrics"].string { let lines = parseLRC(s); if !lines.isEmpty { out.synced = lines } }
        return out
    }

    static func parseLRC(_ s: String) -> [LyricLine] {
        var out: [LyricLine] = []
        for raw in s.split(whereSeparator: \.isNewline) {
            var line = Substring(raw)
            var times: [Double] = []
            while line.hasPrefix("["), let close = line.firstIndex(of: "]") {
                let tag = line[line.index(after: line.startIndex)..<close]
                let parts = tag.split(separator: ":")
                if parts.count == 2, let m = Double(parts[0]), let sec = Double(parts[1]) { times.append(m * 60 + sec) }
                line = line[line.index(after: close)...]
            }
            let text = line.trimmingCharacters(in: .whitespaces)
            for t in times { out.append(LyricLine(time: t, text: text)) }
        }
        return out.sorted { $0.time < $1.time }
    }

    /// (artist, title) to try, best first. YouTube titles are messy: "Artist - Song (Official Video) [4K]".
    static func guesses(_ t: Track) -> [(String, String)] {
        let junk = #"\s*[\(\[【][^\)\]】]*?\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv|hd|hq|4k|remaster(ed)?|live|explicit|clean|prod\.?)\b[^\)\]】]*[\)\]】]"#
        let feat = #"\s*[\(\[]?\s*\b(feat\.?|ft\.?|featuring)\s+[^\)\]\-]*[\)\]]?"#
        func clean(_ s: String) -> String {
            var s = s.replacingOccurrences(of: junk, with: "", options: [.regularExpression, .caseInsensitive])
            s = s.replacingOccurrences(of: feat, with: "", options: [.regularExpression, .caseInsensitive])
            s = s.replacingOccurrences(of: #"\s*(\||//|#).*$"#, with: "", options: .regularExpression)
            return s.replacingOccurrences(of: "\"", with: "").trimmingCharacters(in: CharacterSet(charactersIn: " -–—_."))
        }
        let channel = t.artist.replacingOccurrences(of: #"(\s*-\s*topic|vevo|\s*official( channel)?)$"#, with: "",
                                                    options: [.regularExpression, .caseInsensitive]).trimmingCharacters(in: .whitespaces)
        let title = clean(t.title)
        var out: [(String, String)] = []
        for sep in [" - ", " – ", " — "] where title.contains(sep) {
            let p = title.components(separatedBy: sep)
            let a = p[0].trimmingCharacters(in: .whitespaces), b = p.dropFirst().joined(separator: sep).trimmingCharacters(in: .whitespaces)
            out.append((a, b)); out.append((b, a))
        }
        out.insert((channel, title), at: min(1, out.count))
        var seen = Set<String>()
        return out.filter { !$0.1.isEmpty && seen.insert("\($0.0)|\($0.1)".lowercased()).inserted }
    }
}

/// Covers for songs that have none (or only their playlist's): Spotify, YouTube, iTunes, Deezer.
enum Artwork {
    static func lookup(_ t: Track) async -> String? {
        if t.source == .spotify, let u = await Spotify.cover(t) { return u }
        if t.source == .youtube, let id = t.youtubeID { return "https://i.ytimg.com/vi/\(id)/hqdefault.jpg" }
        for (artist, title) in Lyrics.guesses(t).prefix(2) {
            let q = Net.url("https://itunes.apple.com/search", ["term": "\(artist) \(title)", "media": "music", "entity": "song", "limit": "5"])
            if let r = try? await Net.json(q),
               let hit = r["results"].array.first(where: { matches(artist, title, $0["artistName"].string, $0["trackName"].string) }),
               let art = hit["artworkUrl100"].string {
                return art.replacingOccurrences(of: "100x100bb", with: "600x600bb")
            }
        }
        if let id = t.youtubeID { return "https://i.ytimg.com/vi/\(id)/hqdefault.jpg" }
        return nil
    }

    private static func norm(_ s: String?) -> String {
        (s ?? "").lowercased().replacingOccurrences(of: #"[^a-z0-9]+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }
    private static func matches(_ wa: String, _ wt: String, _ a: String?, _ t: String?) -> Bool {
        let x = norm(wt), y = norm(t)
        guard !x.isEmpty, !y.isEmpty, x.contains(y) || y.contains(x) else { return false }
        let want = Set(norm(wa).split(separator: " ")), got = Set(norm(a).split(separator: " "))
        return want.isEmpty || !want.isDisjoint(with: got)
    }
}
