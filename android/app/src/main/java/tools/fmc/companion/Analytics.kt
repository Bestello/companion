package tools.fmc.companion

import android.content.Context
import com.aptabase.Aptabase
import com.aptabase.InitOptions

/**
 * Anonymous usage counts, on the self-hosted Aptabase (aptabase.freethinkel.dev, app "FMC Companion"). The whole
 * catalogue of what the app reports, in one place:
 *
 *   app_started
 *   watch_connected     the watch was read                 { firmware: patched | stock }
 *   face_installed      a face went onto the watch         { id }
 *   app_installed       a store app installed or updated   { app }
 *   app_removed         an app deleted from the watch      { app }
 *   loader_installed    the App Loader updated
 *   firmware_installed  the patched firmware was flashed
 *
 * No device id, account, location, calendar or notification text: Aptabase keeps no IP and sets no cookie.
 */
object Analytics {
    fun start(context: Context) {
        Aptabase.instance.initialize(context.applicationContext, "A-SH-3280674646", InitOptions(host = "https://aptabase.freethinkel.dev"))
        track("app_started")
    }

    fun track(name: String, props: Map<String, String> = emptyMap()) = Aptabase.instance.trackEvent(name, props)
}
