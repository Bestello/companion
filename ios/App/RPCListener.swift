import UIKit
import UserNotifications
import WireKit

// Answers the running watch app's service requests (docs/miniapp-rpc.md) for as long as the app lives, foreground
// or woken by BLE in the background. Its own BLELink and K session, next to WatchModel.check's: every K host sees
// every K reply, so INFO and MSG from both go one at a time through KLock. A watchface install stops it (the stock
// session binds the watch) and starts it again after.
// ponytail: retries every 30 s while no watch is up (10 min on stock firmware); no CoreBluetooth state restoration,
// so a kill by the system ends it until the next launch.

/// One K exchange at a time across sessions: a reply carries no request id.
actor KLock {
    static let shared = KLock()
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        if busy { await withCheckedContinuation { waiters.append($0) } } else { busy = true }
    }

    func unlock() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }

    func run<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        await lock()
        defer { unlock() }
        return try await body()
    }
}

@MainActor final class RPCListener {
    static let shared = RPCListener()
    private var task: Task<Void, Never>?
    private var link: BLELink?
    /// slot → package name, from INFO
    private var apps: [Int: String] = [:]

    func start() {
        guard task == nil else { return }
        task = Task {
            while !Task.isCancelled {
                let stock = await listen()
                try? await Task.sleep(for: stock ? .seconds(600) : .seconds(30))
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        link?.disconnect()
        link = nil
    }

    /// What WatchModel.check read: the apps by slot, and a loader on a watch that had none.
    func update(_ state: LoaderState?) {
        guard let state else { return }
        apps = state.apps.mapValues(\.name)
        if task == nil { start() }
    }

    /// true: stock firmware, nothing to listen to.
    private func listen() async -> Bool {
        let link = BLELink()
        self.link = link
        var up = true
        link.onDisconnect = { up = false }
        defer {
            link.disconnect()
            if self.link === link { self.link = nil }
        }
        do {
            try await link.connect()
            let (session, state) = try await KLock.shared.run { try await WatchModel.open(link) }
            guard let state else {
                DevLog.shared.log("stock firmware: not listening")
                return true
            }
            apps = state.apps.mapValues(\.name)
            _ = try await KLock.shared.run { try await session.ping() } // registers: the stub sends us EVENTs
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            DevLog.shared.log("listening")
            while up, !Task.isCancelled {
                await runDueJobs(session)
                guard let m = try? await session.recvFrom(timeout: .seconds(5)) else { continue }
                await handle(m.data, slot: Int(m.slot), session)
            }
            DevLog.shared.log("link dropped")
        } catch {
            DevLog.shared.log("link: \(error.localizedDescription)")
        }
        return false
    }

    /// The watch apps' background jobs that are due, while the link is up: in the foreground and in the ~10 s a BLE
    /// wake gives. ponytail: no BGAppRefreshTask; it wakes without the link, and delivering needs it.
    private func runDueJobs(_ session: KSession) async {
        let now = Date.now
        for (package, jobs) in Jobs.shared.all {
            for j in jobs where j.due(now) {
                Jobs.shared.ran(package, j.id, at: now) // first: a failing job waits its interval
                do {
                    let records = try await withDeadline(.seconds(8)) { @MainActor in
                        try await runJob(j, package: package, host: PhoneServices.shared)
                    }
                    await deliver(package, j, records, session)
                } catch {
                    DevLog.shared.log("\(package) job \(j.id): \(error)")
                }
            }
        }
    }

    /// Writes a job's records to the app's widget or page data on the watch while the app doesn't run.
    /// TODO(widget-push): over K as cmf-watch-sdk's widget-push doc says (branch app-order, not written yet); until
    /// then the records are fetched and dropped.
    private func deliver(_ package: String, _ job: RefreshJob, _ records: [Data], _ session: KSession) async {
        DevLog.shared.log("\(package) job \(job.id): \(records.count) records, not delivered (the watch side isn't there yet)")
    }

    private func handle(_ message: Data, slot: Int, _ session: KSession) async {
        guard message.starts(with: rpcPrefix) else { return }
        // the ~10 s a BLE wake gives, kept until the reply is out
        let bg = UIApplication.shared.beginBackgroundTask(withName: "rpc")
        defer { UIApplication.shared.endBackgroundTask(bg) }
        if apps[slot] == nil, let state = try? await KLock.shared.run({ try await readLoader(session) }) {
            apps = state.apps.mapValues(\.name)
        }
        // empty: a part of http.request before its last
        guard let out = await answer(message, package: apps[slot], host: PhoneServices.shared), !out.isEmpty else { return }
        // "R1Q id method …" in, "R1A id status index total" out
        let method = message.count > 4 ? DevLog.methods[message[message.startIndex + 4]] ?? "?" : "?"
        let status = out.first.flatMap { $0.count > 4 ? Int($0[$0.startIndex + 4]) : nil }
            .map { $0 < DevLog.statuses.count ? DevLog.statuses[$0] : "\($0)" } ?? "?"
        do {
            try await KLock.shared.run {
                for m in out { try await session.send(m) }
            }
            DevLog.shared.log("\(apps[slot] ?? "slot \(slot)") \(method) → \(status), \(out.count) msg")
        } catch {
            DevLog.shared.log("\(apps[slot] ?? "slot \(slot)") \(method) → \(status), send failed: \(error)")
        }
    }
}

/// Starts the listener at launch (a background launch has no scene) and opens the asking app's page from the ask notification.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ app: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        RPCListener.shared.start()
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil,
                                               queue: .main) { _ in
            Task { @MainActor in await PhoneServices.shared.refresh(within: .seconds(10)) }
        }
        return true
    }

    func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse) async {
        let package = r.notification.request.content.userInfo["package"] as? String
        await MainActor.run { Permissions.shared.open = package }
    }

    func userNotificationCenter(_ c: UNUserNotificationCenter,
                                willPresent n: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound] // a watch app's notify with its sound, also while the companion is open
    }
}
