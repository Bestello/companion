import SwiftUI
import WireKit

@main
struct FMCCompanionApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var account = Account()
    @State private var watch = WatchModel()
    @State private var catalog = Catalog()
    @State private var tab = CommandLine.arguments.contains("-watch") ? 1 : 0
    /// The catalog's navigation: face ids. A link to a face replaces it.
    @State private var path: [String] = []

    var body: some Scene {
        WindowGroup {
            TabView(selection: $tab) {
                CatalogView(path: $path)
                    .tabItem { Label("Watch faces", systemImage: "square.grid.2x2") }
                    .tag(0)
                ContentView()
                    .tabItem { Label("Watch", systemImage: "applewatch") }
                    .tag(1)
            }
            .onOpenURL(perform: open)
            .onChange(of: Permissions.shared.showScreen) { if $1 { tab = 1 } }
            .environment(account)
            .environment(watch)
            .environment(catalog)
            .tint(.fmcAccent)
            .task { await account.restore() }
            #if DEBUG
            // `-check` reads the watch on launch
            .task { if CommandLine.arguments.contains("-check") { await watch.check() } }
            // `-open <link>`: what onOpenURL gets, without the system's "Open in" prompt
            .task {
                let a = CommandLine.arguments
                if let i = a.firstIndex(of: "-open"), i + 1 < a.count, let url = URL(string: a[i + 1]) { open(url) }
            }
            #endif
        }
    }
}

extension FMCCompanionApp {
    func open(_ url: URL) {
        guard let id = FaceLink.watchface(url) else { return }
        tab = 0
        path = [id]
    }
}

struct ContentView: View {
    @Environment(Account.self) private var account
    @Environment(WatchModel.self) private var watch
    // DEBUG screenshots: `-signin` opens Sign in
    @State private var signingIn = CommandLine.arguments.contains("-signin")

    var body: some View {
        @Bindable var perms = Permissions.shared
        NavigationStack {
            List {
                Section {
                    WatchStatus()
                } footer: {
                    Text("Keep the watch connected in Nothing X. The app only reads from it.")
                }
                if let state = watch.loader {
                    LoaderSection(state: state)
                }
                FacesOnWatchSection()
                Section("Settings") {
                    NavigationLink {
                        PermissionsView()
                    } label: {
                        Label("Permissions", systemImage: "lock.shield")
                            .badge(Permissions.shared.pending.count)
                    }
                    #if DEBUG
                    NavigationLink("Wake probe") { WakeProbeView() }
                    #endif
                }
                Section("FMC account") {
                    if let user = account.user {
                        HStack(spacing: 14) {
                            Badge { Image(systemName: "person.fill").font(.title3) }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(user.name.isEmpty ? "Signed in" : user.name).font(.body.weight(.semibold))
                                Text(user.email).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                        Button("Sign out") { account.signOut() }
                        DeleteAccountButton()
                    } else {
                        Button { signingIn = true } label: {
                            LabeledContent {
                                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                            } label: {
                                Label("Sign in", systemImage: "person.crop.circle")
                            }
                        }
                        .tint(.primary)
                    }
                }
            }
            .navigationTitle("FMC Companion")
            .navigationDestination(isPresented: $signingIn) { SignInView() }
            .navigationDestination(isPresented: $perms.showScreen) { PermissionsView() }
        }
    }
}

/// The watch tile from workout-companion's WatchView, with Zeron's capsule button under it.
struct WatchStatus: View {
    @Environment(WatchModel.self) private var watch

    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: 16) {
                Badge(side: 64, filled: connected) {
                    Image(systemName: "applewatch").font(.system(size: 30, weight: .medium))
                        .symbolEffect(.pulse, isActive: watch.phase == .checking)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.title3.bold())
                    Text(detail).font(.subheadline).foregroundStyle(detailColor)
                }
                Spacer(minLength: 0)
            }
            Button {
                Task { await watch.check() }
            } label: {
                Label(watch.phase == .idle ? "Connect" : "Check again",
                      systemImage: watch.phase == .idle ? "dot.radiowaves.left.and.right" : "arrow.clockwise")
            }
            .buttonStyle(.primary)
            .disabled(watch.phase == .checking)
        }
        .padding(.vertical, 6)
    }

    private var connected: Bool { if case .checked = watch.phase { true } else { false } }

    private var title: LocalizedStringKey {
        switch watch.phase {
        case .idle: "Not connected"
        case .checking: "Reading the watch…"
        case .checked: "Connected"
        case .failed: "Couldn't connect"
        }
    }

    private var detail: String {
        switch watch.phase {
        case .idle: String(localized: "Tap Connect to read it")
        case .checking: String(localized: "This takes a few seconds")
        case .checked(let state): state == nil ? String(localized: "Ready for watch faces") : String(localized: "App Loader found")
        case .failed(let message): message
        }
    }

    private var detailColor: Color {
        switch watch.phase {
        case .checked: .fmcSuccess
        case .failed: .fmcWarning
        default: .secondary
        }
    }
}

/// The watch's faces as last read: by the button (stock session, Nothing X closed) or by an install.
struct FacesOnWatchSection: View {
    @Environment(Catalog.self) private var catalog
    @Environment(WatchModel.self) private var watch
    @State private var faces: [DialSlot] = []
    @State private var date: Date?
    /// [count, capacity] from the a055 header, side-loaded dials included
    @State private var count: [Int]?
    @State private var header = ""
    @State private var reading = false
    @State private var error: String?

    var body: some View {
        Section {
            if !faces.isEmpty {
                // as Nothing X and fmc.tools show them: the round previews in the watch's own order
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 14)], spacing: 14) {
                    ForEach(faces) { slot in
                        let face = slot.face(in: catalog)
                        VStack(spacing: 6) {
                            if let url = face?.previewURL ?? slot.savedPreview {
                                FacePreview(url: url)
                            } else {
                                // no picture anywhere for stock and Nothing X faces: the watch's place for it
                                Circle().fill(.black).aspectRatio(1, contentMode: .fit).overlay {
                                    Text("\(slot.index + 1)").font(.system(.title, design: .rounded, weight: .bold))
                                        .foregroundStyle(Color.fmcAccent)
                                }
                            }
                            Text(face?.name ?? slot.label).font(.caption.weight(.semibold)).lineLimit(1)
                        }
                    }
                }
                .padding(.vertical, 6)
            }
            if reading {
                HStack { Text("Reading the watch…").foregroundStyle(.secondary); Spacer(); ProgressView() }
            } else if faces.isEmpty {
                Text("Install a face from the app once: after that the list is read on every connect.")
                    .foregroundStyle(.secondary)
            }
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        } header: {
            if let count, count.count == 2 { Text("Watch faces · \(count[0]) of \(count[1])") } else { Text("Watch faces") }
        } footer: {
            if let date {
                Text("Read \(date.formatted(.relative(presentation: .named))). Numbered in the order the watch shows them.")
                + Text(header.isEmpty ? "" : "\nWatch header: \(header)").monospaced()
            }
        }
        // the catalog loads with the Watch faces tab; this one can come first
        .task { if catalog.all.isEmpty { await catalog.load() } }
        .onAppear(perform: load)
        // right after each connect
        .task(id: watch.phase) { if case .checked = watch.phase { await read() } }
    }

    private func read() async {
        reading = true
        defer { reading = false }
        do {
            try await FaceInstall.read()
            error = nil
            load()
        } catch UploadError.authFailed {
            // no saved key: the first install from the app binds, the list comes after that
        } catch {
            self.error = String(localized: "Couldn't read the faces on the watch: \(error.localizedDescription)")
        }
    }

    private func load() {
        let ids = UserDefaults.standard.array(forKey: "faces-on-watch") as? [UInt32] ?? []
        faces = ids.enumerated().map { DialSlot(id: $1, index: $0) }
        date = UserDefaults.standard.object(forKey: "faces-on-watch-date") as? Date
        count = UserDefaults.standard.array(forKey: "faces-on-watch-count") as? [Int]
        header = UserDefaults.standard.string(forKey: "faces-on-watch-header") ?? ""
    }
}

struct LoaderSection: View {
    let state: LoaderState

    var body: some View {
        Section("App Loader") {
            HStack {
                Metric(value: "\(state.stub.version)", unit: "Stub")
                Metric(value: state.loader.map { "\($0.version)" } ?? "—", unit: "Loader")
            }
            .padding(.vertical, 4)
        }
        Section("Apps on the watch") {
            if state.apps.isEmpty {
                Text("No apps installed").foregroundStyle(.secondary)
            }
            ForEach(state.apps.keys.sorted(), id: \.self) { slot in
                let app = state.apps[slot]!
                // workout-companion's DayRow: the slot in a filled badge, the name and what it is
                HStack(spacing: 14) {
                    Badge { Text("\(slot)").font(.system(.title3, design: .rounded, weight: .bold)) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(app.name).font(.body.weight(.semibold))
                        Text("Version \(app.version) · slot \(slot)").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }
}

/// Zeron's welcome page (mark, title, tagline, capsule buttons) around a grouped section for the fields.
/// A List, not a ScrollView: a TextField in a ScrollView loops layout on iOS 27.
struct SignInView: View {
    @Environment(Account.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Badge(side: 64) { Image(systemName: "applewatch").font(.system(size: 30, weight: .medium)) }
                        .padding(.bottom, 14)
                    Text("Sign in to FMC").font(.title.weight(.semibold))
                    Text("Your fmc.tools account, for watch faces on your watch.")
                        .font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .plainRow()
            }
            Section {
                TextField("Email", text: $email)
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
            } footer: {
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle.fill").foregroundStyle(Color.fmcDanger)
                }
            }
            Section {
                VStack(spacing: 12) {
                    Button {
                        run { try await account.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password) }
                    } label: {
                        if busy { ProgressView().tint(.fmcOnAccent) } else { Text("Sign in") }
                    }
                    .buttonStyle(.primary)
                    .disabled(busy || email.isEmpty || password.isEmpty)

                    if !account.providers.isEmpty {
                        HStack {
                            VStack { Divider() }
                            Text("or").font(.subheadline).foregroundStyle(.secondary)
                            VStack { Divider() }
                        }
                        .padding(.vertical, 4)
                        // Apple first, as 4.8 wants it offered alongside, and black/white as the HIG asks.
                        ForEach(account.providers.sorted { $0.name == "apple" && $1.name != "apple" }) { p in
                            let apple = p.name == "apple"
                            let button = Button {
                                run { try await account.signIn(with: p) }
                            } label: {
                                Label("Continue with \(p.displayName)", systemImage: apple ? "apple.logo" : "g.circle.fill")
                            }
                            .disabled(busy)
                            if apple { button.buttonStyle(.primary) } else { button.buttonStyle(.secondary) }
                        }
                    }

                    Link("No account? Sign up at fmc.tools", destination: Account.signUp)
                        .font(.subheadline)
                        .padding(.top, 8)
                }
                .plainRow()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await account.loadProviders() }
    }

    private func run(_ action: @escaping () async throws -> Void) {
        Task {
            busy = true
            defer { busy = false }
            do {
                try await action()
                dismiss()
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct DeleteAccountButton: View {
    @Environment(Account.self) private var account
    @State private var asking = false
    @State private var error: String?

    var body: some View {
        Button("Delete account", role: .destructive) { asking = true }
            .confirmationDialog("Delete your FMC account?", isPresented: $asking, titleVisibility: .visible) {
                Button("Delete account", role: .destructive) {
                    Task {
                        do { try await account.deleteAccount() } catch { self.error = error.localizedDescription }
                    }
                }
            } message: {
                Text("This removes your account and profile on fmc.tools. It can't be undone.")
            }
            .alert("Couldn't delete the account", isPresented: .constant(error != nil)) {
                Button("OK") { error = nil }
            } message: {
                Text(error ?? "")
            }
    }
}

private extension View {
    /// A List row without the card behind it, for content that sits on the page.
    func plainRow() -> some View {
        listRowBackground(Color.clear).listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
    }
}
