import AppKit
import UserNotifications

final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()
    private(set) var isReady = false

    func start() {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "file", actions: [
                UNNotificationAction(identifier: "open", title: L("Открыть", "Open")),
                UNNotificationAction(identifier: "reveal", title: L("Показать в Finder", "Show in Finder")),
            ], intentIdentifiers: []),
            UNNotificationCategory(identifier: "link", actions: [
                UNNotificationAction(identifier: "open", title: L("Открыть ссылку", "Open the Link")),
            ], intentIdentifiers: []),
        ])
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            DispatchQueue.main.async {
                self.isReady = granted
                if !granted {
                    log(L("уведомления Tossling не разрешены, показываю через запасное приложение уведомлений\(error.map { ": \($0.localizedDescription)" } ?? "")", "Tossling notifications are not allowed, using the fallback notification app\(error.map { ": \($0.localizedDescription)" } ?? "")"))
                }
            }
        }
    }

    func post(_ body: String, title: String, id: String, sound: Bool, file: URL?, link: URL?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if sound { content.sound = .default }
        if let file = file {
            content.categoryIdentifier = "file"
            content.userInfo = ["file": file.path]
        } else if let link = link {
            content.categoryIdentifier = "link"
            content.userInfo = ["link": link.absoluteString]
        }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil)) { error in
            guard let error = error else { return }
            DispatchQueue.main.async { log(L("не показал уведомление: \(error.localizedDescription)", "could not show a notification: \(error.localizedDescription)")) }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        DispatchQueue.main.async {
            if let path = info["file"] as? String {
                self.openFile(URL(fileURLWithPath: path), reveal: action == "reveal")
            } else if let link = (info["link"] as? String).flatMap(URL.init(string:)) {
                NSWorkspace.shared.open(link)
            } else if action == UNNotificationDefaultActionIdentifier {
                StatusMenu.shared.openMenu()
            }
        }
        completionHandler()
    }

    private func openFile(_ url: URL, reveal: Bool) {
        guard FileManager.default.fileExists(atPath: url.path) else {
            NSApp.activate(ignoringOtherApps: true)
            let alert = NSAlert()
            alert.messageText = L("Файла «\(url.lastPathComponent)» уже нет на этом Mac", "The file «\(url.lastPathComponent)» is no longer on this Mac")
            alert.informativeText = L("Его удалили или переместили: \(url.path)", "It was deleted or moved: \(url.path)")
            alert.runModal()
            return
        }
        if reveal {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } else {
            NSWorkspace.shared.open(url)
        }
    }
}
