import CoreBluetooth
import WireKit

// WireKit's Link over CoreBluetooth, on the link the phone already holds for Nothing X (cmf-watch-sdk
// docs/abi-1.4.md, "Writing a host", steps 1–2). Everything runs on the main queue; one step waits at a time.
// ponytail: foreground only. Background mode and state restoration come with the first feature that needs them.

enum BLEError: LocalizedError {
    case off, unauthorized, noWatch, missing, failed(String)
    var errorDescription: String? {
        switch self {
        case .off: "Bluetooth is off."
        case .unauthorized: "FMC Companion may not use Bluetooth. Allow it in Settings."
        case .noWatch: "No watch connected. Open Nothing X and make sure the watch is connected."
        case .missing: "This watch does not offer the expected services."
        case .failed(let m): m
        }
    }
}

final class BLELink: NSObject, Link, @unchecked Sendable {
    static let service = CBUUID(string: "FFF0")
    private static let known = [Uuid.cmdWrite, Uuid.cmdNotify, Uuid.shellWrite, Uuid.shellNotify]
        .map { (CBUUID(string: $0), $0) }

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var chars: [String: CBCharacteristic] = [:]
    private var handler: (@Sendable (String, Data) -> Void)?
    private var step: CheckedContinuation<Void, Error>?
    private var pendingServices = 0

    /// The connected watch's identifier: keys the cached pairing secret.
    var watchID: UUID? { peripheral?.identifier }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    @MainActor func connect() async throws {
        if central.state != .poweredOn { try await wait() }
        guard let p = central.retrieveConnectedPeripherals(withServices: [Self.service]).first else {
            throw BLEError.noWatch
        }
        peripheral = p
        p.delegate = self
        central.connect(p)
        try await wait() // connected, services and characteristics found, both notifies on
    }

    @MainActor func disconnect() {
        if let peripheral { central.cancelPeripheralConnection(peripheral) }
        peripheral = nil
    }

    /// Ends a step that never finished (the watch went quiet).
    @MainActor func abort() {
        finish(BLEError.failed("The watch did not answer."))
        disconnect()
    }

    func onNotify(_ handler: @escaping @Sendable (String, Data) -> Void) { self.handler = handler }

    func write(_ uuid: String, _ data: Data) async throws {
        try await send(uuid, data)
    }

    /// Writes and, with response, waits for the ack: the step is set in the same main-actor turn as the write,
    /// so the callback cannot come first.
    @MainActor private func send(_ uuid: String, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            guard let p = peripheral, let ch = chars[uuid] else { return c.resume(throwing: BLEError.missing) }
            // a frame over MTU-3 goes as one write with response (abi-1.4.md, step 4)
            let big = data.count > p.maximumWriteValueLength(for: .withoutResponse)
            if big || !ch.properties.contains(.writeWithoutResponse) {
                step = c
                p.writeValue(data, for: ch, type: .withResponse)
            } else {
                p.writeValue(data, for: ch, type: .withoutResponse)
                c.resume()
            }
        }
    }

    @MainActor private func wait() async throws {
        try await withCheckedThrowingContinuation { step = $0 }
    }

    private func finish(_ error: Error? = nil) {
        let s = step
        step = nil
        if let error { s?.resume(throwing: error) } else { s?.resume() }
    }
}

extension BLELink: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn: finish()
        case .poweredOff: finish(BLEError.off)
        case .unauthorized, .unsupported: finish(BLEError.unauthorized)
        default: break
        }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.discoverServices(nil)
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        finish(error ?? BLEError.noWatch)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        finish(BLEError.failed("The watch disconnected."))
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        let services = p.services ?? []
        guard error == nil, !services.isEmpty else { return finish(error ?? BLEError.missing) }
        pendingServices = services.count
        services.forEach { p.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        for c in s.characteristics ?? [] {
            if let name = Self.known.first(where: { $0.0 == c.uuid })?.1 { chars[name] = c }
        }
        pendingServices -= 1
        guard pendingServices == 0 else { return }
        guard Self.known.allSatisfy({ chars[$0.1] != nil }) else { return finish(BLEError.missing) }
        p.setNotifyValue(true, for: chars[Uuid.cmdNotify]!)
        p.setNotifyValue(true, for: chars[Uuid.shellNotify]!)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        if let error { return finish(error) }
        if chars[Uuid.cmdNotify]?.isNotifying == true, chars[Uuid.shellNotify]?.isNotifying == true { finish() }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard let value = c.value, let name = Self.known.first(where: { $0.0 == c.uuid })?.1 else { return }
        handler?(name, value)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        finish(error)
    }
}
