import AppKit
import ServiceManagement
import SwiftUI

let defaultConfigFile = FileManager.default.homeDirectoryForCurrentUser.path + "/.config/tossling/config.json"
let defaultStateFile = FileManager.default.homeDirectoryForCurrentUser.path + "/.cache/tossling/state.json"
let defaultLogFile = FileManager.default.homeDirectoryForCurrentUser.path + "/Library/Logs/Tossling.log"
let isDistributed = Bundle.main.object(forInfoDictionaryKey: "TosslingDistribution") as? Bool == true

func hex(_ count: Int) -> String {
    (0..<count).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
}

func normalizedServer(_ text: String) -> String {
    var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
    while value.hasSuffix("/") { value.removeLast() }
    if !value.isEmpty && !value.lowercased().hasPrefix("http://") && !value.lowercased().hasPrefix("https://") {
        value = "https://" + value
    }
    return value
}

func checkServer(_ server: String, token: String, done: @escaping (String?, String) -> Void) {
    guard let url = URL(string: server + "/v1/account") else { return done(L("Не понял адрес сервера", "Could not read the server address"), "") }
    var request = URLRequest(url: url, timeoutInterval: 15)
    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    URLSession.shared.dataTask(with: request) { data, response, error in
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let problem: String?
        switch code {
        case 200: problem = nil
        case 401, 403: problem = L("Сервер не принял токен", "The server did not accept the token")
        case 0: problem = L("Нет связи с сервером", "No connection to the server") + (error.map { ": \($0.localizedDescription)" } ?? "")
        default: problem = L("Сервер ответил HTTP \(code)", "The server answered HTTP \(code)")
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let prefix = problem == nil ? channelPrefix(server) : ""
            DispatchQueue.main.async { done(problem, prefix) }
        }
    }.resume()
}

func writeNewConfig(_ values: [String: Any], to path: String) -> Bool {
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    var merged = values
    if let data = FileManager.default.contents(atPath: path), let old = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
        for key in ["name", "aliases", "identity", "language", "device_id"] where merged[key] == nil {
            merged[key] = old[key]
        }
    }
    guard let data = try? JSONSerialization.data(withJSONObject: merged, options: [.sortedKeys]) else { return false }
    let tmp = path + ".tmp"
    guard FileManager.default.createFile(atPath: tmp, contents: data, attributes: [.posixPermissions: 0o600]) else { return false }
    return rename(tmp, path) == 0
}

func newRoom(server: String, token: String, deviceID: String, prefix: String) -> [String: Any] {
    var key = Data(count: 32)
    _ = key.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
    return ["server": server, "token": token, "room": prefix + hex(12), "key": key.base64EncodedString(),
            "device_id": deviceID, "owner": deviceID, "legacy_to_mac": "", "legacy_to_phone": "", "images": true]
}

func existingDeviceID(_ path: String) -> String {
    if let data = FileManager.default.contents(atPath: path), let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
       let id = raw["device_id"] as? String, !id.isEmpty {
        return id
    }
    return hex(8)
}

func runSelf(_ arguments: [String], done: @escaping (Int32, String) -> Void) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
    p.arguments = arguments
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = pipe
    p.terminationHandler = { process in
        let text = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        DispatchQueue.main.async { done(process.terminationStatus, text.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
    do {
        try p.run()
    } catch {
        done(-1, error.localizedDescription)
    }
}

func registerAgent() -> String {
    let service = SMAppService.agent(plistName: serviceLabel + ".plist")
    do {
        if service.status != .enabled { try service.register() }
    } catch {
        return error.localizedDescription
    }
    switch service.status {
    case .enabled: return "enabled"
    case .requiresApproval: return "requiresApproval"
    default: return "notRegistered"
    }
}

final class OnboardingModel: ObservableObject {
    enum Step { case choose, server, join, working, pair, done }
    @Published var step = Step.choose
    @Published var server = ""
    @Published var token = ""
    @Published var code = ""
    @Published var problem = ""
    @Published var status = ""
    @Published var qr: NSImage?
    let configPath: String
    var asked = 0.0

    init(configPath: String) {
        self.configPath = configPath
    }

    var registers: Bool { configPath == defaultConfigFile }

    func connect() {
        let address = normalizedServer(server)
        let key = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !address.isEmpty, !key.isEmpty else {
            problem = L("Нужны адрес сервера и токен", "The server address and the token are needed")
            return
        }
        problem = ""
        status = L("Проверяю сервер…", "Checking the server…")
        step = .working
        checkServer(address, token: key) { [self] trouble, prefix in
            if let trouble = trouble {
                problem = trouble
                step = .server
                return
            }
            guard writeNewConfig(newRoom(server: address, token: key, deviceID: existingDeviceID(configPath), prefix: prefix), to: configPath) else {
                problem = L("Не записал настройки в \(configPath)", "Could not save the settings to \(configPath)")
                step = .server
                return
            }
            start(thenPair: true)
        }
    }

    func join() {
        let text = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let slash = text.lastIndex(of: "/") else {
            problem = L("Нужно вида tossling.example.com/ABCD-EFGH", "Expected tossling.example.com/ABCD-EFGH")
            return
        }
        let address = normalizedServer(String(text[..<slash]))
        let invite = String(text[text.index(after: slash)...])
        let out = NSTemporaryDirectory() + "tossling-join-\(getpid()).json"
        problem = ""
        status = L("Жду ответа от другого Mac…", "Waiting for the other Mac…")
        step = .working
        runSelf(["--config", "/dev/null", "--join", address, invite, "--out", out, "--id", existingDeviceID(configPath), "--name", ""]) { [self] code, output in
            defer { try? FileManager.default.removeItem(atPath: out) }
            guard code == 0, let data = FileManager.default.contents(atPath: out),
                  let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let server = raw["s"] as? String, let token = raw["t"] as? String, let room = raw["r"] as? String, let key = raw["k"] as? String else {
                problem = output.isEmpty ? L("Не получилось войти в комнату", "Could not join the room") : output
                step = .join
                return
            }
            let values: [String: Any] = ["server": normalizedServer(server), "token": token, "room": room, "key": key,
                                         "device_id": existingDeviceID(configPath), "owner": raw["o"] as? String ?? "", "images": true]
            guard writeNewConfig(values, to: configPath) else {
                problem = L("Не записал настройки в \(configPath)", "Could not save the settings to \(configPath)")
                step = .join
                return
            }
            status = output.isEmpty ? "" : L("Этот Mac в комнате с \(output)", "This Mac is in the room with \(output)")
            start(thenPair: false)
        }
    }

    func start(thenPair: Bool) {
        if registers {
            let result = registerAgent()
            if result == "requiresApproval" {
                status = L("Разреши Tossling в Системных настройках → Основные → Объекты входа и расширения", "Allow Tossling in System Settings → General → Login Items & Extensions")
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.LoginItems-Settings.extension")!)
            } else if result != "enabled" {
                problem = L("macOS не запустил Tossling: \(result)", "macOS did not start Tossling: \(result)")
            }
        }
        if !thenPair {
            step = .done
            return
        }
        let png = NSTemporaryDirectory() + "tossling-pair-\(getpid()).png"
        runSelf(["--config", configPath, "--qr", png]) { [self] code, _ in
            defer { try? FileManager.default.removeItem(atPath: png) }
            qr = code == 0 ? NSImage(contentsOfFile: png) : nil
            asked = Date().timeIntervalSince1970
            step = qr == nil ? .done : .pair
            if qr != nil { waitForPhone() }
        }
    }

    func waitForPhone() {
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] timer in
            guard let self = self, self.step == .pair else { timer.invalidate(); return }
            guard let data = FileManager.default.contents(atPath: defaultStateFile),
                  let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let at = raw["phone_at"] as? Double, at >= self.asked else { return }
            self.status = L("Подключён \(raw["phone"] as? String ?? "телефон")", "Paired \(raw["phone"] as? String ?? "the phone")")
            self.qr = nil
            self.step = .done
            timer.invalidate()
        }
    }
}

struct OnboardingView: View {
    @ObservedObject var model: OnboardingModel
    let close: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 72, height: 72)
            Text("Tossling")
                .font(.largeTitle.weight(.bold))
            switch model.step {
            case .choose:
                Text(L("Общий буфер обмена твоих компьютеров и телефона через твой сервер.", "One clipboard for your computers and phone, through your own server."))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                Button(L("Подключить свой сервер", "Connect my server")) { model.step = .server }
                    .controlSize(.large)
                    .modifier(GlassButton(prominent: true))
                Button(L("Войти в комнату другого Mac", "Join another Mac's room")) { model.step = .join }
                    .controlSize(.large)
                    .modifier(GlassButton(prominent: false))
                Text(L("Сервер — Tossling Server: один Docker-контейнер, на его странице настройки есть адрес и токен.", "The server is Tossling Server, one Docker container; its setup page shows the address and the token."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            case .server:
                field(L("Адрес сервера", "Server address"), text: $model.server, prompt: "https://tossling.example.com")
                secureField(L("Токен", "Token"), text: $model.token, prompt: "tk_…")
                problemText
                HStack {
                    Button(L("Назад", "Back")) { model.problem = ""; model.step = .choose }
                        .modifier(GlassButton(prominent: false))
                    Button(L("Подключить", "Connect")) { model.connect() }
                        .keyboardShortcut(.defaultAction)
                        .modifier(GlassButton(prominent: true))
                }
                .controlSize(.large)
            case .join:
                Text(L("На Mac, который уже в комнате: строка меню Tossling → Устройства → Пригласить ещё один Mac. Вставь сюда код.", "On a Mac already in the room: Tossling in the menu bar → Devices → Invite Another Mac. Paste the code here."))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                field(L("Код приглашения", "Invite code"), text: $model.code, prompt: "tossling.example.com/ABCD-EFGH")
                problemText
                HStack {
                    Button(L("Назад", "Back")) { model.problem = ""; model.step = .choose }
                        .modifier(GlassButton(prominent: false))
                    Button(L("Войти", "Join")) { model.join() }
                        .keyboardShortcut(.defaultAction)
                        .modifier(GlassButton(prominent: true))
                }
                .controlSize(.large)
            case .working:
                ProgressView()
                Text(model.status).foregroundStyle(.secondary)
            case .pair:
                Text(L("Открой Tossling на телефоне → «Подключить компьютер» и наведи камеру на код.", "Open Tossling on the phone → «Pair with a computer» and point the camera at the code."))
                    .multilineTextAlignment(.center)
                if let qr = model.qr {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 220, height: 220)
                        .padding(14)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                }
                Text(L("В коде ключ комнаты и токен — не показывай его посторонним.", "The code holds the room key and the token; do not show it to others."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button(L("Позже", "Later"), action: close)
                    .modifier(GlassButton(prominent: false))
            case .done:
                Label(model.status.isEmpty ? L("Tossling работает: он в строке меню", "Tossling is running: it lives in the menu bar") : model.status, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                problemText
                Button(L("Готово", "Done"), action: close)
                    .keyboardShortcut(.defaultAction)
                    .controlSize(.large)
                    .modifier(GlassButton(prominent: true))
            }
        }
        .padding(.horizontal, 32)
        .padding(.top, 34)
        .padding(.bottom, 26)
        .frame(width: 420)
    }

    @ViewBuilder var problemText: some View {
        if !model.problem.isEmpty {
            Label(model.problem, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    func field(_ title: String, text: Binding<String>, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.callout.weight(.medium))
            TextField("", text: text, prompt: Text(prompt))
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
        }
    }

    func secureField(_ title: String, text: Binding<String>, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.callout.weight(.medium))
            SecureField("", text: text, prompt: Text(prompt))
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
        }
    }
}

func runOnboarding(configPath: String) -> Never {
    let app = NSApplication.shared
    app.setActivationPolicy(.regular)
    installEditMenu(quit: true)
    let model = OnboardingModel(configPath: configPath)
    var window: NSPanel!
    window = makePanelWindow(OnboardingView(model: model) { window.close(); exit(0) })
    window.styleMask.insert(.miniaturizable)
    window.hidesOnDeactivate = false
    window.center()
    window.makeKeyAndOrderFront(nil)
    if #available(macOS 14, *) {
        app.activate()
    } else {
        app.activate(ignoringOtherApps: true)
    }
    NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { _ in exit(0) }
    app.run()
    exit(0)
}
