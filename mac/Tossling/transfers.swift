import AppKit

/// Progress of streamed file transfers. The agent keeps the model and draws it in the menu bar; a `--send` process
/// started by the CLI or the Finder extension reports through distributed notifications, and the agent asks it to
/// cancel the same way.
let transferNotice = Notification.Name("com.kopylovis.tossling.desktop.transfer")
let transferCancelNotice = Notification.Name("com.kopylovis.tossling.desktop.transfer-cancel")
let transferPostInterval = 0.25
let transferHeartbeat = 10.0
let transferStaleAfter = 30.0

enum TransferState: String {
    case active, done, failed, cancelled
}

struct TransferInfo: Equatable {
    var id: String
    var name: String
    var incoming: Bool
    var done: Int64
    var total: Int64
    var state: TransferState
    var pid: Int32
    /// When the agent last heard of it; not sent.
    var updated = 0.0

    init(id: String, name: String, incoming: Bool, done: Int64, total: Int64, state: TransferState, pid: Int32, updated: Double = 0) {
        self.id = id
        self.name = name
        self.incoming = incoming
        self.done = done
        self.total = total
        self.state = state
        self.pid = pid
        self.updated = updated
    }

    /// Only strings, numbers and booleans: distributed notifications carry property list values.
    var userInfo: [String: Any] {
        ["id": id, "name": name, "incoming": incoming, "done": NSNumber(value: done), "total": NSNumber(value: total),
         "state": state.rawValue, "pid": NSNumber(value: pid)]
    }

    init?(userInfo: [AnyHashable: Any]?) {
        guard let info = userInfo, let id = info["id"] as? String, !id.isEmpty, id.count <= 64,
              let name = info["name"] as? String, let incoming = info["incoming"] as? Bool,
              let done = (info["done"] as? NSNumber)?.int64Value, let total = (info["total"] as? NSNumber)?.int64Value,
              done >= 0, total >= 0, let state = (info["state"] as? String).flatMap(TransferState.init(rawValue:)),
              let pid = (info["pid"] as? NSNumber)?.int32Value, pid > 0 else { return nil }
        self.init(id: id, name: String(name.prefix(200)), incoming: incoming, done: done, total: total, state: state, pid: pid)
    }
}

/// The share of all active transfers that is done: the sum of done over the sum of total, nil when nothing has a size.
func transferFraction(_ items: [TransferInfo]) -> Double? {
    let total = items.reduce(Int64(0)) { $0 + $1.total }
    guard total > 0 else { return nil }
    let done = items.reduce(Int64(0)) { $0 + min($1.done, $1.total) }
    return min(1, max(0, Double(done) / Double(total)))
}

/// A progress update goes out at most every `interval` seconds; the first one and the final state always do.
func shouldPostTransfer(lastAt: Double?, now: Double, final: Bool, interval: Double = transferPostInterval) -> Bool {
    guard !final, let last = lastAt else { return true }
    return now - last >= interval
}

/// Transfers the agent forgets: silent for longer than `after` seconds or reported by a process that is gone.
func staleTransfers(_ items: [TransferInfo], now: Double, after: Double = transferStaleAfter, alive: (Int32) -> Bool) -> [String] {
    items.filter { now - $0.updated > after || !alive($0.pid) }.map(\.id)
}

func processAlive(_ pid: Int32) -> Bool {
    kill(pid, 0) == 0 || errno == EPERM
}

/// "Sending report.pdf: 40%, 12 of 30 MB"; the unit of the done part is left out when it is the total's.
func transferLine(_ t: TransferInfo, size: (Int64) -> String = sizeText) -> String {
    guard t.total > 0 else {
        return t.incoming ? L("Получаю \(t.name)", "Receiving \(t.name)") : L("Отправляю \(t.name)", "Sending \(t.name)")
    }
    let percent = Int(min(t.done, t.total) * 100 / t.total)
    var done = t.done <= 0 ? "0" : size(min(t.done, t.total))
    let total = size(t.total)
    let unit = { (text: String) in text.components(separatedBy: .whitespaces).last ?? "" }
    if unit(done) == unit(total), let cut = done.rangeOfCharacter(from: .whitespaces, options: .backwards) {
        done = String(done[..<cut.lowerBound])
    }
    return t.incoming ? L("Получаю \(t.name): \(percent)%, \(done) из \(total)", "Receiving \(t.name): \(percent)%, \(done) of \(total)")
                      : L("Отправляю \(t.name): \(percent)%, \(done) из \(total)", "Sending \(t.name): \(percent)%, \(done) of \(total)")
}

/// How the open menu follows the transfers: the shown lines that stay (with their new state), the ones to drop and
/// the transfers to add below them, in the model's order.
func transferMenuChanges(shown: [String], now items: [TransferInfo]) -> (keep: [TransferInfo], drop: [String], add: [TransferInfo]) {
    let current = Set(items.map(\.id)), seen = Set(shown)
    return (items.filter { seen.contains($0.id) }, shown.filter { !current.contains($0) }, items.filter { !seen.contains($0.id) })
}

/// One transfer as seen from the code that runs it. In the agent it updates `Transfers` directly, anywhere else it
/// posts distributed notifications. Call it on the main thread.
final class TransferReporter {
    let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    let name: String
    let incoming: Bool
    private(set) var total: Int64
    private(set) var done: Int64 = 0
    private(set) var isCancelled = false
    /// Set by the transfer: stops the work. The transfer then calls `cancelled()`.
    var onCancel: (() -> Void)?
    private var lastPost: Double?
    private var ended = false
    private var observer: NSObjectProtocol?
    private var heartbeat: Timer?
    private let local: Bool

    init(name: String, incoming: Bool, total: Int64) {
        self.name = name
        self.incoming = incoming
        self.total = max(0, total)
        local = isAgent
        if local {
            Transfers.shared.cancels[id] = { [weak self] in self?.cancel() }
        } else {
            observer = DistributedNotificationCenter.default().addObserver(forName: transferCancelNotice, object: id, queue: .main) { [weak self] _ in
                self?.cancel()
            }
        }
        heartbeat = Timer.scheduledTimer(withTimeInterval: transferHeartbeat, repeats: true) { [weak self] _ in self?.post(.active, force: true) }
        post(.active, force: true)
    }

    deinit {
        heartbeat?.invalidate()
        if let observer = observer { DistributedNotificationCenter.default().removeObserver(observer) }
    }

    func progress(_ done: Int64, total: Int64? = nil) {
        guard !ended else { return }
        if let total = total, total > 0 { self.total = total }
        self.done = max(0, self.total > 0 ? min(done, self.total) : done)
        post(.active, force: false)
    }

    func finish() { end(.done) }
    func failed() { end(.failed) }
    func cancelled() { end(.cancelled) }

    func cancel() {
        guard !ended, !isCancelled else { return }
        isCancelled = true
        onCancel?()
    }

    private func end(_ state: TransferState) {
        guard !ended else { return }
        if state == .done { done = total }
        post(state, force: true)
        ended = true
        onCancel = nil
        heartbeat?.invalidate()
        heartbeat = nil
        if let observer = observer { DistributedNotificationCenter.default().removeObserver(observer) }
        observer = nil
        if local { Transfers.shared.cancels[id] = nil }
    }

    private func post(_ state: TransferState, force: Bool) {
        guard !ended else { return }
        let now = Date().timeIntervalSince1970
        guard force || shouldPostTransfer(lastAt: lastPost, now: now, final: state != .active) else { return }
        lastPost = now
        let info = TransferInfo(id: id, name: name, incoming: incoming, done: done, total: total, state: state, pid: getpid())
        if local {
            Transfers.shared.apply(info)
        } else {
            DistributedNotificationCenter.default().postNotificationName(transferNotice, object: nil, userInfo: info.userInfo, deliverImmediately: true)
        }
    }
}

/// The agent's list of running transfers.
final class Transfers {
    static let shared = Transfers()
    private(set) var items = [TransferInfo]()
    var cancels = [String: () -> Void]()
    private var observer: NSObjectProtocol?
    private var sweeper: Timer?

    var fraction: Double? { transferFraction(items) }

    func start() {
        guard observer == nil else { return }
        observer = DistributedNotificationCenter.default().addObserver(forName: transferNotice, object: nil, queue: .main) { [weak self] note in
            guard let info = TransferInfo(userInfo: note.userInfo), info.pid != getpid() else { return }
            self?.apply(info)
        }
        let sweeper = Timer(timeInterval: 5, repeats: true) { [weak self] _ in self?.sweep() }
        // Also while the menu is open, so a transfer whose process died leaves the open menu.
        RunLoop.main.add(sweeper, forMode: .common)
        self.sweeper = sweeper
    }

    func apply(_ info: TransferInfo) {
        var info = info
        info.updated = Date().timeIntervalSince1970
        if info.state == .active {
            if let at = items.firstIndex(where: { $0.id == info.id }) { items[at] = info } else { items.append(info) }
        } else {
            items.removeAll { $0.id == info.id }
        }
        changed()
    }

    func cancel(_ id: String) {
        if let local = cancels[id] {
            local()
            return
        }
        DistributedNotificationCenter.default().postNotificationName(transferCancelNotice, object: id, userInfo: nil, deliverImmediately: true)
    }

    private func sweep() {
        let gone = Set(staleTransfers(items, now: Date().timeIntervalSince1970, alive: processAlive))
        guard !gone.isEmpty else { return }
        items.removeAll { gone.contains($0.id) }
        changed()
    }

    private func changed() {
        StatusMenu.shared.transfersChanged()
    }
}
