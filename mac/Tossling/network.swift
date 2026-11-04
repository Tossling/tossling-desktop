import Foundation
import Network

/// What a network change is judged by: whether the path is usable, its interfaces and its gateways.
struct PathShape: Equatable {
    var satisfied: Bool
    var interfaces: [String]
    var gateways: [String]
}

extension PathShape {
    init(_ path: NWPath) {
        satisfied = path.status == .satisfied
        interfaces = path.availableInterfaces.map { "\($0.name):\($0.type)" }
        gateways = path.gateways.map { "\($0)" }
    }
}

/// Follows a burst of path updates. The first path is where it starts; a burst is over when `settle` is called, and
/// the streams reconnect when anything moved during it and the network is usable at its end, so Wi-Fi dropping and
/// coming back reconnects too.
struct PathChanges {
    private var settled: PathShape?
    private var latest: PathShape?
    private var moved = false

    mutating func note(_ shape: PathShape) {
        if let settled = settled {
            if shape != settled { moved = true }
        } else {
            settled = shape
        }
        latest = shape
    }

    mutating func settle() -> Bool {
        guard let latest = latest else { return false }
        let reconnect = moved && latest.satisfied
        settled = latest
        moved = false
        return reconnect
    }
}

/// Reconnects at once when the network changes (another Wi-Fi, a cable, a VPN), a second after the updates calm down,
/// instead of waiting for the streams to notice the old connection is dead.
final class NetworkWatch {
    static let shared = NetworkWatch()
    static let settleDelay = 1.0

    private let monitor = NWPathMonitor()
    private var changes = PathChanges()
    private var pending: DispatchWorkItem?
    var onChange: (() -> Void)?

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            self.changes.note(PathShape(path))
            self.pending?.cancel()
            let work = DispatchWorkItem { [weak self] in
                guard let self = self, self.changes.settle() else { return }
                self.onChange?()
            }
            self.pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + NetworkWatch.settleDelay, execute: work)
        }
        monitor.start(queue: .main)
    }
}
