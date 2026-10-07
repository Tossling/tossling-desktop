import AppKit

#if canImport(Sparkle)
import Sparkle

final class Updates: NSObject, SPUUpdaterDelegate, SPUStandardUserDriverDelegate {
    static let shared = Updates()
    private var controller: SPUStandardUpdaterController?
    private(set) var waiting: String?

    func start() {
        guard isDistributed, Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") != nil else { return }
        controller = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: self, userDriverDelegate: self)
    }

    var available: Bool { controller != nil }

    func check() {
        NSApp.activate(ignoringOtherApps: true)
        controller?.checkForUpdates(nil)
    }

    func feedURLString(for updater: SPUUpdater) -> String? {
        UserDefaults.standard.string(forKey: "TosslingFeedURL")
    }

    var supportsGentleScheduledUpdateReminders: Bool { true }

    func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool {
        immediateFocus
    }

    func standardUserDriverWillHandleShowingUpdate(_ handleShowingUpdate: Bool, forUpdate update: SUAppcastItem, state: SPUUserUpdateState) {
        guard !handleShowingUpdate, !state.userInitiated else { return }
        waiting = update.displayVersionString
        notify(L("Доступна версия \(update.displayVersionString): установить можно из меню Tossling", "Version \(update.displayVersionString) is available: install it from the Tossling menu"), id: "update")
    }

    func standardUserDriverDidReceiveUserAttention(forUpdate update: SUAppcastItem) {
        waiting = nil
    }

    func standardUserDriverWillFinishUpdateSession() {
        waiting = nil
    }
}
#else
final class Updates {
    static let shared = Updates()
    let waiting: String? = nil
    var available: Bool { false }
    func start() {}
    func check() {}
}
#endif
