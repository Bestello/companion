import Foundation
import Observation
import WireKit

// Is the App Loader on the watch? Read only: K from the shell, then INFO (fmc_frontend ble.ts readWp2).
// Features that need the loader show only when `loader` is set.

@MainActor @Observable final class WatchModel {
    enum Phase: Equatable {
        case idle, checking
        /// Connected; nil = stock firmware (or a stub from before 2026-11-13, which has no K).
        case checked(LoaderState?)
        case failed(String)
    }

    var phase = Phase.idle

    var loader: LoaderState? {
        if case .checked(let s) = phase { s } else { nil }
    }

    /// The last read while a new one runs: lists keep their rows through a refresh.
    private(set) var lastLoader: LoaderState?
    var listedLoader: LoaderState? {
        if case .checking = phase { lastLoader } else { loader }
    }
    var isChecking: Bool {
        switch phase { case .idle, .checking: true; default: false }
    }

    func check() async {
        phase = .checking
        do {
            let state = try await read()
            phase = .checked(state)
            Analytics.track("watch_connected", ["firmware": state == nil ? "stock" : "patched"])
            lastLoader = state
            RPCListener.shared.update(state)
        } catch {
            phase = .failed(error.localizedDescription)
            lastLoader = nil
        }
    }

    private func read() async throws -> LoaderState? {
        let link = BLELink()
        defer { link.disconnect() }
        let watchdog = Task {
            try await Task.sleep(for: .seconds(30))
            link.abort()
        }
        defer { watchdog.cancel() }
        try await link.connect()
        AppOrder.shared.watch = link.watchID?.uuidString
        return try await KLock.shared.run { try await Self.open(link) }.1
    }

    /// A K session on `link` and the loader it reads: the cached K first, read again once when it gets no answer
    /// (K changes with a factory reset). Hold KLock: the RPC listener talks K on its own link.
    static func open(_ link: BLELink) async throws -> (KSession, LoaderState?) {
        let account = "k-\(link.watchID?.uuidString ?? "")"
        let cached = Keychain.get(account)
        let (session, secret) = try await KSession.open(link: link, secretHex: cached, register: false)
        Keychain.set(account, secret)
        if let state = try await readLoader(session) { return (session, state) }
        guard cached != nil else { return (session, nil) }
        let (fresh, newSecret) = try await KSession.open(link: link, register: false)
        Keychain.set(account, newSecret)
        return (fresh, try await readLoader(fresh))
    }
}
