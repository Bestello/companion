import Foundation
import Observation

// FMC catalog: PocketBase `watchfaces` at fmc.tools, as fmc_frontend modules/market/model/market.api.ts
// (loadMarketFx, loadMyFx, loadWatchfaceFx, toggleLikeFx). Publishing and the editor stay on the site.

struct Watchface: Decodable, Identifiable, Hashable {
    struct Owner: Decodable, Hashable {
        var id: String
        var name: String
        var avatar: String?
        var avatarURL: URL? {
            avatar.flatMap { $0.isEmpty ? nil : Account.base.appending(path: "api/files/users/\(id)/\($0)").appending(queryItems: [.init(name: "thumb", value: "100x100")]) }
        }
    }
    struct Expand: Decodable, Hashable { var owner: Owner? }

    var id: String
    var collectionId: String
    var name: String
    var description: String
    var preview: String
    var bin: String
    var published: Bool
    var downloads: Int
    var created: String
    /// "Analog", "Digital", …; "" for most
    var type: String?
    /// "watch_pro_2", "watch_3_pro", …; "" for most, which count as Watch Pro 2 (the site's matchesDevice)
    var device: String?
    var expand: Expand?

    /// Made for another watch: the name to warn with, nil for the Watch Pro 2 and untagged faces.
    var otherDevice: String? {
        guard let d = device, !d.isEmpty, d != "watch_pro_2" else { return nil }
        return Self.devices[d] ?? d
    }
    /// fmc_frontend deviceLabel
    static let devices = ["watch_pro": "Watch Pro", "watch_3_pro": "Watch 3 Pro"]

    var author: String? { expand?.owner?.name }
    var authorAvatar: URL? { expand?.owner?.avatarURL }
    var ownerId: String? { expand?.owner?.id }
    var previewURL: URL? { file(preview) }
    var binURL: URL? { file(bin) }
    var pageURL: URL { Account.base.appending(path: "market/\(id)") }

    private func file(_ name: String) -> URL? {
        name.isEmpty ? nil : Account.base.appending(path: "api/files/\(collectionId)/\(id)/\(name)")
    }
}

@MainActor @Observable final class Catalog {
    /// Every published face, for any watch: a face for another one says so on its page (`otherDevice`).
    private(set) var all: [Watchface] = []
    /// The signed-in user's own faces, drafts included (loadMyFx).
    private(set) var mine: [Watchface] = []
    private(set) var error: String?
    /// Every like on fmc.tools (the site loads them whole too): counts and the user's own.
    private(set) var likes: [Like] = []

    struct Like: Decodable, Hashable { var id, user, watchface: String }

    func likeCount(_ face: Watchface) -> Int { likes.lazy.filter { $0.watchface == face.id }.count }
    func liked(_ face: Watchface, by user: User?) -> Bool {
        user.map { u in likes.contains { $0.watchface == face.id && $0.user == u.id } } ?? false
    }

    func loadLikes() async {
        // ponytail: every like (a few thousand ids), as the site's getFullList; a count field on the face if it grows
        if let l: [Like] = try? await Self.all(Self.likesURL, fields: "id,user,watchface") { likes = l }
    }

    /// Likes or unlikes as the signed-in user, then reloads the likes (toggleLikeFx).
    func toggleLike(_ face: Watchface, account: Account) async {
        guard let user = account.user else { return }
        var r: URLRequest
        if let mine = likes.first(where: { $0.watchface == face.id && $0.user == user.id }) {
            r = URLRequest(url: Self.likesURL.appending(path: mine.id))
            r.httpMethod = "DELETE"
            likes.removeAll { $0.id == mine.id }
        } else {
            r = URLRequest(url: Self.likesURL)
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONEncoder().encode(["user": user.id, "watchface": face.id])
            likes.append(Like(id: "", user: user.id, watchface: face.id))
        }
        account.authorize(&r)
        _ = try? await URLSession.shared.data(for: r)
        await loadLikes()
    }

    func load() async {
        do {
            all = try await Self.list(filter: "published = true", sort: "-created")
            error = nil
        } catch {
            if !Self.cancelled(error) { self.error = error.localizedDescription }
        }
    }

    func loadMine(_ account: Account) async {
        guard let id = account.user?.id, FaceLink.isRecordID(id) else { mine = []; return }
        do {
            mine = try await Self.list(filter: "owner = '\(id)'", sort: "-updated", account: account)
            error = nil
        } catch {
            if !Self.cancelled(error) { self.error = error.localizedDescription }
        }
    }

    /// A face from the catalog or the user's own list, else from the server: a deep link can name one
    /// before either list is loaded.
    func face(_ id: String, account: Account) async throws -> Watchface {
        if let f = (all + mine).first(where: { $0.id == id }) { return f }
        var r = URLRequest(url: Self.records.appending(path: id).appending(queryItems: [.init(name: "expand", value: "owner")]))
        account.authorize(&r)
        return try await Self.get(r)
    }

    /// A list that left the screen mid-load: not an error to show.
    private static func cancelled(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private static let records = Account.base.appending(path: "api/collections/watchfaces/records")
    private static let likesURL = Account.base.appending(path: "api/collections/likes/records")

    private struct Page<T: Decodable>: Decodable { var items: [T]; var totalPages: Int }

    private static func all<T: Decodable>(_ url: URL, fields: String) async throws -> [T] {
        var out: [T] = []
        var page = 1
        while true {
            let p: Page<T> = try await get(URLRequest(url: url.appending(queryItems: [
                .init(name: "page", value: "\(page)"), .init(name: "perPage", value: "500"),
                .init(name: "fields", value: fields), .init(name: "skipTotal", value: "0"),
            ])))
            out += p.items
            guard page < p.totalPages else { return out }
            page += 1
        }
    }

    // ponytail: whole list in 500-record pages, as the site's getFullList; a few hundred faces. Real
    // paging when the catalog grows into the thousands.
    private static func list(filter: String, sort: String, account: Account? = nil) async throws -> [Watchface] {
        struct Page: Decodable { var items: [Watchface]; var totalPages: Int }
        var out: [Watchface] = []
        var page = 1
        while true {
            var r = URLRequest(url: records.appending(queryItems: [
                .init(name: "page", value: "\(page)"), .init(name: "perPage", value: "500"),
                .init(name: "filter", value: filter), .init(name: "sort", value: sort),
                .init(name: "expand", value: "owner"),
            ]))
            account?.authorize(&r)
            let p: Page = try await get(r)
            out += p.items
            guard page < p.totalPages else { return out }
            page += 1
        }
    }

    private static func get<T: Decodable>(_ r: URLRequest) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw AccountError.server(status, "Catalog request failed (\(status))") }
        return try JSONDecoder().decode(T.self, from: data)
    }
}

/// Links that open a face in the app: `fmc://install?url=…` from the Install button on fmc.tools,
/// `fmc://install?id=…`, and the site's own `https://fmc.tools/market/<id>` (a universal link once the
/// site serves apple-app-site-association).
///
/// A link only ever names a catalog record. The file to install is always that record's own `bin`
/// from fmc.tools, never a URL from the link: a web page can't make the app fetch and flash a file.
enum FaceLink {
    static func watchface(_ url: URL) -> String? {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let query = { (name: String) in c.queryItems?.first { $0.name == name }?.value }
        let parts = c.path.split(separator: "/").map(String.init)
        switch c.scheme?.lowercased() {
        case "fmc" where c.host == "install":
            if let id = query("id") { return isRecordID(id) ? id : nil }
            return query("url").flatMap(URL.init(string:)).flatMap(fileRecord)
        case "https" where isFMC(c.host):
            return parts.count == 2 && parts[0] == "market" && isRecordID(parts[1]) ? parts[1] : nil
        default:
            return nil
        }
    }

    /// `https://fmc.tools/api/files/<collection>/<record>/<file>` → the record.
    private static func fileRecord(_ url: URL) -> String? {
        let p = url.pathComponents
        guard url.scheme == "https", isFMC(url.host), p.count == 6, p[1] == "api", p[2] == "files",
              isRecordID(p[4]) else { return nil }
        return p[4]
    }

    private static func isFMC(_ host: String?) -> Bool {
        host?.lowercased() == Account.base.host
    }

    /// PocketBase ids: 15 lowercase letters and digits. Also what makes a filter safe to build from one.
    static func isRecordID(_ s: String) -> Bool {
        s.count == 15 && s.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber) }
    }
}
