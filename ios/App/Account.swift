import Foundation
import Observation

// FMC login: PocketBase `users` at fmc.tools, email and password, as fmc_frontend modules/auth/model/auth.api.ts.
// ponytail: sign-in only; sign-up stays on the site, so the app needs no in-app account deletion (App Store 5.1.1(v)).
// Google + Sign in with Apple wait for Egor's decision (FMC-7).

struct User: Decodable, Equatable {
    var id: String
    var email: String
    var name: String
}

@MainActor @Observable final class Account {
    static let base = URL(string: "https://fmc.tools")!
    static let signUp = URL(string: "https://fmc.tools/register")!

    private(set) var user: User?
    private var token: String? {
        didSet { Keychain.set("token", token) }
    }

    init() {
        token = Keychain.get("token")
    }

    /// Checks the stored token and renews it; a rejected one signs out.
    func restore() async {
        guard token != nil else { return }
        do {
            try await auth("auth-refresh", body: nil)
        } catch AccountError.server(let status, _) where status == 401 || status == 403 || status == 404 {
            signOut()
        } catch {
            // offline: keep the token, try again on the next launch
        }
    }

    func signIn(email: String, password: String) async throws {
        try await auth("auth-with-password", body: ["identity": email, "password": password])
    }

    func signOut() {
        token = nil
        user = nil
    }

    private func auth(_ path: String, body: [String: String]?) async throws {
        var r = URLRequest(url: Self.base.appending(path: "api/collections/users/\(path)"))
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token { r.setValue(token, forHTTPHeaderField: "Authorization") }
        r.httpBody = try JSONEncoder().encode(body ?? [:])
        let (data, response) = try await URLSession.shared.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            let message = (try? JSONDecoder().decode(PBError.self, from: data))?.message
            throw AccountError.server(status, message ?? "Sign-in failed (\(status))")
        }
        let reply = try JSONDecoder().decode(AuthReply.self, from: data)
        token = reply.token
        user = reply.record
    }
}

enum AccountError: LocalizedError {
    case server(Int, String)
    var errorDescription: String? {
        switch self { case .server(_, let m): m }
    }
}

private struct AuthReply: Decodable {
    var token: String
    var record: User
}

private struct PBError: Decodable {
    var message: String
}
