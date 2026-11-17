import Foundation
import Testing
@testable import WireKit

/// rpc-vectors.json: the byte layouts of docs/miniapp-rpc.md, shared with Android and the watch apps.
struct RPCVectors: Decodable {
    struct Req: Decodable { let hex: String; let id: UInt8?; let method: UInt8?; let args: String?; let rpc: Bool?; let bad: Bool? }
    struct Cut: Decodable { let text: String; let max: Int; let hex: String }
    struct Cal: Decodable {
        struct In: Decodable {
            let day: Int, start: Int, duration: Int, all_day: Bool, tentative: Bool, color: UInt16, title: String
            let show: Bool
        }
        let `in`: In; let hex: String
    }
    struct CalDate: Decodable {
        let tz: String, now: String, start: String, end: String, day: Int, start_min: Int, duration: Int
    }
    struct Loc: Decodable {
        struct In: Decodable { let lat: Double, lon: Double, age_min: Int, place: String }
        let `in`: In; let hex: String
    }
    struct Weather: Decodable {
        struct In: Decodable {
            let kind: String; let temp: Double?; let code: Int; let wind: Double?; let humidity: Int?; let age_min: Int?
            let day: Int?; let min: Double?; let max: Double?; let precip: Int?
        }
        let `in`: In; let hex: String
    }
    struct Http: Decodable {
        struct In: Decodable { let code: Int; let body: String }
        let `in`: In; let records: [String]
    }
    struct Notif: Decodable {
        struct In: Decodable { let age_min: Int, app: String, text: String, show: Bool }
        let `in`: In; let hex: String
    }
    struct Reply: Decodable { let id: UInt8; let status: UInt8; let records: [String]; let messages: [String] }
    let requests: [Req]
    let utf8_cut: [Cut]
    let calendar: [Cal]
    let calendar_dates: [CalDate]
    let location: [Loc]
    let weather: [Weather]
    let http: [Http]
    let notifications: [Notif]
    let replies: [Reply]

    static let shared: RPCVectors = {
        let url = Bundle.module.url(forResource: "rpc-vectors", withExtension: "json")!
        return try! JSONDecoder().decode(RPCVectors.self, from: Data(contentsOf: url))
    }()
}

private let rv = RPCVectors.shared

@Test func rpcRequests() {
    for r in rv.requests {
        let got = parseRequest(hex(r.hex))
        if r.rpc == false {
            #expect(got == nil, "\(r.hex)")
        } else if r.bad == true {
            #expect(got == .bad(id: hex(r.hex)[3]), "\(r.hex)")
        } else {
            #expect(got == .call(id: r.id!, method: r.method!, args: hex(r.args!)), "\(r.hex)")
        }
    }
}

@Test func rpcUtf8Cut() {
    for c in rv.utf8_cut { #expect(utf8Cut(c.text, c.max) == hex(c.hex), "\(c.text)") }
    // not in the vectors: a text of exactly max bytes is cut too (Android does the same; open question in the spec)
    #expect(utf8Cut(String(repeating: "a", count: 64), 64).count == 63)
}

@Test func rpcCalendarRecords() {
    for c in rv.calendar {
        let i = c.in
        let e = CalendarEvent(day: i.day, start: i.start, duration: i.duration, allDay: i.all_day,
                              tentative: i.tentative, color: i.color, title: i.title)
        #expect(calendarRecord(e, show: i.show) == hex(c.hex), "\(i.title)")
    }
}

@Test func rpcCalendarDates() {
    for c in rv.calendar_dates {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: c.tz)!
        let f = DateFormatter()
        f.calendar = cal
        f.timeZone = cal.timeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        let e = CalendarEvent(start: f.date(from: c.start)!, end: f.date(from: c.end)!, allDay: false,
                              now: f.date(from: c.now)!, calendar: cal, tentative: false, color: 0, title: "")
        #expect([e.day, e.start, e.duration] as [Int] == [c.day, c.start_min, c.duration], "\(c.tz) \(c.start)")
    }
}

@Test func rpcAllDaySpansDays() {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
    let start = cal.date(from: DateComponents(year: 2026, month: 3, day: 28))!
    // EventKit's end of a 2-day all-day event over the DST change: 23:59:59 of the second day
    let end = cal.date(from: DateComponents(year: 2026, month: 3, day: 29, hour: 23, minute: 59, second: 59))!
    let e = CalendarEvent(start: start, end: end, allDay: true, now: start, calendar: cal, tentative: false, color: 0,
                          title: "")
    #expect([e.day, e.start, e.duration] as [Int] == [0, 0, 2880])
}

@Test func rpcLocationWeatherNotifications() {
    for l in rv.location {
        #expect(locationRecord(LocationFix(lat: l.in.lat, lon: l.in.lon, ageMin: l.in.age_min, place: l.in.place))
            == hex(l.hex))
    }
    for w in rv.weather {
        let i = w.in
        let got = i.kind == "now"
            ? weatherRecord(WeatherNow(temp: i.temp!, code: i.code, wind: i.wind!, humidity: i.humidity!,
                                       ageMin: i.age_min!))
            : weatherRecord(WeatherDay(day: i.day!, min: i.min!, max: i.max!, code: i.code, precip: i.precip!))
        #expect(got == hex(w.hex), "\(i.kind)")
    }
    for n in rv.notifications {
        let i = n.in
        #expect(notificationRecord(PhoneNotification(ageMin: i.age_min, app: i.app, text: i.text), show: i.show)
            == hex(n.hex))
    }
}

@Test func rpcHttpRecords() throws {
    for h in rv.http {
        #expect(try httpRecords(code: h.in.code, body: hex(h.in.body)) == h.records.map(hex))
    }
    #expect(throws: RPCStatus.tooLarge) { try httpRecords(code: 200, body: Data(count: 2049)) }
}

@Test func rpcReplies() {
    for r in rv.replies {
        #expect(replies(id: r.id, status: RPCStatus(rawValue: r.status)!, records: r.records.map(hex))
            == r.messages.map(hex))
    }
}

// MARK: dispatcher

@MainActor final class FakeHost: RPCHost {
    var given: [String: [String: Bool]] = [:]
    var asked: [String] = []
    var osOff: Set<String> = []
    var show = false
    var notificationsAvailable = false
    var events: [CalendarEvent] = []
    var slow = false

    func grants(_ package: String) -> [String: Bool] { given[package] ?? [:] }
    func ask(_ package: String, _ cap: String) { asked.append("\(package) \(cap)") }
    func osAllowed(_ cap: String) -> Bool { !osOff.contains(cap) }
    func showText(_ package: String) -> Bool { show }
    func calendar(fromDay: Int, days: Int) async throws -> [CalendarEvent] {
        if slow { try? await Task.sleep(for: .seconds(5)) }
        return events
    }
    func location() async throws -> LocationFix { throw RPCStatus.unavailable }
    func weather() async throws -> (now: WeatherNow, days: [WeatherDay]) {
        (WeatherNow(temp: 1, code: 0, wind: 1, humidity: 50, ageMin: 0),
         (0..<10).map { WeatherDay(day: $0, min: 0, max: 1, code: 0, precip: 0) })
    }
    func httpGet(_ url: URL) async throws -> (code: Int, body: Data) { (200, Data("ok".utf8)) }
    func notifications(limit: Int) async throws -> [PhoneNotification] { [] }
}

private func call(_ method: UInt8, _ args: [UInt8] = [], id: UInt8 = 1) -> Data {
    Data("R1Q".utf8) + [id, method] + args
}

/// (status, records) of the replies.
private func ask(_ host: FakeHost, _ m: Data, package: String? = "weather.app",
                 deadline: Duration = .seconds(7)) async -> (RPCStatus, [Data])? {
    guard let r = await answer(m, package: package, host: host, deadline: deadline) else { return nil }
    return (RPCStatus(rawValue: r[0][4])!, r[0][7...].isEmpty && r[0][6] == 0 ? [] : r.map { Data($0.dropFirst(7)) })
}

@Test @MainActor func rpcAsksDeniesAndRuns() async throws {
    let h = FakeHost()
    let cal = call(RPCMethod.calendar, [0, 1, 5])
    #expect(await ask(h, cal)?.0 == .ask)
    #expect(h.asked == ["weather.app calendar"])
    #expect(await ask(h, call(RPCMethod.caps))?.1 == [])

    h.given["weather.app"] = ["calendar": false]
    #expect(await ask(h, cal)?.0 == .denied)
    #expect(h.asked.count == 1, "no new notification once answered")

    h.given["weather.app"] = ["calendar": true, "http:example.com": true]
    h.osOff = ["calendar"]
    #expect(await ask(h, cal)?.0 == .denied, "OS permission off")
    #expect(await ask(h, call(RPCMethod.caps))?.1 == [Data([RPCMethod.http])])

    h.osOff = []
    h.events = [
        CalendarEvent(day: 1, start: 0, duration: 1440, allDay: true, tentative: false, color: 0, title: "b"),
        CalendarEvent(day: 0, start: 600, duration: 30, allDay: false, tentative: false, color: 0, title: "a"),
        CalendarEvent(day: 1, start: 60, duration: 30, allDay: false, tentative: false, color: 0, title: "c"),
    ]
    let (s, records) = try #require(await ask(h, call(RPCMethod.calendar, [0, 9, 2])))
    #expect(s == .ok)
    #expect(records.map { $0[0] } == [0, 1], "sorted, all-day first in a day, cut to limit")
    #expect(records.allSatisfy { $0[5] & 4 != 0 && $0.count == 8 }, "text hidden by default")
    #expect(await ask(h, call(RPCMethod.caps))?.1 == [Data([RPCMethod.calendar, RPCMethod.http])])
}

@Test @MainActor func rpcBadRequests() async {
    let h = FakeHost()
    #expect(await ask(h, call(0x7F))?.0 == .badRequest)
    #expect(await ask(h, call(RPCMethod.calendar, [0]))?.0 == .badRequest)
    #expect(await ask(h, call(RPCMethod.http, Array("http://example.com/".utf8)))?.0 == .badRequest)
    #expect(await ask(h, Data("R1Q".utf8) + [3])?.0 == .badRequest)
    #expect(await ask(h, Data("R1P\u{1}\u{0}".utf8)) == nil)
    #expect(await ask(h, Data("hello".utf8)) == nil)
    #expect(await ask(h, call(RPCMethod.notifications, [3]))?.0 == .unavailable, "iOS has none")
    #expect(h.asked.isEmpty)
    #expect(await ask(h, call(RPCMethod.caps), package: nil)?.0 == .unavailable, "slot not read")
}

@Test @MainActor func rpcHttpWeatherAndDeadline() async throws {
    let h = FakeHost()
    let get = call(RPCMethod.http, Array("https://Example.com/a?b=1".utf8))
    #expect(await ask(h, get)?.0 == .ask)
    #expect(h.asked == ["weather.app http:example.com"])
    h.given["weather.app"] = ["http:example.com": true, "weather": true, "calendar": true]
    #expect(await ask(h, get)?.1 == [Data([200, 0, 2, 0]), Data("ok".utf8)])
    #expect(await ask(h, call(RPCMethod.weather, [3]))?.1.map { $0[0] } == [0, 1, 1, 1])
    #expect(await ask(h, call(RPCMethod.weather, [99]))?.1.count == 8, "days clamped to 7")
    h.slow = true
    #expect(await ask(h, call(RPCMethod.calendar, [0, 1, 1]), deadline: .milliseconds(200))?.0 == .unavailable)
}
