package tools.fmc.companion

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.net.URLEncoder
import org.json.JSONObject
import tools.fmc.companion.wire.LoaderState
import tools.fmc.companion.wire.PackageHeader
import androidx.compose.material3.Badge as CountPill

// The apps on the watch and what each may use on the phone (docs/miniapp-rpc.md, "Permissions"): a list of apps,
// and per app "Allow <app> to access" with a switch per permission.

private const val NEARBY = "Calendar titles and notification text. Anyone nearby with a Bluetooth scanner could read " +
    "what goes to the watch, location included (rounded to about 10 km)."

/** An app on the watch, or one that has answers on the phone but is not on the watch now. */
class WatchApp(val name: String, val header: PackageHeader? = null, val slot: Int? = null) {
    /** The store's name for it, else the package name. */
    val title get() = StoreApps.listing(name)?.name ?: name
    val detail get() = if (header != null && slot != null) "Version ${header.version} · slot $slot" else "Not on the watch"

    companion object {
        fun all(loader: LoaderState?, grants: Grants): List<WatchApp> {
            val installed = (loader?.apps ?: emptyMap()).toSortedMap().map { (slot, h) -> WatchApp(h.name, h, slot) }
            val names = installed.map { it.name }.toSet()
            return installed + grants.packages().filter { it !in names }.map { WatchApp(it) }
        }
    }
}

/** The FMC store's listing of watch apps (fmc.tools `apps`), matched to a package by slug or name. Loaded once per launch. */
object StoreApps {
    class Listing(val name: String, val icon: String?)

    private var byKey by mutableStateOf<Map<String, Listing>>(emptyMap())
    private var loaded = false

    fun listing(pkg: String) = byKey[pkg.lowercase()]

    suspend fun load() {
        if (loaded) return
        val filter = URLEncoder.encode("published = true", "UTF-8")
        val body = runCatching {
            http("GET", "$FMC/api/collections/apps/records?filter=$filter&perPage=200&fields=id,collectionId,slug,name,icon")
        }.getOrNull() ?: return
        byKey = parse(body)
        loaded = true
    }

    /** slug and name, lowercased → listing. */
    fun parse(json: String): Map<String, Listing> {
        val items = JSONObject(json).getJSONArray("items")
        val out = mutableMapOf<String, Listing>()
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            val icon = r.optString("icon")
            // ponytail: BitmapFactory has no SVG decoder, those apps keep the letter tile
            val url = if (icon.isEmpty() || icon.endsWith(".svg", ignoreCase = true)) null
                else "$FMC/api/files/${r.getString("collectionId")}/${r.getString("id")}/$icon"
            val l = Listing(r.getString("name"), url)
            out[r.getString("slug").lowercase()] = l
            out[r.getString("name").lowercase()] = l
        }
        return out
    }
}

/** Grants, recomposing the caller when the service writes an ask or the user comes back from system settings. */
@Composable
fun liveGrants(): Grants {
    val context = LocalContext.current
    val grants = remember { Grants(context) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(grants) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        grants.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { grants.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose {} }
    @Suppress("UNUSED_EXPRESSION") tick // read: a non-Unit composable recomposes its caller
    return grants
}

/** A watch app's icon, always a circle: its store icon, or its first letter in white on grey. */
@Composable
fun AppIcon(name: String, side: Dp = 40.dp) {
    val bitmap = rememberImage(StoreApps.listing(name)?.icon)
    // systemGray2 behind the letter, as on iOS; nothing behind a store icon
    Surface(Modifier.size(side), shape = CircleShape, color = if (bitmap == null) Color(0xFF8E8E93) else Color.Transparent,
        contentColor = Color.White) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, null, Modifier.size(side), contentScale = ContentScale.Crop)
            else Text(name.take(1).uppercase(), fontSize = (side.value * 0.45f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A white symbol on a coloured circle, as Android's settings rows show them. */
@Composable
fun IconTile(icon: ImageVector, color: Color) = IconTile(rememberVectorPainter(icon), color)

@Composable
fun IconTile(painter: androidx.compose.ui.graphics.painter.Painter, color: Color) =
    Surface(Modifier.size(36.dp), shape = CircleShape, color = color, contentColor = Color.White) {
        Box(contentAlignment = Alignment.Center) { Icon(painter, null, Modifier.size(20.dp)) }
    }

/** A tappable row: leading tile, title (and a line under it), the count of unanswered asks, the chevron. */
@Composable
fun NavRow(title: String, detail: String? = null, count: Int = 0, onClick: () -> Unit, leading: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fmc.secondary) }
        }
        if (count > 0) CountPill { Text("$count") }
        Text("›", color = Fmc.secondary)
    }

@Composable
fun AppsScreen(model: AppModel, open: (String) -> Unit, back: () -> Unit) {
    val grants = liveGrants()
    LaunchedEffect(Unit) { StoreApps.load() }
    val apps = WatchApp.all(model.loader, grants)
    Column {
        Bar("Apps", back)
        LazyColumn {
            val (on, off) = apps.partition { it.slot != null }
            fun group(title: String, list: List<WatchApp>, footer: String? = null) = item(title) {
                Section(title, footer) {
                    list.forEachIndexed { i, app ->
                        if (i > 0) HorizontalDivider()
                        NavRow(app.title, app.detail, grants.pendingCount(app.name), { open(app.name) }) { AppIcon(app.name) }
                    }
                }
            }
            if (on.isNotEmpty()) group("On the watch", on)
            if (off.isNotEmpty()) {
                if (model.loader == null) group("Asked for permissions", off, "Connect on the Watch tab to see every app on the watch.")
                else group("Not on the watch", off)
            }
            if (apps.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Connect on the Watch tab to see the apps on the watch.", color = Fmc.secondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun AppScreen(model: AppModel, name: String, back: () -> Unit) {
    val context = LocalContext.current
    val grants = liveGrants()
    LaunchedEffect(Unit) { StoreApps.load() }
    var resetting by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        RpcService.start(context) // location adds its foreground service type
    }
    fun askOs(cap: String) {
        if (osAllows(context, cap)) return
        osPermission(cap)?.let { return ask.launch(it) }
        if (cap == "notifications") context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationWatcher::class.java).flattenToString()))
    }
    fun openSettings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    val app = WatchApp.all(model.loader, grants).firstOrNull { it.name == name } ?: WatchApp(name)
    val caps = grants.caps(name)
    val allowed = grants.of(name)
    val offInOs = caps.filter { allowed[it] == true && !osAllows(context, it) }
    Column {
        Bar(app.title, back)
        LazyColumn {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIcon(name, 72.dp)
                    Text(app.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(app.detail, color = Fmc.secondary)
                }
            }
            if (caps.isEmpty()) item {
                Section { Text("${app.title} hasn't asked for anything on your phone.", color = Fmc.secondary) }
            } else {
                item {
                    Section("Allow ${app.title} to access",
                        footer = if (offInOs.isEmpty()) null else "Some of these are off for FMC Companion in Android settings.") {
                        caps.forEachIndexed { i, cap ->
                            if (i > 0) HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                CapTile(cap)
                                Column(Modifier.weight(1f)) {
                                    Text(capTitle(cap))
                                    when {
                                        grants.isPending(name, cap) -> Text("Waiting for your answer",
                                            style = MaterialTheme.typography.bodyMedium, color = Fmc.warning)
                                        cap in offInOs -> Text("Off in system settings. Tap to turn on.",
                                            Modifier.clickable { askOs(cap) }, style = MaterialTheme.typography.bodyMedium,
                                            color = Fmc.warning)
                                    }
                                }
                                Switch(allowed[cap] == true, { on ->
                                    grants.set(name, cap, on)
                                    if (on) askOs(cap)
                                })
                            }
                        }
                        if (offInOs.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Open app settings", Modifier.fillMaxWidth().clickable { openSettings() })
                        }
                    }
                }
                item {
                    Section(footer = NEARBY) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconTile(Icons.Default.Email, Color(0xFF757575))
                            Text("Show text on the watch", Modifier.weight(1f))
                            Switch(grants.showText(name), { grants.setShowText(name, it) })
                        }
                    }
                }
                item {
                    Section(footer = "${app.title} asks again the next time it needs something.") {
                        Text("Reset permissions", Modifier.fillMaxWidth().clickable { resetting = true }, color = Fmc.danger)
                    }
                }
            }
        }
    }
    if (resetting) AlertDialog(
        onDismissRequest = { resetting = false },
        title = { Text("Reset permissions for $name?") },
        text = { Text("${app.title} asks again the next time it needs something.") },
        confirmButton = { TextButton({ resetting = false; grants.reset(name) }) { Text("Reset", color = Fmc.danger) } },
        dismissButton = { TextButton({ resetting = false }) { Text("Cancel") } },
    )
}

fun capTitle(cap: String) = when (cap) {
    "calendar" -> "Calendar"
    "location" -> "Location"
    "weather" -> "Weather"
    "notifications" -> "Notifications"
    else -> cap.removePrefix("http:")
}

@Composable
private fun CapTile(cap: String) = when (cap) {
    "calendar" -> IconTile(Icons.Default.DateRange, Color(0xFFE53935))
    "location" -> IconTile(Icons.Default.LocationOn, Color(0xFF1E88E5))
    "weather" -> IconTile(painterResource(R.drawable.ic_cloud), Color(0xFF00ACC1))
    "notifications" -> IconTile(Icons.Default.Notifications, Color(0xFFE53935))
    else -> IconTile(painterResource(R.drawable.ic_public), Color(0xFF757575))
}
