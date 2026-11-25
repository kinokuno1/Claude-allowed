import Foundation

/// One sung word: when it starts and ends (seconds) and its text (with the space after it).
struct SungWord: Hashable { var start: Double; var end: Double; var text: String }
struct WordLine: Hashable { var start: Double; var end: Double; var words: [SungWord] }

struct WordLyrics {
    var lines: [WordLine]
    var source: String?
    var estimated: Bool
}

/// Word-by-word timing, looked up only when you switch the lyrics to "Word" (same sources as the desktop app):
///   1. Musixmatch "richsync" through the desktop app's anonymous token,
///   2. NetEase Cloud Music "yrc", which also covers most Western songs,
///   3. estimated: LRCLIB's line times shared among each line's words by their length.
enum WordSync {
    private static let mxm = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private static let mxmApp = "web-desktop-app-v1.0"
    private static let desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private static var cache: [String: WordLyrics] = [:]
    private static let lock = NSLock()

    static func get(_ t: Track, lines: LyricsResult?) async -> WordLyrics {
        if let hit = lock.withLock({ cache[t.id] }) { return hit }
        var res: WordLyrics?
        if let l = await musixmatch(t), !l.isEmpty { res = WordLyrics(lines: l, source: "Musixmatch", estimated: false) }
        else if let l = await netease(t), !l.isEmpty { res = WordLyrics(lines: l, source: "NetEase", estimated: false) }
        else if let synced = lines?.synced, !synced.isEmpty {
            res = WordLyrics(lines: estimate(synced, songEnd: Double(t.durationSec)), source: "LRCLIB (word timing estimated)", estimated: true)
        }
        let out = res ?? WordLyrics(lines: [], source: nil, estimated: true)
        if res != nil { lock.withLock { cache[t.id] = out } }
        return out
    }

    // MARK: 1. Musixmatch richsync

    private static var token: String? {
        get {
            let d = UserDefaults.standard
            guard Date().timeIntervalSince1970 - d.double(forKey: "mxm.at") < 6 * 3600 else { return nil }
            return d.string(forKey: "mxm.token")
        }
        set {
            UserDefaults.standard.set(newValue, forKey: "mxm.token")
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "mxm.at")
        }
    }

    private static func mxmGet(_ url: URL) async -> J? {
        var r = URLRequest(url: url, timeoutInterval: 12)
        r.setValue(desktopUA, forHTTPHeaderField: "User-Agent")
        r.setValue("AWSELBCORS=0; AWSELB=0", forHTTPHeaderField: "Cookie")
        r.httpShouldHandleCookies = false
        guard let res = try? await Net.data(r), let o = try? JSONSerialization.jsonObject(with: res.0) else { return nil }
        return J(o)
    }

    private static func call(_ method: String, _ params: [String: String]) async -> J? {
        if token == nil {
            let now = String(Int(Date().timeIntervalSince1970 * 1000))
            let r = await mxmGet(Net.url(mxm + "token.get", ["app_id": mxmApp, "user_language": "en", "t": now]))
            guard let tok = r?["message"]["body"]["user_token"].string, !tok.contains("UpgradeOnly") else { return nil }
            token = tok
        }
        guard let tok = token else { return nil }
        var q = params
        q["app_id"] = mxmApp; q["usertoken"] = tok; q["t"] = String(Int(Date().timeIntervalSince1970 * 1000))
        let msg = await mxmGet(Net.url(mxm + method, q))?["message"]
        if msg?["header"]["status_code"].int == 401 { UserDefaults.standard.removeObject(forKey: "mxm.token") }
        return msg
    }

    private static func musixmatch(_ t: Track) async -> [WordLine]? {
        for (artist, title) in Lyrics.guesses(t).prefix(2) {
            var p = ["q_track": title, "q_artist": artist]
            if t.durationSec > 0 { p["q_duration"] = String(t.durationSec) }
            guard let tr = await call("matcher.track.get", p)?["body"]["track"], let id = tr["track_id"].int, id > 0,
                  (tr["has_richsync"].int ?? 0) == 1,
                  close(t, tr["artist_name"].string ?? "", tr["track_name"].string ?? "", tr["track_length"].double) else { continue }
            if let body = await call("track.richsync.get", ["track_id": String(id)])?["body"]["richsync"]["richsync_body"].string {
                let lines = parseRichsync(body)
                if !lines.isEmpty { return lines }
            }
        }
        return nil
    }

    /// [{"ts": 12.3, "te": 15.6, "l": [{"c": "Hello", "o": 0.0}, {"c": " ", "o": 0.4}, …]}] → lines of words.
    static func parseRichsync(_ body: String) -> [WordLine] {
        guard let arr = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [Any] else { return [] }
        var out: [WordLine] = []
        for item in arr {
            let line = J(item)
            let ts = line["ts"].double ?? 0, te = line["te"].double ?? 0
            var words: [SungWord] = []
            for part in line["l"].array {
                let text = part["c"].string ?? "", start = ts + (part["o"].double ?? 0)
                if text.trimmingCharacters(in: .whitespaces).isEmpty, !words.isEmpty { words[words.count - 1].text += text; continue }
                words.append(SungWord(start: start, end: 0, text: text))
            }
            for i in words.indices { words[i].end = i + 1 < words.count ? words[i + 1].start : te }
            if !words.isEmpty { out.append(WordLine(start: ts, end: te, words: words)) }
        }
        return out
    }

    // MARK: 2. NetEase yrc

    private static func netease(_ t: Track) async -> [WordLine]? {
        let hdr = ["Referer": "https://music.163.com/"]
        for (artist, title) in Lyrics.guesses(t).prefix(2) {
            guard let r = try? await Net.json(Net.url("https://music.163.com/api/search/get", ["s": "\(artist) \(title)", "type": "1", "limit": "6"]), headers: hdr)
            else { continue }
            for s in r["result"]["songs"].array {
                let artists = s["artists"].array.compactMap { $0["name"].string }.joined(separator: " ")
                guard let id = s["id"].int, close(t, artists, s["name"].string ?? "", (s["duration"].double ?? 0) / 1000) else { continue }
                let u = Net.url("https://music.163.com/api/song/lyric/v1", ["id": String(id), "lv": "0", "kv": "0", "tv": "0", "rv": "0", "yv": "0", "ytv": "0", "yrv": "0"])
                if let l = try? await Net.json(u, headers: hdr), let yrc = l["yrc"]["lyric"].string {
                    let lines = parseYrc(yrc)
                    if !lines.isEmpty { return lines }
                }
            }
        }
        return nil
    }

    /// "[12340,3450](12340,500,0)Hello (12840,400,0)world" lines (milliseconds) → lines of words.
    static func parseYrc(_ yrc: String) -> [WordLine] {
        let lineRx = try! NSRegularExpression(pattern: #"^\[(\d+),(\d+)\](.*)$"#)
        let wordRx = try! NSRegularExpression(pattern: #"\((\d+),(\d+),\d+\)([^(]*)"#)
        var out: [WordLine] = []
        for raw in yrc.split(whereSeparator: \.isNewline) {
            let s = String(raw).trimmingCharacters(in: .whitespaces) as NSString
            guard let m = lineRx.firstMatch(in: s as String, range: NSRange(location: 0, length: s.length)) else { continue }
            let start = (Double(s.substring(with: m.range(at: 1))) ?? 0) / 1000, dur = (Double(s.substring(with: m.range(at: 2))) ?? 0) / 1000
            let rest = s.substring(with: m.range(at: 3)) as NSString
            var words: [SungWord] = []
            for w in wordRx.matches(in: rest as String, range: NSRange(location: 0, length: rest.length)) {
                let a = (Double(rest.substring(with: w.range(at: 1))) ?? 0) / 1000, b = (Double(rest.substring(with: w.range(at: 2))) ?? 0) / 1000
                let text = rest.substring(with: w.range(at: 3))
                if !text.isEmpty { words.append(SungWord(start: a, end: a + b, text: text)) }
            }
            if !words.isEmpty { out.append(WordLine(start: start, end: start + dur, words: words)) }
        }
        return out
    }

    // MARK: 3. estimated

    /// Shares each line's time among its words by their length (not stretched over long pauses).
    static func estimate(_ synced: [LyricLine], songEnd: Double) -> [WordLine] {
        var out: [WordLine] = []
        for (i, line) in synced.enumerated() where !line.text.trimmingCharacters(in: .whitespaces).isEmpty {
            let next = i + 1 < synced.count ? synced[i + 1].time : (songEnd > 0 ? songEnd : line.time + 5)
            var words: [String] = []
            for part in line.text.split(separator: " ", omittingEmptySubsequences: true) { words.append(String(part) + " ") }
            let weight = words.map { Double($0.trimmingCharacters(in: .whitespaces).count) + 1.5 }
            let total = weight.reduce(0, +)
            let span = max(0.4, min(next - line.time, total * 0.12 + 0.8, 12))
            var t = line.time
            var ws: [SungWord] = []
            for (w, k) in zip(words, weight) {
                let d = span * k / total
                ws.append(SungWord(start: t, end: t + d, text: w)); t += d
            }
            out.append(WordLine(start: line.time, end: line.time + span, words: ws))
        }
        return out
    }

    // MARK: matching

    private static func norm(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber } }

    private static func close(_ t: Track, _ artist: String, _ title: String, _ dur: Double?) -> Bool {
        if t.durationSec > 0, let d = dur, d > 0, abs(d - Double(t.durationSec)) > 8 { return false }
        let a = norm(artist), n = norm(title)
        guard !n.isEmpty else { return false }
        let g = Lyrics.guesses(t)
        let titleOK = g.contains { let s = norm($0.1); return !s.isEmpty && (s.contains(n) || n.contains(s)) }
        let artistOK = a.isEmpty || g.contains { let x = norm($0.0); return !x.isEmpty && (x.contains(a) || a.contains(x)) }
        return titleOK && artistOK
    }
}
