import Foundation

/// Where a song comes from. Same names as the Android and Windows apps, so libraries look the same everywhere.
enum Source: String, Codable, CaseIterable {
    case youtube = "YOUTUBE", soundcloud = "SOUNDCLOUD", tiktok = "TIKTOK", spotify = "SPOTIFY", other = "OTHER"

    var label: String {
        switch self {
        case .youtube: return "YouTube"
        case .soundcloud: return "SoundCloud"
        case .tiktok: return "TikTok"
        case .spotify: return "Spotify"
        case .other: return "Web"
        }
    }
    var short: String {
        switch self {
        case .youtube: return "YT"
        case .soundcloud: return "SC"
        case .tiktok: return "TT"
        case .spotify: return "SP"
        case .other: return "WEB"
        }
    }
    var prefix: String {
        switch self {
        case .youtube: return "yt"
        case .soundcloud: return "sc"
        case .tiktok: return "tt"
        case .spotify: return "sp"
        case .other: return "x"
        }
    }

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = Source(rawValue: raw) ?? .other
    }
}

struct Track: Codable, Identifiable, Hashable {
    var id: String
    var source: Source
    var title: String
    var artist: String = ""
    var durationSec: Int = 0
    var thumbnail: String?
    var url: String?
    /// Spotify songs play from YouTube: the video they were matched to.
    var matchedUrl: String?
    /// A cover was looked up for this song (so it isn't looked up again).
    var artChecked: Bool?

    init(id: String, source: Source, title: String, artist: String = "", durationSec: Int = 0, thumbnail: String? = nil,
         url: String? = nil, matchedUrl: String? = nil, artChecked: Bool? = nil) {
        self.id = id; self.source = source; self.title = title; self.artist = artist; self.durationSec = durationSec
        self.thumbnail = thumbnail; self.url = url; self.matchedUrl = matchedUrl; self.artChecked = artChecked
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        source = (try? c.decode(Source.self, forKey: .source)) ?? .other
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        artist = (try? c.decode(String.self, forKey: .artist)) ?? ""
        durationSec = (try? c.decode(Int.self, forKey: .durationSec)) ?? 0
        thumbnail = try? c.decode(String.self, forKey: .thumbnail)
        url = try? c.decode(String.self, forKey: .url)
        matchedUrl = try? c.decode(String.self, forKey: .matchedUrl)
        artChecked = try? c.decode(Bool.self, forKey: .artChecked)
    }

    static func makeId(_ source: Source, _ raw: String) -> String {
        let clean = String(raw.map { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" ? $0 : "_" }.prefix(60))
        return "\(source.prefix)_\(clean)"
    }

    /// The link the audio comes from (Spotify songs: the matched YouTube video).
    var playTarget: String? { source == .spotify ? matchedUrl : url }

    var youtubeID: String? {
        if source == .youtube, id.hasPrefix("yt_"), id.count == 14 { return String(id.dropFirst(3)) }
        for u in [url, matchedUrl].compactMap({ $0 }) {
            if let id = YouTubeLink.videoID(u) { return id }
        }
        return nil
    }

    var searchQuery: String { artist.isEmpty ? title : "\(artist) - \(title)" }
    var thumbnailURL: URL? { thumbnail.flatMap { URL(string: $0) } }
}

struct Playlist: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var trackIds: [String]
    var sourceUrl: String?
    var cover: String?
}

/// A search result page or an imported list (playlist, album, mix…).
struct TrackList {
    var title: String?
    var tracks: [Track]
    var cover: String?
    var sourceUrl: String?
}

enum YouTubeLink {
    static func videoID(_ s: String) -> String? {
        let patterns = [#"(?:v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})"#]
        for p in patterns {
            if let r = s.range(of: p, options: .regularExpression) {
                let m = String(s[r])
                return String(m.suffix(11))
            }
        }
        return nil
    }
    static func playlistID(_ s: String) -> String? {
        guard let r = s.range(of: #"[?&]list=([A-Za-z0-9_-]+)"#, options: .regularExpression) else { return nil }
        return String(s[r].split(separator: "=").last ?? "")
    }
}

func formatDuration(_ sec: Int) -> String {
    guard sec > 0 else { return "" }
    let h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// "3:45" or "1:02:03" → seconds.
func parseDuration(_ s: String?) -> Int {
    guard let s, !s.isEmpty else { return 0 }
    return s.split(separator: ":").reduce(0) { $0 * 60 + (Int($1.trimmingCharacters(in: .whitespaces)) ?? 0) }
}

struct TunioError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
