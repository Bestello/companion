package tools.fmc.companion

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import tools.fmc.companion.wire.InstallError
import tools.fmc.companion.wire.KSession
import tools.fmc.companion.wire.LoaderState
import tools.fmc.companion.wire.PackageHeader
import tools.fmc.companion.wire.checkOtaTarget
import tools.fmc.companion.wire.flashFirmware
import tools.fmc.companion.wire.inspectOtaImage
import tools.fmc.companion.wire.install
import tools.fmc.companion.wire.parseHeader

// Firmware: what the watch runs (the patched stub and the App Loader) against what fmc.tools publishes (`firmwares`,
// kinds `patched` and `loader`), checked right after each connect. The App Loader updates from here over K; the
// patched firmware goes over the stock OTA (wire/Ota.kt, as fmc.tools' flashFirmware). As ios/App/Firmware.swift.

object FirmwareModel {
    /** `stub`: STUB_VERSION for the patched firmware. */
    class Release(val name: String, val version: String, val stub: Long, val file: String)

    /** `loaderPackage`: the store's loader; its header's CRC tells whether the watch has it, and Update installs it. */
    class Check(val firmware: Release?, val loader: Release?, val loaderPackage: ByteArray?, val loaderHeader: PackageHeader?)

    var check by mutableStateOf<Check?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Double?>(null)
        private set

    /** The firmware update under way: what it is doing and, once the watch reports a percent, how far. */
    class Flash(val step: String, val percent: Int? = null, val started: Long = System.currentTimeMillis()) {
        /** Seconds left, from the watch's own percent: it skips what it has, so the rate is what counts. */
        val left: Long? get() = percent?.takeIf { it in 2..99 }?.let { p ->
            (System.currentTimeMillis() - started) / 1000 * (100 - p) / p
        }
    }
    var flash by mutableStateOf<Flash?>(null)
        private set
    var flashed by mutableStateOf(false)
        private set

    /** The store's loader differs from the one on the watch (or the watch has none). Its header version is always 1. */
    fun loaderUpdate(state: LoaderState?): Boolean {
        val want = check?.loaderHeader ?: return false
        return state != null && state.loader?.crc != want.crc
    }

    /** The store's patched firmware has a newer stub than the watch. */
    fun firmwareUpdate(state: LoaderState?): Boolean {
        val fw = check?.firmware ?: return false
        return state != null && fw.stub > state.stub.version
    }

    fun updates(state: LoaderState?) = (if (loaderUpdate(state)) 1 else 0) + (if (firmwareUpdate(state)) 1 else 0)

    /** [token]: a moderator's sign-in also lists the drafts, as fmc.tools does (fmc_pocketbase's list rule decides). */
    suspend fun refresh(token: String? = null) {
        if (checking) return
        checking = true
        try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val filter = enc("device = 'watch_pro_2' && (kind = 'patched' || kind = 'loader')")
            val items = JSONObject(http("GET", "$FMC/api/collections/firmwares/records?filter=$filter&sort=-updated" +
                "&fields=id,collectionId,name,kind,version,image,stub,published", token = token)).getJSONArray("items")
            val records = (0 until items.length()).map(items::getJSONObject)
            fun release(kind: String) = records.firstOrNull { it.optString("kind") == kind }?.let {
                Release(it.optString("name") + if (it.optBoolean("published")) "" else " (draft)", it.optString("version"), it.optLong("stub"),
                    "$FMC/api/files/${it.getString("collectionId")}/${it.getString("id")}/${it.getString("image")}")
            }
            val loader = release("loader")
            val pkg = loader?.let { download(it.file) }
            check = Check(release("patched"), loader, pkg, pkg?.let(::parseHeader))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't check for updates: ${e.message}"
        } finally {
            checking = false
        }
    }

    /**
     * The store's patched firmware over the stock OTA. The image is checked before connecting and the watch's model
     * before the stock session opens (ota-image.ts checkOtaTarget): nothing goes out for a refused one. The services'
     * listener holds K on the same link: off meanwhile.
     */
    suspend fun flashFirmware(context: Context, model: AppModel) {
        val file = check?.firmware?.file ?: return
        if (flash != null) return
        flash = Flash("Downloading the firmware…")
        flashed = false
        error = null
        val services = model.services
        try {
            val image = download(file)
            // 57 MB of CRCs: off the main thread
            val info = withContext(Dispatchers.Default) { inspectOtaImage(image) }
            flash = Flash("Connecting to the watch…")
            if (services) RpcService.stop(context)
            withStockSession(context, model.store, before = { checkOtaTarget(info, it.watchName) }) { session, _ ->
                flash = Flash("Sending the firmware…")
                session.flashFirmware(image) { p ->
                    val f = flash
                    flash = Flash("Sending the firmware…", p, if (f?.percent == null) System.currentTimeMillis() else f.started)
                }
            }
            DevLog.log(context, "Firmware sent: ${check?.firmware?.version}")
            flashed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't update the firmware: ${e.message}"
        } finally {
            flash = null
            if (services) RpcService.start(context)
        }
    }

    /** Installs the store's App Loader over K, then reads the watch again. */
    suspend fun updateLoader(context: Context, model: AppModel) {
        val pkg = check?.loaderPackage ?: return
        progress = 0.0
        // the services' listener holds the watch's K channel: off while installing
        try {
            kInstall(context, model, pkg) { p -> progress = p }
            DevLog.log(context, "App Loader installed (crc %08x)".format(check?.loaderHeader?.crc ?: 0))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: InstallError.Busy) {
            // stub 6 takes the install behind a page and reloads it there (cmf-watch-sdk docs/abi-1.4.md): busy is
            // then only what is on screen
            error = if ((model.loader?.stub?.version ?: 0) >= 6)
                "An app or the Apps menu is open on the watch. Go back to the watch face, then try again."
            else "The App Loader is running on the watch. Close the Apps menu and any running app, and remove the watch app pages and widgets you added, then try again. Patched firmware with stub 6 or later doesn't need this."
        } catch (e: Exception) {
            error = "Couldn't update the App Loader: ${e.message}"
        } finally {
            progress = null
        }
        if (error == null) model.check()
    }
}

@Composable
fun FirmwareScreen(model: AppModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fm = FirmwareModel
    val state = model.loader
    val connected = model.phase is Phase.Checked
    LaunchedEffect(Unit) { if (fm.check == null) fm.refresh(model.account.token) }
    var confirmFlash by remember { mutableStateOf(false) }
    // a 57 MB update: the screen stays on while it runs, and the time left ticks
    val view = LocalView.current
    val flashing = fm.flash != null
    DisposableEffect(flashing) {
        view.keepScreenOn = flashing
        onDispose { view.keepScreenOn = false }
    }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(flashing) { while (flashing) { delay(1000); tick++ } }
    if (tick < 0) return // reading tick recomposes the time left each second
    if (confirmFlash) AlertDialog(
        onDismissRequest = { confirmFlash = false },
        title = { Text("Close Nothing X first") },
        text = {
            Text("The watch talks to one app at a time. Close Nothing X (swipe it away in Recents), then install.\n\n" +
                "The watch should be charged and the phone next to it. Keep this screen open until the update is sent; the watch then installs it and restarts.\n\n" +
                "Afterwards Nothing X may not see the watch. Then forget the watch in Bluetooth settings and add it again in Nothing X.")
        },
        // the model's scope: leaving the page doesn't cut the update short
        confirmButton = { TextButton({ confirmFlash = false; model.viewModelScope.launch { fm.flashFirmware(context, model) } }) { Text("Install") } },
        dismissButton = { TextButton({ confirmFlash = false }) { Text("Cancel") } },
    )

    @Composable
    fun row(label: String, detail: String? = null, value: @Composable () -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fmc.secondary) }
            }
            value()
        }

    Column {
        Bar("Firmware", back)
        LazyColumn {
            item {
                Section("On the watch", footer = if (connected) null else "Connect on the Watch tab to read the watch.") {
                    row("Firmware") {
                        Text(when { state != null -> "Patched · stub ${state.stub.version}"; connected -> "Stock"; else -> "—" },
                            color = Fmc.secondary)
                    }
                    HorizontalDivider()
                    row("App Loader") {
                        val l = state?.loader
                        Text(when { l != null -> "%08x".format(l.crc); state != null -> "Not installed"; else -> "—" },
                            color = Fmc.secondary, fontFamily = if (l != null) FontFamily.Monospace else null)
                    }
                }
            }
            item {
                val fwUpdate = fm.firmwareUpdate(state)
                val offer = fwUpdate || (state == null && connected)
                val f = fm.flash
                val footer = when {
                    f != null -> f.percent?.let { p ->
                        val left = f.left?.let { s -> if (s >= 60) ", about ${s / 60} min ${s % 60} s left" else ", about $s s left" } ?: ""
                        "Sending the firmware: $p%$left. Keep the phone next to the watch and this screen open."
                    } ?: f.step
                    fm.flashed -> "Firmware sent. The watch installs it and restarts by itself, which takes a few minutes. Then connect again on the Watch tab."
                    offer -> "The firmware is about 57 MB. Before installing: charge the watch, keep the phone next to it, and close Nothing X."
                    else -> null
                }
                Section("fmc.tools", footer = footer) {
                    fm.check?.firmware?.let { fw ->
                        row(fw.name, "${fw.version} · stub ${fw.stub}") {
                            when {
                                f != null -> LinearProgressIndicator({ (f.percent ?: 0) / 100f }, Modifier.width(80.dp))
                                offer -> Button({ confirmFlash = true }, enabled = fm.progress == null) {
                                    Text(if (state == null) "Install" else "Update")
                                }
                                else -> Text(if (state == null) "" else "Up to date", color = Fmc.secondary)
                            }
                        }
                        HorizontalDivider()
                    }
                    fm.check?.loader?.let { loader ->
                        row(loader.name, loader.version) {
                            val p = fm.progress
                            when {
                                p != null -> LinearProgressIndicator({ p.toFloat() }, Modifier.width(80.dp))
                                // the model's scope: leaving the page doesn't cut an install short
                                fm.loaderUpdate(state) -> Button({ model.viewModelScope.launch { fm.updateLoader(context, model) } },
                                    enabled = fm.check?.loaderPackage != null) {
                                    Text(if (state?.loader == null) "Install" else "Update")
                                }
                                else -> Text(if (state == null) "" else "Up to date", color = Fmc.secondary)
                            }
                        }
                        HorizontalDivider()
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !fm.checking) { scope.launch { fm.refresh(model.account.token) } },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Check for updates", Modifier.weight(1f))
                        if (fm.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    fm.error?.let { Text(it, color = Fmc.danger, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** One package over K (the loader or an app, into its own slot); the services' listener holds K, so it is off meanwhile. */
suspend fun kInstall(context: Context, model: AppModel, pkg: ByteArray, progress: (Double) -> Unit) {
    val services = model.services
    if (services) RpcService.stop(context)
    val link = BleLink(context)
    try {
        withTimeout(120_000) {
            link.connect()
            // ponytail: K read fresh each time (a few seconds) rather than trying the cached one first
            val (session, secret) = KSession.open(link, register = false)
            model.store["k-${link.watchId}"] = secret
            install(session, pkg, progress)
        }
    } finally {
        link.disconnect()
        if (services) RpcService.start(context)
    }
}
