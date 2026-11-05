import AppKit

/// A downloaded update installs and restarts the agent only when that cuts nothing short: no file is on its way, the
/// menu is closed and no Tossling window is open.
func updateCanInstall(transfers: Int, menuOpen: Bool, windowOpen: Bool) -> Bool {
    transfers == 0 && !menuOpen && !windowOpen
}

#if canImport(Sparkle)
import Sparkle

final class Updates: NSObject, SPUUpdaterDelegate, SPUStandardUserDriverDelegate {
    static let shared = Updates()
    private var controller: SPUStandardUpdaterController?
    private(set) var waiting: String?
    /// Sparkle's handler that installs the downloaded update and relaunches; a bare launch starts the agent again.
    private var install: (() -> Void)?
    private var installTimer: Timer?

    func start() {
        guard isDistributed, Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") != nil else { return }
        controller = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: self, userDriverDelegate: self)
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
            guard let updater = self?.controller?.updater, updater.automaticallyChecksForUpdates, !updater.sessionInProgress else { return }
            updater.checkForUpdatesInBackground()
        }
    }

    var available: Bool { controller != nil }

    func check() {
        NSApp.activate(ignoringOtherApps: true)
        controller?.checkForUpdates(nil)
    }

    func feedURLString(for updater: SPUUpdater) -> String? {
        UserDefaults.standard.string(forKey: "TosslingFeedURL")
    }

    /// The agent runs for weeks and Sparkle installs a downloaded update only when the app quits, so without this a Mac
    /// stayed on the old version until the next restart.
    func updater(_ updater: SPUUpdater, willInstallUpdateOnQuit item: SUAppcastItem, immediateInstallationBlock immediateInstallHandler: @escaping () -> Void) -> Bool {
        install = immediateInstallHandler
        log(L("скачал версию \(item.displayVersionString): поставлю, когда не будет передач", "downloaded version \(item.displayVersionString): installing once no transfer runs"))
        if installTimer == nil {
            let timer = Timer(timeInterval: 60, repeats: true) { [weak self] _ in self?.installIfIdle() }
            RunLoop.main.add(timer, forMode: .common)
            installTimer = timer
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in self?.installIfIdle() }
        return true
    }

    private func installIfIdle() {
        guard let install = install else { return }
        let windowOpen = NSApp.windows.contains { $0.isVisible && $0.className != "NSStatusBarWindow" }
        guard updateCanInstall(transfers: Transfers.shared.items.count, menuOpen: StatusMenu.shared.menuOpen, windowOpen: windowOpen) else { return }
        log(L("ставлю обновление и перезапускаюсь", "installing the update and restarting"))
        install()
    }

    var supportsGentleScheduledUpdateReminders: Bool { true }

    func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool {
        false
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
