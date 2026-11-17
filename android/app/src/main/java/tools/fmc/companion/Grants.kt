package tools.fmc.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// Who may call which miniapp service (docs/miniapp-rpc.md, "Permissions"). Keyed by the package name in the slot's
// header. SharedPreferences "grants": "g|pkg|cap" = allowed, "p|pkg|cap" = asked at (ms, pending until answered),
// "show|pkg" = text on the watch.

class Grants(context: Context) {
    val prefs = context.getSharedPreferences("grants", Context.MODE_PRIVATE)!!

    private fun parts(prefix: String) = prefs.all.keys.filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).split("|", limit = 2) }.filter { it.size == 2 }.map { it[0] to it[1] }

    fun of(pkg: String): Map<String, Boolean> =
        parts("g|").filter { it.first == pkg }.associate { it.second to prefs.getBoolean("g|$pkg|${it.second}", false) }

    /** pkg → cap → allowed, every package that has answered caps. */
    fun all(): Map<String, Map<String, Boolean>> =
        parts("g|").map { it.first }.distinct().sorted().associateWith(::of)

    /** (pkg, cap) asked and not answered yet. */
    fun pending(): List<Pair<String, String>> = parts("p|").filter { it.second !in of(it.first) }

    fun set(pkg: String, cap: String, allow: Boolean) =
        prefs.edit().putBoolean("g|$pkg|$cap", allow).remove("p|$pkg|$cap").apply()

    /** true when the pair was not asked in the last day: the caller tells the user. */
    fun markAsked(pkg: String, cap: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("p|$pkg|$cap", 0) < 24 * 3600_000L) return false
        prefs.edit().putLong("p|$pkg|$cap", now).apply()
        return true
    }

    fun showText(pkg: String) = prefs.getBoolean("show|$pkg", false)
    fun setShowText(pkg: String, on: Boolean) = prefs.edit().putBoolean("show|$pkg", on).apply()
}

fun capLabel(cap: String) = when (cap) {
    "calendar" -> "your calendar"
    "location" -> "your location"
    "weather" -> "the weather where you are"
    "notifications" -> "your notifications"
    else -> "data from ${cap.removePrefix("http:")}"
}

/** The Android permission a cap needs; notifications and http have none to ask with a dialog. */
fun osPermission(cap: String) = when (cap) {
    "calendar" -> Manifest.permission.READ_CALENDAR
    "location", "weather" -> Manifest.permission.ACCESS_COARSE_LOCATION
    else -> null
}

fun osAllows(context: Context, cap: String): Boolean = when {
    cap == "notifications" -> context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    else -> osPermission(cap)?.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ?: true
}

fun channel(context: Context, id: String, name: String, importance: Int) =
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, name, importance))

/** Opens the Permissions screen. */
fun permissionsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 1, Intent(context, MainActivity::class.java).putExtra("permissions", true)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** "‹package› wants ‹cap›"; nothing without POST_NOTIFICATIONS (the Permissions screen still lists it). */
fun notifyAsk(context: Context, pkg: String, cap: String) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    channel(context, "ask", "Watch app requests", NotificationManager.IMPORTANCE_DEFAULT)
    val n = NotificationCompat.Builder(context, "ask")
        .setSmallIcon(R.drawable.ic_watch)
        .setContentTitle("$pkg wants ${capLabel(cap)}")
        .setContentText("Allow or deny it in FMC Companion.")
        .setContentIntent(permissionsIntent(context))
        .setAutoCancel(true)
        .build()
    context.getSystemService(NotificationManager::class.java).notify("$pkg|$cap".hashCode(), n)
}
