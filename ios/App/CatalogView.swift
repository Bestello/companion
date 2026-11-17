import SwiftUI

/// The FMC catalog and, signed in, the user's own faces (fmc_frontend market.svelte and profile.svelte):
/// a grid of round previews, search by name or author.
struct CatalogView: View {
    @Environment(Account.self) private var account
    @Environment(Catalog.self) private var catalog
    @Binding var path: [String]
    @State private var mine = CommandLine.arguments.contains("-mine")
    @State private var query = ""

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                if account.user != nil {
                    Picker("Show", selection: $mine) {
                        Text("Catalog").tag(false)
                        Text("My watch faces").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                }
                grid
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(mine ? "My watch faces" : "Watch faces")
            .searchable(text: $query, prompt: "Name or author")
            .refreshable { await reload() }
            .task(id: account.user?.id) { await reload() }
            .navigationDestination(for: String.self) { FaceDetail(id: $0) }
        }
    }

    @ViewBuilder private var grid: some View {
        let faces = shown
        if faces.isEmpty {
            ContentUnavailableView {
                Label(empty, systemImage: "square.grid.2x2")
            } description: {
                if let error = catalog.error { Text(error) }
            }
            .padding(.top, 80)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 20) {
                ForEach(faces) { face in
                    NavigationLink(value: face.id) { FaceCell(face: face) }
                        .buttonStyle(.plain)
                }
            }
            .padding()
        }
    }

    private var shown: [Watchface] {
        let list = mine ? catalog.mine : catalog.all
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return list }
        return list.filter { $0.name.localizedStandardContains(q) || ($0.author ?? "").localizedStandardContains(q) }
    }

    private var empty: LocalizedStringKey {
        if catalog.error != nil { "Couldn't load watch faces" }
        else if !query.isEmpty { "Nothing found" }
        else if mine { "No watch faces yet" }
        else { "Loading…" }
    }

    private func reload() async {
        if account.user != nil { await catalog.loadMine(account) }
        await catalog.load()
    }
}

/// A round preview with the name and author under it.
struct FaceCell: View {
    let face: Watchface

    var body: some View {
        VStack(spacing: 8) {
            FacePreview(url: face.previewURL)
            VStack(spacing: 2) {
                Text(face.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(face.published ? face.author ?? " " : String(localized: "Draft"))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The watch's 466×466 round panel.
struct FacePreview: View {
    let url: URL?

    var body: some View {
        // ponytail: AsyncImage with URLSession's default cache; a disk cache of our own if scrolling
        // the whole catalog refetches too much.
        AsyncImage(url: url) { image in
            image.resizable().scaledToFit()
        } placeholder: {
            Color(.tertiarySystemGroupedBackground)
        }
        .clipShape(.circle)
        .background(Circle().fill(.black))
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

struct FaceDetail: View {
    let id: String
    @Environment(Account.self) private var account
    @Environment(Catalog.self) private var catalog
    @Environment(WatchModel.self) private var watch
    @State private var face: Watchface?
    @State private var error: String?
    /// Install progress: 0…1, nil when idle.
    @State private var progress: Double?
    @State private var installed = false
    // DEBUG screenshots: `-install` opens the Nothing X warning
    @State private var closeNothingX = CommandLine.arguments.contains("-install")
    /// A file past the safe image size, waiting for the user's yes.
    @State private var big: (bin: Data, w: Int, h: Int)?
    @State private var failure: String?

    var body: some View {
        List {
            if let face {
                Section {
                    VStack(spacing: 14) {
                        FacePreview(url: face.previewURL).frame(maxWidth: 240)
                        VStack(spacing: 4) {
                            Text(face.name).font(.title2.bold()).multilineTextAlignment(.center)
                            if let author = face.author { Text(author).foregroundStyle(.secondary) }
                        }
                        install(face)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                } footer: {
                    Text(footer).frame(maxWidth: .infinity)
                }
                Section {
                    LabeledContent("Downloads", value: "\(face.downloads)")
                    Link(destination: face.pageURL) { Label("Open on fmc.tools", systemImage: "safari") }
                }
                if !face.description.isEmpty {
                    Section("About") { Text(face.description) }
                }
            } else if let error {
                ContentUnavailableView("Couldn't load the watch face", systemImage: "exclamationmark.triangle",
                                       description: Text(error))
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .alert("Close Nothing X first", isPresented: $closeNothingX) {
            Button("Install") { Task { await fetchAndInstall() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The watch talks to one app at a time. Close Nothing X (swipe it away in the app switcher), then install.\n\nAfterwards Nothing X may not see the watch. Then forget the watch in Bluetooth settings and add it again in Nothing X.")
        }
        .alert("Large images", isPresented: Binding { big != nil } set: { if !$0 { big = nil } }) {
            Button("Install anyway", role: .destructive) {
                if let bin = big?.bin { Task { await upload(bin) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let big {
                Text("This face has a \(big.w)×\(big.h) px image, larger than factory faces use. Large images can make the watch restart in a loop.")
            }
        }
        .alert("Couldn't install", isPresented: Binding { failure != nil } set: { if !$0 { failure = nil } }) {
            Button("OK") {}
        } message: {
            Text(failure ?? "")
        }
        .task(id: id) {
            do { face = try await catalog.face(id, account: account) } catch { self.error = error.localizedDescription }
        }
    }

    @ViewBuilder private func install(_ face: Watchface) -> some View {
        if let progress {
            ProgressView(value: progress) { Text("Installing…") }
                .padding(.horizontal, 20)
        } else {
            Button {
                if watch.demo { Task { await demoInstall() } } else { closeNothingX = true }
            } label: {
                Label(installed ? "Installed" : "Install",
                      systemImage: installed ? "checkmark" : "arrow.down.circle")
            }
            .buttonStyle(.primary)
            .disabled(installed || face.binURL == nil)
        }
    }

    private var footer: LocalizedStringKey {
        if watch.demo { "Demo mode: installs on the sample watch." }
        else { "Close Nothing X before installing: the watch talks to one app at a time." }
    }

    private func fetchAndInstall() async {
        guard let face else { return }
        progress = 0
        do {
            let (bin, verdict) = try await FaceInstall.fetch(face)
            if case .confirm(let w, let h) = verdict {
                progress = nil
                big = (bin, w, h)
                return
            }
            await upload(bin)
        } catch {
            progress = nil
            failure = error.localizedDescription
        }
    }

    private func upload(_ bin: Data) async {
        guard let face else { return }
        progress = 0
        defer { progress = nil }
        do {
            try await FaceInstall.install(bin, seed: face.id) { p in
                Task { @MainActor in progress = Double(p) / 100 }
            }
            installed = true
        } catch {
            failure = error.localizedDescription
        }
    }

    /// What the App Store review sees without a watch: the progress of a real install, nothing sent.
    private func demoInstall() async {
        for step in 0...20 {
            progress = Double(step) / 20
            try? await Task.sleep(for: .milliseconds(80))
        }
        progress = nil
        installed = true
    }
}
