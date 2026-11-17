import Foundation
import os
import WireKit

// A watchface onto the watch the way fmc.tools does it through Bluefy (fmc_frontend ble.ts connect, pair,
// authenticate, uploadWatchface). The stock session can't share the watch with Nothing X, so the user closes
// Nothing X first, and may have to add the watch to it again afterwards (fmc issue #27). The UI says so before
// every install (FMC-8, item 2: Egor's call, 2026-11-17).

enum FaceInstallError: LocalizedError {
    case refused(FaceRefusal), full, noFile

    var errorDescription: String? {
        switch self {
        case .refused(.aodWithoutPreview):
            "The always-on screen of this face has no thumbnail. Faces like this made the screen flash on a watch. Open it in the editor on fmc.tools and install it from there."
        case .refused(.imageTooBig(let w, let h)):
            "An image of this face is \(w)×\(h) px. Over \(maxImageSide) px can make the watch restart in a loop."
        case .refused: "This file is not a watch face."
        case .full: "The watch is full. Delete a face on the watch (long press the face, swipe one up) and try again."
        case .noFile: "This watch face has no file."
        }
    }
}

/// A dial on the watch. `index`: its place in the watch's own carousel, the only handle on a dial nobody named.
struct DialSlot: Identifiable {
    let id: UInt32, index: Int
    /// The watch only reports ids: faces this app installed keep their name, stock ones are named from fmc.tools'
    /// stock-dials.json, Nothing X gallery ones have nothing to go on.
    var label: String {
        UserDefaults.standard.string(forKey: "dial-name-\(id)") ?? Self.stock[id] ?? String(localized: "Face \(index + 1)")
    }

    /// The preview of a face this app installed (a draft that is in no list now, or a face since deleted).
    var savedPreview: URL? { UserDefaults.standard.url(forKey: "dial-preview-\(id)") }

    /// An FMC catalog face, from this app or from fmc.tools: its dial id is `dialId(record id)`.
    @MainActor func face(in catalog: Catalog) -> Watchface? {
        (catalog.mine + catalog.all).first { dialId($0.id) == id }
    }

    /// fmc_frontend modules/device/lib/stock-dials.json: id → name of the factory and Nothing gallery faces.
    static let stock: [UInt32: String] = {
        struct Entry: Decodable { var name: String }
        guard let url = Bundle.main.url(forResource: "stock-dials", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let map = try? JSONDecoder().decode([String: Entry].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: map.compactMap { k, v in UInt32(k).map { ($0, v.name) } })
    }()
}

/// ble.ts WF_CAPACITY
let capacity = 6

enum FaceInstall {
    /// The file and the gate's verdict: nothing goes to the watch before the user has seen it.
    static func fetch(_ face: Watchface) async throws -> (Data, FaceVerdict) {
        guard let url = face.binURL else { throw FaceInstallError.noFile }
        let (bin, _) = try await URLSession.shared.data(from: url)
        let verdict = checkFace(bin)
        if case .refuse(let why) = verdict { throw FaceInstallError.refused(why) }
        return (bin, verdict)
    }

    /// Connects and opens a stock session (a new bind only when the saved key is refused, and `bind` allows one:
    /// it pops up on the watch), then `body`.
    @MainActor private static func session<T>(bind: Bool = true,
                                              _ body: (StockSession, String) async throws -> T) async throws -> T {
        // the stock session binds the watch: the RPC listener waits it out
        RPCListener.shared.stop()
        defer { RPCListener.shared.start() }
        let link = BLELink()
        defer { link.disconnect() }
        let watchdog = Task {
            try await Task.sleep(for: .seconds(30))
            link.abort()
        }
        try await link.connect(scan: true)
        watchdog.cancel()
        let watch = link.watchID?.uuidString ?? ""
        var secret = Keychain.get("k-\(watch)")
        if secret == nil {
            secret = try await KSession.open(link: link, register: false).secretHex
            Keychain.set("k-\(watch)", secret)
        }
        let session = StockSession(link: link)
        let saved = Keychain.get("stock-\(watch)").flatMap(Data.init(hex:))
        do {
            guard let saved else { throw UploadError.authFailed }
            try await session.authenticate(k1: saved)
        } catch UploadError.authFailed where bind, WireError.timeout where bind {
            // no key yet, or the watch was bound again since (Nothing X, a reset): bind, as the site does
            let k1 = try await session.pair(secret: Data(hex: secret ?? "") ?? Data())
            Keychain.set("stock-\(watch)", k1.map { String(format: "%02x", $0) }.joined())
        }
        // a055 comes on its own after auth
        try await Task.sleep(for: .seconds(1.5))
        return try await body(session, watch)
    }

    /// What the watch reported in a055, plus the dials this app put there that the list leaves out.
    private static func dials(_ session: StockSession, _ watch: String) async
        -> (mine: [UInt32], installed: [UInt32], counted: (count: Int, capacity: Int)?, header: String) {
        let mine = UserDefaults.standard.array(forKey: "dials-\(watch)") as? [UInt32] ?? []
        let reported = await session.reported ?? []
        let installed = reported + mine.filter { !reported.contains($0) }
        // the header counts side-loaded dials too; the hardcoded capacity and the id list are the fallback
        let counted = await session.dialCount
        let header = (await session.dialHeader ?? Data()).map { String(format: "%02x", $0) }.joined(separator: " ")
        Logger(subsystem: "tools.fmc.companion", category: "dials")
            .notice("a055 header \(header, privacy: .public), \(reported.count) listed, \(installed.count) known")
        return (mine, installed, counted, header)
    }

    /// What the Watch tab shows.
    private static func remember(_ onWatch: [UInt32], count: Int?, capacity: Int, header: String) {
        UserDefaults.standard.set(onWatch, forKey: "faces-on-watch")
        UserDefaults.standard.set(Date(), forKey: "faces-on-watch-date")
        UserDefaults.standard.set(count.map { [$0, capacity] }, forKey: "faces-on-watch-count")
        UserDefaults.standard.set(header, forKey: "faces-on-watch-header")
    }

    /// Reads the dials off the watch for the Watch tab, with the saved key only: no bind popup on a watch this app
    /// never installed on. Takes the watch from Nothing X, like an install.
    @MainActor static func read() async throws {
        try await session(bind: false) { session, watch in
            let d = await dials(session, watch)
            remember(d.installed, count: d.counted?.count, capacity: d.counted?.capacity ?? capacity, header: d.header)
        }
    }

    /// Uploads `bin`. `pick`: the watch is full, which dial goes; nil cancels the install.
    @MainActor static func install(_ bin: Data, seed: String, name: String,
                                   pick: @MainActor ([DialSlot]) async -> UInt32?,
                                   progress: @escaping @Sendable (Int) -> Void) async throws {
        try await session { session, watch in
            let (mine, installed, counted, header) = await dials(session, watch)
            let cap = counted?.capacity ?? capacity
            let full = counted.map { $0.count >= $0.capacity } ?? (installed.count >= capacity)
            // which dial to replace (ble.ts NoFreeSlotError, slot-dialog.svelte)
            func ask() async throws -> UInt32 {
                guard let picked = await pick(installed.enumerated().map { DialSlot(id: $1, index: $0) })
                else { throw CancellationError() }
                return picked
            }
            // full and not a re-install of this face: ask before sending anything
            var slot: UInt32?
            if full, !installed.contains(dialId(seed)) { slot = try await ask() }
            let id: UInt32
            do {
                do {
                    id = try await installFace(session, bin, seed: seed, installed: installed, capacity: cap,
                                               slot: slot, progress: progress)
                } catch UploadError.noFreeSlot where slot == nil && !installed.isEmpty {
                    // the count said there was room and the watch said no: the header reading is wrong, or it is missing
                    slot = try await ask()
                    id = try await installFace(session, bin, seed: seed, installed: installed, capacity: cap,
                                               slot: slot, progress: progress)
                }
            } catch UploadError.noFreeSlot {
                throw FaceInstallError.full
            }
            UserDefaults.standard.set(name, forKey: "dial-name-\(id)")
            let now = mine.filter { $0 != slot } + (mine.contains(id) ? [] : [id])
            UserDefaults.standard.set(now, forKey: "dials-\(watch)")
            let onWatch = slot.map { s in installed.map { $0 == s ? id : $0 } }
                ?? (installed.contains(id) ? installed : installed + [id])
            remember(onWatch, count: counted.map { slot != nil || installed.contains(id) ? $0.count : $0.count + 1 },
                     capacity: cap, header: header)
        }
    }
}

extension Data {
    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var d = Data()
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            d.append(b)
            i = j
        }
        self = d
    }
}
