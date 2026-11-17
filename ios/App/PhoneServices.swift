import CoreLocation
import EventKit
import UIKit
import WeatherKit
import WireKit

// The phone's side of the miniapp services (docs/miniapp-rpc.md, "Methods"): EventKit, CoreLocation, WeatherKit,
// URLSession. Grants come from Permissions; the byte layouts and the dispatcher live in WireKit (RPC.swift).

@MainActor final class PhoneServices: NSObject, RPCHost {
    static let shared = PhoneServices()

    private var events = EKEventStore()
    private let locations = CLLocationManager()
    private var fix: CheckedContinuation<Void, Never>?
    /// rounded coordinate → locality
    private var places: [String: String] = [:]
    private var cached: (at: Date, now: CurrentWeather, days: [DayWeather])?

    override init() {
        super.init()
        locations.delegate = self
        locations.desiredAccuracy = kCLLocationAccuracyReduced
    }

    // MARK: grants

    func grants(_ package: String) -> [String: Bool] { Permissions.shared.grants(package) }
    func ask(_ package: String, _ cap: String) { Permissions.shared.ask(package, cap) }
    func showText(_ package: String) -> Bool { Permissions.shared.showText(package) }
    let notificationsAvailable = false

    func osAllowed(_ cap: String) -> Bool {
        switch cap {
        case "calendar": EKEventStore.authorizationStatus(for: .event) == .fullAccess
        case "location", "weather": [.authorizedWhenInUse, .authorizedAlways].contains(locations.authorizationStatus)
        case "notifications": false
        default: cap.hasPrefix("http:")
        }
    }

    /// Allow on the Permissions screen: the OS prompt for what the cap needs.
    func requestOS(_ cap: String) async {
        switch cap {
        case "calendar":
            _ = try? await events.requestFullAccessToEvents()
            events = EKEventStore() // a store made before the grant sees no calendars
        case "location", "weather":
            if locations.authorizationStatus == .notDetermined { locations.requestWhenInUseAuthorization() }
        default: break
        }
    }

    // MARK: services

    func calendar(fromDay: Int, days: Int) async throws -> [CalendarEvent] {
        let cal = Calendar.current, now = Date.now
        let start = cal.date(byAdding: .day, value: fromDay, to: cal.startOfDay(for: now))!
        let end = cal.date(byAdding: .day, value: days, to: start)!
        return events.events(matching: events.predicateForEvents(withStart: start, end: end, calendars: nil)).map { e in
            var (r, g, b) = (CGFloat(0), CGFloat(0), CGFloat(0))
            UIColor(cgColor: e.calendar.cgColor).getRed(&r, green: &g, blue: &b, alpha: nil)
            return CalendarEvent(start: e.startDate, end: e.endDate, allDay: e.isAllDay, now: now, calendar: cal,
                                 tentative: e.status == .tentative || e.availability == .tentative,
                                 color: rgb565(r, g, b), title: e.title ?? "")
        }
    }

    func location() async throws -> LocationFix {
        let loc = try await lastLocation()
        return LocationFix(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                           ageMin: Int(-loc.timestamp.timeIntervalSinceNow / 60), place: await locality(loc))
    }

    func weather() async throws -> (now: WeatherNow, days: [WeatherDay]) {
        if cached.map({ -$0.at.timeIntervalSinceNow > 30 * 60 }) ?? true {
            let loc = try await lastLocation()
            let (now, daily) = try await withDeadline(.seconds(6)) {
                try await WeatherService.shared.weather(for: loc, including: .current, .daily)
            }
            cached = (.now, now, Array(daily))
        }
        guard let cached else { throw RPCStatus.unavailable }
        let cal = Calendar.current, today = cal.startOfDay(for: .now)
        let c = cached.now
        let now = WeatherNow(temp: c.temperature.converted(to: .celsius).value, code: wmo(c.condition),
                             wind: c.wind.speed.converted(to: .metersPerSecond).value,
                             humidity: Int((c.humidity * 100).rounded()),
                             ageMin: Int(-cached.at.timeIntervalSinceNow / 60))
        let days = cached.days.compactMap { d -> WeatherDay? in
            let day = cal.dateComponents([.day], from: today, to: cal.startOfDay(for: d.date)).day ?? -1
            guard day >= 0 else { return nil }
            return WeatherDay(day: day, min: d.lowTemperature.converted(to: .celsius).value,
                              max: d.highTemperature.converted(to: .celsius).value, code: wmo(d.condition),
                              precip: Int((d.precipitationChance * 100).rounded()))
        }
        return (now, days)
    }

    func httpGet(_ url: URL) async throws -> (code: Int, body: Data) {
        try await withDeadline(.seconds(6)) {
            var req = URLRequest(url: url, timeoutInterval: 6)
            req.setValue("FMC-Companion", forHTTPHeaderField: "User-Agent")
            let (bytes, response) = try await Self.http.bytes(for: req, delegate: SameHost())
            var body = Data()
            for try await b in bytes {
                body.append(b)
                if body.count > 2048 { throw RPCStatus.tooLarge }
            }
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, body)
        }
    }

    func notifications(limit: Int) async throws -> [PhoneNotification] { throw RPCStatus.unavailable }

    private static let http: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.timeoutIntervalForRequest = 6
        return URLSession(configuration: c)
    }()

    // MARK: location

    /// The last known fix; a fresh one first when it is over 15 min old and one comes within 3 s (foreground, or
    /// whatever When In Use allows in the background).
    private func lastLocation() async throws -> CLLocation {
        if osAllowed("location"), locations.location.map({ -$0.timestamp.timeIntervalSinceNow > 15 * 60 }) ?? true {
            await refresh(within: .seconds(3))
        }
        guard let loc = locations.location else { throw RPCStatus.unavailable }
        return loc
    }

    /// Called when the app comes to the foreground, too.
    func refresh(within limit: Duration) async {
        guard osAllowed("location"), fix == nil else { return }
        locations.requestLocation()
        let timer = Task { [weak self] in
            try? await Task.sleep(for: limit)
            self?.settle()
        }
        await withCheckedContinuation { fix = $0 }
        timer.cancel()
    }

    private func settle() {
        fix?.resume()
        fix = nil
    }

    /// ponytail: CLGeocoder (deprecated in iOS 26 for MapKit's reverse geocoding, still works); cached per 0.1°.
    private func locality(_ loc: CLLocation) async -> String {
        let key = String(format: "%.1f,%.1f", loc.coordinate.latitude, loc.coordinate.longitude)
        if let p = places[key] { return p }
        let place = (try? await withDeadline(.seconds(2)) {
            try await CLGeocoder().reverseGeocodeLocation(loc).first?.locality ?? ""
        }) ?? ""
        if !place.isEmpty { places[key] = place }
        return place
    }
}

extension PhoneServices: CLLocationManagerDelegate {
    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations l: [CLLocation]) {
        MainActor.assumeIsolated { settle() }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated { settle() }
    }
}

/// Redirects only within the URL's host; a redirect elsewhere answers with its 3xx.
private final class SameHost: NSObject, URLSessionTaskDelegate {
    func urlSession(_ s: URLSession, task: URLSessionTask, willPerformHTTPRedirection r: HTTPURLResponse,
                    newRequest: URLRequest) async -> URLRequest? {
        let from = task.originalRequest?.url
        guard let to = newRequest.url, to.scheme == "https", to.host?.lowercased() == from?.host?.lowercased()
        else { return nil }
        return newRequest
    }
}

/// WeatherKit's condition as the WMO code Open-Meteo gives (the code table in docs/miniapp-rpc.md).
/// ponytail: nearest code by eye; wind-only conditions read as mostly clear.
func wmo(_ c: WeatherCondition) -> Int {
    switch c {
    case .clear, .hot, .frigid: 0
    case .mostlyClear, .breezy, .windy: 1
    case .partlyCloudy: 2
    case .mostlyCloudy, .cloudy: 3
    case .foggy, .haze, .smoky, .blowingDust: 45
    case .drizzle: 53
    case .freezingDrizzle: 56
    case .sunShowers: 80
    case .rain: 63
    case .heavyRain: 65
    case .freezingRain, .sleet, .wintryMix: 67
    case .flurries, .sunFlurries: 71
    case .snow, .blowingSnow: 73
    case .heavySnow, .blizzard: 75
    case .isolatedThunderstorms, .scatteredThunderstorms, .thunderstorms, .tropicalStorm, .hurricane: 95
    case .hail: 96
    case .strongStorms: 99
    @unknown default: 3
    }
}
