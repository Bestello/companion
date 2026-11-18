package tools.fmc.companion

import com.mikepenz.markdown.m3.Markdown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.Manifest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import tools.fmc.companion.wire.UploadError
import tools.fmc.companion.wire.WireError
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
import androidx.compose.material3.Surface
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import tools.fmc.companion.wire.FaceVerdict

/** Where the app is: a tab, and a face or the sign-in page on top of it. */
class Nav {
    var tab by mutableStateOf(0)
    /** The catalog's pages over its grid: face ids, "user:<id>" for an author. */
    val pages = mutableStateListOf<String>()
    var face: String?
        get() = pages.lastOrNull()
        set(v) { if (v == null) pages.removeLastOrNull() else pages.add(v) }
    var signingIn by mutableStateOf(false)
    /** A watch app's page, over Apps or straight from its ask notification. */
    var app by mutableStateOf<String?>(null)
    var developer by mutableStateOf(false)
    var firmware by mutableStateOf(false)

    /** Back to the tab's root. */
    fun root() { pages.clear(); signingIn = false; app = null; developer = false; firmware = false }
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
                nav.face?.startsWith("user:") == true -> AuthorScreen(model, nav.face!!.removePrefix("user:"), { nav.face = it }) { nav.face = null }
                nav.face != null -> FaceScreen(model, nav.face!!, { nav.face = "user:$it" }) { nav.face = null }
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
    // 0 all, 1 liked, 2 mine
    var shelf by rememberSaveable { mutableIntStateOf(0) }
    // fmc_frontend market.svelte SORT_OPTIONS (Top creators, a shelf of rails, left out)
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sorting by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signedIn = model.user != null
    val showMine = signedIn && shelf == 2
    var list = if (showMine) model.mine else model.all
    if (signedIn && shelf == 1) list = list.filter(model::liked)
    val q = query.trim()
    if (q.isNotEmpty()) list = list.filter {
        it.name.contains(q, ignoreCase = true) || (it.author ?: "").contains(q, ignoreCase = true)
    }
    val counts = remember(model.likes) { model.likes.groupingBy { it.watchface }.eachCount() }
    val shown = when (sort) {
        1 -> list.sortedByDescending { counts[it.id] ?: 0 }
        2 -> list.sortedByDescending { it.downloads }
        else -> list.sortedByDescending { it.created }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Bar(if (showMine) "My watch faces" else if (signedIn && shelf == 1) "Liked" else "Watch faces") }
            Box {
                TextButton({ sorting = true }) { Text(listOf("Newest", "Popular", "Most downloaded")[sort]) }
                DropdownMenu(sorting, { sorting = false }) {
                    listOf("Newest", "Popular", "Most downloaded").forEachIndexed { i, label ->
                        DropdownMenuItem({ Text(label, fontWeight = if (i == sort) FontWeight.SemiBold else null) }, { sort = i; sorting = false })
                    }
                }
            }
        }
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; model.reload(); refreshing = false } }) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (model.user != null) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf("All", "Liked", "Mine").forEachIndexed { i, label ->
                                    SegmentedButton(shelf == i, { shelf = i },
                                        SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
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
                            signedIn && shelf == 1 -> "No liked watch faces"
                            else -> "Loading…"
                        }
                        Text(empty, Modifier.padding(top = 80.dp), color = Fmc.secondary, textAlign = TextAlign.Center)
                    }
                }
                items(shown, key = { it.id }) { face -> FaceCard(model, face) { open(face.id) } }
            }
        }
    }
}

/** As fmc.tools' watchface-card: the dial, its name, the author, then downloads, likes and the type. */
@Composable
fun FaceCard(model: AppModel, face: Watchface, open: () -> Unit) =
    Surface(Modifier.clickable(onClick = open), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FacePreview(face.previewURL, Modifier.fillMaxWidth().padding(bottom = 2.dp))
            Text(face.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!face.published) Text("Draft", style = MaterialTheme.typography.bodySmall, color = Fmc.secondary)
            else face.author?.let { author ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Avatar(face.authorAvatar, author, 18.dp)
                    Text(author, style = MaterialTheme.typography.bodySmall, color = Fmc.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("↓ ${compact(face.downloads)}", style = MaterialTheme.typography.bodySmall, color = Fmc.secondary)
                LikeButton(model, face)
                Spacer(Modifier.weight(1f))
                if (face.type.isNotEmpty()) Text(face.type, Modifier.clip(CircleShape).background(Fmc.secondary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

/** A creator's page, as fmc.tools /user/<id>: avatar, name, totals, then their published faces. */
@Composable
fun AuthorScreen(model: AppModel, id: String, open: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val faces = model.all.filter { it.ownerId == id }.sortedByDescending { it.created }
    val first = faces.firstOrNull()
    Column {
        Bar(first?.author ?: "", back) {
            IconButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$FMC/user/$id"))) }) {
                Icon(painterResource(R.drawable.ic_open_in_new), "Open on fmc.tools")
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(first?.authorAvatar, first?.author ?: "?", 80.dp)
                    Text(first?.author ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${faces.size} watch faces · ${compact(faces.sumOf { it.downloads })} downloads · " +
                        "${compact(faces.sumOf { model.likeCount(it) })} likes", color = Fmc.secondary)
                }
            }
            items(faces, key = { it.id }) { face -> FaceCard(model, face) { open(face.id) } }
        }
    }
}

/** The heart and the count; a tap likes or unlikes when signed in. */
@Composable
fun LikeButton(model: AppModel, face: Watchface) {
    val scope = rememberCoroutineScope()
    val liked = model.liked(face)
    Row(Modifier.clip(CircleShape).clickable(enabled = model.user != null) { scope.launch { model.toggleLike(face) } },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(15.dp),
            tint = if (liked) Color(0xFFE5484D) else Fmc.secondary)
        Text(compact(model.likeCount(face)), style = MaterialTheme.typography.bodySmall, color = Fmc.secondary)
    }
}

/** A round avatar, else the name's first letter. */
@Composable
fun Avatar(url: String?, name: String, side: Dp) {
    val bitmap = rememberImage(url)
    Surface(Modifier.size(side), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        if (bitmap != null) Image(bitmap, null, Modifier.size(side), contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = (side.value * 0.5f).sp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** 1234 → "1.2K", as fmc.tools' compact(). */
fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fK".format(n / 1_000.0).replace(".0K", "K")
    else -> "$n"
}

/** A face on the watch as Nothing X and fmc.tools show it: its round preview and name; the active one ringed yellow. */
/** A face's round picture: its preview, else the black panel with its number. */
@Composable
fun DialPicture(model: AppModel, slot: DialSlot, modifier: Modifier = Modifier) {
    val face = (model.mine + model.all).firstOrNull { dialId(it.id) == slot.id }
    val url = face?.previewURL ?: slot.preview
    if (url != null) FacePreview(url, modifier)
    else Box(modifier.aspectRatio(1f).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        // no picture anywhere for stock and Nothing X faces: the watch's place for it
        Text("${slot.index + 1}", color = Color(0xFFFFC700), fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The CMF Watch Pro 2 render fmc.tools frames dials with (fmc_frontend watch-case.svelte), [content] on its glass. Laid
 * out as the bezel alone ([side] across): the case and strap hang outside it and take no space. The render is square,
 * the bezel 51.75% of it, centred a hair under the middle; the panel 94% of the bezel.
 */
@Composable
fun WatchFrame(side: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.size(side), contentAlignment = Alignment.Center) {
        val render = side / 0.5175f
        Image(painterResource(R.drawable.watch_frame), null,
            Modifier.wrapContentSize(unbounded = true).requiredSize(render).offset(y = -render * 0.00175f))
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(0.94f).clip(CircleShape)) { content() }
        }
    }

@Composable
fun DialTile(model: AppModel, slot: DialSlot, modifier: Modifier = Modifier, active: Boolean = false) {
    val face = (model.mine + model.all).firstOrNull { dialId(it.id) == slot.id }
    Column(modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ring = if (active) Modifier.border(2.5.dp, Color(0xFFFFC700), CircleShape) else Modifier
        Box(ring.padding(3.dp)) { DialPicture(model, slot, Modifier.fillMaxWidth()) }
        Text(face?.name ?: slot.label, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FaceScreen(model: AppModel, id: String, author: (String) -> Unit = {}, back: () -> Unit) {
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
    /** Picked on the Install tap, before connecting. */
    var preset by remember { mutableStateOf<Long?>(null) }
    suspend fun ask(slots: List<DialSlot>) = CompletableDeferred<Long?>().also { picking = slots to it }.await()

    LaunchedEffect(id) {
        try { face = model.face(id) } catch (e: Exception) { error = e.message ?: "Couldn't load the watch face" }
    }

    fun upload(bin: ByteArray) = scope.launch {
        val f = face ?: return@launch
        progress = 0f
        try {
            installFaceOnWatch(context, model.store, bin, f.id, f.name, pick = { slots -> ask(slots) }, preset = preset) { p -> scope.launch { progress = p / 100f } }
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

    // full when last read: the dial to replace first, then the Nothing X warning
    val install = withBluetooth {
        scope.launch {
            preset = null
            val known = face?.let { knownFull(context, it.id) }
            if (known != null) preset = ask(known) ?: return@launch
            closeNothingX = true
        }
    }

    Column {
        Bar(face?.name ?: "", back) {
            face?.let { f ->
                IconButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.pageURL))) }) {
                    Icon(painterResource(R.drawable.ic_open_in_new), "Open on fmc.tools")
                }
            }
        }
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
                            f.otherDevice?.let { other ->
                                Text("⚠ Made for the $other. It may not work on the Watch Pro 2.", color = Fmc.warning,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(Fmc.warning.copy(alpha = 0.12f)).padding(12.dp))
                            }
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
                            f.author?.let { name ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = f.ownerId != null) { f.ownerId?.let(author) },
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Author", Modifier.weight(1f))
                                    Avatar(f.authorAvatar, name, 24.dp)
                                    Text(name, color = Fmc.secondary)
                                    Text("›", color = Fmc.secondary)
                                }
                                HorizontalDivider()
                            }
                            Row { Text("Downloads", Modifier.weight(1f)); Text("${f.downloads}", color = Fmc.secondary) }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Likes", Modifier.weight(1f)); LikeButton(model, f) }
                        }
                    }
                    if (f.description.isNotEmpty()) item { Section("About") { SelectionContainer { Markdown(f.description) } } }
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
                // the faces as on the Watch tab: tap the one to replace
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Pick the face to replace. Faces are in the order the watch shows them.", color = Fmc.secondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(slots, key = { it.index }) { s ->
                            DialTile(model, s, Modifier.clip(RoundedCornerShape(12.dp)).clickable { done(s.id) })
                        }
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
            AppUpdate.available?.let { r ->
                item {
                    Section(footer = AppUpdate.error) {
                        val progress = AppUpdate.progress
                        if (progress != null) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Downloading ${r.name}…")
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                        } else {
                            NavRow("Update to ${r.name}", onClick = { scope.launch { AppUpdate.install(context) } }) {
                                IconTile(painterResource(R.drawable.ic_watch), Color(0xFF34C759))
                            }
                        }
                    }
                }
            }
            item {
                Section(footer = "Keep the watch connected in Nothing X. The app only reads from it.") {
                    WatchStatus(model) { check() }
                    // the watch is usually there: read it on opening, as the Apps tab does
                    LaunchedEffect(Unit) { if (model.phase is Phase.Idle) check() }
                }
            }
            // faces and firmware are the watch's: only while it is connected
            if (model.phase is Phase.Checked) item {
                var reading by remember { mutableStateOf(false) }
                var readError by remember { mutableStateOf<String?>(null) }
                suspend fun read(bind: Boolean, retry: Boolean = true) {
                    reading = true
                    readError = try {
                        readFacesOnWatch(context, model.store, bind); null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // right after a connect or Bluetooth coming back the watch can be slow to answer: once more
                        if (!bind && retry && e is WireError.Timeout) return read(false, retry = false)
                        if (!bind) DevLog.log(context, "faces: ${if (e is UploadError.AuthFailed) "the saved stock key was refused" else e.toString()}")
                        if (!bind && (e is UploadError.AuthFailed || e is WireError.Timeout)) null // the list stays as last read
                        else "Couldn't read the faces on the watch: ${e.message}"
                    }
                    reading = false
                    model.facesRead++
                }
                // right after each connect, with the saved key only: a bind takes the watch from Nothing X
                LaunchedEffect(model.phase) { if (model.phase is Phase.Checked) read(bind = false) }
                var busy by remember { mutableStateOf(false) }
                var deleting by remember { mutableStateOf<DialSlot?>(null) }
                // a change to the list on the watch, then the list again
                fun change(op: suspend () -> Unit) = scope.launch {
                    busy = true
                    readError = try { op(); null } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        else "Couldn't change the faces on the watch: ${e.message}"
                    }
                    busy = false
                    model.facesRead++
                }
                deleting?.let { slot ->
                    AlertDialog({ deleting = null }, title = { Text("Delete ${slot.label}?") },
                        text = { Text("It goes from the watch. To get it back, install it again.") },
                        confirmButton = { TextButton({ deleting = null; change { deleteFace(context, model.store, slot.id) } }) { Text("Delete", color = Fmc.danger) } },
                        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
                }
                val on = remember(model.facesRead) { facesOnWatch(context) }
                Section(on.count?.let { "Watch faces · $it" } ?: "Watch faces") {
                    val faces = on.faces
                    // as Nothing X and fmc.tools show them: the round previews in the watch's own order. An FMC face,
                    // from here or fmc.tools, is dialId(record id) and has its catalog preview.
                    val row = rememberLazyListState()
                    // edge to edge in the card: out over Section's 16 dp, the first face still lined up with the text
                    if (faces.isNotEmpty()) LazyRow(
                        Modifier.layout { m, c ->
                            val p = 16.dp.roundToPx()
                            val pl = m.measure(c.copy(maxWidth = c.maxWidth + 2 * p, minWidth = c.minWidth + 2 * p))
                            layout(pl.width - 2 * p, pl.height) { pl.place(-p, 0) }
                        },
                        state = row, contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(faces, key = { it.index }) { slot ->
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                DialTile(model, slot, Modifier.alpha(if (busy) 0.4f else 1f).combinedClickable(enabled = !busy,
                                    onLongClick = { menu = true }) { change { activateFace(context, model.store, slot.id) } },
                                    active = slot.id == on.active)
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("Show on the watch") }, { menu = false; change { activateFace(context, model.store, slot.id) } })
                                    DropdownMenuItem({ Text("Delete", color = Fmc.danger) }, { menu = false; deleting = slot })
                                }
                            }
                        }
                    }
                    // ponytail: Compose has no scrollbar for a row; a plain thumb, not draggable
                    val info = row.layoutInfo
                    if (faces.isNotEmpty() && info.visibleItemsInfo.size < info.totalItemsCount) {
                        val n = info.totalItemsCount.toFloat()
                        val w = info.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
                        val start = (row.firstVisibleItemIndex + row.firstVisibleItemScrollOffset.toFloat() / w) / n
                        val shown = (info.viewportEndOffset - info.viewportStartOffset).toFloat() / (w + 14 * LocalDensity.current.density) / n
                        Row(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Fmc.secondary.copy(alpha = 0.2f))) {
                            if (start > 0.001f) Spacer(Modifier.weight(start))
                            Box(Modifier.weight(shown.coerceIn(0.05f, 1f)).fillMaxHeight().clip(CircleShape).background(Fmc.secondary))
                            val rest = 1f - start - shown
                            if (rest > 0.001f) Spacer(Modifier.weight(rest))
                        }
                    }
                    if (reading) Text("Reading the watch…", color = Fmc.secondary)
                    else if (faces.isEmpty()) Text("Connect to see the faces on the watch.", color = Fmc.secondary)
                    readError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                // updates are checked right after each connect: the Firmware row shows them
                LaunchedEffect(model.phase) { if (model.phase is Phase.Checked) FirmwareModel.refresh(model.account.token) }
                Section {
                    if (model.phase is Phase.Checked) {
                        NavRow("Firmware", count = FirmwareModel.updates(model.loader), onClick = { nav.firmware = true }) {
                            IconTile(painterResource(R.drawable.ic_memory), Color(0xFF757575))
                        }
                        HorizontalDivider()
                    }
                    NavRow("Developer", onClick = { nav.developer = true }) { IconTile(Icons.Default.Build, Color(0xFF757575)) }
                }
            }
            item {
                Section("Settings", footer = "Watch apps ask for your calendar, location, weather, notifications or a website. " +
                    "You answer each one on the Apps tab.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Services for watch apps", Modifier.weight(1f))
                        Switch(model.services, { on -> if (on) startServices() else model.switchServices(false) })
                    }
                }
            }
            item {
                Section("FMC account") {
                    val user = model.user
                    if (user != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val avatar = rememberImage(user.avatarUrl)
                            Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                if (avatar != null) Image(avatar, null, Modifier.size(44.dp), contentScale = ContentScale.Crop)
                                else Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimary) }
                            }
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
    // the bezel is the layout: the same gap above it and to the button; case and strap get cut by both
    Column(Modifier.padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val context = LocalContext.current
            val on = remember(model.facesRead) { facesOnWatch(context) }
            val active = on.faces.firstOrNull { it.id == on.active }
            // a shorter row than the bezel: the card's top and the button below cut into the watch
            // the bezel lines up with the button's left edge; the case hangs out past it
            WatchFrame(76.dp, Modifier.padding(end = 16.dp)) {
                // the face on screen once connected; till then what the app is doing
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (phase) {
                        Phase.Checking -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        is Phase.Checked -> if (active != null) DialPicture(model, active, Modifier.fillMaxSize())
                        else -> Icon(painterResource(R.drawable.ic_watch), null, Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            Column {
                val title = when (phase) {
                    Phase.Idle -> "Not connected"
                    Phase.Checking -> "Connecting…"
                    is Phase.Checked -> "Connected"
                    is Phase.Failed -> "Couldn't connect"
                }
                val (detail, color) = when (phase) {
                    Phase.Idle -> "Tap Connect to read it" to Fmc.secondary
                    Phase.Checking -> "This takes a few seconds" to Fmc.secondary
                    is Phase.Checked -> (if (phase.state == null) "Ready for watch faces" else "App Loader found") to Fmc.success
                    is Phase.Failed -> phase.message to Fmc.warning
                }
                // one line each: a taller row would move the watch
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // opaque while disabled: the strap runs under it
        Box(Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)) {
            PrimaryButton(if (phase == Phase.Idle) "Connect" else "Check again", phase != Phase.Checking, check)
        }
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

