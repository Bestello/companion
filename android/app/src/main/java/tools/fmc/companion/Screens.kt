package tools.fmc.companion

import android.Manifest
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import tools.fmc.companion.wire.UploadError
import tools.fmc.companion.wire.dialId
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tools.fmc.companion.wire.FaceVerdict

/** Where the app is: a tab, and a face or the sign-in page on top of it. */
class Nav {
    var tab by mutableStateOf(0)
    var face by mutableStateOf<String?>(null)
    var signingIn by mutableStateOf(false)
    /** A watch app's page, over Apps or straight from its ask notification. */
    var app by mutableStateOf<String?>(null)
    var developer by mutableStateOf(false)
    var firmware by mutableStateOf(false)

    /** Back to the tab's root. */
    fun root() { face = null; signingIn = false; app = null; developer = false; firmware = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(model: AppModel, nav: Nav) {
    BackHandler(nav.face != null || nav.signingIn || nav.app != null || nav.developer || nav.firmware) {
        when {
            nav.signingIn -> nav.signingIn = false
            nav.app != null -> nav.app = null
            nav.developer -> nav.developer = false
            nav.firmware -> nav.firmware = false
            else -> nav.face = null
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(nav.tab == 0, { nav.tab = 0; nav.root() },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Watch faces") })
                val pending = liveGrants().pending().size
                NavigationBarItem(nav.tab == 2, { nav.tab = 2; nav.root() }, label = { Text("Apps") }, icon = {
                    BadgedBox({ if (pending > 0) androidx.compose.material3.Badge { Text("$pending") } }) {
                        Icon(painterResource(R.drawable.ic_apps), null, Modifier.size(24.dp))
                    }
                })
                NavigationBarItem(nav.tab == 1, { nav.tab = 1; nav.root() },
                    icon = { Icon(painterResource(R.drawable.ic_watch), null, Modifier.size(24.dp)) }, label = { Text("Watch") })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            when {
                nav.signingIn -> SignInScreen(model) { nav.signingIn = false }
                nav.app != null -> AppScreen(model, nav.app!!) { nav.app = null }
                nav.developer -> DeveloperScreen { nav.developer = false }
                nav.firmware -> FirmwareScreen(model) { nav.firmware = false }
                nav.face != null -> FaceScreen(model, nav.face!!) { nav.face = null }
                nav.tab == 0 -> CatalogScreen(model) { nav.face = it }
                nav.tab == 2 -> AppsScreen(model) { nav.app = it }
                else -> WatchScreen(model, nav) { nav.signingIn = true }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) = TopAppBar(
    title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
    navigationIcon = { back?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
    actions = actions,
    windowInsets = WindowInsets(0),
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
)

/** Asks for Bluetooth once, then runs `action`. */
@Composable
private fun withBluetooth(action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) action()
    }
    return {
        if (perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) action()
        else ask.launch(perms)
    }
}

// Catalog

/** The FMC catalog and, signed in, the user's own faces: a grid of round previews, search by name or author. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(model: AppModel, open: (String) -> Unit) {
    var showMine by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = if (showMine && model.user != null) model.mine else model.all
    val q = query.trim()
    val shown = if (q.isEmpty()) list else list.filter {
        it.name.contains(q, ignoreCase = true) || (it.author ?: "").contains(q, ignoreCase = true)
    }
    Column {
        Bar(if (showMine) "My watch faces" else "Watch faces")
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; model.reload(); refreshing = false } }) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (model.user != null) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf("Catalog", "My watch faces").forEachIndexed { i, label ->
                                    SegmentedButton(showMine == (i == 1), { showMine = i == 1 },
                                        SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                                }
                            }
                        }
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Name or author") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                    }
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val empty = when {
                            model.catalogError != null -> "Couldn't load watch faces\n${model.catalogError}"
                            q.isNotEmpty() -> "Nothing found"
                            showMine -> "No watch faces yet"
                            else -> "Loading…"
                        }
                        Text(empty, Modifier.padding(top = 80.dp), color = Fmc.secondary, textAlign = TextAlign.Center)
                    }
                }
                items(shown, key = { it.id }) { face ->
                    Column(Modifier.clickable { open(face.id) }, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FacePreview(face.previewURL, Modifier.fillMaxWidth())
                        Text(face.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (face.published) face.author ?: " " else "Draft", style = MaterialTheme.typography.bodySmall,
                            color = Fmc.secondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun FaceScreen(model: AppModel, id: String, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var face by remember(id) { mutableStateOf<Watchface?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    /** Install progress 0…1, null when idle. */
    var progress by remember(id) { mutableStateOf<Float?>(null) }
    var installed by remember(id) { mutableStateOf(false) }
    var closeNothingX by remember { mutableStateOf(false) }
    /** A file past the safe image size, waiting for the user's yes. */
    var big by remember { mutableStateOf<Triple<ByteArray, Int, Int>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    /** The watch is full: the install waits here for the dial to replace. */
    var picking by remember { mutableStateOf<Pair<List<DialSlot>, CompletableDeferred<Long?>>?>(null) }

    LaunchedEffect(id) {
        try { face = model.face(id) } catch (e: Exception) { error = e.message ?: "Couldn't load the watch face" }
    }

    fun upload(bin: ByteArray) = scope.launch {
        val f = face ?: return@launch
        progress = 0f
        try {
            installFaceOnWatch(context, model.store, bin, f.id, f.name, pick = { slots ->
                CompletableDeferred<Long?>().also { picking = slots to it }.await()
            }) { p -> scope.launch { progress = p / 100f } }
            context.getSharedPreferences("dials", 0).edit().putString("preview-${dialId(f.id)}", f.previewURL).apply()
            installed = true
        } catch (e: CancellationException) {
            if (!isActive) throw e // a dismissed slot dialog cancels the install, not the screen
        } catch (e: Exception) {
            failure = e.message ?: "The watch did not answer."
        } finally {
            progress = null
        }
    }

    fun fetchAndInstall() = scope.launch {
        val f = face ?: return@launch
        progress = 0f
        try {
            val (bin, verdict) = fetchFace(f)
            if (verdict is FaceVerdict.Confirm) {
                progress = null
                big = Triple(bin, verdict.w, verdict.h)
            } else {
                upload(bin)
            }
        } catch (e: Exception) {
            progress = null
            failure = e.message
        }
    }

    val install = withBluetooth { closeNothingX = true }

    Column {
        Bar(face?.name ?: "", back)
        LazyColumn {
            val f = face
            when {
                f != null -> {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            FacePreview(f.previewURL, Modifier.widthIn(max = 240.dp).fillMaxWidth())
                            Text(f.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center)
                            f.author?.let { Text(it, color = Fmc.secondary) }
                            val p = progress
                            if (p != null) {
                                Text("Installing…")
                                LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                            } else {
                                PrimaryButton(if (installed) "Installed" else "Install", !installed && f.binURL != null) {
                                    install()
                                }
                            }
                            Text("Close Nothing X before installing: the watch talks to one app at a time.",
                                style = MaterialTheme.typography.bodySmall, color = Fmc.secondary, textAlign = TextAlign.Center)
                        }
                    }
                    item {
                        Section {
                            Row { Text("Downloads", Modifier.weight(1f)); Text("${f.downloads}", color = Fmc.secondary) }
                            HorizontalDivider()
                            Text("Open on fmc.tools", Modifier.fillMaxWidth().clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.pageURL)))
                            })
                        }
                    }
                    if (f.description.isNotEmpty()) item { Section("About") { Text(f.description) } }
                }
                error != null -> item {
                    Text("Couldn't load the watch face\n$error", Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center, color = Fmc.secondary)
                }
                else -> item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (closeNothingX) AlertDialog(
        onDismissRequest = { closeNothingX = false },
        title = { Text("Close Nothing X first") },
        text = {
            Text("The watch talks to one app at a time. Close Nothing X (swipe it away in Recents), then install.\n\n" +
                "Afterwards Nothing X may not see the watch. Then forget the watch in Bluetooth settings and add it again in Nothing X.")
        },
        confirmButton = { TextButton({ closeNothingX = false; fetchAndInstall() }) { Text("Install") } },
        dismissButton = { TextButton({ closeNothingX = false }) { Text("Cancel") } },
    )
    big?.let { (bin, w, h) ->
        AlertDialog(
            onDismissRequest = { big = null },
            title = { Text("Large images") },
            text = { Text("This face has a $w×$h px image, larger than factory faces use. Large images can make the watch restart in a loop.") },
            confirmButton = { TextButton({ big = null; upload(bin) }) { Text("Install anyway", color = Fmc.danger) } },
            dismissButton = { TextButton({ big = null }) { Text("Cancel") } },
        )
    }
    picking?.let { (slots, answer) ->
        fun done(id: Long?) { picking = null; answer.complete(id) }
        AlertDialog(
            onDismissRequest = { done(null) },
            title = { Text("The watch is full") },
            text = {
                Column {
                    Text("Pick the face to replace. Faces are numbered in the order the watch shows them.",
                        color = Fmc.secondary)
                    slots.forEach { s ->
                        TextButton({ done(s.id) }) { Text("Replace ${s.label}", color = Fmc.danger) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ done(null) }) { Text("Cancel") } },
        )
    }
    failure?.let {
        AlertDialog(onDismissRequest = { failure = null }, title = { Text("Couldn't install") }, text = { Text(it) },
            confirmButton = { TextButton({ failure = null }) { Text("OK") } })
    }
}

// Watch

@Composable
fun WatchScreen(model: AppModel, nav: Nav, signIn: () -> Unit) {
    val context = LocalContext.current
    val check = withBluetooth { model.check() }
    // the ASK notifications need POST_NOTIFICATIONS; the services run without it
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.switchServices(true) }
    val startServices = withBluetooth { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        Bar("FMC Companion")
        LazyColumn {
            item {
                Section(footer = "Keep the watch connected in Nothing X. The app only reads from it.") {
                    WatchStatus(model) { check() }
                }
            }
            item {
                // updates are checked right after each connect: the Firmware row shows them
                LaunchedEffect(model.phase) { if (model.phase is Phase.Checked) FirmwareModel.refresh() }
                Section {
                    NavRow("Firmware", count = FirmwareModel.updates(model.loader), onClick = { nav.firmware = true }) {
                        IconTile(painterResource(R.drawable.ic_memory), Color(0xFF757575))
                    }
                }
            }
            item {
                var reads by remember { mutableIntStateOf(0) }
                var reading by remember { mutableStateOf(false) }
                var readError by remember { mutableStateOf<String?>(null) }
                // right after each connect
                LaunchedEffect(model.phase) {
                    if (model.phase !is Phase.Checked) return@LaunchedEffect
                    reading = true
                    readError = try {
                        readFacesOnWatch(context, model.store); null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // no saved key: the first install from the app binds, the list comes after that
                        if (e is UploadError.AuthFailed) null else "Couldn't read the faces on the watch: ${e.message}"
                    }
                    reading = false
                    reads++
                }
                val on = remember(reads) { facesOnWatch(context) }
                Section(on.count?.let { "Watch faces · $it" } ?: "Watch faces",
                    footer = if (on.at == 0L) null else "Read " +
                        DateUtils.getRelativeTimeSpanString(on.at) + ". Numbered in the order the watch shows them." +
                        if (on.header.isEmpty()) "" else "\nWatch header: ${on.header}") {
                    val faces = on.faces
                    // as Nothing X and fmc.tools show them: the round previews in the watch's own order. An FMC face,
                    // from here or fmc.tools, is dialId(record id) and has its catalog preview.
                    if (faces.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(faces, key = { it.index }) { slot ->
                            val face = (model.mine + model.all).firstOrNull { dialId(it.id) == slot.id }
                            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val url = face?.previewURL ?: slot.preview
                                if (url != null) FacePreview(url, Modifier.fillMaxWidth())
                                else Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Color.Black),
                                    contentAlignment = Alignment.Center) {
                                    // no picture anywhere for stock and Nothing X faces: the watch's place for it
                                    Text("${slot.index + 1}", color = Color(0xFFFFC700), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(face?.name ?: slot.label, style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (reading) Text("Reading the watch…", color = Fmc.secondary)
                    else if (faces.isEmpty()) Text("Install a face from the app once: after that the list is read on every connect.", color = Fmc.secondary)
                    readError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Section("Settings", footer = "Watch apps ask for your calendar, location, weather, notifications or a website. " +
                    "You answer each one on the Apps tab.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Services for watch apps", Modifier.weight(1f))
                        Switch(model.services, { on -> if (on) startServices() else model.switchServices(false) })
                    }
                    HorizontalDivider()
                    NavRow("Developer", onClick = { nav.developer = true }) { IconTile(Icons.Default.Build, Color(0xFF757575)) }
                }
            }
            item {
                Section("FMC account") {
                    val user = model.user
                    if (user != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Badge { Icon(Icons.Default.Person, null) }
                            Column {
                                Text(user.name.ifEmpty { "Signed in" }, fontWeight = FontWeight.SemiBold)
                                Text(user.email, style = MaterialTheme.typography.bodyMedium, color = Fmc.secondary)
                            }
                        }
                        HorizontalDivider()
                        Text("Sign out", Modifier.fillMaxWidth().clickable { model.signOut() })
                        HorizontalDivider()
                        Text("Delete account", Modifier.fillMaxWidth().clickable { deleting = true }, color = Fmc.danger)
                    } else {
                        Row(Modifier.fillMaxWidth().clickable(onClick = signIn), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.AccountCircle, null)
                            Text("Sign in", Modifier.weight(1f))
                            Text("›", color = Fmc.secondary)
                        }
                    }
                }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete your FMC account?") },
        text = { Text("This removes your account and profile on fmc.tools. It can't be undone.") },
        confirmButton = {
            TextButton({
                deleting = false
                scope.launch { try { model.deleteAccount() } catch (e: Exception) { deleteError = e.message } }
            }) { Text("Delete account", color = Fmc.danger) }
        },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
    )
    deleteError?.let {
        AlertDialog(onDismissRequest = { deleteError = null }, title = { Text("Couldn't delete the account") },
            text = { Text(it) }, confirmButton = { TextButton({ deleteError = null }) { Text("OK") } })
    }
}

/** The watch tile from workout-companion's WatchView, with Zeron's capsule button under it. */
@Composable
private fun WatchStatus(model: AppModel, check: () -> Unit) {
    val phase = model.phase
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Badge(64.dp, filled = phase is Phase.Checked) {
                if (phase == Phase.Checking) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                else Icon(painterResource(R.drawable.ic_watch), null, Modifier.size(32.dp))
            }
            Column {
                val title = when (phase) {
                    Phase.Idle -> "Not connected"
                    Phase.Checking -> "Reading the watch…"
                    is Phase.Checked -> "Connected"
                    is Phase.Failed -> "Couldn't connect"
                }
                val (detail, color) = when (phase) {
                    Phase.Idle -> "Tap Connect to read it" to Fmc.secondary
                    Phase.Checking -> "This takes a few seconds" to Fmc.secondary
                    is Phase.Checked -> (if (phase.state == null) "Ready for watch faces" else "App Loader found") to Fmc.success
                    is Phase.Failed -> phase.message to Fmc.warning
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
        PrimaryButton(if (phase == Phase.Idle) "Connect" else "Check again", phase != Phase.Checking, check)
    }
}

// Sign in

/** Zeron's welcome page (mark, title, tagline, capsule buttons) around the fields. */
@Composable
fun SignInScreen(model: AppModel, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job != null
    LaunchedEffect(Unit) { model.loadProviders() }

    fun run(action: suspend () -> Unit) {
        job = scope.launch {
            try {
                action()
                done()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
            } finally {
                job = null
            }
        }
    }

    Column {
        Bar("", done)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Badge(64.dp) { Icon(painterResource(R.drawable.ic_watch), null, Modifier.size(32.dp)) }
                    Spacer(Modifier.height(2.dp))
                    Text("Sign in to FMC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Your fmc.tools account, for watch faces on your watch.", color = Fmc.secondary, textAlign = TextAlign.Center)
                }
            }
            item {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            }
            item {
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
            error?.let { item { Text(it, color = Fmc.danger) } }
            item {
                if (busy) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        TextButton({ job?.cancel() }) { Text("Cancel") }
                    }
                } else {
                    PrimaryButton("Sign in", email.isNotBlank() && password.isNotEmpty()) {
                        run { model.signIn(email.trim(), password) }
                    }
                }
            }
            if (model.providers.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text("or", Modifier.padding(horizontal = 12.dp), color = Fmc.secondary)
                        HorizontalDivider(Modifier.weight(1f))
                    }
                }
                items(model.providers.size) { i ->
                    val p = model.providers[i]
                    SecondaryButton("Continue with ${p.displayName}", !busy) {
                        error = null
                        run {
                            model.signIn(p) { url -> openAuthPage(context, url) }
                            // closes the provider's tab, which sits on top of us in this task
                            context.startActivity(Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }
                    }
                }
            }
            item {
                Text("No account? Sign up at fmc.tools", Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Account.SIGN_UP)))
                }, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The provider's page in a Custom Tab, the platform's place for OAuth (Google refuses WebViews). The extra is the
 * Custom Tabs protocol itself, no androidx.browser needed; a browser without Custom Tabs opens a plain tab.
 */
private fun openAuthPage(context: android.content.Context, url: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    i.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
    context.startActivity(i)
}

