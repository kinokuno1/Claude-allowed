import Foundation

/// Small HTTP helpers.
enum Net {
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 25
        c.httpAdditionalHeaders = ["Accept-Language": "en-US,en;q=0.9"]
        return URLSession(configuration: c)
    }()

    static func request(_ url: URL, method: String = "GET", headers: [String: String] = [:], body: Data? = nil,
                        timeout: TimeInterval = 25) -> URLRequest {
        var r = URLRequest(url: url, timeoutInterval: timeout)
        r.httpMethod = method
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = body
        return r
    }

    static func data(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw TunioError("No answer from \(req.url?.host ?? "the server")") }
        guard (200..<300).contains(http.statusCode) else {
            throw TunioError("\(req.url?.host ?? "Server") answered \(http.statusCode)")
        }
        return (data, http)
    }

    static func get(_ url: URL, headers: [String: String] = [:]) async throws -> Data {
        try await data(request(url, headers: headers)).0
    }

    static func text(_ url: URL, headers: [String: String] = [:]) async throws -> String {
        String(decoding: try await get(url, headers: headers), as: UTF8.self)
    }

    static func json(_ url: URL, headers: [String: String] = [:]) async throws -> J {
        J(try JSONSerialization.jsonObject(with: try await get(url, headers: headers)))
    }

    static func postJSON(_ url: URL, _ body: Any, headers: [String: String] = [:], timeout: TimeInterval = 25) async throws -> J {
        var h = headers
        h["Content-Type"] = "application/json"
        let req = request(url, method: "POST", headers: h, body: try JSONSerialization.data(withJSONObject: body), timeout: timeout)
        return J(try JSONSerialization.jsonObject(with: try await data(req).0))
    }

    static func url(_ base: String, _ query: [String: String]) -> URL {
        var c = URLComponents(string: base)!
        c.queryItems = (c.queryItems ?? []) + query.map { URLQueryItem(name: $0.key, value: $0.value) }
        // "+" must be escaped too, or servers read it as a space
        c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return c.url!
    }
}

/// Easy reading of JSON from websites: j["a"]["b"][0].string
struct J {
    let value: Any?
    init(_ value: Any?) { self.value = value }

    subscript(key: String) -> J { J((value as? [String: Any])?[key]) }
    subscript(index: Int) -> J {
        guard let a = value as? [Any], index >= 0, index < a.count else { return J(nil) }
        return J(a[index])
    }
    var string: String? {
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return nil
    }
    var int: Int? {
        if let n = value as? NSNumber { return n.intValue }
        if let s = value as? String { return Int(s) }
        return nil
    }
    var double: Double? {
        if let n = value as? NSNumber { return n.doubleValue }
        if let s = value as? String { return Double(s) }
        return nil
    }
    var bool: Bool? { (value as? NSNumber)?.boolValue }
    var array: [J] { (value as? [Any])?.map { J($0) } ?? [] }
    var dict: [String: Any]? { value as? [String: Any] }
    var exists: Bool { value != nil && !(value is NSNull) }

    /// YouTube text: {"simpleText": "…"} or {"runs": [{"text": "…"}, …]} or {"content": "…"}.
    var text: String? {
        if let s = self["simpleText"].string { return s }
        if let s = self["content"].string { return s }
        let runs = self["runs"].array.compactMap { $0["text"].string }
        return runs.isEmpty ? string : runs.joined()
    }

    /// Every object stored under `key`, anywhere inside (depth-first, in order).
    func all(_ key: String, limit: Int = 500) -> [J] {
        var out: [J] = []
        func walk(_ v: Any?) {
            if out.count >= limit { return }
            if let d = v as? [String: Any] {
                if let hit = d[key] { out.append(J(hit)) }
                for (k, child) in d where k != key { walk(child) }
            } else if let a = v as? [Any] {
                for child in a { walk(child) }
            }
        }
        walk(value)
        return out
    }

    /// First object stored under `key`, anywhere inside.
    func first(_ key: String) -> J { all(key, limit: 1).first ?? J(nil) }
}
