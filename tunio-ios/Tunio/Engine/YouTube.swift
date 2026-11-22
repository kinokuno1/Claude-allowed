import Foundation
import YouTubeKit

/// YouTube on the phone: search, mixes and playlists through YouTube's own web API (InnerTube),
/// audio links through YouTubeKit.
enum YT {
    private static let api = "https://www.youtube.com/youtubei/v1/"
    private static let context: [String: Any] = [
        "client": ["clientName": "WEB", "clientVersion": "2.20251120.01.00", "hl": "en", "gl": "US"],
    ]

    private static func call(_ endpoint: String, _ body: [String: Any]) async throws -> J {
        var b = body
        b["context"] = context
        return try await Net.postJSON(Net.url(api + endpoint, ["prettyPrint": "false"]), b,
                                      headers: ["Origin": "https://www.youtube.com", "X-Youtube-Client-Name": "1",
                                                "X-Youtube-Client-Version": "2.20251120.01.00"])
    }

    private static func thumb(_ j: J, id: String) -> String {
        j["thumbnail"]["thumbnails"].array.last?["url"].string?.replacingOccurrences(of: "&amp;", with: "&")
            ?? "https://i.ytimg.com/vi/\(id)/hqdefault.jpg"
    }

    private static func track(id: String, title: String?, artist: String?, duration: Int, thumbnail: String?) -> Track {
        Track(id: Track.makeId(.youtube, id), source: .youtube, title: title ?? "YouTube video",
              artist: (artist ?? "").replacingOccurrences(of: " - Topic", with: ""), durationSec: duration,
              thumbnail: thumbnail ?? "https://i.ytimg.com/vi/\(id)/hqdefault.jpg",
              url: "https://www.youtube.com/watch?v=\(id)")
    }

    static func search(_ query: String, limit: Int = 25) async throws -> [Track] {
        let r = try await call("search", ["query": query, "params": "EgIQAQ%3D%3D"]) // videos only
        var out: [Track] = []
        for v in r.all("videoRenderer") {
            guard let id = v["videoId"].string, v["lengthText"].exists else { continue } // skip live streams
            out.append(track(id: id, title: v["title"].text, artist: v["ownerText"].text ?? v["longBylineText"].text,
                             duration: parseDuration(v["lengthText"].text), thumbnail: thumb(v, id: id)))
            if out.count >= limit { break }
        }
        return out
    }

    /// YouTube's endless "Mix" for a video: songs like it (used for autoplay).
    static func mix(videoID: String, limit: Int = 25) async throws -> [Track] {
        let r = try await call("next", ["videoId": videoID, "playlistId": "RD" + videoID])
        return panel(r).filter { $0.youtubeID != videoID }.prefix(limit).map { $0 }
    }

    private static func panel(_ r: J) -> [Track] {
        var seen = Set<String>()
        return r.all("playlistPanelVideoRenderer").compactMap { v in
            guard let id = v["videoId"].string, seen.insert(id).inserted else { return nil }
            return track(id: id, title: v["title"].text, artist: v["shortBylineText"].text ?? v["longBylineText"].text,
                         duration: parseDuration(v["lengthText"].text), thumbnail: thumb(v, id: id))
        }
    }

    static func playlist(_ listID: String) async throws -> TrackList {
        let r = try await call("browse", ["browseId": "VL" + listID])
        var tracks: [Track] = r.all("playlistVideoRenderer").compactMap { v in
            guard let id = v["videoId"].string else { return nil }
            return track(id: id, title: v["title"].text, artist: v["shortBylineText"].text,
                         duration: v["lengthSeconds"].int ?? parseDuration(v["lengthText"].text), thumbnail: thumb(v, id: id))
        }
        var title = r.first("playlistMetadataRenderer")["title"].string
        if tracks.isEmpty { // mixes and some lists only open in the player
            let n = try await call("next", ["playlistId": listID])
            tracks = panel(n)
            title = title ?? n.first("playlist")["title"].string
        }
        guard !tracks.isEmpty else { throw TunioError("This YouTube playlist is empty or private") }
        return TrackList(title: title ?? "YouTube playlist", tracks: tracks, cover: tracks.first?.thumbnail,
                         sourceUrl: "https://www.youtube.com/playlist?list=\(listID)")
    }

    static func video(_ id: String) async throws -> Track {
        let page = "https://www.youtube.com/watch?v=\(id)"
        let o = try? await Net.json(Net.url("https://www.youtube.com/oembed", ["url": page, "format": "json"]))
        return track(id: id, title: o?["title"].string, artist: o?["author_name"].string, duration: 0,
                     thumbnail: "https://i.ytimg.com/vi/\(id)/hqdefault.jpg")
    }

    // MARK: audio

    private static var cache: [String: (URL, Date)] = [:]
    private static let lock = NSLock()

    /// A direct link to the song's audio that iOS can play (m4a/AAC), good for a few hours.
    static func audioURL(videoID: String) async throws -> URL {
        let hit: URL? = lock.withLock {
            if case let (u, at)? = cache[videoID], Date().timeIntervalSince(at) < 3 * 3600 { return u }
            return nil
        }
        if let hit { return hit }
        let streams = try await YouTube(videoID: videoID, methods: [.local, .remote]).streams
        let audio = streams.filterAudioOnly()
        guard let pick = audio.filter({ $0.isNativelyPlayable }).highestAudioBitrateStream()
                ?? audio.highestAudioBitrateStream() else {
            throw TunioError("YouTube didn't give an audio stream for this video")
        }
        lock.withLock { cache[videoID] = (pick.url, Date()) }
        return pick.url
    }
}
