import Aptabase

// Anonymous usage counts, on the self-hosted Aptabase (aptabase.freethinkel.dev, app "FMC Companion"). The whole
// catalogue of what the app reports, in one place:
//
//   app_started
//   watch_connected     the watch was read                 { firmware: patched | stock }
//   face_installed      a face went onto the watch         { id }
//   app_installed       a store app installed or updated   { app }
//   app_removed         an app deleted from the watch      { app }
//   loader_installed    the App Loader updated
//   firmware_installed  the patched firmware was flashed
//
// No device id, account, location, calendar or notification text: Aptabase keeps no IP and sets no cookie.
enum Analytics {
    static func start() {
        Aptabase.shared.initialize(appKey: "A-SH-3280674646", with: InitOptions(host: "https://aptabase.freethinkel.dev"))
        track("app_started")
    }

    static func track(_ name: String, _ props: [String: String] = [:]) {
        Aptabase.shared.trackEvent(name, with: props)
    }
}
