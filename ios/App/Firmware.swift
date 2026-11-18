import SwiftUI
import WireKit

// Firmware: what the watch runs (the patched stub and the App Loader) against what fmc.tools publishes (`firmwares`,
// kinds `patched` and `loader`), checked right after each connect. The App Loader updates from here over K; the
// stub needs the stock OTA, which the app does not do yet.

@MainActor @Observable final class FirmwareModel {
    static let shared = FirmwareModel()

    struct Release {
        let name: String
        let version: String
        /// STUB_VERSION for the patched firmware
        let stub: Int
        let file: URL
    }

    struct Check {
        var firmware: Release?
        var loader: Release?
        /// the store's loader package: its CRC tells whether the watch has it, and it is what Update installs
        var loaderPackage: Data?
        var loaderHeader: PackageHeader?
    }

    private(set) var check: Check?
    private(set) var checking = false
    private(set) var error: String?
    private(set) var progress: Double?

    /// The store's loader differs from the one on the watch (or the watch has none).
    func loaderUpdate(_ state: LoaderState?) -> Bool {
        guard let state, let want = check?.loaderHeader else { return false }
        return state.loader?.crc != want.crc
    }

    /// The store's patched firmware has a newer stub than the watch.
    func firmwareUpdate(_ state: LoaderState?) -> Bool {
        guard let state, let fw = check?.firmware else { return false }
        return fw.stub > Int(state.stub.version)
    }

    func updates(_ state: LoaderState?) -> Int { (loaderUpdate(state) ? 1 : 0) + (firmwareUpdate(state) ? 1 : 0) }

    /// `account`: a moderator's sign-in also lists the drafts, as fmc.tools does (fmc_pocketbase's list rule decides).
    func refresh(_ account: Account? = nil) async {
        guard !checking else { return }
        checking = true
        defer { checking = false }
        do {
            struct Page: Decodable { var items: [Record] }
            struct Record: Decodable { var id, collectionId, name, kind, version, image: String; var stub: Int; var published: Bool }
            var url = Account.base.appending(path: "api/collections/firmwares/records")
            url.append(queryItems: [
                .init(name: "filter", value: "device = 'watch_pro_2' && (kind = 'patched' || kind = 'loader')"),
                .init(name: "sort", value: "-updated"),
                .init(name: "fields", value: "id,collectionId,name,kind,version,image,stub,published"),
            ])
            var r = URLRequest(url: url)
            account?.authorize(&r)
            let (data, _) = try await URLSession.shared.data(for: r)
            let records = try JSONDecoder().decode(Page.self, from: data).items
            func release(_ kind: String) -> Release? {
                records.first { $0.kind == kind }.map {
                    Release(name: $0.published ? $0.name : "\($0.name) (draft)", version: $0.version, stub: $0.stub,
                            file: Account.base.appending(path: "api/files/\($0.collectionId)/\($0.id)/\($0.image)"))
                }
            }
            var c = Check(firmware: release("patched"), loader: release("loader"))
            if let loader = c.loader {
                let (pkg, _) = try await URLSession.shared.data(from: loader.file)
                c.loaderPackage = pkg
                c.loaderHeader = parseHeader(pkg)
            }
            check = c
            error = nil
        } catch {
            self.error = String(localized: "Couldn't check for updates: \(error.localizedDescription)")
        }
    }

    /// One package over K (the loader or an app, into its own slot), with the RPC listener off meanwhile.
    static func install(_ pkg: Data, progress: @escaping @Sendable (Double) -> Void) async throws {
        RPCListener.shared.stop()
        defer { RPCListener.shared.start() }
        let link = BLELink()
        defer { link.disconnect() }
        try await link.connect()
        try await KLock.shared.run {
            let (session, _) = try await WatchModel.open(link)
            _ = try await WireKit.install(session, package: pkg, progress: progress)
        }
    }

    /// Installs the store's App Loader over K, then reads the watch again.
    func updateLoader(_ watch: WatchModel) async {
        guard let pkg = check?.loaderPackage else { return }
        progress = 0
        defer { progress = nil }
        do {
            try await Self.install(pkg) { p in Task { @MainActor in self.progress = p } }
            DevLog.shared.log("App Loader installed (crc \(String(format: "%08x", check?.loaderHeader?.crc ?? 0)))")
            error = nil
            await watch.check()
        } catch InstallError.busy {
            // stub 6 takes the install behind a page and reloads it there (cmf-watch-sdk docs/abi-1.4.md): busy is
            // then only what is on screen
            error = (watch.loader?.stub.version ?? 0) >= 6
                ? String(localized: "An app or the Apps menu is open on the watch. Go back to the watch face, then try again.")
                : String(localized: "The App Loader is running on the watch. Close the Apps menu and any running app, and remove the watch app pages and widgets you added, then try again. Patched firmware with stub 6 or later doesn't need this.")
        } catch {
            self.error = String(localized: "Couldn't update the App Loader: \(error.localizedDescription)")
        }
    }
}

struct FirmwareView: View {
    @Environment(WatchModel.self) private var watch
    @Environment(Account.self) private var account
    private var model = FirmwareModel.shared

    var body: some View {
        let state = watch.loader
        List {
            Section {
                LabeledContent("Firmware") {
                    if let state { Text("Patched · stub \(state.stub.version)") }
                    else if case .checked = watch.phase { Text("Stock") }
                    else { Text("—") }
                }
                LabeledContent("App Loader") {
                    if let l = state?.loader { Text(String(format: "%08x", l.crc)).monospaced() }
                    else if state != nil { Text("Not installed") }
                    else { Text("—") }
                }
            } header: {
                Text("On the watch")
            } footer: {
                if !connected { Text("Connect on the Watch tab to read the watch.") }
            }

            Section {
                if let fw = model.check?.firmware {
                    LabeledContent {
                        Text(model.firmwareUpdate(state) ? "Update available" : (state == nil ? "" : "Up to date"))
                            .foregroundStyle(model.firmwareUpdate(state) ? Color.fmcAccent : .secondary)
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(fw.name)
                            Text("\(fw.version) · stub \(fw.stub)").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                if let loader = model.check?.loader {
                    LabeledContent {
                        if model.loaderUpdate(state), model.progress == nil {
                            Button(state?.loader == nil ? "Install" : "Update") {
                                Task { await model.updateLoader(watch) }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(model.check?.loaderPackage == nil)
                        } else if let p = model.progress {
                            ProgressView(value: p).frame(width: 80)
                        } else {
                            Text(state == nil ? "" : "Up to date").foregroundStyle(.secondary)
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(loader.name)
                            Text(loader.version).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                Button {
                    Task { await model.refresh(account) }
                } label: {
                    HStack {
                        Text("Check for updates")
                        Spacer()
                        if model.checking { ProgressView() }
                    }
                }
                .disabled(model.checking)
            } header: {
                Text("fmc.tools")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if model.firmwareUpdate(state) {
                        Text("The firmware updates over the stock OTA, which this app doesn't do yet.")
                    }
                    if let error = model.error { Text(error).foregroundStyle(.red) }
                }
            }
        }
        .navigationTitle("Firmware")
        .task { if model.check == nil { await model.refresh(account) } }
    }

    private var connected: Bool { if case .checked = watch.phase { true } else { false } }
}
