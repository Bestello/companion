package tools.fmc.companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import tools.fmc.companion.wire.FaceRefusal
import tools.fmc.companion.wire.FaceVerdict
import tools.fmc.companion.wire.KSession
import tools.fmc.companion.wire.LoaderState
import tools.fmc.companion.wire.MAX_IMAGE_SIDE
import tools.fmc.companion.wire.PackageHeader
import tools.fmc.companion.wire.StockSession
import tools.fmc.companion.wire.Stub
import tools.fmc.companion.wire.UploadError
import tools.fmc.companion.wire.WireError
import tools.fmc.companion.wire.checkFace
import tools.fmc.companion.wire.dialId
import tools.fmc.companion.wire.hex
import tools.fmc.companion.wire.hexOrNull
import tools.fmc.companion.wire.installFace
import tools.fmc.companion.wire.readLoader

// What the app does with the watch, as ios/App WatchModel.swift and FaceInstall.swift.

/**
 * Is the App Loader on the watch? Read only: K from the shell, then INFO (fmc_frontend ble.ts readWp2). null = stock
 * firmware (or a stub from before 2026-11-13, which has no K). On the watch: `AT GETSECRET` and INFO, nothing else.
 */
suspend fun readWatch(context: Context, store: SecureStore): LoaderState? {
    val link = BleLink(context)
    try {
        return withTimeout(30_000) {
            link.connect()
            val account = "k-${link.watchId}"
            val cached = store[account]
            val (session, secret) = KSession.open(link, cached, register = false)
            store[account] = secret
            readLoader(session)?.let { return@withTimeout it }
            // K changes with a factory reset: a cached one that gets no answer is read again once, and only asked
            // again when it really changed (FMC Systems' review of the iOS app: no second 2.5 s on stock watches)
            if (cached == null) return@withTimeout null
            val (fresh, newSecret) = KSession.open(link, register = false)
            if (newSecret == cached) return@withTimeout null
            store[account] = newSecret
            readLoader(fresh)
        }
    } finally {
        link.disconnect()
    }
}

/** What Play review sees without a watch. */
suspend fun demoState(): LoaderState {
    delay(1000)
    return LoaderState(
        Stub(5, 3), PackageHeader(1, "loader", 20_261_115),
        mapOf(0 to PackageHeader(0, "Orbit", 1), 1 to PackageHeader(0, "Vibration Lab", 2)),
    )
}

class FaceInstallError(message: String) : Exception(message)

fun refusalText(why: FaceRefusal) = when (why) {
    FaceRefusal.AodWithoutPreview ->
        "The always-on screen of this face has no thumbnail. Faces like this made the screen flash on a watch. " +
            "Open it in the editor on fmc.tools and install it from there."
    is FaceRefusal.ImageTooBig ->
        "An image of this face is ${why.w}×${why.h} px. Over $MAX_IMAGE_SIDE px can make the watch restart in a loop."
    else -> "This file is not a watch face."
}

/** The file and the gate's verdict: nothing goes to the watch before the user has seen it. */
suspend fun fetchFace(face: Watchface): Pair<ByteArray, FaceVerdict> {
    val url = face.binURL ?: throw FaceInstallError("This watch face has no file.")
    val bin = download(url)
    val verdict = checkFace(bin)
    if (verdict is FaceVerdict.Refuse) throw FaceInstallError(refusalText(verdict.why))
    return bin to verdict
}

/**
 * A watchface onto the watch the way fmc.tools does it through Chrome's Web Bluetooth (ble.ts connect, pair,
 * authenticate, uploadWatchface). The stock session can't share the watch with Nothing X, so the user closes
 * Nothing X first, and may have to add the watch to it again afterwards (fmc issue #27); the UI says so first.
 */
/** A dial on the watch. [index]: its place in the watch's own carousel, the only handle on a dial nobody named. */
/**
 * The watch only reports ids: faces this app installed keep their name and preview, stock ones are named from
 * fmc.tools' stock-dials.json, Nothing X gallery ones have nothing to go on.
 */
data class DialSlot(val id: Long, val index: Int, val name: String?, val preview: String? = null) {
    val label get() = name ?: "Face ${index + 1}"
}

/** fmc_frontend modules/device/lib/stock-dials.json (assets): id → name of the factory and Nothing gallery faces. */
private var stock: Map<Long, String>? = null

fun stockDials(context: Context): Map<Long, String> = stock ?: runCatching {
    val j = org.json.JSONObject(context.assets.open("stock-dials.json").bufferedReader().readText())
    j.keys().asSequence().associate { it.toLong() to j.getJSONObject(it).getString("name") }
}.getOrDefault(emptyMap()).also { stock = it }

private fun slot(context: Context, prefs: android.content.SharedPreferences, id: Long, i: Int) =
    DialSlot(id, i, prefs.getString("name-$id", null) ?: stockDials(context)[id], prefs.getString("preview-$id", null))

/** The watch's faces as the last install from this app saw them. */
class FacesOnWatch(
    val faces: List<DialSlot>,
    /** epoch ms, 0 = never */
    val at: Long,
    /** "6 of 7" from the a055 header, side-loaded dials included */
    val count: String?,
    val header: String,
)

fun facesOnWatch(context: Context): FacesOnWatch {
    val prefs = context.getSharedPreferences("dials", Context.MODE_PRIVATE)
    val ids = prefs.getString("faces-on-watch", "")!!.split(",").mapNotNull { it.toLongOrNull() }
    return FacesOnWatch(
        ids.mapIndexed { i, d -> slot(context, prefs, d, i) },
        prefs.getLong("faces-on-watch-date", 0),
        prefs.getString("faces-on-watch-count", null)?.split(",")?.let { (n, c) -> "$n of $c" },
        prefs.getString("faces-on-watch-header", "")!!,
    )
}

/** ble.ts WF_CAPACITY */
const val CAPACITY = 6

/** [pick]: the watch is full, which dial goes; null cancels the install. */
suspend fun installFaceOnWatch(
    context: Context, store: SecureStore, bin: ByteArray, seed: String, name: String,
    pick: suspend (List<DialSlot>) -> Long?, progress: (Int) -> Unit,
) {
    val link = BleLink(context)
    try {
        withTimeout(30_000) { link.connect(scan = true) }
        val watch = link.watchId
        var secret = store["k-$watch"]
        if (secret == null) {
            secret = KSession.open(link, register = false).second
            store["k-$watch"] = secret
        }
        val session = StockSession(link)
        try {
            val saved = store["stock-$watch"]?.let(::hexOrNull) ?: throw UploadError.AuthFailed
            session.authenticate(saved)
        } catch (e: Exception) {
            if (e !is UploadError.AuthFailed && e !is WireError.Timeout) throw e
            // no key yet, or the watch was bound again since (Nothing X, a reset): bind, as the site does
            val k1 = session.pair(hexOrNull(secret) ?: ByteArray(0))
            store["stock-$watch"] = k1.hex()
        }
        // a055 comes on its own after auth
        delay(1500)
        val prefs = context.getSharedPreferences("dials", Context.MODE_PRIVATE)
        val mine = prefs.getString("dials-$watch", "")!!.split(",").mapNotNull { it.toLongOrNull() }
        val reported = session.reported ?: emptyList()
        val installed = reported + mine.filter { it !in reported }
        // the header counts side-loaded dials too; the hardcoded capacity and the id list are the fallback
        val counted = session.dialCount
        val header = (session.dialHeader ?: ByteArray(0)).joinToString(" ") { "%02x".format(it) }
        Log.i("dials", "a055 header $header, ${reported.size} listed, ${installed.size} known")
        val cap = counted?.capacity ?: CAPACITY
        val full = counted?.let { it.count >= it.capacity } ?: (installed.size >= CAPACITY)
        // which dial to replace (ble.ts NoFreeSlotError, slot-dialog.svelte)
        suspend fun ask() = pick(installed.mapIndexed { i, d -> slot(context, prefs, d, i) })
            ?: throw CancellationException("no dial picked")
        // full and not a re-install of this face: ask before sending anything
        var slot: Long? = if (full && dialId(seed) !in installed) ask() else null
        val id = try {
            try {
                installFace(session, bin, seed, installed, cap, slot, progress = progress)
            } catch (e: UploadError.NoFreeSlot) {
                if (slot != null || installed.isEmpty()) throw e
                // the count said there was room and the watch said no: the header reading is wrong, or it is missing
                slot = ask()
                installFace(session, bin, seed, installed, cap, slot, progress = progress)
            }
        } catch (_: UploadError.NoFreeSlot) {
            throw FaceInstallError("The watch is full. Delete a face on the watch (long press the face, swipe one up) and try again.")
        }
        val now = mine.filter { it != slot } + if (id in mine) emptyList() else listOf(id)
        // what the Watch tab shows: reading it there would need Nothing X closed, an install has it anyway
        val onWatch = slot?.let { sl -> installed.map { if (it == sl) id else it } }
            ?: if (id in installed) installed else installed + id
        prefs.edit().putString("dials-$watch", now.joinToString(",")).putString("name-$id", name)
            .putString("faces-on-watch", onWatch.joinToString(",")).putLong("faces-on-watch-date", System.currentTimeMillis())
            .putString("faces-on-watch-count",
                counted?.let { "${if (slot != null || id in installed) it.count else it.count + 1},$cap" })
            .putString("faces-on-watch-header", header)
            .apply()
    } finally {
        link.disconnect()
    }
}
