import Foundation

// Miniapp services (docs/miniapp-rpc.md): requests from the running watch app, answers from the phone's services.
// Byte layouts and the dispatcher, without the OS: the app gives an RPCHost (EventKit, CoreLocation, WeatherKit,
// URLSession). Vectors: rpc-vectors.json, shared with Android.

public enum RPCStatus: UInt8, Error, Sendable {
    case ok = 0, badRequest, denied, unavailable, ask, tooLarge
}

public enum RPCMethod {
    public static let caps: UInt8 = 0x01, calendar: UInt8 = 0x10, location: UInt8 = 0x20, weather: UInt8 = 0x30
    public static let http: UInt8 = 0x40, httpRequest: UInt8 = 0x41, notifications: UInt8 = 0x50
    public static let storageGet: UInt8 = 0x60, storagePut: UInt8 = 0x61, storageDelete: UInt8 = 0x62
    /// Methods whose request comes in parts (`index ‖ total ‖ chunk`).
    public static let multiPart: Set<UInt8> = [httpRequest, storagePut]
}

/// What the phone keeps between requests: the parts of a request not joined yet, and each package's store.
@MainActor public final class RPCState {
    public let parts = RequestParts()
    public let storage: KeyValueStore
    public init(storage: KeyValueStore) { self.storage = storage }
}

/// Every RPC message opens with "R1"; the app's own messages on the channel do not.
public let rpcPrefix = Data("R1".utf8)

public enum RPCRequest: Equatable, Sendable {
    case call(id: UInt8, method: UInt8, args: Data)
    /// "R1Q" ‖ id and nothing the phone can read after it: BAD_REQUEST.
    case bad(id: UInt8)
}

/// nil: not a request ("R1" ‖ anything but 'Q', or no id byte), dropped.
public func parseRequest(_ d: Data) -> RPCRequest? {
    let b = [UInt8](d)
    guard b.count >= 4, b[0] == 0x52, b[1] == 0x31, b[2] == 0x51 else { return nil }
    guard b.count >= 5 else { return .bad(id: b[3]) }
    return .call(id: b[3], method: b[4], args: Data(b[5...]))
}

/// One message per record ("R1" 'A' id status index total record), or one empty message.
public func replies(id: UInt8, status: RPCStatus, records: [Data]) -> [Data] {
    let head = { (i: Int, n: Int) in rpcPrefix + Data([0x41, id, status.rawValue, UInt8(i), UInt8(n)]) }
    if records.isEmpty { return [head(0, 0)] }
    return records.enumerated().map { head($0, records.count) + $1 }
}

/// UTF-8, cut to at most `max` bytes at a character boundary.
public func utf8Cut(_ s: String, _ max: Int) -> Data {
    var out = Data()
    for c in s {
        let u = Data(String(c).utf8)
        guard out.count + u.count <= max else { break }
        out += u
    }
    return out
}

private func i8(_ v: Int) -> UInt8 { UInt8(bitPattern: Int8(clamping: v)) }

private extension Data {
    mutating func u16(_ v: Int) { le(UInt16(clamping: v)) }
    mutating func i16(_ v: Int) { le(Int16(clamping: v)) }
}

// MARK: records

public struct CalendarEvent: Equatable, Sendable {
    public var day: Int, start: Int, duration: Int
    public var allDay: Bool, tentative: Bool
    /// RGB565
    public var color: UInt16
    public var title: String

    public init(day: Int, start: Int, duration: Int, allDay: Bool, tentative: Bool, color: UInt16, title: String) {
        (self.day, self.start, self.duration, self.allDay, self.tentative) = (day, start, duration, allDay, tentative)
        (self.color, self.title) = (color, title)
    }

    /// Day, minute and real duration of an event in `calendar`'s zone, counted from `now`'s local date.
    public init(start: Date, end: Date, allDay: Bool, now: Date, calendar: Calendar, tentative: Bool,
                color: UInt16, title: String) {
        let day0 = calendar.startOfDay(for: start)
        let day = calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: day0).day ?? 0
        if allDay {
            // EventKit ends an all-day event at 23:59:59 of its last day, others at midnight after it
            let last = calendar.startOfDay(for: max(start, end.addingTimeInterval(-1)))
            let days = (calendar.dateComponents([.day], from: day0, to: last).day ?? 0) + 1
            self.init(day: day, start: 0, duration: 1440 * days, allDay: true, tentative: tentative, color: color,
                      title: title)
        } else {
            let t = calendar.dateComponents([.hour, .minute], from: start)
            self.init(day: day, start: (t.hour ?? 0) * 60 + (t.minute ?? 0),
                      duration: Int(end.timeIntervalSince(start) / 60), allDay: false, tentative: tentative,
                      color: color, title: title)
        }
    }
}

/// `day:i8 ‖ start:u16 ‖ duration:u16 ‖ flags:u8 ‖ color:u16 ‖ title`
public func calendarRecord(_ e: CalendarEvent, show: Bool) -> Data {
    var d = Data([i8(e.day)])
    d.u16(e.start)
    d.u16(e.duration)
    d.append((e.allDay ? 1 : 0) | (e.tentative ? 2 : 0) | (show ? 0 : 4))
    d.le(e.color)
    return show ? d + utf8Cut(e.title, 64) : d
}

/// 0…1 per channel.
public func rgb565(_ r: Double, _ g: Double, _ b: Double) -> UInt16 {
    func c(_ v: Double, _ bits: Int) -> UInt16 { UInt16((min(max(v, 0), 1) * Double((1 << bits) - 1)).rounded()) }
    return c(r, 5) << 11 | c(g, 6) << 5 | c(b, 5)
}

public struct LocationFix: Equatable, Sendable {
    public var lat: Double, lon: Double, ageMin: Int, place: String
    public init(lat: Double, lon: Double, ageMin: Int, place: String) {
        (self.lat, self.lon, self.ageMin, self.place) = (lat, lon, ageMin, place)
    }
}

/// `lat:i16 ‖ lon:i16 ‖ age:u16 ‖ place`, 0.1°.
public func locationRecord(_ f: LocationFix) -> Data {
    var d = Data()
    d.i16(Int((f.lat * 10).rounded()))
    d.i16(Int((f.lon * 10).rounded()))
    d.u16(f.ageMin)
    return d + utf8Cut(f.place, 32)
}

public struct WeatherNow: Equatable, Sendable {
    public var temp: Double, code: Int, wind: Double, humidity: Int, ageMin: Int
    public init(temp: Double, code: Int, wind: Double, humidity: Int, ageMin: Int) {
        (self.temp, self.code, self.wind, self.humidity, self.ageMin) = (temp, code, wind, humidity, ageMin)
    }
}

public struct WeatherDay: Equatable, Sendable {
    public var day: Int, min: Double, max: Double, code: Int, precip: Int
    public init(day: Int, min: Double, max: Double, code: Int, precip: Int) {
        (self.day, self.min, self.max, self.code, self.precip) = (day, min, max, code, precip)
    }
}

/// `0 ‖ temp:i16 ‖ code ‖ wind ‖ humidity ‖ age`
public func weatherRecord(_ w: WeatherNow) -> Data {
    var d = Data([0])
    d.i16(Int((w.temp * 10).rounded()))
    return d + [UInt8(clamping: w.code), UInt8(clamping: Int(w.wind.rounded())), UInt8(clamping: w.humidity),
                UInt8(clamping: w.ageMin)]
}

/// `1 ‖ day:i8 ‖ min:i8 ‖ max:i8 ‖ code ‖ precip`
public func weatherRecord(_ w: WeatherDay) -> Data {
    Data([1, i8(w.day), i8(Int(w.min.rounded())), i8(Int(w.max.rounded())), UInt8(clamping: w.code),
          UInt8(clamping: w.precip)])
}

/// `code:u16 ‖ length:u16`, then the body in 121-byte chunks; over 2048 bytes is TOO_LARGE.
public func httpRecords(code: Int, body: Data) throws -> [Data] {
    guard body.count <= 2048 else { throw RPCStatus.tooLarge }
    var head = Data()
    head.u16(code)
    head.u16(body.count)
    return [head] + stride(from: 0, to: body.count, by: 121).map {
        Data(body[body.startIndex + $0..<body.startIndex + min($0 + 121, body.count)])
    }
}

/// One `http.request` (0x41) or `http.get` (as GET with no body).
public struct HTTPCall: Equatable, Sendable {
    public static let methods = ["GET", "POST", "PUT", "PATCH", "DELETE"]
    public var method: String, url: URL, body: Data
    public init(method: String, url: URL, body: Data = Data()) { (self.method, self.url, self.body) = (method, url, body) }
    /// The lowercased host; the cap is `http:<host>`.
    public var host: String { url.host?.lowercased() ?? "" }
}

/// An `https://` URL with a host; nil for anything else.
func httpsURL(_ d: Data) -> URL? {
    guard let s = String(data: d, encoding: .utf8), let url = URL(string: s), url.scheme?.lowercased() == "https",
          let h = url.host, !h.isEmpty else { return nil }
    return url
}

/// The joined `http.request` payload, `method:u8 ‖ url_len:u8 ‖ url ‖ body`; nil: unknown method, not https, a
/// url past the end, GET with a body.
public func parseHTTPCall(_ d: Data) -> HTTPCall? {
    let b = [UInt8](d)
    guard b.count >= 2, Int(b[0]) < HTTPCall.methods.count, b.count >= 2 + Int(b[1]),
          let url = httpsURL(Data(b[2..<2 + Int(b[1])])) else { return nil }
    let body = Data(b[(2 + Int(b[1]))...])
    guard b[0] != 0 || body.isEmpty else { return nil }
    return HTTPCall(method: HTTPCall.methods[Int(b[0])], url: url, body: body)
}

/// A header the user set for a watch app's requests to one host.
public struct HTTPHeader: Codable, Hashable, Sendable {
    public var name: String, value: String
    public init(name: String, value: String) { (self.name, self.value) = (name, value) }
}

/// The user's headers, plus `Content-Type: application/json` for a body that starts with `{` or `[` when they set
/// none.
public func requestHeaders(_ user: [HTTPHeader], body: Data) -> [HTTPHeader] {
    let named = user.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
    guard let first = body.first, first == UInt8(ascii: "{") || first == UInt8(ascii: "["),
          !named.contains(where: { $0.name.lowercased() == "content-type" }) else { return named }
    return named + [HTTPHeader(name: "Content-Type", value: "application/json")]
}

/// Joins the parts of `http.request` (`index:u8 ‖ total:u8 ‖ chunk`), one open request per package.
@MainActor public final class RequestParts {
    private var open: [String: (id: UInt8, method: UInt8, total: Int, next: Int, data: Data)] = [:]
    public init() {}

    /// The joined payload after the last part, nil before it. BAD_REQUEST for a part out of order, a new id (or
    /// another multi-part method) before the last part, or total outside 1…9; the open request is dropped then.
    public func add(_ package: String, id: UInt8, method: UInt8 = RPCMethod.httpRequest, args: Data) throws -> Data? {
        let a = [UInt8](args)
        let prev = open.removeValue(forKey: package)
        guard a.count >= 2, a.count - 2 <= 121, (1...9).contains(Int(a[1])) else { throw RPCStatus.badRequest }
        let index = Int(a[0]), total = Int(a[1])
        let data: Data
        if let prev {
            guard prev.id == id, prev.method == method, prev.total == total, index == prev.next else {
                throw RPCStatus.badRequest
            }
            data = prev.data + a[2...]
        } else {
            guard index == 0 else { throw RPCStatus.badRequest }
            data = Data(a[2...])
        }
        if index == total - 1 { return data }
        open[package] = (id, method, total, index + 1, data)
        return nil
    }
}

public struct PhoneNotification: Equatable, Sendable {
    public var ageMin: Int, app: String, text: String
    public init(ageMin: Int, app: String, text: String) { (self.ageMin, self.app, self.text) = (ageMin, app, text) }
}

/// `age:u16 ‖ flags ‖ app_len ‖ app ‖ text`
public func notificationRecord(_ n: PhoneNotification, show: Bool) -> Data {
    let app = utf8Cut(n.app, 24)
    var d = Data()
    d.u16(n.ageMin)
    d += [show ? 0 : 4, UInt8(app.count)]
    return d + app + (show ? utf8Cut(n.text, 64) : Data())
}

// MARK: dispatcher

/// What the phone gives the dispatcher: the user's answers per package and the services. Services throw
/// `RPCStatus.unavailable` (or any error, read the same) when they cannot answer.
@MainActor public protocol RPCHost: AnyObject, Sendable {
    /// cap → allowed; a cap the user has not answered is missing.
    func grants(_ package: String) -> [String: Bool]
    /// The user has not answered: keep the request, tell the user (at most once a day).
    /// `write`: http.request, which can change data on the host (the ask says so).
    func ask(_ package: String, _ cap: String, write: Bool)
    /// The OS permission the cap needs is on.
    func osAllowed(_ cap: String) -> Bool
    /// "Show text on the watch" for this package.
    func showText(_ package: String) -> Bool
    /// false on iOS: no notifications to read.
    var notificationsAvailable: Bool { get }
    func calendar(fromDay: Int, days: Int) async throws -> [CalendarEvent]
    func location() async throws -> LocationFix
    func weather() async throws -> (now: WeatherNow, days: [WeatherDay])
    /// http.get and http.request, with the user's headers and the cookie jar of (package, host).
    func http(_ call: HTTPCall, package: String) async throws -> (code: Int, body: Data)
    /// Parts, stores: one per phone.
    var state: RPCState { get }
    func notifications(limit: Int) async throws -> [PhoneNotification]
}

/// The reply messages to one message from the app in `package`'s slot; nil when it is not a request.
/// `package` nil: the slot could not be read. Everything answers within `deadline`, UNAVAILABLE after it.
public func answer(_ message: Data, package: String?, host: any RPCHost,
                   deadline: Duration = .seconds(7)) async -> [Data]? {
    switch parseRequest(message) {
    case nil: return nil
    case .bad(let id)?: return replies(id: id, status: .badRequest, records: [])
    case .call(let id, let method, let args)?:
        do {
            let records = try await withDeadline(deadline) {
                try await run(id, method, args, package: package, host: host)
            }
            // nil: a part of http.request before its last, no reply
            guard let records else { return [] }
            return replies(id: id, status: .ok, records: records)
        } catch let s as RPCStatus {
            return replies(id: id, status: s, records: [])
        } catch {
            return replies(id: id, status: .unavailable, records: [])
        }
    }
}

@MainActor private func run(_ id: UInt8, _ method: UInt8, _ args: Data, package: String?, host: any RPCHost)
    async throws -> [Data]? {
    let a = [UInt8](args)
    guard let package else { throw RPCStatus.unavailable }
    func permit(_ cap: String) throws {
        switch host.grants(package)[cap] {
        case nil:
            host.ask(package, cap, write: method == RPCMethod.httpRequest)
            throw RPCStatus.ask
        case false?: throw RPCStatus.denied
        case true?: guard host.osAllowed(cap) else { throw RPCStatus.denied }
        }
    }
    switch method {
    case RPCMethod.caps:
        let g = host.grants(package)
        let on = { (cap: String) in g[cap] == true && host.osAllowed(cap) }
        var ids: [UInt8] = []
        if on("calendar") { ids.append(RPCMethod.calendar) }
        if on("location") { ids.append(RPCMethod.location) }
        if on("weather") { ids.append(RPCMethod.weather) }
        if g.contains(where: { $0.key.hasPrefix("http:") && $0.value }) { ids += [RPCMethod.http, RPCMethod.httpRequest] }
        if host.notificationsAvailable, on("notifications") { ids.append(RPCMethod.notifications) }
        ids += [RPCMethod.storageGet, RPCMethod.storagePut, RPCMethod.storageDelete]
        return ids.isEmpty ? [] : [Data(ids)]
    case RPCMethod.calendar:
        guard a.count >= 3 else { throw RPCStatus.badRequest }
        try permit("calendar")
        let from = Int(Int8(bitPattern: a[0])), days = min(max(Int(a[1]), 1), 7), limit = min(max(Int(a[2]), 1), 20)
        let events = try await host.calendar(fromDay: from, days: days)
            .sorted { ($0.day, $0.allDay ? 0 : 1, $0.start) < ($1.day, $1.allDay ? 0 : 1, $1.start) }
        let show = host.showText(package)
        return events.prefix(limit).map { calendarRecord($0, show: show) }
    case RPCMethod.location:
        try permit("location")
        return [locationRecord(try await host.location())]
    case RPCMethod.weather:
        guard let days = a.first else { throw RPCStatus.badRequest }
        try permit("weather")
        let w = try await host.weather()
        return [weatherRecord(w.now)] + w.days.prefix(min(Int(days), 7)).map { weatherRecord($0) }
    case RPCMethod.http:
        guard args.count <= 123, let url = httpsURL(args) else { throw RPCStatus.badRequest }
        let call = HTTPCall(method: "GET", url: url)
        try permit("http:\(call.host)")
        let r = try await host.http(call, package: package)
        return try httpRecords(code: r.code, body: r.body)
    case RPCMethod.httpRequest:
        guard let joined = try host.state.parts.add(package, id: id, method: method, args: args) else { return nil }
        guard let call = parseHTTPCall(joined) else { throw RPCStatus.badRequest }
        try permit("http:\(call.host)")
        let r = try await host.http(call, package: package)
        return try httpRecords(code: r.code, body: r.body)
    case RPCMethod.storageGet:
        let key = try storageKey(args)
        guard let value = host.state.storage.get(package, key) else { return [] }
        return valueRecords(value)
    case RPCMethod.storagePut:
        guard let joined = try host.state.parts.add(package, id: id, method: method, args: args) else { return nil }
        let j = [UInt8](joined)
        guard let n = j.first, j.count >= 1 + Int(n) else { throw RPCStatus.badRequest }
        try host.state.storage.put(package, try storageKey(Data(j[1..<1 + Int(n)])), Data(j[(1 + Int(n))...]))
        return []
    case RPCMethod.storageDelete:
        host.state.storage.delete(package, try storageKey(args))
        return []
    case RPCMethod.notifications:
        guard host.notificationsAvailable else { throw RPCStatus.unavailable }
        guard let limit = a.first else { throw RPCStatus.badRequest }
        try permit("notifications")
        let show = host.showText(package)
        return try await host.notifications(limit: min(max(Int(limit), 1), 10)).map {
            notificationRecord($0, show: show)
        }
    default:
        throw RPCStatus.badRequest
    }
}

/// `op`, or `RPCStatus.unavailable` once `limit` is up; `op` is cancelled then but not waited for (EventKit and
/// CoreLocation do not stop on cancel).
public func withDeadline<T: Sendable>(_ limit: Duration, _ op: @escaping @Sendable () async throws -> T)
    async throws -> T {
    let once = Once<T>()
    return try await withCheckedThrowingContinuation { c in
        once.set(c)
        let work = Task {
            do { once.resume(.success(try await op())) } catch { once.resume(.failure(error)) }
        }
        Task {
            try? await Task.sleep(for: limit)
            work.cancel()
            once.resume(.failure(RPCStatus.unavailable))
        }
    }
}

private final class Once<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var c: CheckedContinuation<T, Error>?

    func set(_ c: CheckedContinuation<T, Error>) { lock.withLock { self.c = c } }

    func resume(_ r: Result<T, Error>) {
        let c = lock.withLock { () -> CheckedContinuation<T, Error>? in
            defer { self.c = nil }
            return self.c
        }
        c?.resume(with: r)
    }
}
