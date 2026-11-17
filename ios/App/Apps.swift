import SwiftUI
import WireKit

// The apps on the watch and what each may use on the phone, laid out like iOS Settings: a list of apps, and per app
// "Allow <app> to access" with a switch per permission (docs/miniapp-rpc.md, "Permissions").

/// An app on the watch, or one that has answers on the phone but is not on the watch now.
struct WatchApp: Identifiable {
    var id: String { name }
    let name: String
    /// nil: not on the watch (or the watch was not read)
    let header: PackageHeader?
    let slot: Int?

    /// The store's name for it, else the package name.
    @MainActor var title: String { StoreApps.shared.listing(name)?.name ?? name }

    var detail: String {
        if let header, let slot { String(localized: "Version \(header.version) · slot \(slot)") }
        else { String(localized: "Not on the watch") }
    }

    @MainActor static func all(_ loader: LoaderState?, _ perms: Permissions) -> [WatchApp] {
        let installed = (loader?.apps ?? [:]).sorted { $0.key < $1.key }
            .map { WatchApp(name: $0.value.name, header: $0.value, slot: $0.key) }
        let names = Set(installed.map(\.name))
        return installed + perms.packages.filter { !names.contains($0) }.map { WatchApp(name: $0, header: nil, slot: nil) }
    }
}

/// The coloured rounded square of an iOS Settings row.
struct SettingsIcon: View {
    let symbol: String
    let color: Color
    var side: CGFloat = 29

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: side * 0.55, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: side, height: side)
            .background(color.gradient, in: .rect(cornerRadius: side * 0.225, style: .continuous))
    }
}

/// The red count before a row's chevron, as on Settings' rows.
struct CountBadge: View {
    let count: Int

    var body: some View {
        if count > 0 {
            Text("\(count)")
                .font(.subheadline.weight(.semibold)).monospacedDigit()
                .foregroundStyle(.white)
                .padding(.horizontal, 7)
                .frame(minWidth: 22, minHeight: 22)
                .background(.red, in: .capsule)
        }
    }
}

/// The FMC store's listing of watch apps (fmc.tools `apps`): their names and icons, matched to a package by slug or
/// name. Loaded once per launch.
@MainActor @Observable final class StoreApps {
    static let shared = StoreApps()
    struct Listing { let name: String; let icon: URL? }
    private(set) var byKey: [String: Listing] = [:]
    private var loaded = false

    func listing(_ package: String) -> Listing? { byKey[package.lowercased()] }

    func load() async {
        guard !loaded else { return }
        struct Page: Decodable { var items: [Record] }
        struct Record: Decodable { var id, collectionId, slug, name, icon: String }
        var url = Account.base.appending(path: "api/collections/apps/records")
        url.append(queryItems: [.init(name: "filter", value: "published = true"), .init(name: "perPage", value: "200"),
                                .init(name: "fields", value: "id,collectionId,slug,name,icon")])
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let page = try? JSONDecoder().decode(Page.self, from: data) else { return }
        loaded = true
        for r in page.items {
            // ponytail: SVG icons have no image decoder here, those apps keep the letter tile
            let icon = r.icon.isEmpty || r.icon.hasSuffix(".svg") ? nil
                : Account.base.appending(path: "api/files/\(r.collectionId)/\(r.id)/\(r.icon)")
            let l = Listing(name: r.name, icon: icon)
            byKey[r.slug.lowercased()] = l
            byKey[r.name.lowercased()] = l
        }
    }
}

/// A watch app's tile: its store icon, or its first letter on the accent, as a home-screen icon.
struct AppIcon: View {
    let name: String
    var side: CGFloat = 29

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: side * 0.225, style: .continuous)
        Group {
            if let url = StoreApps.shared.listing(name)?.icon {
                AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { letter }
            } else {
                letter
            }
        }
        .frame(width: side, height: side)
        .clipShape(shape)
        .overlay(shape.strokeBorder(.white.opacity(0.08), lineWidth: 0.5))
    }

    private var letter: some View {
        Text(name.prefix(1).uppercased())
            .font(.system(size: side * 0.5, weight: .bold, design: .rounded))
            .foregroundStyle(Color.fmcOnAccent)
            .frame(width: side, height: side)
            .background(Color.fmcAccent.gradient)
    }
}

struct AppsView: View {
    @Environment(WatchModel.self) private var watch
    private var perms = Permissions.shared

    var body: some View {
        let apps = WatchApp.all(watch.loader, perms)
        List {
            let on = apps.filter { $0.slot != nil }, off = apps.filter { $0.slot == nil }
            if !on.isEmpty {
                Section("On the watch") { ForEach(on) { row($0) } }
            }
            if !off.isEmpty {
                Section {
                    ForEach(off) { row($0) }
                } header: {
                    Text(watch.loader == nil ? "Asked for permissions" : "Not on the watch")
                } footer: {
                    if watch.loader == nil { Text("Connect on the Watch tab to see every app on the watch.") }
                }
            }
        }
        .overlay {
            if apps.isEmpty {
                ContentUnavailableView("No apps", systemImage: "square.grid.2x2",
                                       description: Text("Connect on the Watch tab to see the apps on the watch."))
            }
        }
        .navigationTitle("Apps")
        .task { await StoreApps.shared.load() }
    }

    private func row(_ app: WatchApp) -> some View {
        NavigationLink(value: app.name) {
            HStack(spacing: 12) {
                AppIcon(name: app.name)
                VStack(alignment: .leading, spacing: 1) {
                    Text(app.title)
                    Text(app.detail).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                CountBadge(count: perms.pendingCount(app.name))
            }
        }
    }
}

struct AppView: View {
    @Environment(WatchModel.self) private var watch
    let name: String
    private var perms = Permissions.shared
    private let services = PhoneServices.shared
    @State private var resetting = false

    init(name: String) { self.name = name }

    var body: some View {
        let app = WatchApp.all(watch.loader, perms).first { $0.name == name } ?? WatchApp(name: name, header: nil, slot: nil)
        let caps = perms.caps(name)
        List {
            Section {
                VStack(spacing: 8) {
                    AppIcon(name: name, side: 60)
                    Text(app.title).font(.title3.weight(.semibold))
                    Text(app.detail).font(.subheadline).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            if caps.isEmpty {
                Section {
                    Text("\(app.title) hasn't asked for anything on your phone.").foregroundStyle(.secondary)
                }
            } else {
                Section {
                    ForEach(caps, id: \.self) { cap in
                        Toggle(isOn: Binding(get: { perms.grants(name)[cap] == true },
                                             set: { perms.set(name, cap, $0) })) {
                            HStack(spacing: 12) {
                                SettingsIcon(symbol: Self.symbol(cap), color: Self.color(cap))
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(Self.title(cap))
                                    if let note = note(cap) {
                                        Text(note).font(.footnote).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                } header: {
                    Text("Allow \(app.title) to access")
                } footer: {
                    if caps.contains(where: { perms.grants(name)[$0] == true && !services.osAllowed($0) }) {
                        Text("Some of these are off for FMC Companion in iOS Settings. [Open Settings](app-settings:)")
                    }
                }
                Section {
                    Toggle(isOn: Binding(get: { perms.showText(name) }, set: { perms.setShowText(name, $0) })) {
                        HStack(spacing: 12) {
                            SettingsIcon(symbol: "text.bubble.fill", color: .gray)
                            Text("Show text on the watch")
                        }
                    }
                } footer: {
                    Text("Calendar titles and notification text. Anyone nearby with a Bluetooth scanner could read what goes to the watch, location included (rounded to about 10 km).")
                }
                Section {
                    Button("Reset permissions", role: .destructive) { resetting = true }
                } footer: {
                    Text("\(app.title) asks again the next time it needs something.")
                }
            }
        }
        .navigationTitle(app.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await StoreApps.shared.load() }
        .confirmationDialog("Reset permissions for \(name)?", isPresented: $resetting, titleVisibility: .visible) {
            Button("Reset", role: .destructive) { perms.reset(name) }
        }
    }

    private func note(_ cap: String) -> String? {
        if perms.isPending(name, cap) { return String(localized: "Waiting for your answer") }
        if cap == "notifications", !services.notificationsAvailable { return String(localized: "Not available on iPhone") }
        if perms.grants(name)[cap] == true, !services.osAllowed(cap) { return String(localized: "Off in iOS Settings") }
        return nil
    }

    static func title(_ cap: String) -> String {
        switch cap {
        case "calendar": String(localized: "Calendars")
        case "location": String(localized: "Location")
        case "weather": String(localized: "Weather")
        case "notifications": String(localized: "Notifications")
        default: cap.hasPrefix("http:") ? String(cap.dropFirst(5)) : cap
        }
    }

    static func symbol(_ cap: String) -> String {
        switch cap {
        case "calendar": "calendar"
        case "location": "location.fill"
        case "weather": "cloud.sun.fill"
        case "notifications": "bell.badge.fill"
        default: "globe"
        }
    }

    static func color(_ cap: String) -> Color {
        switch cap {
        case "calendar", "notifications": .red
        case "location": .blue
        case "weather": .cyan
        default: .gray
        }
    }
}
