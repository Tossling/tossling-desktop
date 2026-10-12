import AppKit
import CryptoKit

func channelPrefix(_ server: String) -> String {
    healthOf(server) == nil ? "tossy-" : "tossling-"
}

func healthOf(_ server: String) -> [String: Any]? {
    guard let url = URL(string: trimmedServer(server) + "/v1/tossling/health") else { return nil }
    var request = URLRequest(url: url)
    request.timeoutInterval = 10
    let (code, data) = runSync(request)
    guard code == 200, let health = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
          health["server"] as? String == "tossling-server" else { return nil }
    return health
}

func serverRequest(_ server: String, _ path: String, method: String = "GET", token: String) -> URLRequest {
    var r = URLRequest(url: URL(string: "\(trimmedServer(server))/\(path)") ?? URL(fileURLWithPath: "/dev/null"))
    r.httpMethod = method
    if !token.isEmpty { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    return r
}

func readConfigFile(_ path: String) -> [String: Any]? {
    FileManager.default.contents(atPath: path).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
}

final class OwnToken {
    static let shared = OwnToken(logs: true)

    let logs: Bool
    var health: (String) -> [String: Any]? = healthOf
    var issue: (URLRequest) -> (Int, Data) = runSync
    private var plain = Set<String>()
    private let queue = DispatchQueue(label: "tossling.own-token")

    init(logs: Bool = false) {
        self.logs = logs
    }

    static func offered(_ health: [String: Any]?) -> Bool {
        health?["devices"] as? Bool == true
    }

    func fetch(config path: String, name: String) -> (server: String, token: String, own: String)? {
        queue.sync { obtain(path, name) }
    }

    static func keep(_ found: (server: String, token: String, own: String), config path: String) -> Bool {
        var kept = false
        let saved = rewriteConfig(at: path) { raw in
            guard raw["server"] as? String == found.server, raw["token"] as? String == found.token,
                  (raw["own"] as? String ?? "").isEmpty else { return }
            raw["own"] = found.own
            kept = true
        }
        return saved && kept
    }

    private func obtain(_ path: String, _ name: String) -> (server: String, token: String, own: String)? {
        guard let raw = readConfigFile(path), (raw["own"] as? String ?? "").isEmpty, !(raw["room"] as? String ?? "").isEmpty,
              let server = raw["server"] as? String, let token = raw["token"] as? String, !token.isEmpty,
              let id = raw["device_id"] as? String, !id.isEmpty else { return nil }
        let origin = trimmedServer(server).lowercased()
        guard !plain.contains(origin), let health = health(server) else { return nil }
        guard OwnToken.offered(health) else {
            plain.insert(origin)
            return nil
        }
        var r = serverRequest(server, "v1/tossling/devices", method: "POST", token: token)
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["id": id, "name": name])
        r.timeoutInterval = 20
        let (code, body) = issue(r)
        guard code == 200, let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let own = json["token"] as? String, !own.isEmpty else {
            if logs { DispatchQueue.main.async { log(L("не получил свой токен: HTTP \(code), попробую позже", "no token of its own yet: HTTP \(code), will try later")) } }
            return nil
        }
        return (server, token, own)
    }
}

func ensureOwnToken() {
    guard !conf.room.isEmpty, conf.own.isEmpty, !conf.token.isEmpty else { return }
    let path = configFile
    let name = conf.deviceName
    DispatchQueue.global(qos: .utility).async {
        guard let found = OwnToken.shared.fetch(config: path, name: name) else { return }
        DispatchQueue.main.async {
            guard OwnToken.keep(found, config: path) else { return }
            log(L("сервер выдал этому Mac свой токен", "the server gave this Mac a token of its own"))
            reloadConfig()
            streams.forEach { $0.reconnect() }
            Alerts.shared.renew()
        }
    }
}

func deleteDeviceTokens(server: String, token: String, id: String, done: @escaping (Int, Int) -> Void) {
    var r = serverRequest(server, "v1/tossling/devices/\(id)", method: "DELETE", token: token)
    r.timeoutInterval = 15
    fetch(r) { data, response, _ in
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let removed = (data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any])?["removed"] as? Int ?? 0
        DispatchQueue.main.async { done(code, removed) }
    }
}

func leaveOwnToken(config path: String, done: @escaping () -> Void) {
    guard let raw = readConfigFile(path), let own = raw["own"] as? String, !own.isEmpty else { return done() }
    let server = raw["server"] as? String ?? ""
    let id = raw["device_id"] as? String ?? ""
    let forget = {
        _ = rewriteConfig(at: path) { $0["own"] = nil }
        done()
    }
    guard !server.isEmpty, !id.isEmpty else { return forget() }
    deleteDeviceTokens(server: server, token: own, id: id) { code, _ in
        log((200..<300).contains(code) ? L("удалил свой токен этого Mac на \(trimmedServer(server))", "deleted this Mac's own token on \(trimmedServer(server))")
            : L("свой токен этого Mac остался на \(trimmedServer(server)): HTTP \(code)", "this Mac's own token stays on \(trimmedServer(server)): HTTP \(code)"))
        forget()
    }
}

func moveRoom(_ raw: inout [String: Any], room: String, key: String, token: String?) {
    raw["room"] = room
    raw["key"] = key
    raw["legacy_to_mac"] = ""
    raw["legacy_to_phone"] = ""
    if let token = token, !token.isEmpty { raw["token"] = token }
}

func followServerMove() {
    let current = trimmedServer(conf.server)
    let token = conf.authToken
    DispatchQueue.global(qos: .utility).async {
        guard let main = healthOf(current)?["url"] as? String, !main.isEmpty else { return }
        let target = trimmedServer(normalizedServer(main))
        guard target.lowercased() != current.lowercased(), let next = URL(string: target), let now = URL(string: current),
              next.scheme == "https" || next.scheme == now.scheme, healthOf(target) != nil,
              let account = URL(string: target + "/v1/account") else { return }
        var request = URLRequest(url: account)
        request.timeoutInterval = 10
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        guard runSync(request).0 == 200 else {
            log(L("сервер теперь \(target), но он не принял токен: остаюсь на \(current)", "the server is now \(target), but it did not accept the token: staying on \(current)"))
            return
        }
        DispatchQueue.main.async {
            guard trimmedServer(conf.server) == current, rewriteConfig({ $0["server"] = target }) else { return }
            log(L("сервер переехал: \(current) → \(target)", "the server moved: \(current) → \(target)"))
            restartAgent()
        }
    }
}

func isRoomTopic(_ topic: String) -> Bool {
    topic.hasPrefix("tossling-") || topic.hasPrefix("tossy-")
}

func randomHex(_ bytes: Int) -> String {
    (0..<bytes).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
}

func reloadConfig() {
    if let fresh = loadConfig() { conf = fresh }
    StatusMenu.shared.refresh()
}

func changeConfig(_ change: (inout [String: Any]) -> Void) {
    if rewriteConfig(change) { reloadConfig() }
}

func memberKey(_ info: [String: Any]) -> String? {
    guard let text = info["pk"] as? String, Data(base64Encoded: text)?.count == 32 else { return nil }
    return text
}

func mintToken(server: String, token: String, label: String) -> (code: Int, token: String?) {
    var r = serverRequest(server, "v1/account/token", method: "POST", token: token)
    r.setValue("application/json", forHTTPHeaderField: "Content-Type")
    r.httpBody = try? JSONSerialization.data(withJSONObject: ["label": label, "expires": 0])
    r.timeoutInterval = 20
    let (code, body) = runSync(r)
    guard code == 200, let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
          let minted = json["token"] as? String, !minted.isEmpty else {
        log(L("не выпустил новый токен: HTTP \(code)", "could not issue a new token: HTTP \(code)"))
        return (code, nil)
    }
    return (code, minted)
}

func createToken(label: String) -> String? {
    mintToken(server: conf.server, token: conf.authToken, label: label).token
}

func retireTokens() {
    guard let data = FileManager.default.contents(atPath: configFile),
          let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
          let pending = raw["retire"] as? [[String: Any]], !pending.isEmpty else { return }
    let now = Date().timeIntervalSince1970
    for entry in pending {
        guard let token = entry["t"] as? String, (entry["at"] as? Double ?? 0) <= now, token != conf.token else { continue }
        var r = request("v1/account/token", method: "DELETE")
        r.setValue(token, forHTTPHeaderField: "X-Token")
        r.timeoutInterval = 20
        fetch(r) { _, response, _ in
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let outcome: String
            switch code {
            case 200..<300: outcome = L("удалил старый токен ntfy", "deleted the old server token")
            case 404, 410: outcome = L("старого токена ntfy уже нет: HTTP \(code)", "the old server token is already gone: HTTP \(code)")
            case 409: outcome = L("старый токен ntfy задан в настройках сервера, удалить его нельзя: оставляю как есть", "the old server token is set in the server's configuration and cannot be deleted: leaving it as it is")
            case 401, 429:
                DispatchQueue.main.async { log(L("не удалил старый токен ntfy: HTTP \(code), попробую позже", "did not delete the old server token: HTTP \(code), will try later")) }
                return
            case 400..<500: outcome = L("старый токен ntfy уже недействителен: HTTP \(code)", "the old server token is already invalid: HTTP \(code)")
            default: return
            }
            DispatchQueue.main.async {
                log(outcome)
                _ = rewriteConfig { raw in
                    raw["retire"] = (raw["retire"] as? [[String: Any]] ?? []).filter { $0["t"] as? String != token }
                }
            }
        }
    }
}

func dropToken(_ token: String) {
    var r = request("v1/account/token", method: "DELETE")
    r.setValue(token, forHTTPHeaderField: "X-Token")
    r.timeoutInterval = 20
    fetch(r) { _, response, _ in
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        DispatchQueue.main.async {
            log((200..<300).contains(code) ? L("удалил новый токен ntfy: комната осталась прежней", "deleted the new server token: the room stays as it was")
                : L("не удалил новый токен ntfy: HTTP \(code)", "could not delete the new server token: HTTP \(code)"))
        }
    }
}

func revokeDevice(_ id: String) {
    guard id != conf.owner else {
        notify(L("Создателя комнаты нельзя отключить", "The creator of the room cannot be disconnected"))
        return
    }
    let name = (members[id]?["name"] as? String).map { conf.aliases[id] ?? $0 } ?? L("устройство", "a device")
    let others = members.filter { $0.key != id && $0.key != conf.deviceID && !$0.key.hasPrefix("legacy-") }
    let isLegacy = others.values.contains { memberKey($0) == nil }
    let suffix = randomHex(12)
    let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }.base64EncodedString()
    let oldToken = conf.token
    let own = conf.own
    let server = conf.server
    DispatchQueue.global(qos: .userInitiated).async {
        let room = channelPrefix(conf.server) + suffix
        let token = isLegacy || oldToken.isEmpty ? nil : createToken(label: "tossling")
        DispatchQueue.main.async {
            let abandon: (String) -> Void = { text in
                if let token = token { dropToken(token) }
                notify(text)
            }
            publish(kind: "kick", mime: "text/plain", data: Data(), text: "", extra: ["to": [id]]) { kicked in
                guard kicked else {
                    abandon(L("Не отключил \(name): нет связи с сервером", "Did not disconnect \(name): no connection to the server"))
                    return
                }
                let finish = {
                    let saved = rewriteConfig { raw in
                        moveRoom(&raw, room: room, key: key, token: token)
                        if token != nil {
                            var retire = raw["retire"] as? [[String: Any]] ?? []
                            retire.append(["t": oldToken, "at": Date().timeIntervalSince1970 + 24 * 3600])
                            raw["retire"] = retire
                        }
                    }
                    members[id] = nil
                    lastID = ""
                    writeState()
                    log(L("отключил \(name): новая комната и ключ\(token == nil ? "" : ", новый токен")", "disconnected \(name): a new room and key\(token == nil ? "" : ", a new token")"))
                    notify(L("\(name) отключён от комнаты\(isLegacy ? ". Часть устройств без ключей: обнови их и отключи ещё раз, если нужно" : "")", "\(name) is disconnected from the room\(isLegacy ? ". Some devices have no keys: update them and disconnect again if needed" : "")"))
                    let restart = { if saved { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { restartAgent() } } }
                    guard !own.isEmpty else { return restart() }
                    deleteDeviceTokens(server: server, token: own, id: id) { code, removed in
                        log((200..<300).contains(code) ? L("удалил токены \(name) на сервере: \(removed)", "deleted the server tokens of \(name): \(removed)")
                            : L("токен \(name) остался на сервере: HTTP \(code)", "the server token of \(name) stays: HTTP \(code)"))
                        restart()
                    }
                }
                guard !others.isEmpty else { return finish() }
                let keys = others.compactMapValues(memberKey)
                let sealed = sealRoomKey(room: room, key: key, token: token, for: keys)
                var extra: [String: Any] = ["to": Array(others.keys), "e": sealed.ephemeral, "keys": sealed.keys]
                if isLegacy {
                    extra["r"] = room
                    extra["key"] = key
                }
                publish(kind: "rekey", mime: "text/plain", data: Data(), text: "", extra: extra) { ok in
                    if ok { finish() } else { abandon(L("Не разослал новый ключ: нет связи с сервером, \(name) всё ещё в комнате", "Did not send out the new key: no connection to the server, \(name) is still in the room")) }
                }
            }
        }
    }
}

func renameSelf(_ name: String) {
    changeConfig { $0["name"] = name }
    publish(kind: "hello", mime: "text/plain", data: Data(), text: "")
}

func setAlias(_ id: String, _ alias: String) {
    changeConfig { raw in
        var aliases = raw["aliases"] as? [String: String] ?? [:]
        aliases[id] = alias.isEmpty ? nil : alias
        raw["aliases"] = aliases
    }
}

func pause(until: Double) {
    changeConfig { $0["paused_until"] = until }
    log(until == 0 ? L("снова отправляю", "sending again") : L("пауза до \(DateFormatter.localizedString(from: Date(timeIntervalSince1970: until), dateStyle: .none, timeStyle: .short))", "paused until \(DateFormatter.localizedString(from: Date(timeIntervalSince1970: until), dateStyle: .none, timeStyle: .short))"))
}

func setHotkeyKeys(_ keys: String) {
    changeConfig { $0["hotkey_keys"] = keys }
    unregisterHotKey()
    if conf.hotkey { registerHotKey() }
}

func setFlag(_ key: String, _ value: Bool) {
    changeConfig { $0[key] = value }
    if key == "hotkey" { value ? registerHotKey() : unregisterHotKey() }
    if key == "screenshots" { ScreenshotWatcher.shared.refresh() }
}

func helperCommand(_ args: [String], output: ((String) -> Void)? = nil, done: ((Int32) -> Void)? = nil) -> Process? {
    guard let path = Bundle.main.executablePath else { return nil }
    let p = Process()
    p.executableURL = URL(fileURLWithPath: path)
    p.arguments = ["--config", configFile] + args
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = FileHandle.nullDevice
    if let output = output {
        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            text.split(separator: "\n").forEach { line in DispatchQueue.main.async { output(String(line)) } }
        }
    }
    p.terminationHandler = { proc in
        pipe.fileHandleForReading.readabilityHandler = nil
        DispatchQueue.main.async { done?(proc.terminationStatus) }
    }
    do {
        try p.run()
    } catch {
        return nil
    }
    return p
}

func restartAgent() {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
    p.arguments = ["kickstart", "-k", "gui/\(getuid())/\(serviceLabel)"]
    try? p.run()
    p.waitUntilExit()
    exit(1)
}
