import Foundation
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

enum FaceInstall {
    /// The file and the gate's verdict: nothing goes to the watch before the user has seen it.
    static func fetch(_ face: Watchface) async throws -> (Data, FaceVerdict) {
        guard let url = face.binURL else { throw FaceInstallError.noFile }
        let (bin, _) = try await URLSession.shared.data(from: url)
        let verdict = checkFace(bin)
        if case .refuse(let why) = verdict { throw FaceInstallError.refused(why) }
        return (bin, verdict)
    }

    /// Connects, opens a stock session (a new bind only when the saved key is refused) and uploads.
    @MainActor static func install(_ bin: Data, seed: String, progress: @escaping @Sendable (Int) -> Void)
        async throws {
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
        } catch UploadError.authFailed, WireError.timeout {
            // no key yet, or the watch was bound again since (Nothing X, a reset): bind, as the site does
            let k1 = try await session.pair(secret: Data(hex: secret ?? "") ?? Data())
            Keychain.set("stock-\(watch)", k1.map { String(format: "%02x", $0) }.joined())
        }
        // a055 comes on its own after auth
        try await Task.sleep(for: .seconds(1.5))
        let mine = UserDefaults.standard.array(forKey: "dials-\(watch)") as? [UInt32] ?? []
        let reported = await session.reported ?? []
        let installed = reported + mine.filter { !reported.contains($0) }
        let id: UInt32
        do {
            // ponytail: no slot picker. A full watch is told to free a slot on the watch; add the picker
            // (ble.ts NoFreeSlotError) if people hit it often.
            id = try await installFace(session, bin, seed: seed, installed: installed, capacity: 6,
                                       progress: progress)
        } catch UploadError.noFreeSlot {
            throw FaceInstallError.full
        }
        if !installed.contains(id) { UserDefaults.standard.set(mine + [id], forKey: "dials-\(watch)") }
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
