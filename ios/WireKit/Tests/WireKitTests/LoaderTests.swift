import Foundation
import Testing
@testable import WireKit

/// A watch for INFO only: `stub` is the reply to slot 0xFE (nil = stock, never answers).
final class FakeWatch: Requester, @unchecked Sendable {
    var stub: Data?
    var used: [Int: Data] = [:]

    func call(_ c2: UInt16, _ payload: Data, timeout: Duration) async throws -> Data {
        #expect(c2 == Cmd.info, "read only")
        let slot = Int(payload[payload.startIndex])
        if slot == Int(stubSlot) {
            guard let stub else { throw WireError.timeout("no reply to 90A0") }
            return stub
        }
        return Data([0]) + (used[slot] ?? Data())
    }
}

@Test func stockFirmwareIsNil() async throws {
    #expect(try await readLoader(FakeWatch()) == nil)
}

@Test func stubOneHasNoVersion() async throws {
    let w = FakeWatch()
    w.stub = Data([1])
    #expect(try await readLoader(w)?.stub == Stub(version: 1, abiMinor: nil))
}

@Test func readsStubLoaderAndSlots() async throws {
    let w = FakeWatch()
    w.stub = Data([0, 5, 0, 0, 0, 3])
    var loader = Data(header)
    loader[6] = 1
    w.used = [2: Data(header), loaderSlot: loader]
    let s = try #require(try await readLoader(w))
    #expect(s.stub == Stub(version: 5, abiMinor: 3))
    #expect(s.loader?.kind == 1)
    #expect(s.apps == [2: PackageHeader(kind: pkg.kind, name: pkg.name, version: pkg.version)])
}

@Test func garbledStubReplyThrows() async throws {
    let w = FakeWatch()
    w.stub = Data([0, 5])
    await #expect(throws: WireError.refused(0)) { try await readLoader(w) }
}
