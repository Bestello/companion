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
    var demo = UserDefaults.standard.bool(forKey: "demo") {
        didSet {
            UserDefaults.standard.set(demo, forKey: "demo")
            phase = .idle
        }
    }

    var loader: LoaderState? {
        if case .checked(let s) = phase { s } else { nil }
    }

    func check() async {
        phase = .checking
        do {
            phase = .checked(demo ? try await Self.demoState() : try await read())
        } catch {
            phase = .failed(error.localizedDescription)
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
        let account = "k-\(link.watchID?.uuidString ?? "")"
        let cached = Keychain.get(account)
        let (session, secret) = try await KSession.open(link: link, secretHex: cached, register: false)
        Keychain.set(account, secret)
        if let state = try await readLoader(session) { return state }
        // K changes with a factory reset: a cached one that gets no answer is read again once
        guard cached != nil else { return nil }
        let (fresh, newSecret) = try await KSession.open(link: link, register: false)
        Keychain.set(account, newSecret)
        return try await readLoader(fresh)
    }

    /// What the App Store review sees without a watch.
    static func demoState() async throws -> LoaderState {
        try await Task.sleep(for: .seconds(1))
        let loader = PackageHeader(kind: 1, name: "loader", version: 20_261_115)
        return LoaderState(stub: Stub(version: 5, abiMinor: 3), loader: loader, apps: [
            0: PackageHeader(kind: 0, name: "Orbit", version: 1),
            1: PackageHeader(kind: 0, name: "Vibration Lab", version: 2),
        ])
    }
}
