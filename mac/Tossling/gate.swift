import CryptoKit
import Foundation

let defaultGateFile = FileManager.default.homeDirectoryForCurrentUser.path + "/.cache/tossling/refusals.json"

enum Refusal: String {
    case token, address

    var code: Int { self == .token ? 401 : 429 }

    var text: String {
        self == .token
            ? L("Сервер больше не принимает токен этого компьютера. Подключи его к комнате заново.", "The server no longer accepts this computer's token. Connect it to the room again.")
            : L("С этой сети слишком много запросов, сервер просит подождать. Tossling попробует позже.", "Too many requests from this network, the server asks to wait. Tossling will try again later.")
    }
}

final class Gate {
    static let shared = Gate(file: defaultGateFile, logs: true)
    static let firstPause = 60.0
    static let longestPause = 30.0 * 60
    static let forgetAfter = 24.0 * 3600

    let file: String
    let logs: Bool
    var clock: () -> Double = { Date().timeIntervalSince1970 }
    private let lock = NSLock()
    private var entries = [String: [String: Double]]()

    init(file: String, logs: Bool = false) {
        self.file = file
        self.logs = logs
    }

    static func pause(_ failures: Int) -> Double {
        min(firstPause * pow(2, Double(max(failures, 1) - 1)), longestPause)
    }

    static func origin(_ url: URL?) -> String {
        guard let url = url, let scheme = url.scheme, let host = url.host else { return "" }
        return "\(scheme)://\(host)\(url.port.map { ":\($0)" } ?? "")".lowercased()
    }

    static func tokenKey(_ origin: String, _ token: String) -> String {
        "token \(origin) " + SHA256.hash(data: Data(token.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    static func addressKey(_ origin: String) -> String {
        "address \(origin)"
    }

    static func bearer(_ request: URLRequest) -> String {
        let value = request.value(forHTTPHeaderField: "Authorization") ?? ""
        return value.hasPrefix("Bearer ") ? String(value.dropFirst("Bearer ".count)) : ""
    }

    static func isHealth(_ request: URLRequest) -> Bool {
        request.url.map { ["/v1/health", "/v1/tossling/health", "/v1/tossy/health"].contains($0.path) } == true
    }

    func refusal(for request: URLRequest) -> (kind: Refusal, wait: Double)? {
        guard !Gate.isHealth(request) else { return nil }
        let origin = Gate.origin(request.url)
        let now = clock()
        return withEntries { entries in
            for (kind, key) in [(Refusal.address, Gate.addressKey(origin)), (.token, Gate.tokenKey(origin, Gate.bearer(request)))] {
                if let until = entries[key]?["until"], until > now { return (kind, until - now) }
            }
            return nil
        }
    }

    func standing(server: String, token: String) -> Refusal? {
        let origin = Gate.origin(URL(string: server))
        return withEntries { entries in
            if entries[Gate.tokenKey(origin, token)] != nil { return .token }
            if entries[Gate.addressKey(origin)] != nil { return .address }
            return nil
        }
    }

    func note(_ request: URLRequest, code: Int, sentAt: Double) {
        guard !Gate.isHealth(request) else { return }
        let origin = Gate.origin(request.url)
        let tokenKey = Gate.tokenKey(origin, Gate.bearer(request))
        let addressKey = Gate.addressKey(origin)
        if (200..<300).contains(code) {
            withEntries { entries in
                entries[tokenKey] = nil
                entries[addressKey] = nil
            }
            return
        }
        let account = request.url?.path.contains("/v1/account") == true
        let kind: Refusal
        switch code {
        case 429: kind = .address
        case 401: kind = .token
        case 403 where account: kind = .token
        default: return
        }
        let key = kind == .token ? tokenKey : addressKey
        let now = clock()
        let paused: Double? = withEntries { entries in
            let failures = entries[key]?["n"] ?? 0
            if let until = entries[key]?["until"], sentAt < until { return nil }
            let pause = Gate.pause(Int(failures) + 1)
            entries[key] = ["n": failures + 1, "until": now + pause]
            return pause
        }
        guard logs, let pause = paused else { return }
        let minutes = Int(pause / 60)
        log(kind == .token
            ? L("\(origin) не принял токен: HTTP \(code), следующий запрос через \(minutes) мин", "\(origin) did not accept the token: HTTP \(code), the next request in \(minutes) min")
            : L("\(origin) просит подождать: HTTP 429, следующий запрос через \(minutes) мин", "\(origin) asks to wait: HTTP 429, the next request in \(minutes) min"))
    }

    @discardableResult
    private func withEntries<T>(_ body: (inout [String: [String: Double]]) -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        var fd = file.isEmpty ? -1 : open(file, O_RDWR | O_CREAT, 0o600)
        if fd < 0 && !file.isEmpty && errno == ENOENT {
            try? FileManager.default.createDirectory(atPath: (file as NSString).deletingLastPathComponent, withIntermediateDirectories: true,
                                                     attributes: [.posixPermissions: 0o700])
            fd = open(file, O_RDWR | O_CREAT, 0o600)
        }
        defer { if fd >= 0 { close(fd) } }
        if fd >= 0 {
            flock(fd, LOCK_EX)
            let data = FileHandle(fileDescriptor: fd, closeOnDealloc: false).readDataToEndOfFile()
            entries = (try? JSONSerialization.jsonObject(with: data)) as? [String: [String: Double]] ?? [:]
        }
        let before = entries
        let now = clock()
        entries = entries.filter { ($0.value["until"] ?? 0) > now - Gate.forgetAfter }
        let result = body(&entries)
        if fd >= 0 && entries != before, let out = try? JSONSerialization.data(withJSONObject: entries, options: [.sortedKeys]) {
            ftruncate(fd, 0)
            _ = out.withUnsafeBytes { pwrite(fd, $0.baseAddress, out.count, 0) }
        }
        return result
    }
}

func refusedResponse(_ request: URLRequest, _ kind: Refusal) -> HTTPURLResponse? {
    HTTPURLResponse(url: request.url ?? URL(fileURLWithPath: "/"), statusCode: kind.code, httpVersion: nil, headerFields: nil)
}

func fetch(_ request: URLRequest, _ done: @escaping (Data?, URLResponse?, Error?) -> Void) {
    if let refused = Gate.shared.refusal(for: request) {
        DispatchQueue.global(qos: .utility).async { done(Data(refused.kind.text.utf8), refusedResponse(request, refused.kind), nil) }
        return
    }
    let sentAt = Date().timeIntervalSince1970
    URLSession.shared.dataTask(with: request) { data, response, error in
        Gate.shared.note(request, code: (response as? HTTPURLResponse)?.statusCode ?? 0, sentAt: sentAt)
        done(data, response, error)
    }.resume()
}
