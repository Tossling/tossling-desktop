import AppKit
import ServiceManagement

final class ActionItem: NSMenuItem {
    private let handler: () -> Void

    init(_ title: String, symbol: String? = nil, _ handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
        if let symbol = symbol { image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) }
    }

    required init(coder: NSCoder) {
        fatalError()
    }

    @objc private func fire() {
        handler()
    }
}

func ago(_ time: Double) -> String {
    let seconds = max(0, Date().timeIntervalSince1970 - time)
    switch seconds {
    case ..<60: return L("только что", "just now")
    case ..<3600: return L("\(Int(seconds / 60)) мин назад", "\(Int(seconds / 60)) min ago")
    case ..<86400: return L("\(Int(seconds / 3600)) ч назад", "\(Int(seconds / 3600)) h ago")
    default: return DateFormatter.localizedString(from: Date(timeIntervalSince1970: time), dateStyle: .short, timeStyle: .none)
    }
}

func clock(_ time: Double) -> String {
    DateFormatter.localizedString(from: Date(timeIntervalSince1970: time), dateStyle: .none, timeStyle: .short)
}

final class StatusMenu: NSObject, NSMenuDelegate, NSWindowDelegate {
    static let shared = StatusMenu()
    static let onlineWindow = 5.0 * 60
    static let shownHistory = 8

    private var item: NSStatusItem?
    private var panel: NSPanel?
    private var panelModel: PanelModel?
    private var inviteProcess: Process?
    private var waitsForPhone = false

    func install() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = StatusMenu.icon()
        let menu = NSMenu()
        menu.delegate = self
        menu.autoenablesItems = false
        item.menu = menu
        self.item = item
        refresh()
    }

    func refresh() {
        guard let button = item?.button else { return }
        button.appearsDisabled = !connected || isPaused
        button.toolTip = "Tossling — \(statusLine())"
    }

    func deviceJoined(_ name: String, isMac: Bool) {
        guard panel != nil, waitsForPhone, !isMac else { return }
        waitsForPhone = false
        showDone(L("\(name) подключён", "\(name) is connected"))
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        if let fresh = loadConfig() { conf = fresh }
        menu.removeAllItems()
        menu.addItem(info(statusLine(), symbol: connected ? (isPaused ? "pause.circle" : "checkmark.circle") : "wifi.exclamationmark"))
        menu.addItem(.separator())
        addHistory(to: menu)
        menu.addItem(.separator())
        let send = ActionItem(L("Отправить буфер сейчас", "Send the Clipboard Now"), symbol: "paperplane") { sendNow() }
        if conf.hotkey {
            send.keyEquivalent = "c"
            send.keyEquivalentModifierMask = hotkeyChoice.menu
        }
        menu.addItem(send)
        menu.addItem(ActionItem(L("Отправить файл…", "Send a File…"), symbol: "doc.badge.arrow.up") { [weak self] in self?.pickFiles() })
        menu.addItem(.separator())
        menu.addItem(submenu(L("Устройства", "Devices"), symbol: "laptopcomputer.and.iphone", devicesMenu()))
        menu.addItem(submenu(L("Уведомления проектов", "Project Notifications"), symbol: "bell", alertsMenu()))
        menu.addItem(submenu(isPaused ? L("Пауза до \(clock(conf.pausedUntil))", "Paused until \(clock(conf.pausedUntil))") : L("Пауза", "Pause"), symbol: "pause", pauseMenu()))
        menu.addItem(submenu(L("Настройки", "Settings"), symbol: "gearshape", settingsMenu()))
        menu.addItem(.separator())
        menu.addItem(ActionItem(L("Открыть Загрузки/Tossling", "Open Downloads/Tossling"), symbol: "folder") {
            let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/Tossling", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            NSWorkspace.shared.open(dir)
        })
        menu.addItem(ActionItem(L("Журнал", "Log"), symbol: "list.bullet.rectangle") {
            NSWorkspace.shared.open(FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Tossling.log"))
        })
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        if !version.isEmpty { menu.addItem(info("Tossling \(version)")) }
        if Updates.shared.available {
            if let version = Updates.shared.waiting {
                menu.addItem(ActionItem(L("Установить версию \(version)…", "Install Version \(version)…"), symbol: "arrow.down.circle") { Updates.shared.check() })
            } else {
                menu.addItem(ActionItem(L("Проверить обновления…", "Check for Updates…"), symbol: "arrow.triangle.2.circlepath") { Updates.shared.check() })
            }
        }
        menu.addItem(.separator())
        menu.addItem(ActionItem(L("Выключить Tossling на этом Mac…", "Turn Off Tossling on This Mac…")) { [weak self] in self?.turnOff() })
    }

    private var isPaused: Bool { conf.pausedUntil > Date().timeIntervalSince1970 }

    private func statusLine() -> String {
        let host = conf.server.components(separatedBy: "://").last ?? conf.server
        if !connected { return L("Нет связи с \(host), переподключаюсь", "No connection to \(host), reconnecting") }
        if isPaused { return L("На паузе до \(clock(conf.pausedUntil)), принимаю", "Paused until \(clock(conf.pausedUntil)), receiving") }
        if !conf.auto { return L("На связи · отправляю только по \(hotkeyChoice.title)", "Connected · sending only on \(hotkeyChoice.title)") }
        return L("На связи · \(host)", "Connected · \(host)")
    }

    private func info(_ title: String, symbol: String? = nil) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        if let symbol = symbol { item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) }
        return item
    }

    private func submenu(_ title: String, symbol: String, _ menu: NSMenu) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        menu.autoenablesItems = false
        item.submenu = menu
        return item
    }

    private func styled(_ title: String, _ detail: String) -> NSAttributedString {
        let text = NSMutableAttributedString(string: title, attributes: [.font: NSFont.menuFont(ofSize: 0)])
        text.append(NSAttributedString(string: "   " + detail, attributes: [
            .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize),
            .foregroundColor: NSColor.secondaryLabelColor,
        ]))
        return text
    }

    private func addHistory(to menu: NSMenu) {
        let items = History.shared.items
        guard !items.isEmpty else {
            menu.addItem(info(L("Здесь будет недавнее: скопируй что-нибудь", "Recent items will appear here: copy something")))
            return
        }
        menu.addItem(info(L("Недавнее", "Recent")))
        items.prefix(StatusMenu.shownHistory).forEach { addEntry($0, to: menu) }
        if items.count > StatusMenu.shownHistory {
            let more = NSMenu()
            items.dropFirst(StatusMenu.shownHistory).forEach { addEntry($0, to: more) }
            menu.addItem(submenu(L("Ещё \(items.count - StatusMenu.shownHistory)", "\(items.count - StatusMenu.shownHistory) More"), symbol: "clock.arrow.circlepath", more))
        }
        menu.addItem(ActionItem(L("Очистить историю", "Clear History")) { History.shared.clear() })
    }

    private func addEntry(_ entry: HistoryItem, to menu: NSMenu) {
        let direction = entry.incoming ? "← \(entry.device.isEmpty ? "?" : entry.device)" : (entry.device.isEmpty ? L("→ всем", "→ everyone") : "→ \(entry.device)")
        let detail = "\(direction) · \(clock(entry.time))"
        let exists = History.shared.exists(entry)
        switch entry.kind {
        case "text":
            let line = (entry.text ?? "").split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
            let short = line.count > 42 ? String(line.prefix(42)) + "…" : line
            let item = ActionItem(short.isEmpty ? L("(пустой текст)", "(empty text)") : short, symbol: "text.alignleft") { [weak self] in
                copyAgain(entry)
                self?.flash(L("Текст снова в буфере", "The text is in the clipboard again"))
            }
            item.attributedTitle = styled(item.title, detail)
            item.toolTip = String((entry.text ?? "").prefix(600))
            menu.addItem(item)
        case "image":
            let item = ActionItem(L("Картинка, \(sizeText(entry.size))", "Image, \(sizeText(entry.size))"), symbol: "photo") { [weak self] in
                guard exists else {
                    self?.missing(entry)
                    return
                }
                copyAgain(entry)
                self?.flash(L("Картинка снова в буфере", "The image is in the clipboard again"))
            }
            item.attributedTitle = styled(item.title, detail)
            if let path = entry.file, let image = NSImage(contentsOfFile: path) {
                let side = 22.0
                let scale = side / max(image.size.width, image.size.height, 1)
                image.size = NSSize(width: image.size.width * scale, height: image.size.height * scale)
                item.image = image
            }
            if !exists { item.attributedTitle = styled(item.title, L("картинки уже нет · \(detail)", "the image is gone · \(detail)")) }
            menu.addItem(item)
        default:
            let name = entry.name ?? L("файл", "file")
            let url = URL(fileURLWithPath: entry.file ?? "")
            let reveal = ActionItem(name, symbol: "doc") { [weak self] in
                if exists {
                    NSWorkspace.shared.activateFileViewerSelecting([url])
                } else {
                    self?.missing(entry)
                }
            }
            reveal.attributedTitle = styled(name, exists ? "\(sizeText(entry.size)) · \(detail)" : L("файла уже нет · \(detail)", "the file is gone · \(detail)"))
            reveal.toolTip = exists ? L("Показать в Finder, с ⌥ — открыть", "Show in Finder, with ⌥ open") : L("Файла уже нет на этом Mac", "The file is no longer on this Mac")
            menu.addItem(reveal)
            let open = ActionItem(name, symbol: "arrow.up.forward.app") { [weak self] in
                if exists {
                    NSWorkspace.shared.open(url)
                } else {
                    self?.missing(entry)
                }
            }
            open.attributedTitle = styled(name, L("открыть", "open"))
            open.isAlternate = true
            open.keyEquivalentModifierMask = .option
            menu.addItem(open)
        }
    }

    private func devicesMenu() -> NSMenu {
        let menu = NSMenu()
        let now = Date().timeIntervalSince1970
        let others = members.filter { $0.key != conf.deviceID }.sorted {
            ($0.value["seen"] as? Double ?? 0) > ($1.value["seen"] as? Double ?? 0)
        }
        if others.isEmpty { menu.addItem(info(L("В комнате пока только этот Mac", "Only this Mac is in the room so far"))) }
        for (id, value) in others {
            let own = value["name"] as? String ?? "?"
            let alias = conf.aliases[id].flatMap { $0.isEmpty || $0 == own ? nil : $0 }
            let name = alias ?? own
            let seen = value["seen"] as? Double ?? 0
            let isMac = value["src"] as? String == "mac"
            let state = now - seen < StatusMenu.onlineWindow ? L("на связи", "online") : L("был \(ago(seen))", "seen \(ago(seen))")
            let item = submenu(name, symbol: isMac ? "laptopcomputer" : "iphone", deviceMenu(id: id, name: name, own: own, hasAlias: alias != nil))
            let role = id == conf.owner ? L(" · создатель комнаты", " · created the room") : ""
            let ownNote = alias == nil ? "" : "\(own) · "
            item.attributedTitle = styled(name, ownNote + (memberKey(value) == nil ? L("\(state) · старая версия", "\(state) · old version") : state) + role)
            menu.addItem(item)
        }
        menu.addItem(.separator())
        let me = NSMenu()
        me.addItem(ActionItem(L("Переименовать…", "Rename…")) { [weak self] in
            self?.ask(L("Как назвать этот Mac?", "What should this Mac be called?"), value: conf.deviceName) { renameSelf($0) }
        })
        let self_ = submenu(conf.deviceName, symbol: "desktopcomputer", me)
        self_.attributedTitle = styled(conf.deviceName, conf.isOwner ? L("этот Mac · создатель комнаты", "this Mac · created the room") : L("этот Mac", "this Mac"))
        menu.addItem(self_)
        menu.addItem(.separator())
        menu.addItem(ActionItem(L("Подключить телефон…", "Pair a Phone…"), symbol: "qrcode") { [weak self] in self?.showQR() })
        menu.addItem(ActionItem(L("Пригласить ещё один Mac…", "Invite Another Mac…"), symbol: "person.badge.plus") { [weak self] in self?.showInvite() })
        menu.addItem(ActionItem(L("Войти в комнату другого Mac…", "Join Another Mac's Room…"), symbol: "arrow.right.circle") { [weak self] in self?.showJoin() })
        return menu
    }

    private func deviceMenu(id: String, name: String, own: String, hasAlias: Bool) -> NSMenu {
        let menu = NSMenu()
        if hasAlias {
            menu.addItem(info(L("На этом Mac: \(name)", "On this Mac: \(name)")))
        }
        menu.addItem(info(L("Своё имя: \(own)", "Its own name: \(own)")))
        menu.addItem(.separator())
        menu.addItem(ActionItem(L("Переименовать…", "Rename…")) { [weak self] in
            self?.ask(L("Как подписать «\(own)» на этом Mac?", "What should «\(own)» be called on this Mac?"), value: name,
                      note: L("Имя видно только на этом Mac. Пусто — своё имя устройства.", "The name is shown on this Mac only. Empty: the device's own name.")) { setAlias(id, $0) }
        })
        if hasAlias {
            menu.addItem(ActionItem(L("Вернуть своё имя", "Use Its Own Name")) { setAlias(id, "") })
        }
        guard id != conf.owner else {
            menu.addItem(.separator())
            menu.addItem(info(L("Создатель комнаты: отключить нельзя", "Created the room: cannot be disconnected")))
            return menu
        }
        menu.addItem(.separator())
        menu.addItem(ActionItem(L("Отключить от комнаты…", "Disconnect from the Room…")) { [weak self] in
            self?.confirm(L("Отключить \(name)?", "Disconnect \(name)?"),
                          L("Комната получит новый ключ, а при поддержке сервера и новый токен. \(name) больше не увидит буфер, пока его не подключат заново.", "The room gets a new key, and a new token where the server allows it. \(name) will not see the clipboard until it is paired again."),
                          button: L("Отключить", "Disconnect")) { revokeDevice(id) }
        })
        return menu
    }

    private func alertsMenu() -> NSMenu {
        let menu = NSMenu()
        let recent = Alerts.shared.recent.prefix(8)
        if recent.isEmpty {
            menu.addItem(info(L("Пока ничего не пришло", "Nothing has arrived yet")))
        }
        for entry in recent {
            let project = Alerts.shared.name(of: entry.topic)
            let line = entry.title.isEmpty ? entry.message : entry.title
            let short = line.count > 46 ? String(line.prefix(46)) + "…" : line
            let item = ActionItem(short.isEmpty ? project : short, symbol: "bell") {
                if let link = entry.click.flatMap(URL.init(string:)) {
                    NSWorkspace.shared.open(link)
                } else {
                    pasteboard.clearContents()
                    pasteboard.setString([entry.title, entry.message].filter { !$0.isEmpty }.joined(separator: "\n"), forType: .string)
                    ownCount = pasteboard.changeCount
                    seenCount = ownCount
                    StatusMenu.shared.flash(L("Текст уведомления в буфере", "The notification text is in the clipboard"))
                }
            }
            item.attributedTitle = styled(item.title, "\(project) · \(clock(entry.time))")
            item.toolTip = entry.message
            menu.addItem(item)
        }
        menu.addItem(.separator())
        let channels = NSMenu()
        if Alerts.shared.channels.isEmpty {
            channels.addItem(info(L("Список каналов не загрузился", "The channel list did not load")))
        }
        for channel in Alerts.shared.channels {
            let on = Alerts.shared.isOn(channel.topic)
            let item = ActionItem(channel.name) { setAlertTopic(channel.topic, !on) }
            item.attributedTitle = styled(channel.name, channel.topic)
            item.state = on ? .on : .off
            channels.addItem(item)
        }
        channels.addItem(.separator())
        channels.addItem(ActionItem(L("Обновить список", "Refresh the List")) { Alerts.shared.refresh() })
        channels.addItem(info(L("Список общий с Tossling на телефоне", "The list is shared with Tossling on the phone")))
        menu.addItem(submenu(L("Каналы", "Channels"), symbol: "list.bullet", channels))
        return menu
    }

    private func pauseMenu() -> NSMenu {
        let menu = NSMenu()
        if isPaused {
            menu.addItem(ActionItem(L("Снова отправлять", "Send Again"), symbol: "play") { pause(until: 0) })
            menu.addItem(.separator())
        }
        let now = Date().timeIntervalSince1970
        menu.addItem(ActionItem(L("30 минут", "30 Minutes")) { pause(until: now + 30 * 60) })
        menu.addItem(ActionItem(L("1 час", "1 Hour")) { pause(until: now + 3600) })
        menu.addItem(ActionItem(L("3 часа", "3 Hours")) { pause(until: now + 3 * 3600) })
        let morning = Calendar.current.nextDate(after: Date(), matching: DateComponents(hour: 8), matchingPolicy: .nextTime)
        if let morning = morning {
            menu.addItem(ActionItem(L("До утра, \(clock(morning.timeIntervalSince1970))", "Until Morning, \(clock(morning.timeIntervalSince1970))")) { pause(until: morning.timeIntervalSince1970) })
        }
        menu.addItem(.separator())
        menu.addItem(info(L("На паузе этот Mac не отправляет, но принимает", "A paused Mac does not send, but receives")))
        return menu
    }

    private func settingsMenu() -> NSMenu {
        let menu = NSMenu()
        let flags: [(String, String, Bool)] = [
            (L("Отправлять сам после ⌘C", "Send by Itself after ⌘C"), "auto", conf.auto),
            (L("Передавать картинки", "Send Images"), "images", conf.images),
            (L("Снимки экрана — сразу на устройства", "Screenshots Go to the Devices at Once"), "screenshots", conf.screenshots),
            (L("\(hotkeyChoice.title) — отправить вручную", "\(hotkeyChoice.title) Sends by Hand"), "hotkey", conf.hotkey),
            (L("Уведомления", "Notifications"), "notifications", conf.notifications),
        ]
        for (title, key, value) in flags {
            let item = ActionItem(title) { setFlag(key, !value) }
            item.state = value ? .on : .off
            menu.addItem(item)
        }
        let keys = NSMenu()
        for choice in hotkeyChoices {
            let item = ActionItem(choice.title) { setHotkeyKeys(choice.keys) }
            item.state = choice.keys == hotkeyChoice.keys ? .on : .off
            keys.addItem(item)
        }
        keys.addItem(.separator())
        keys.addItem(info(L("⇧⌘C перехватит его в Chrome, Finder, Android Studio и Xcode", "⇧⌘C takes it over in Chrome, Finder, Android Studio and Xcode")))
        menu.addItem(.separator())
        menu.addItem(submenu(L("Сочетание: \(hotkeyChoice.title)", "Shortcut: \(hotkeyChoice.title)"), symbol: "keyboard", keys))
        return menu
    }

    private var hud: NSPanel?

    func flash(_ text: String) {
        hud?.close()
        guard let button = item?.button, let window = button.window else { return }
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.sizeToFit()
        let size = NSSize(width: label.frame.width + 28, height: 30)
        let effect = NSVisualEffectView(frame: NSRect(origin: .zero, size: size))
        effect.material = .hudWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 15
        label.frame.origin = NSPoint(x: 14, y: (size.height - label.frame.height) / 2)
        effect.addSubview(label)
        let anchor = window.convertToScreen(button.convert(button.bounds, to: nil))
        let panel = NSPanel(contentRect: NSRect(x: anchor.midX - size.width / 2, y: anchor.minY - size.height - 6,
                                                width: size.width, height: size.height),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.level = .statusBar
        panel.hasShadow = true
        panel.contentView = effect
        panel.orderFrontRegardless()
        hud = panel
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { [weak self, weak panel] in
            NSAnimationContext.runAnimationGroup({ $0.duration = 0.25; panel?.animator().alphaValue = 0 }) {
                panel?.close()
                if self?.hud === panel { self?.hud = nil }
            }
        }
    }

    private func missing(_ entry: HistoryItem) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = entry.kind == "image" ? L("Картинки уже нет", "The image is gone") : L("Файла «\(entry.name ?? "файл")» уже нет на этом Mac", "The file «\(entry.name ?? "file")» is no longer on this Mac")
        alert.informativeText = entry.kind == "image" ? L("Копия картинки удалена вместе с историей.", "The copy of the image was deleted with the history.") : L("Его удалили или переместили: \(entry.file ?? "")", "It was deleted or moved: \(entry.file ?? "")")
        alert.addButton(withTitle: L("Убрать из истории", "Remove from History"))
        alert.addButton(withTitle: L("ОК", "OK"))
        if alert.runModal() == .alertFirstButtonReturn { History.shared.remove(entry) }
    }

    func openMenu() {
        item?.button?.performClick(nil)
    }

    func pickTargets(_ done: @escaping ([String]?) -> Void) {
        let others = members.filter { $0.key != conf.deviceID }.sorted {
            ($0.value["seen"] as? Double ?? 0) > ($1.value["seen"] as? Double ?? 0)
        }
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = L("Кому отправить?", "Send to whom?")
        let popup = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 260, height: 26), pullsDown: false)
        popup.addItem(withTitle: L("Всем устройствам комнаты", "Every device in the room"))
        for (id, value) in others {
            popup.addItem(withTitle: conf.aliases[id] ?? value["name"] as? String ?? id)
        }
        alert.accessoryView = popup
        alert.addButton(withTitle: L("Отправить", "Send"))
        alert.addButton(withTitle: L("Отмена", "Cancel"))
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let index = popup.indexOfSelectedItem
        done(index == 0 ? nil : [others[index - 1].key])
    }

    private func pickFiles() {
        NSApp.activate(ignoringOtherApps: true)
        let open = NSOpenPanel()
        open.allowsMultipleSelection = true
        open.canChooseDirectories = true
        open.message = L("Файлы уйдут на все устройства комнаты, папки — zip-архивом", "Files go to every device in the room, folders as zip archives")
        open.prompt = L("Отправить", "Send")
        guard open.runModal() == .OK, !open.urls.isEmpty else { return }
        sendQueue(open.urls.map { url in { next in sendItem(url, extra: [:], announce: true, done: { _ in next() }) } })
    }

    private func turnOff() {
        confirm(L("Выключить Tossling на этом Mac?", "Turn off Tossling on this Mac?"), L("Буфер перестанет синхронизироваться, пока не выполнишь tossling on.", "The clipboard stops syncing until you run tossling on."), button: L("Выключить", "Turn Off")) {
            log(L("выключен из строки меню", "turned off from the menu bar"))
            let plist = serviceLabel + ".plist"
            try? SMAppService.agent(plistName: plist).unregister()
            exit(0)
        }
    }

    private func ask(_ title: String, value: String, note: String = "", button: String = L("Сохранить", "Save"), _ done: @escaping (String) -> Void) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = note
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24))
        field.stringValue = value
        alert.accessoryView = field
        alert.addButton(withTitle: button)
        alert.addButton(withTitle: L("Отмена", "Cancel"))
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        done(field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private func confirm(_ title: String, _ text: String, button: String, _ done: @escaping () -> Void) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        alert.alertStyle = .warning
        alert.addButton(withTitle: button)
        alert.addButton(withTitle: L("Отмена", "Cancel"))
        if alert.runModal() == .alertFirstButtonReturn { done() }
    }

    private func showPanel(title: String, image: NSImage?, note: String, status: String) -> PanelModel {
        closePanel()
        let model = PanelModel(status: status)
        let panel = makePanelWindow(PanelView(title: title, image: image, note: note, model: model) { [weak self] in self?.closePanel() })
        panel.title = title
        panel.delegate = self
        panel.center()
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
        self.panel = panel
        panelModel = model
        return model
    }

    private func showDone(_ text: String) {
        panelModel?.done(text)
    }

    private func closePanel() {
        inviteProcess?.terminate()
        inviteProcess = nil
        waitsForPhone = false
        panelModel = nil
        panel?.delegate = nil
        panel?.close()
        panel = nil
    }

    func windowWillClose(_ notification: Notification) {
        inviteProcess?.terminate()
        inviteProcess = nil
        waitsForPhone = false
        panelModel = nil
        panel = nil
    }

    private func showQR() {
        guard let png = pairingQR(), let image = NSImage(data: png) else {
            notify(L("Не собрал QR-код", "Could not make the QR code"))
            return
        }
        _ = showPanel(title: L("Подключить телефон", "Pair a Phone"), image: image,
                      note: L("В Tossling на телефоне: «Добавить Mac» → наведи камеру на код. В коде ключ комнаты и токен, не показывай его посторонним.", "In Tossling on the phone: «Pair with a Mac» → point the camera at the code. The code holds the room key and the token, do not show it to others."),
                      status: L("Жду телефон…", "Waiting for the phone…"))
        waitsForPhone = true
    }

    private func showInvite() {
        let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        let raw = String((0..<8).map { _ in alphabet[Int.random(in: 0..<alphabet.count)] })
        let code = "\(raw.prefix(4))-\(raw.suffix(4))"
        let host = conf.server.components(separatedBy: "://").last ?? conf.server
        let command = "tossling join \(host)/\(code)"
        let model = showPanel(title: L("Пригласить Mac", "Invite a Mac"), image: nil,
                              note: L("Выполни эту команду на втором Mac. Код действует 10 минут, никому его не показывай.", "Run this command on the other Mac. The code works for 10 minutes; do not show it to anyone."),
                              status: L("Готовлю приглашение…", "Preparing the invite…"))
        var lines = 0
        inviteProcess = helperCommand(["--invite", code], output: { line in
            lines += 1
            if lines == 1 {
                guard line == "ok" else {
                    model.failed(L("Не отправил приглашение: \(line)", "Could not send the invite: \(line)"))
                    return
                }
                model.command = command
                model.status = L("Жду второй Mac…", "Waiting for the other Mac…")
            } else if line == "timeout" {
                model.failed(L("За 10 минут никто не подключился", "Nobody joined within 10 minutes"))
            } else {
                model.done(L("\(line) в комнате", "\(line) is in the room"))
            }
        })
    }

    private func showJoin() {
        ask(L("Войти в комнату другого Mac", "Join Another Mac's Room"), value: "",
            note: L("На Mac, который уже в комнате: Устройства → Пригласить ещё один Mac (или tossling invite). Вставь код вида tossling.example.com/ABCD-EFGH. Этот Mac перейдёт в ту комнату.",
                    "On a Mac that is already in the room: Devices → Invite Another Mac (or tossling invite). Paste the code like tossling.example.com/ABCD-EFGH. This Mac moves to that room."),
            button: L("Войти", "Join")) { [weak self] text in self?.join(text) }
    }

    private func join(_ text: String) {
        var target = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if target.hasPrefix("tossling join ") { target = String(target.dropFirst("tossling join ".count)).trimmingCharacters(in: .whitespaces) }
        guard let slash = target.lastIndex(of: "/"), target.index(after: slash) < target.endIndex else {
            notify(L("Нужен код вида tossling.example.com/ABCD-EFGH", "The code should look like tossling.example.com/ABCD-EFGH"))
            return
        }
        let address = normalizedServer(String(target[..<slash]))
        let code = String(target[target.index(after: slash)...])
        let out = NSTemporaryDirectory() + "tossling-join-\(getpid()).json"
        let model = showPanel(title: L("Войти в комнату", "Join a Room"), image: nil,
                              note: L("Второй Mac передаёт ключ комнаты, зашифрованный кодом.", "The other Mac sends the room key, encrypted with the code."),
                              status: L("Жду ответа от другого Mac…", "Waiting for the other Mac…"))
        var answer = ""
        inviteProcess = helperCommand(["--config", "/dev/null", "--join", address, code, "--out", out, "--id", conf.deviceID, "--name", conf.deviceName],
                                      output: { answer = $0 }) { status in
            defer { try? FileManager.default.removeItem(atPath: out) }
            guard status == 0, let data = FileManager.default.contents(atPath: out),
                  let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let server = raw["s"] as? String, let token = raw["t"] as? String, let room = raw["r"] as? String, let key = raw["k"] as? String else {
                model.failed(answer.isEmpty ? L("Не получилось войти в комнату", "Could not join the room") : answer)
                return
            }
            let saved = rewriteConfig { config in
                config["server"] = normalizedServer(server)
                config["token"] = token
                config["room"] = room
                config["key"] = key
                config["owner"] = raw["o"] as? String ?? ""
                config["device_id"] = conf.deviceID
                config["legacy_to_mac"] = ""
                config["legacy_to_phone"] = ""
            }
            guard saved else {
                model.failed(L("Не записал настройки в \(configFile)", "Could not save the settings to \(configFile)"))
                return
            }
            log(L("вошёл в комнату \(answer)", "joined the room of \(answer)"))
            model.done(L("Этот Mac в комнате с \(answer). Перезапускаюсь…", "This Mac is in the room with \(answer). Restarting…"))
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { restartAgent() }
        }
    }

    static func icon() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: true) { _ in
            NSColor.black.setFill()
            let dot = 1.3
            for (x, y) in [(5.0, 18.0), (6.47, 14.31), (8.6, 10.89), (11.8, 8.63), (15.72, 8.89)] {
                NSBezierPath(ovalIn: NSRect(x: x - 3 - dot, y: y - 3 - dot, width: 2 * dot, height: 2 * dot)).fill()
            }
            NSBezierPath(ovalIn: NSRect(x: 17.5 - 3 - 3.2, y: 7.5 - 3 - 3.2, width: 6.4, height: 6.4)).fill()
            return true
        }
        image.isTemplate = true
        return image
    }
}
