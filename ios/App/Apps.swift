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

    @MainActor var detail: String {
        guard let header, let slot else { return String(localized: "Not on the watch") }
        if let update { return String(localized: "Version \(versionText(header.version)) → \(versionText(update.version)) · slot \(slot)") }
        return String(localized: "Version \(versionText(header.version)) · slot \(slot)")
    }

    /// The store's build when it's newer than the one on the watch.
    @MainActor var update: PackageHeader? {
        guard let header, let latest = StoreApps.shared.latest(name), latest.version > header.version else { return nil }
        return latest
    }

    @MainActor static func all(_ loader: LoaderState?, _ perms: Permissions) -> [WatchApp] {
        let installed = (loader?.apps ?? [:]).sorted { $0.key < $1.key }
            .map { WatchApp(name: $0.value.name, header: $0.value, slot: $0.key) }
        let names = Set(installed.map(\.name))
        return installed + perms.packages.filter { !names.contains($0) }.map { WatchApp(name: $0, header: nil, slot: nil) }
    }

    /// Every app in the FMC store, the ones on the watch with their version and slot, then the installed and asked
    /// ones the store doesn't list.
    @MainActor static func store(_ loader: LoaderState?, _ perms: Permissions) -> [WatchApp] {
        let installed = (loader?.apps ?? [:]).map { (slot: $0.key, header: $0.value) }
        let store = StoreApps.shared
        let listed = store.all.map { l in
            if let on = installed.first(where: { store.listing($0.header.name)?.slug == l.slug }) {
                WatchApp(name: on.header.name, header: on.header, slot: on.slot)
            } else {
                WatchApp(name: l.slug, header: nil, slot: nil)
            }
        }
        return listed + all(loader, perms).filter { store.listing($0.name) == nil }
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

/// The header's u32 version as the store's MAJOR.MINOR.PATCH (apps/scripts/store build_number), else the number.
func versionText(_ v: UInt32) -> String { v >= 1024 ? "\(v >> 20).\(v >> 10 & 1023).\(v & 1023)" : "\(v)" }

/// The FMC store's listing of watch apps (fmc.tools `apps`): their names and icons, matched to a package by slug or
/// name. Loaded once per launch.
@MainActor @Observable final class StoreApps {
    static let shared = StoreApps()
    struct Listing {
        let id, slug, name: String
        let icon, package: URL?
        var page: URL { Account.base.appending(path: "apps/\(id)") }
    }
    private(set) var byKey: [String: Listing] = [:]
    /// slug → the store package's header. Its u32 version, not the listing's text: SDK examples don't encode it.
    private(set) var headers: [String: PackageHeader] = [:]
    /// By name, for the All segment.
    private(set) var all: [Listing] = []
    private var loaded = false

    func listing(_ package: String) -> Listing? { byKey[package.lowercased()] }
    func latest(_ package: String) -> PackageHeader? { listing(package).flatMap { headers[$0.slug] } }

    func load() async {
        guard !loaded else { return }
        struct Page: Decodable { var items: [Record] }
        struct Record: Decodable { var id, collectionId, slug, name, icon: String; var package: String? }
        var url = Account.base.appending(path: "api/collections/apps/records")
        url.append(queryItems: [.init(name: "filter", value: "published = true"), .init(name: "perPage", value: "200"),
                                .init(name: "fields", value: "id,collectionId,slug,name,icon,package")])
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let page = try? JSONDecoder().decode(Page.self, from: data) else { return }
        loaded = true
        for r in page.items {
            // ponytail: SVG icons have no image decoder here, those apps keep the letter tile
            let files = Account.base.appending(path: "api/files/\(r.collectionId)/\(r.id)")
            let icon = r.icon.isEmpty || r.icon.hasSuffix(".svg") ? nil : files.appending(path: r.icon)
            let package = r.package.flatMap { $0.isEmpty ? nil : files.appending(path: $0) }
            let l = Listing(id: r.id, slug: r.slug, name: r.name, icon: icon, package: package)
            byKey[r.slug.lowercased()] = l
            byKey[r.name.lowercased()] = l
            all.append(l)
        }
        all.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        // ponytail: one 64-byte request per store app, a few dozen at most; a field with the header's u32 on the
        // store record would make this one request
        headers = await withTaskGroup(of: (String, PackageHeader)?.self) { group in
            for l in all {
                guard let url = l.package else { continue }
                group.addTask {
                    var req = URLRequest(url: url)
                    req.setValue("bytes=0-63", forHTTPHeaderField: "Range")
                    guard let (data, _) = try? await URLSession.shared.data(for: req), let h = parseHeader(data) else { return nil }
                    return (l.slug, h)
                }
            }
            var out: [String: PackageHeader] = [:]
            for await case let (slug, h)? in group { out[slug] = h }
            return out
        }
    }
}

/// A watch app's icon: round, as on the watch. Its store icon, or its first letter on grey.
struct AppIcon: View {
    let name: String
    var side: CGFloat = 29

    var body: some View {
        let shape = Circle()
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
            .foregroundStyle(.white)
            .frame(width: side, height: side)
            .background(Color(.systemGray2).gradient)
    }
}

/// The Apps tab: the list, and an app's page over it.
struct AppsTab: View {
    // DEBUG screenshots: `-app <name>` opens that app's page
    @State private var path: [String] = {
        let a = CommandLine.arguments
        guard let i = a.firstIndex(of: "-app"), i + 1 < a.count else { return [] }
        return [a[i + 1]]
    }()

    var body: some View {
        @Bindable var perms = Permissions.shared
        NavigationStack(path: $path) {
            AppsView()
                .navigationDestination(for: String.self) { AppView(name: $0) }
        }
        .alert("Couldn't update", isPresented: Binding { AppUpdates.shared.error != nil } set: { if !$0 { AppUpdates.shared.error = nil } }) {
            Button("OK") {}
        } message: { Text(AppUpdates.shared.error ?? "") }
        // the ask notification: that app's page
        .onChange(of: perms.open, initial: true) { _, name in
            guard let name else { return }
            path = [name]
            perms.open = nil
        }
    }
}

/// Store updates for the apps on the watch: the new package over K into the app's own slot.
@MainActor @Observable final class AppUpdates {
    static let shared = AppUpdates()
    /// name → 0…1 while installing
    private(set) var progress: [String: Double] = [:]
    var error: String?

    func update(_ name: String, _ watch: WatchModel) async {
        guard progress[name] == nil, let url = StoreApps.shared.listing(name)?.package else { return }
        progress[name] = 0
        defer { progress[name] = nil }
        do {
            let (pkg, _) = try await URLSession.shared.data(from: url)
            try await FirmwareModel.install(pkg) { p in Task { @MainActor in self.progress[name] = p } }
            DevLog.shared.log("\(name) updated")
            await watch.check()
        } catch InstallError.busy {
            error = String(localized: "\(name) or the Apps menu is open on the watch. Go back to the watch face, then try again.")
        } catch {
            self.error = String(localized: "Couldn't update \(name): \(error.localizedDescription)")
        }
    }
}

/// Update, or the progress while it installs.
struct UpdateButton: View {
    @Environment(WatchModel.self) private var watch
    let name: String

    var body: some View {
        if let p = AppUpdates.shared.progress[name] {
            ProgressView(value: p).frame(width: 60)
        } else {
            Button("Update") { Task { await AppUpdates.shared.update(name, watch) } }
                .buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
        }
    }
}

struct AppsView: View {
    @Environment(WatchModel.self) private var watch
    private var perms = Permissions.shared
    @State private var showAll = false

    var body: some View {
        // Installed is the watch's list: nothing until it is read, not the apps that only asked for something
        let apps = showAll ? WatchApp.store(watch.loader, perms) : watch.loader == nil ? [] : WatchApp.all(watch.loader, perms)
        List {
            Section {
                Picker("Show", selection: $showAll) {
                    Text("Installed").tag(false)
                    Text("All").tag(true)
                }
                .pickerStyle(.segmented)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            if showAll {
                Section { ForEach(apps) { row($0) } } footer: { Text("Apps in the FMC store.") }
            } else {
                installed(apps)
            }
        }
        .overlay {
            if apps.isEmpty {
                if showAll {
                    ContentUnavailableView("No apps", systemImage: "square.grid.2x2",
                                           description: Text("Couldn't load the FMC store."))
                } else {
                    ContentUnavailableView("No apps", systemImage: "square.grid.2x2",
                                           description: Text("Connect on the Watch tab to see the apps on the watch."))
                }
            }
        }
        .navigationTitle("Apps")
        .task { await StoreApps.shared.load() }
        // the list of installed apps is the watch's: read it here too, not only from Connect on the Watch tab
        .task { if watch.phase == .idle { await watch.check() } }
        .refreshable { await watch.check() }
    }

    @ViewBuilder private func installed(_ apps: [WatchApp]) -> some View {
        let on = apps.filter { $0.slot != nil }, off = apps.filter { $0.slot == nil }
        let updates = on.filter { $0.update != nil }, current = on.filter { $0.update == nil }
        if !updates.isEmpty {
            Section {
                ForEach(updates) { row($0) }
            } header: {
                Text("Updates")
            } footer: {
                Text("Updates install over Bluetooth. Keep the app closed on the watch meanwhile.")
            }
        }
        if !current.isEmpty {
            Section("On the watch") { ForEach(current) { row($0) } }
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
                if app.slot != nil, app.update != nil { UpdateButton(name: app.name).buttonStyle(.borderless) }
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
            if let new = app.update, let listing = StoreApps.shared.listing(name) {
                Section {
                    HStack {
                        Text("Version \(versionText(new.version)) is out").fontWeight(.semibold)
                        Spacer()
                        UpdateButton(name: name)
                    }
                    Link("Open on fmc.tools", destination: listing.page)
                }
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
                    Toggle(isOn: Binding(get: { perms.showText(name) }, set: { perms.setShowText(name, $0) })) {
                        HStack(spacing: 12) {
                            SettingsIcon(symbol: "text.bubble.fill", color: .gray)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("Show text on the watch")
                                Text("Calendar titles and notification text").font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Allow \(app.title) to access")
                } footer: {
                    if caps.contains(where: { perms.grants(name)[$0] == true && !services.osAllowed($0) }) {
                        Text("Some of these are off for FMC Companion in iOS Settings. [Open Settings](app-settings:)\n")
                    }
                    Text("Anyone nearby with a Bluetooth scanner could read what goes to the watch, location included (rounded to about 10 km).")
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
