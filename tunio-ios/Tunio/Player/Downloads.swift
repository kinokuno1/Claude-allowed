import Foundation

/// Offline songs: saved in the app's Documents/Offline folder (visible in the Files app under "On My iPhone").
@MainActor
final class Downloads: ObservableObject {
    static let shared = Downloads()

    @Published private(set) var offline: Set<String> = []
    /// track id → 0…1 while downloading
    @Published private(set) var progress: [String: Double] = [:]
    @Published var lastError: String?

    let folder: URL
    private var queue: [Track] = []
    private var running = 0
    private let fetcher = Fetcher()

    private init() {
        folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Offline")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        rescan()
    }

    func rescan() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        offline = Set(names.filter { !$0.hasSuffix(".part") }.map { ($0 as NSString).deletingPathExtension })
    }

    func file(for t: Track) -> URL? {
        guard offline.contains(t.id) else { return nil }
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names.first { ($0 as NSString).deletingPathExtension == t.id }.map { folder.appendingPathComponent($0) }
    }

    func isOffline(_ t: Track) -> Bool { offline.contains(t.id) }

    func download(_ list: [Track]) {
        for t in list where !offline.contains(t.id) && progress[t.id] == nil && !queue.contains(where: { $0.id == t.id }) {
            queue.append(t)
            progress[t.id] = 0
        }
        Library.shared.remember(list)
        pump()
    }

    func remove(_ t: Track) {
        if let f = file(for: t) { try? FileManager.default.removeItem(at: f) }
        offline.remove(t.id)
    }

    func removeAll() {
        try? FileManager.default.removeItem(at: folder)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        rescan()
    }

    var totalBytes: Int64 {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names.reduce(0) { sum, n in
            sum + ((try? FileManager.default.attributesOfItem(atPath: folder.appendingPathComponent(n).path)[.size] as? Int64) ?? 0)
        }
    }

    private func pump() {
        while running < 2, !queue.isEmpty {
            let t = queue.removeFirst()
            running += 1
            Task {
                await run(t)
                running -= 1
                pump()
            }
        }
    }

    private func run(_ t: Track) async {
        do {
            let (audio, matched) = try await Engine.audio(for: t)
            if matched.matchedUrl != t.matchedUrl { Library.shared.remember(matched) }
            guard case let .remote(url, headers) = audio else { progress[t.id] = nil; rescan(); return }
            var req = Net.request(url, headers: headers, timeout: 120)
            req.setValue(nil, forHTTPHeaderField: "User-Agent")
            let id = t.id
            let (tmp, resp) = try await fetcher.download(req) { p in Task { @MainActor in self.progress[id] = p } }
            guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw TunioError("Download failed") }
            let ext = Self.fileExtension(http, url)
            let dest = folder.appendingPathComponent("\(t.id).\(ext)")
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            offline.insert(t.id)
        } catch {
            lastError = "Couldn't download “\(t.title)”: \(error.localizedDescription)"
        }
        progress[t.id] = nil
    }

    private static func fileExtension(_ r: HTTPURLResponse, _ url: URL) -> String {
        if let e = r.value(forHTTPHeaderField: "X-Tunio-Ext"), !e.isEmpty { return e }
        let type = (r.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        if type.contains("mpeg") || url.absoluteString.contains(".mp3") { return "mp3" }
        if type.contains("webm") { return "webm" }
        if type.contains("ogg") || type.contains("opus") { return "opus" }
        return "m4a"
    }
}

/// URLSession downloads with progress.
final class Fetcher: NSObject, URLSessionDownloadDelegate {
    private var session: URLSession!
    private var waiting: [Int: (CheckedContinuation<(URL, URLResponse), Error>, (Double) -> Void)] = [:]
    private let lock = NSLock()

    override init() {
        super.init()
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 120
        session = URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }

    func download(_ req: URLRequest, progress: @escaping (Double) -> Void) async throws -> (URL, URLResponse) {
        try await withCheckedThrowingContinuation { cont in
            let task = session.downloadTask(with: req)
            lock.lock(); waiting[task.taskIdentifier] = (cont, progress); lock.unlock()
            task.resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        lock.lock(); let w = waiting[downloadTask.taskIdentifier]; lock.unlock()
        w?.1(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // the temporary file is deleted when this returns: move it somewhere safe first
        let keep = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        lock.lock(); let w = waiting.removeValue(forKey: downloadTask.taskIdentifier); lock.unlock()
        do {
            try FileManager.default.moveItem(at: location, to: keep)
            if let resp = downloadTask.response { w?.0.resume(returning: (keep, resp)) } else { w?.0.resume(throwing: TunioError("No answer")) }
        } catch { w?.0.resume(throwing: error) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        lock.lock(); let w = waiting.removeValue(forKey: task.taskIdentifier); lock.unlock()
        w?.0.resume(throwing: error)
    }
}
