import SwiftUI
import WireKit

@main
struct FMCCompanionApp: App {
    @State private var account = Account()
    @State private var watch = WatchModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(account)
                .environment(watch)
                .task { await account.restore() }
                #if DEBUG
                // Screenshots: `-demo YES -check` reads the sample watch on launch
                .task { if CommandLine.arguments.contains("-check") { await watch.check() } }
                #endif
        }
    }
}

struct ContentView: View {
    @Environment(Account.self) private var account
    @Environment(WatchModel.self) private var watch
    // DEBUG screenshots: `-signin` opens Sign in
    @State private var signingIn = CommandLine.arguments.contains("-signin")

    var body: some View {
        @Bindable var watch = watch
        NavigationStack {
            Form {
                Section("Watch") {
                    WatchStatus()
                }
                if let state = watch.loader {
                    LoaderSection(state: state)
                }
                Section {
                    Toggle("Demo mode", isOn: $watch.demo)
                } footer: {
                    Text("Shows a sample watch, for trying the app without one.")
                }
                Section("FMC account") {
                    if let user = account.user {
                        LabeledContent(user.name.isEmpty ? "Signed in" : user.name, value: user.email)
                        Button("Sign out") { account.signOut() }
                        DeleteAccountButton()
                    } else {
                        Button("Sign in") { signingIn = true }
                    }
                }
            }
            .navigationTitle("FMC Companion")
            .navigationDestination(isPresented: $signingIn) { SignInView() }
        }
    }
}

struct WatchStatus: View {
    @Environment(WatchModel.self) private var watch

    var body: some View {
        switch watch.phase {
        case .idle:
            Text(watch.demo ? "Sample watch" : "Keep the watch connected in Nothing X.")
                .foregroundStyle(.secondary)
        case .checking:
            HStack {
                ProgressView()
                Text("Reading the watch…")
            }
        case .checked:
            Label("Connected", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        }
        Button(watch.phase == .idle ? "Connect" : "Check again") {
            Task { await watch.check() }
        }
        .disabled(watch.phase == .checking)
    }
}

struct LoaderSection: View {
    let state: LoaderState

    var body: some View {
        Section("App Loader") {
            LabeledContent("Stub", value: "\(state.stub.version)")
            LabeledContent("Loader", value: state.loader.map { "\($0.version)" } ?? "not installed")
        }
        Section("Apps on the watch") {
            if state.apps.isEmpty {
                Text("No apps installed").foregroundStyle(.secondary)
            }
            ForEach(state.apps.keys.sorted(), id: \.self) { slot in
                let app = state.apps[slot]!
                LabeledContent(app.name, value: "v\(app.version) · slot \(slot)")
            }
        }
    }
}

struct SignInView: View {
    @Environment(Account.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        Form {
            Section {
                TextField("Email", text: $email)
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
            } footer: {
                if let error { Text(error).foregroundStyle(.red) }
            }
            Section {
                Button {
                    run { try await account.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password) }
                } label: {
                    if busy { ProgressView() } else { Text("Sign in") }
                }
                .disabled(busy || email.isEmpty || password.isEmpty)
            } footer: {
                Link("No account? Sign up at fmc.tools", destination: Account.signUp)
            }
            if !account.providers.isEmpty {
                Section {
                    // Apple first, as 4.8 wants it offered alongside, and in Apple's black style (HIG).
                    ForEach(account.providers.sorted { $0.name == "apple" && $1.name != "apple" }) { p in
                        Button {
                            run { try await account.signIn(with: p) }
                        } label: {
                            Label("Continue with \(p.displayName)", systemImage: p.name == "apple" ? "apple.logo" : "person.crop.circle")
                                .frame(maxWidth: .infinity)
                        }
                        .listRowBackground(p.name == "apple" ? Color.black : nil)
                        .foregroundStyle(p.name == "apple" ? .white : .accentColor)
                        .disabled(busy)
                    }
                }
            }
        }
        .navigationTitle("Sign in")
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
