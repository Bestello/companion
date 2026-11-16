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
        }
    }
}

struct ContentView: View {
    @Environment(Account.self) private var account
    @Environment(WatchModel.self) private var watch

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
                        Button("Sign out", role: .destructive) { account.signOut() }
                    } else {
                        NavigationLink("Sign in") { SignInView() }
                    }
                }
            }
            .navigationTitle("FMC Companion")
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
                    Task { await signIn() }
                } label: {
                    if busy { ProgressView() } else { Text("Sign in") }
                }
                .disabled(busy || email.isEmpty || password.isEmpty)
            } footer: {
                Link("No account? Sign up at fmc.tools", destination: Account.signUp)
            }
        }
        .navigationTitle("Sign in")
    }

    private func signIn() async {
        busy = true
        defer { busy = false }
        do {
            try await account.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
