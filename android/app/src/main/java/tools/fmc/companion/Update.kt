package tools.fmc.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Updates without a store: the newest GitHub release of fmctools/companion with an asset named
 * `fmc-companion-<versionCode>.apk` (`make android-release` publishes one). The system asks the user before installing,
 * and installs over this app only when the APK is signed with the same key.
 */
object AppUpdate {
    class Release(val code: Int, val name: String, val apk: String)

    var available by mutableStateOf<Release?>(null)
        private set
    /** 0…1 while downloading, null otherwise. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val asset = Regex("""fmc-companion-(\d+)\.apk""")

    /** Debug builds are signed with a per-machine key and can't take a release APK, so they skip the check. */
    suspend fun check() {
        if (BuildConfig.DEBUG) return
        // ponytail: the last 10 releases, unauthenticated (60 requests an hour per IP), once per launch
        val releases = runCatching { JSONArray(http("GET", "https://api.github.com/repos/fmctools/companion/releases?per_page=10")) }
            .getOrNull() ?: return
        available = (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .flatMap { r ->
                val assets = r.getJSONArray("assets")
                (0 until assets.length()).map { assets.getJSONObject(it) }.mapNotNull { a ->
                    asset.matchEntire(a.getString("name"))?.let {
                        Release(it.groupValues[1].toInt(), r.optString("name").ifEmpty { r.getString("tag_name") }, a.getString("browser_download_url"))
                    }
                }
            }
            .maxByOrNull { it.code }
            ?.takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    suspend fun install(context: Context) {
        val release = available ?: return
        error = null
        progress = 0f
        try {
            val apk = download(release.apk)
            progress = 1f
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("app.apk", 0, apk.size.toLong()).use { it.write(apk); s.fsync(it) }
                    listen(context)
                    val intent = Intent(ACTION).setPackage(context.packageName)
                    s.commit(PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
                }
            }
        } catch (e: Exception) {
            error = e.message ?: str(R.string.update_download_failed)
        } finally {
            progress = null
        }
    }

    private const val ACTION = "tools.fmc.companion.UPDATE"
    private var listening = false

    /** The installer's answers: the "Update this app?" dialog to show, or why it failed. */
    private fun listen(context: Context) {
        if (listening) return
        listening = true
        context.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    PackageInstaller.STATUS_SUCCESS -> {}
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {}
                    else -> error = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: str(R.string.update_install_failed)
                }
            }
        }, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
    }
}
