import SwiftUI
import UserNotifications
import WireKit

// The user's answers per watch app (docs/miniapp-rpc.md, "Permissions"): grants keyed by package name, pending asks,
// "Show text on the watch". A local notification asks, at most once a day per package and cap.
// ponytail: UserDefaults, a handful of packages; move to a file if this grows past that.

@MainActor @Observable final class Permissions {
    static let shared = Permissions()
    private static let key = "rpc-permissions"

    struct Store: Codable {
        /// package → cap → allowed
        var grants: [String: [String: Bool]] = [:]
        /// package → cap → last notified
        var asked: [String: [String: Date]] = [:]
        var showText: Set<String> = []
    }

    private(set) var store: Store
    /// The ask notification was tapped: the Watch tab opens the Permissions screen.
    var showScreen = false

    private init() {
        store = UserDefaults.standard.data(forKey: Self.key).flatMap { try? JSONDecoder().decode(Store.self, from: $0) }
            ?? Store()
    }

    private func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(store), forKey: Self.key)
    }

    var packages: [String] { Set(store.grants.keys).union(store.asked.keys).sorted() }

    struct Ask: Hashable { let package: String, cap: String }

    /// Asked and not answered.
    var pending: [Ask] {
        store.asked.flatMap { p, caps in caps.keys.filter { store.grants[p]?[$0] == nil }.map { Ask(package: p, cap: $0) } }
            .sorted { ($0.package, $0.cap) < ($1.package, $1.cap) }
    }

    func grants(_ package: String) -> [String: Bool] { store.grants[package] ?? [:] }

    func ask(_ package: String, _ cap: String, now: Date = .now) {
        if let last = store.asked[package]?[cap], now.timeIntervalSince(last) < 86_400 { return }
        store.asked[package, default: [:]][cap] = now
        save()
        let n = UNMutableNotificationContent()
        n.title = String(localized: "\(package) wants \(Self.label(cap))")
        n.body = String(localized: "Allow or deny it in FMC Companion.")
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "ask-\(package)-\(cap)", content: n,
                                                                    trigger: nil))
    }

    /// Allow also asks for the OS permission the cap needs.
    func set(_ package: String, _ cap: String, _ allowed: Bool) {
        store.grants[package, default: [:]][cap] = allowed
        save()
        if allowed { Task { await PhoneServices.shared.requestOS(cap) } }
    }

    /// Forgets the answer: the next call asks again.
    func revoke(_ package: String, _ cap: String) {
        store.grants[package]?[cap] = nil
        store.asked[package]?[cap] = nil
        save()
    }

    func showText(_ package: String) -> Bool { store.showText.contains(package) }

    func setShowText(_ package: String, _ on: Bool) {
        if on { store.showText.insert(package) } else { store.showText.remove(package) }
        save()
    }

    static func label(_ cap: String) -> String {
        switch cap {
        case "calendar": String(localized: "your calendar")
        case "location": String(localized: "your approximate location")
        case "weather": String(localized: "the weather where you are")
        case "notifications": String(localized: "your notifications")
        default: cap.hasPrefix("http:") ? String(localized: "the web: \(String(cap.dropFirst(5)))") : cap
        }
    }
}

struct PermissionsView: View {
    private var perms = Permissions.shared

    var body: some View {
        List {
            let pending = perms.pending
            if !pending.isEmpty {
                Section("Asking now") {
                    ForEach(pending, id: \.self) { a in
                        let (p, cap) = (a.package, a.cap)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("\(p) wants \(Permissions.label(cap))").font(.body.weight(.semibold))
                            HStack {
                                Button("Allow") { perms.set(p, cap, true) }.buttonStyle(.borderedProminent)
                                Button("Deny", role: .destructive) { perms.set(p, cap, false) }.buttonStyle(.bordered)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            ForEach(perms.packages, id: \.self) { p in
                let grants = perms.grants(p).sorted { $0.key < $1.key }
                Section {
                    ForEach(grants, id: \.key) { cap, allowed in
                        VStack(alignment: .leading, spacing: 2) {
                            Toggle(Permissions.label(cap).capitalizedFirst,
                                   isOn: Binding(get: { allowed }, set: { perms.set(p, cap, $0) }))
                            if allowed, !PhoneServices.shared.osAllowed(cap) {
                                Link("Off in iOS Settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                                    .font(.footnote)
                            }
                        }
                        .swipeActions { Button("Revoke", role: .destructive) { perms.revoke(p, cap) } }
                    }
                    Toggle("Show text on the watch",
                           isOn: Binding(get: { perms.showText(p) }, set: { perms.setShowText(p, $0) }))
                } header: {
                    Text(p)
                } footer: {
                    Text("Calendar titles and notification text. Anyone nearby with a Bluetooth scanner could read what goes to the watch, location included (rounded to about 10 km). Swipe a permission to revoke it.")
                }
            }
            if perms.packages.isEmpty {
                Text("Watch apps that ask for your calendar, location, weather or the web show up here.")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Permissions")
    }
}

private extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
