import Foundation
import UIKit

struct JamTrack: Codable, Identifiable, Hashable {
    var qid: String
    var id: String
    var source: String?
    var title: String
    var artist: String?
    var durationSec: Int?
    var thumbnail: String?
    var url: String?
    var matchedUrl: String?
    var addedBy: String?

    var track: Track {
        Track(id: id, source: Source(rawValue: source ?? "") ?? .other, title: title, artist: artist ?? "", durationSec: durationSec ?? 0,
              thumbnail: thumbnail, url: url, matchedUrl: matchedUrl, artChecked: true)
    }
}

struct JamPerson: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var host: Bool?
}

struct JamNow: Codable, Hashable { var title: String?; var artist: String? }

/// A Jam as listed on a server (before joining).
struct JamInfo: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var locked: Bool?
    var host: String?
    var members: Int?
    var now: JamNow?
}

struct JamState: Codable {
    var id: String
    var name: String
    var locked: Bool?
    var host: String?
    var version: Int
    var ended: Bool?
    var queue: [JamTrack]
    var index: Int
    var playing: Bool
    var position: Double
    var serverTime: Double
    var people: [JamPerson]?
    var you: JamPerson?

    var current: JamTrack? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: [JamTrack] { index < 0 ? queue : Array(queue.dropFirst(index + 1)) }
}

/// A Jam found nearby or from a link, and on which server.
struct JamFound: Identifiable, Hashable {
    var server: String
    var info: JamInfo
    var id: String { info.id }
}

/// Listening together (like Spotify Jam), through a Tunio home server that keeps the shared queue; this follows it
/// with long polling. Music plays on the host's phone (its speaker / Bluetooth device); a guest can also play along
/// on their own iPhone, in sync with the host.
@MainActor
final class Jam: ObservableObject {
    static let shared = Jam()

    @Published private(set) var state: JamState?
    /// Guests: also play the music on this iPhone (synced), not only on the host's phone.
    @Published var playAlong = false { didSet { if playAlong != oldValue { Player.shared.jamPlayAlongChanged(playAlong) } } }
    /// A Jam to join (tapped nearby, or from a link): the join prompt shows it.
    @Published var pending: JamFound?
    /// The Jam sheet (from the Jam bar or a link).
    @Published var showSheet = false

    private(set) var server = ""
    private var token = ""
    private var offset = 0.0
    private var bestRtt = Double.greatestFiniteMagnitude
    private var pollTask: Task<Void, Never>?

    var active: Bool { state != nil }
    var isHost: Bool { state?.you?.host == true }
    /// This phone plays the Jam's music: the host always, a guest when playing along.
    var playsHere: Bool { active && (isHost || playAlong) }
    var link: String { state.map { "\(server)/jam/\($0.id)" } ?? "" }

    var myName: String {
        get { UserDefaults.standard.string(forKey: "jam.name") ?? UIDevice.current.name }
        set { UserDefaults.standard.set(String(newValue.trimmingCharacters(in: .whitespaces).prefix(40)), forKey: "jam.name") }
    }

    func serverNow() -> Double { Date().timeIntervalSince1970 + offset }

    /// Where the current song should be now (seconds).
    func expectedPosition(_ s: JamState? = nil) -> Double {
        guard let s = s ?? state else { return 0 }
        return s.position + (s.playing ? max(0, serverNow() - s.serverTime) : 0)
    }

    // MARK: finding, starting, joining

    private struct JamList: Codable { var jams: [JamInfo] }
    private struct Joined: Codable { var id: String; var token: String; var state: JamState }

    /// Jams nearby: announced over Bluetooth by phones around you, and on Tunio servers on this Wi-Fi.
    func nearby() async -> [JamFound] {
        async let bluetooth = JamBeacon.shared.scan()
        let home = HomeServer.shared
        var servers: [String] = []
        if !home.address.isEmpty { servers.append(home.address) }
        home.discover()
        try? await Task.sleep(nanoseconds: 3_500_000_000)
        for a in home.found where !servers.contains(a) { servers.append(a) }
        let ble = await bluetooth
        for f in ble where !servers.contains(f.server) { servers.append(f.server) }
        var out: [JamFound] = []
        for s in servers {
            guard let data = try? await request("GET", s + "/api/jams", timeout: 5),
                  let list = try? JSONDecoder().decode(JamList.self, from: data) else { continue }
            for j in list.jams where !out.contains(where: { $0.info.id == j.id }) { out.append(JamFound(server: s, info: j)) }
        }
        // Bluetooth ones are kept even if their server can't be listed from here
        for f in ble where !out.contains(where: { $0.info.id == f.info.id }) { out.append(f) }
        return out
    }

    /// Starts a Jam on your home server with what's playing now.
    func create(name: String, password: String) async throws {
        let home = HomeServer.shared
        guard !home.address.isEmpty, !home.password.isEmpty else {
            throw TunioError("A Jam runs on your Tunio home server. Set it up in Settings first.")
        }
        let p = Player.shared
        let body: [String: Any] = [
            "name": name, "password": password, "you": myName, "tracks": p.queue.map(wire), "index": max(0, p.index),
            "position": Clock.shared.time, "playing": p.isPlaying,
        ]
        let data = try await request("POST", home.address + "/api/jams", body: body, headers: home.authHeader)
        start(home.address, try JSONDecoder().decode(Joined.self, from: data))
    }

    func join(_ found: JamFound, password: String) async throws {
        let data = try await request("POST", found.server + "/api/jam/\(found.info.id)/join", body: ["you": myName, "password": password])
        playAlong = false
        start(found.server, try JSONDecoder().decode(Joined.self, from: data))
    }

    /// What a Jam link points to: https://server/jam/ID or tunio://jam?u=server&j=ID.
    static func parseLink(_ text: String) -> (server: String, id: String)? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = t.range(of: #"tunio://jam\?\S+"#, options: .regularExpression), let c = URLComponents(string: String(t[r])) {
            let q = Dictionary((c.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
            if let u = q["u"], let j = q["j"], !u.isEmpty, !j.isEmpty { return (u.hasSuffix("/") ? String(u.dropLast()) : u, j) }
        }
        if let r = t.range(of: #"https?://[^\s/]+(/\S*?)?/jam/[A-Za-z0-9]+"#, options: .regularExpression) {
            let s = String(t[r])
            if let cut = s.range(of: "/jam/", options: .backwards) {
                return (String(s[..<cut.lowerBound]), String(s[cut.upperBound...]))
            }
        }
        return nil
    }

    /// A link was opened: look the Jam up and ask to join it.
    func openLink(server: String, id: String) async {
        if let data = try? await request("GET", server + "/api/jams", timeout: 6),
           let list = try? JSONDecoder().decode(JamList.self, from: data), let info = list.jams.first(where: { $0.id == id }) {
            pending = JamFound(server: server, info: info)
            showSheet = true
        } else {
            Player.shared.message = "That Jam has ended, or its server can't be reached."
        }
    }

    private func start(_ srv: String, _ j: Joined) {
        stop()
        server = srv.hasSuffix("/") ? String(srv.dropLast()) : srv
        token = j.token
        bestRtt = .greatestFiniteMagnitude
        set(j.state)
        Task { await syncClock() }
        pollTask = Task { await poll() }
    }

    func leave() {
        guard let s = state else { return }
        let (srv, tok) = (server, token)
        let action = s.you?.host == true ? "end" : "leave"
        Task.detached { _ = try? await Jam.send("POST", srv + "/api/jam/\(s.id)", body: ["action": action], token: tok) }
        stop()
    }

    private func stop() {
        pollTask?.cancel()
        pollTask = nil
        if state != nil { set(nil) }
        if playAlong { playAlong = false }
    }

    private func set(_ s: JamState?) {
        let old = state
        state = s
        Player.shared.followJam(old, s)
        // the host's phone announces the Jam over Bluetooth to phones nearby
        if let s, s.you?.host == true {
            JamBeacon.shared.startHosting(server: server, id: s.id, name: s.name, locked: s.locked == true, host: s.you?.name ?? "")
        } else if old?.you?.host == true {
            JamBeacon.shared.stopHosting()
        }
    }

    // MARK: following the Jam

    private func poll() async {
        var fails = 0
        while !Task.isCancelled, let s = state {
            do {
                let sent = Date().timeIntervalSince1970
                let data = try await request("GET", server + "/api/jam/\(s.id)?since=\(s.version)&wait=20", timeout: 35)
                let next = try JSONDecoder().decode(JamState.self, from: data)
                clock(sent, Date().timeIntervalSince1970, next.serverTime)
                fails = 0
                guard !Task.isCancelled, state?.id == next.id else { return }
                if next.ended == true {
                    Player.shared.message = isHost ? "The Jam has ended." : "The host ended the Jam."
                    stop()
                    return
                }
                if next.version != s.version { set(next) }
            } catch let e as JamHTTPError where [403, 404, 410].contains(e.code) {
                Player.shared.message = "The Jam has ended."
                stop()
                return
            } catch {
                if Task.isCancelled { return }
                fails += 1
                if fails == 3 { Player.shared.message = "Can't reach the Jam. Trying again…" }
                try? await Task.sleep(nanoseconds: UInt64(min(10, fails)) * 1_000_000_000)
            }
        }
    }

    /// A few quick round trips to learn the server's clock (for playing in sync).
    private func syncClock() async {
        for _ in 0..<5 {
            let sent = Date().timeIntervalSince1970
            if let data = try? await request("GET", server + "/api/jam-time", timeout: 5),
               let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let t = o["serverTime"] as? Double {
                clock(sent, Date().timeIntervalSince1970, t)
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
    }

    private func clock(_ sent: Double, _ got: Double, _ serverTime: Double) {
        let rtt = got - sent
        guard serverTime > 0, rtt <= 2, rtt <= bestRtt * 1.5 + 0.05 else { return }
        bestRtt = min(bestRtt, rtt)
        offset = serverTime - (sent + got) / 2
    }

    // MARK: actions (anyone in the Jam)

    func add(_ tracks: [Track], next: Bool = false, play: Bool = false) {
        guard !tracks.isEmpty else { return }
        act(["action": "add", "tracks": tracks.map(wire), "next": next, "play": play],
            done: tracks.count == 1 ? "Added to the Jam: \(tracks[0].title)" : "Added \(tracks.count) songs to the Jam")
    }
    func remove(_ qid: String) { act(["action": "remove", "qid": qid]) }
    func move(_ qid: String, to: Int) { act(["action": "move", "qid": qid, "to": to]) }
    func jump(_ qid: String) { act(["action": "jump", "qid": qid]) }
    func next(from qid: String? = nil) {
        var body: [String: Any] = ["action": "next"]
        if let qid { body["from"] = qid }
        act(body)
    }
    func previous() { act(["action": "prev"]) }
    func setPlaying(_ play: Bool) { act(["action": play ? "play" : "pause"]) }
    func seek(_ seconds: Double) { act(["action": "seek", "position": seconds]) }

    /// The host's real position, so guests playing along stay in sync with what's actually heard.
    func report(qid: String, position: Double, playing: Bool) {
        guard isHost else { return }
        act(["action": "report", "qid": qid, "position": position, "playing": playing], quiet: true)
    }

    private func act(_ body: [String: Any], done: String? = nil, quiet: Bool = false) {
        guard let s = state else { return }
        let (srv, tok) = (server, token)
        Task {
            do {
                let data = try await Jam.send("POST", srv + "/api/jam/\(s.id)", body: body, token: tok)
                let next = try JSONDecoder().decode(JamState.self, from: data)
                if state?.id == next.id, next.version > (state?.version ?? 0) { set(next) }
                if let done { Player.shared.message = done }
            } catch {
                if !quiet { Player.shared.message = error.localizedDescription }
            }
        }
    }

    /// Where a guest playing along streams a Jam song from (the server fetched it ahead).
    func audioURL(for t: Track) -> URL? {
        guard active, !isHost, playAlong, let s = state else { return nil }
        guard let q = (s.current?.id == t.id ? s.current : s.queue.first { $0.id == t.id }) else { return nil }
        return Net.url(server + "/api/jam/\(s.id)/audio", ["qid": q.qid, "token": token])
    }

    private func wire(_ t: Track) -> [String: Any] {
        var o: [String: Any] = ["id": t.id, "source": t.source.rawValue, "title": t.title, "artist": t.artist, "durationSec": t.durationSec]
        if let v = t.thumbnail { o["thumbnail"] = v }
        if let v = t.url { o["url"] = v }
        if let v = t.matchedUrl { o["matchedUrl"] = v }
        return o
    }

    private func request(_ method: String, _ url: String, body: [String: Any]? = nil, headers: [String: String] = [:],
                         timeout: TimeInterval = 12) async throws -> Data {
        try await Jam.send(method, url, body: body, token: token.isEmpty ? nil : token, headers: headers, timeout: timeout)
    }

    nonisolated static func send(_ method: String, _ url: String, body: [String: Any]? = nil, token: String? = nil,
                                 headers: [String: String] = [:], timeout: TimeInterval = 12) async throws -> Data {
        guard let u = URL(string: url) else { throw TunioError("Bad Jam address") }
        var r = URLRequest(url: u, timeoutInterval: timeout)
        r.httpMethod = method
        r.cachePolicy = .reloadIgnoringLocalCacheData
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { r.setValue(token, forHTTPHeaderField: "X-Jam-Token") }
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await Net.session.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw JamHTTPError(code: code, message: msg ?? (code == 404 ? "This server doesn't know Jams yet: update it with the install command." : "The Jam server answered \(code)"))
        }
        return data
    }
}

struct JamHTTPError: LocalizedError {
    var code: Int
    var message: String
    var errorDescription: String? { message }
}
