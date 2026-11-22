import Foundation
import Network

/// Your Tunio home server (the Raspberry Pi): when it answers, searches, links and audio go through it, so the
/// phone doesn't do the work (and songs it has kept start instantly). Otherwise the phone does it itself.
@MainActor
final class HomeServer: ObservableObject {
    static let shared = HomeServer()

    enum Status: Equatable { case notSetUp, checking, online(songs: Int), offline, wrongPassword }

    @Published var address: String { didSet { UserDefaults.standard.set(address, forKey: "home.address") } }
    @Published var password: String { didSet { UserDefaults.standard.set(password, forKey: "home.password") } }
    @Published var enabled: Bool { didSet { UserDefaults.standard.set(enabled, forKey: "home.enabled") } }
    @Published private(set) var status: Status = .notSetUp
    @Published private(set) var found: [String] = []

    private var checkedAt = Date.distantPast
    private var browser: NWBrowser?

    private init() {
        address = UserDefaults.standard.string(forKey: "home.address") ?? ""
        password = UserDefaults.standard.string(forKey: "home.password") ?? ""
        enabled = UserDefaults.standard.object(forKey: "home.enabled") as? Bool ?? true
        status = address.isEmpty ? .notSetUp : .checking
    }

    var isSetUp: Bool { enabled && !address.isEmpty && !password.isEmpty }
    var usable: Bool { if case .online = status { return isSetUp } else { return false } }
    var authHeader: [String: String] { ["Authorization": "Bearer \(password)"] }
    private var base: String { address.hasSuffix("/") ? String(address.dropLast()) : address }

    func configure(address a: String, password p: String) {
        var a = a.trimmingCharacters(in: .whitespaces)
        if !a.isEmpty && !a.contains("://") { a = "http://" + a }
        if !a.isEmpty, URL(string: a)?.port == nil, a.filter({ $0 == ":" }).count < 2 { a += ":8484" }
        address = a; password = p.trimmingCharacters(in: .whitespaces); enabled = true
        Task { await check(force: true) }
    }

    func forget() { address = ""; password = ""; status = .notSetUp }

    /// tunio://pair?u=http://…&k=password (from the server's QR code page)
    func pair(_ url: URL) {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let q = Dictionary(uniqueKeysWithValues: (c.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        if let u = q["u"], let k = q["k"] { configure(address: u, password: k) }
    }

    /// Is the server there? Remembered for 5 minutes when it is, 1 minute when it isn't.
    @discardableResult
    func check(force: Bool = false) async -> Bool {
        guard isSetUp else { status = .notSetUp; return false }
        let ttl: TimeInterval = usable ? 300 : 60
        if !force, Date().timeIntervalSince(checkedAt) < ttl, status != .checking { return usable }
        if case .online = status {} else { status = .checking }
        checkedAt = Date()
        do {
            var req = Net.request(URL(string: base + "/api/ping")!, headers: authHeader, timeout: 4)
            req.cachePolicy = .reloadIgnoringLocalCacheData
            let (data, resp) = try await Net.session.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 401 { status = .wrongPassword; return false }
            guard code == 200, let o = try? JSONSerialization.jsonObject(with: data) else { status = .offline; return false }
            status = .online(songs: J(o)["songs"].int ?? 0)
            return true
        } catch {
            status = .offline
            return false
        }
    }

    func markDown() { status = .offline; checkedAt = Date() }

    /// yt-dlp's listing done by the server (search "ytsearch25:…", playlists, mixes, TikTok…).
    func flat(_ url: String, items: String? = nil) async -> J? {
        guard await check() else { return nil }
        var body: [String: Any] = ["url": url]
        if let items { body["items"] = items }
        do { return try await Net.postJSON(URL(string: base + "/api/flat")!, body, headers: authHeader, timeout: 60) }
        catch { markDown(); return nil }
    }

    /// Where the player streams a song from: the server sends it while fetching it (and keeps it for next time).
    func audioURL(target: String, track: Track) -> URL {
        Net.url(base + "/audio", ["url": target, "title": track.title, "artist": track.artist])
    }

    func warm(_ targets: [String]) {
        guard usable, !targets.isEmpty else { return }
        let req = Net.request(URL(string: base + "/api/warm")!, method: "POST", headers: authHeader.merging(["Content-Type": "application/json"]) { a, _ in a },
                              body: try? JSONSerialization.data(withJSONObject: ["urls": Array(targets.prefix(6))]))
        Task.detached { _ = try? await Net.session.data(for: req) }
    }

    // MARK: finding the server on the Wi-Fi (it announces itself as _tunio._tcp)

    func discover() {
        browser?.cancel()
        found = []
        let b = NWBrowser(for: .bonjour(type: "_tunio._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            for r in results { self?.resolve(r.endpoint) }
        }
        b.start(queue: .main)
        browser = b
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in self?.browser?.cancel() }
    }

    nonisolated private func resolve(_ endpoint: NWEndpoint) {
        let c = NWConnection(to: endpoint, using: .tcp)
        c.stateUpdateHandler = { [weak self] state in
            guard case .ready = state else { return }
            if case let .hostPort(host, port)? = c.currentPath?.remoteEndpoint {
                var h = "\(host)"
                if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }
                if h.contains(":") { h = "[\(h)]" }
                let addr = "http://\(h):\(port)"
                Task { @MainActor in if !(self?.found.contains(addr) ?? true) { self?.found.append(addr) } }
            }
            c.cancel()
        }
        c.start(queue: .global())
    }
}

/// yt-dlp listing JSON (from the home server) → tracks.
enum FlatParser {
    static func tracks(_ j: J) -> TrackList {
        let entries = j["entries"].array.isEmpty ? [j] : j["entries"].array
        let tracks: [Track] = entries.compactMap { e in
            guard let id = e["id"].string else { return nil }
            let url = e["webpage_url"].string ?? e["url"].string ?? ""
            let key = (e["ie_key"].string ?? e["extractor_key"].string ?? "").lowercased()
            let source: Source = key.contains("youtube") || url.contains("youtu") ? .youtube
                : key.contains("soundcloud") || url.contains("soundcloud") ? .soundcloud
                : key.contains("tiktok") || url.contains("tiktok") ? .tiktok : .other
            let page = source == .youtube ? "https://www.youtube.com/watch?v=\(id)" : url
            let thumb = e["thumbnails"].array.last?["url"].string ?? e["thumbnail"].string
                ?? (source == .youtube ? "https://i.ytimg.com/vi/\(id)/hqdefault.jpg" : nil)
            return Track(id: Track.makeId(source, id), source: source, title: e["title"].string ?? "Untitled",
                         artist: (e["artist"].string ?? e["uploader"].string ?? e["channel"].string ?? "").replacingOccurrences(of: " - Topic", with: ""),
                         durationSec: Int(e["duration"].double ?? 0), thumbnail: thumb, url: page)
        }
        return TrackList(title: j["entries"].array.isEmpty ? nil : j["title"].string, tracks: tracks,
                         cover: j["thumbnails"].array.last?["url"].string ?? tracks.first?.thumbnail, sourceUrl: j["webpage_url"].string)
    }
}
