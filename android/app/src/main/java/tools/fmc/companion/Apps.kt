package tools.fmc.companion

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
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
import tools.fmc.companion.wire.parseHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Badge as CountPill

// The apps on the watch and what each may use on the phone (docs/miniapp-rpc.md, "Permissions"): a list of apps,
// and per app "Allow <app> to access" with a switch per permission.

private const val NEARBY = "Anyone nearby with a Bluetooth scanner could read " +
    "what goes to the watch, location included (rounded to about 10 km)."

/** An app on the watch, or one that has answers on the phone but is not on the watch now. */
class WatchApp(val name: String, val header: PackageHeader? = null, val slot: Int? = null) {
    /** The store's name for it, else the package name. */
    val title get() = StoreApps.listing(name)?.name ?: name
    val detail get() = when {
        header == null || slot == null -> "Not on the watch"
        update != null -> "Version ${versionText(header.version)} → ${versionText(update!!.version)} · slot $slot"
        else -> "Version ${versionText(header.version)} · slot $slot"
    }
    /** The store's build when it's newer than the one on the watch. */
    val update get() = header?.let { h -> StoreApps.latest(name)?.takeIf { it.version > h.version } }

    companion object {
        fun all(loader: LoaderState?, grants: Grants): List<WatchApp> {
            val installed = (loader?.apps ?: emptyMap()).toSortedMap().map { (slot, h) -> WatchApp(h.name, h, slot) }
            val names = installed.map { it.name }.toSet()
            return installed + grants.packages().filter { it !in names }.map { WatchApp(it) }
        }

        /** Every app in the FMC store, the ones on the watch with their version and slot, then the installed and asked ones the store doesn't list. */
        fun store(loader: LoaderState?, grants: Grants): List<WatchApp> = StoreApps.all.map { l ->
            loader?.apps?.entries?.firstOrNull { StoreApps.listing(it.value.name)?.slug == l.slug }
                ?.let { WatchApp(it.value.name, it.value, it.key) } ?: WatchApp(l.slug)
        } + all(loader, grants).filter { StoreApps.listing(it.name) == null }
    }
}

/** The FMC store's listing of watch apps (fmc.tools `apps`), matched to a package by slug or name. Loaded once per launch. */
/** The header's u32 version as the store's MAJOR.MINOR.PATCH (apps/scripts/store build_number), else the number. */
fun versionText(v: Long) = if (v >= 1024) "${v shr 20}.${v shr 10 and 1023}.${v and 1023}" else "$v"

object StoreApps {
    class Listing(val id: String, val slug: String, val name: String, val icon: String?, val pkg: String?,
                  val summary: String = "", val description: String = "", val author: String = "", val version: String = "",
                  val abi: String = "", val source: String = "", val downloads: Int = 0, val screenshots: List<String> = emptyList()) {
        val page get() = "$FMC/apps/$id"
        /** The download hook: counts the install as a download and redirects to the package. */
        val download get() = "$FMC/api/apps/$id/download"
    }

    private var byKey by mutableStateOf<Map<String, Listing>>(emptyMap())
    /** slug → the store package's header. Its u32 version, not the listing's text: SDK examples don't encode it. */
    private var headers by mutableStateOf<Map<String, PackageHeader>>(emptyMap())

    /** By name, for the All segment. */
    val all get() = byKey.values.distinct().sortedBy { it.name.lowercase() }
    var loaded by mutableStateOf(false)
        private set

    fun listing(pkg: String) = byKey[pkg.lowercase()]
    fun latest(pkg: String) = listing(pkg)?.let { headers[it.slug] }

    suspend fun load() {
        if (loaded) return
        val filter = URLEncoder.encode("published = true", "UTF-8")
        val body = runCatching {
            http("GET", "$FMC/api/collections/apps/records?filter=$filter&perPage=200&fields=id,collectionId,slug,name,icon,package,summary,description,author,version,abi,source,downloads,screenshots")
        }.getOrNull() ?: return
        byKey = parse(body)
        loaded = true
        // ponytail: one 64-byte request per store app, a few dozen at most; a version field with the header's u32 on
        // the store record would make this one request
        headers = coroutineScope {
            all.map { l -> async { l.pkg?.let { runCatching { parseHeader(download(it, 64)) }.getOrNull() }?.let { l.slug to it } } }
                .awaitAll().filterNotNull().toMap()
        }
    }

    /** slug and name, lowercased → listing. */
    fun parse(json: String): Map<String, Listing> {
        val items = JSONObject(json).getJSONArray("items")
        val out = mutableMapOf<String, Listing>()
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            val icon = r.optString("icon")
            // ponytail: BitmapFactory has no SVG decoder, those apps keep the letter tile
            val files = "$FMC/api/files/${r.getString("collectionId")}/${r.getString("id")}"
            val url = if (icon.isEmpty() || icon.endsWith(".svg", ignoreCase = true)) null else "$files/$icon"
            val pkg = r.optString("package").takeIf { it.isNotEmpty() }?.let { "$files/$it" }
            val shots = r.optJSONArray("screenshots")?.let { a -> (0 until a.length()).map { "$files/${a.getString(it)}" } }.orEmpty()
            val l = Listing(r.getString("id"), r.getString("slug"), r.getString("name"), url, pkg, r.optString("summary"),
                r.optString("description"), r.optString("author"), r.optString("version"), r.optString("abi"),
                r.optString("source"), r.optInt("downloads"), shots)
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
fun NavRow(title: String, detail: String? = null, count: Int = 0, onClick: () -> Unit,
           trailing: (@Composable () -> Unit)? = null, leading: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fmc.secondary) }
        }
        if (count > 0) CountPill { Text("$count") }
        trailing?.invoke()
        Text("›", color = Fmc.secondary)
    }

@Composable
fun AppsScreen(model: AppModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val grants = liveGrants()
    LaunchedEffect(Unit) { StoreApps.load() }
    // the list of installed apps is the watch's: read it here too, not only from Connect on the Watch tab
    // ponytail: only when Bluetooth is already allowed; the Watch tab's Connect asks for it
    LaunchedEffect(Unit) {
        val bt = listOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
            .all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (bt && model.phase == Phase.Idle) model.check()
    }
    var showAll by remember { mutableStateOf(false) }
    // Installed is the watch's list: nothing until it is read, not the apps that only asked for something
    val loader = model.listedLoader
    val apps = if (showAll) WatchApp.store(loader, grants) else if (loader == null) emptyList() else WatchApp.all(loader, grants)
    // first load: placeholder rows; a refresh keeps the rows it has
    val loading = apps.isEmpty() && if (showAll) !StoreApps.loaded else model.isChecking
    Column {
        Bar("Apps")
        LazyColumn {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    listOf("Installed", "All").forEachIndexed { i, label ->
                        SegmentedButton(showAll == (i == 1), { showAll = i == 1 },
                            SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
            }
            val (on, off) = apps.partition { it.slot != null }
            fun group(title: String, list: List<WatchApp>, footer: String? = null) = item(title) {
                Section(title, footer) {
                    list.forEachIndexed { i, app ->
                        if (i > 0) HorizontalDivider()
                        NavRow(app.title, app.detail, grants.pendingCount(app.name), { open(app.name) },
                            trailing = if (app.update != null || (app.slot == null && StoreApps.listing(app.name)?.pkg != null)) {{ GetButton(model, app) }}
                                else if (showAll && app.slot != null) {{ Text("Installed", color = Fmc.secondary, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall) }} else null) { AppIcon(app.name) }
                    }
                }
            }
            val (updates, current) = on.partition { it.update != null }
            if (showAll) { if (apps.isNotEmpty()) group("FMC store", apps, if (model.listedLoader == null) "Connect on the Watch tab to install apps." else null) }
            else {
                if (updates.isNotEmpty()) group("Updates", updates, "Updates install over Bluetooth. Keep the app closed on the watch meanwhile.")
                if (current.isNotEmpty()) group("On the watch", current)
            }
            if (!showAll && off.isNotEmpty()) {
                if (model.listedLoader == null) group("Asked for permissions", off, "Connect on the Watch tab to see every app on the watch.")
                else group("Not on the watch", off)
            }
            if (loading) item {
                Section {
                    repeat(6) { i ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val tone = Fmc.secondary.copy(alpha = 0.15f)
                            Box(Modifier.size(40.dp).clip(CircleShape).background(tone))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.width(140.dp).height(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(tone))
                                Box(Modifier.width(100.dp).height(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(tone))
                            }
                        }
                    }
                }
            } else if (apps.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (showAll) "Couldn't load the FMC store." else "Connect on the Watch tab to see the apps on the watch.",
                        color = Fmc.secondary, textAlign = TextAlign.Center)
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

    val app = WatchApp.all(model.listedLoader, grants).firstOrNull { it.name == name } ?: WatchApp(name)
    val listing = StoreApps.listing(name)
    val caps = grants.caps(name)
    val allowed = grants.of(name)
    val offInOs = caps.filter { allowed[it] == true && !osAllows(context, it) }
    Column {
        Bar(app.title, back) {
            listing?.let { l ->
                IconButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.page))) }) {
                    Icon(painterResource(R.drawable.ic_open_in_new), "Open on fmc.tools")
                }
            }
        }
        LazyColumn {
            // the App Store's header: icon, name, summary, the button
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppIcon(name, 108.dp)
                    Column(Modifier.weight(1f).heightIn(min = 108.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                        if (!listing?.summary.isNullOrEmpty()) Text(listing!!.summary, color = Fmc.secondary,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        if (listing != null || app.slot != null) GetButton(model, app, large = true)
                        if (listing?.pkg != null && model.listedLoader == null) Text("Connect on the Watch tab to install.",
                            style = MaterialTheme.typography.bodySmall, color = Fmc.secondary)
                    }
                }
            }
            if (listing != null) {
                item { Column { InfoStrip(app, listing) } }
                if (listing.screenshots.isNotEmpty()) item {
                    androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(listing.screenshots.size) { i ->
                            val img = rememberImage(listing.screenshots[i])
                            Box(Modifier.size(180.dp).clip(CircleShape).background(Fmc.secondary.copy(alpha = 0.15f))) {
                                img?.let { Image(it, null, Modifier.size(180.dp), contentScale = ContentScale.Crop) }
                            }
                        }
                    }
                }
                if (listing.description.isNotEmpty()) item {
                    Section("Description") { SelectionContainer { com.mikepenz.markdown.m3.Markdown(listing.description) } }
                }
                item {
                    Section("Information") {
                        val rows = buildList {
                            if (listing.author.isNotEmpty()) add("Developer" to listing.author)
                            if (listing.version.isNotEmpty()) add("Version" to listing.version)
                            app.header?.let { h -> add("On the watch" to "${versionText(h.version)} · slot ${app.slot}") }
                            if (listing.abi.isNotEmpty()) add("Loader ABI" to listing.abi)
                        }
                        rows.forEachIndexed { i, (k, v) ->
                            if (i > 0) HorizontalDivider()
                            Row { Text(k, Modifier.weight(1f)); Text(v, color = Fmc.secondary) }
                        }
                        if (listing.source.isNotEmpty()) {
                            if (rows.isNotEmpty()) HorizontalDivider()
                            Row(Modifier.fillMaxWidth().clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listing.source)))
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Text("Source code", Modifier.weight(1f))
                                Icon(painterResource(R.drawable.ic_open_in_new), null, tint = Fmc.secondary)
                            }
                        }
                    }
                }
            } else item { Section { Row { Text("On the watch", Modifier.weight(1f)); Text(app.detail, color = Fmc.secondary) } } }
            app.slot?.let { slot ->
                item {
                    var confirm by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    Section(footer = "Removes ${app.title} and its data from the watch.") {
                        Text("Delete from the Watch", Modifier.fillMaxWidth().clickable(enabled = name !in AppUpdates.progress) { confirm = true },
                            color = Fmc.danger)
                    }
                    if (confirm) DeleteDialog(app, { confirm = false }) { scope.launch { AppUpdates.remove(context, model, name, slot) } }
                }
            }
            if (caps.isEmpty()) item {
                Section { Text("${app.title} hasn't asked for anything on your phone.", color = Fmc.secondary) }
            } else {
                item {
                    Section("Allow ${app.title} to access",
                        footer = (if (offInOs.isEmpty()) "" else "Some of these are off for FMC Companion in Android settings.\n\n") + NEARBY) {
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
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconTile(Icons.Default.Email, Color(0xFF757575))
                            Column(Modifier.weight(1f)) {
                                Text("Show text on the watch")
                                Text("Calendar titles and notification text", style = MaterialTheme.typography.bodyMedium, color = Fmc.secondary)
                            }
                            Switch(grants.showText(name), { grants.setShowText(name, it) })
                        }
                        if (offInOs.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Open app settings", Modifier.fillMaxWidth().clickable { openSettings() })
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

/** Store installs and updates: the package over K into the app's own slot, else the first free one. */
object AppUpdates {
    /** name → 0…1 while installing */
    val progress = androidx.compose.runtime.mutableStateMapOf<String, Double>()
    var error by mutableStateOf<String?>(null)

    suspend fun update(context: Context, model: AppModel, name: String) {
        val listing = StoreApps.listing(name)?.takeIf { it.pkg != null } ?: return
        if (name in progress) return
        progress[name] = 0.0
        try {
            val pkg = download(listing.download)
            kInstall(context, model, pkg) { progress[name] = it }
            DevLog.log(context, "$name installed")
            model.check()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: tools.fmc.companion.wire.InstallError.Busy) {
            error = "${listing.name} or the Apps menu is open on the watch. Go back to the watch face, then try again."
        } catch (_: tools.fmc.companion.wire.InstallError.NoFreeSlot) {
            error = "All 8 app slots on the watch are taken."
        } catch (e: Exception) {
            error = "Couldn't install ${listing.name}: ${e.message}"
        } finally {
            progress.remove(name)
        }
    }

    suspend fun remove(context: Context, model: AppModel, name: String, slot: Int) {
        if (name in progress) return
        val title = StoreApps.listing(name)?.name ?: name
        progress[name] = 0.0
        try {
            kRun(context, model) { tools.fmc.companion.wire.remove(it, slot) }
            DevLog.log(context, "$name removed from slot $slot")
            model.check()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: tools.fmc.companion.wire.InstallError.Busy) {
            error = "$title or the Apps menu is open on the watch. Go back to the watch face, then try again."
        } catch (e: Exception) {
            error = "Couldn't delete $title: ${e.message}"
        } finally {
            progress.remove(name)
        }
    }
}

@Composable
fun DeleteDialog(app: WatchApp, dismiss: () -> Unit, delete: () -> Unit) = AlertDialog(onDismissRequest = dismiss,
    title = { Text("Delete ${app.title} from the watch?") }, text = { Text("Its data on the watch goes too.") },
    confirmButton = { TextButton({ dismiss(); delete() }) { Text("Delete", color = Fmc.danger) } },
    dismissButton = { TextButton(dismiss) { Text("Cancel") } })

/** The App Store's button: Get, Update or Delete, and the progress while it installs; failures show as a dialog. */
@Composable
fun GetButton(model: AppModel, app: WatchApp, large: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = AppUpdates.progress[app.name]
    val width = if (large) 84.dp else 72.dp
    when {
        p != null -> LinearProgressIndicator({ p.toFloat() }, Modifier.width(width))
        app.slot != null && app.update == null -> {
            var confirm by remember { mutableStateOf(false) }
            androidx.compose.material3.OutlinedButton({ confirm = true }, Modifier.widthIn(min = width).height(if (large) 36.dp else 30.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Fmc.danger),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                Text("Delete", fontWeight = FontWeight.Bold)
            }
            if (confirm) DeleteDialog(app, { confirm = false }) { scope.launch { AppUpdates.remove(context, model, app.name, app.slot) } }
        }
        else -> androidx.compose.material3.Button({ scope.launch { AppUpdates.update(context, model, app.name) } },
            Modifier.widthIn(min = width).height(if (large) 36.dp else 30.dp),
            enabled = model.loader != null && StoreApps.listing(app.name)?.pkg != null,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            Text(if (app.slot == null) "Get" else "Update", fontWeight = FontWeight.Bold)
        }
    }
    AppUpdates.error?.let {
        AlertDialog(onDismissRequest = { AppUpdates.error = null }, title = { Text("Couldn't install") },
            text = { Text(it) }, confirmButton = { TextButton({ AppUpdates.error = null }) { Text("OK") } })
    }
}

/** The App Store's row of facts under the header. */
@Composable
private fun ColumnScope.InfoStrip(app: WatchApp, listing: StoreApps.Listing) {
    @Composable
    fun cell(title: String, value: String, caption: String? = null) =
        Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Fmc.secondary, fontWeight = FontWeight.SemiBold)
            Text(value, Modifier.height(30.dp), style = MaterialTheme.typography.titleLarge, color = Fmc.secondary, fontWeight = FontWeight.Bold, maxLines = 1)
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fmc.secondary, maxLines = 1) }
        }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top) {
        val cells = buildList<@Composable () -> Unit> {
            add { cell("DOWNLOADS", compact(listing.downloads)) }
            if (listing.version.isNotEmpty()) add { cell("VERSION", listing.version) }
            if (listing.author.isNotEmpty()) add { cell("DEVELOPER", "👤", listing.author) }
            app.slot?.let { add { cell("SLOT", "$it", "on the watch") } }
            if (listing.abi.isNotEmpty()) add { cell("ABI", listing.abi) }
        }
        cells.forEachIndexed { i, c ->
            if (i > 0) androidx.compose.material3.VerticalDivider(Modifier.padding(top = 10.dp).height(36.dp))
            c()
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
