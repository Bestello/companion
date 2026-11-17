package tools.fmc.companion

import android.content.Context
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
suspend fun installFaceOnWatch(context: Context, store: SecureStore, bin: ByteArray, seed: String, progress: (Int) -> Unit) {
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
        // ponytail: no slot picker. A full watch is told to free a slot on the watch; add the picker
        // (ble.ts NoFreeSlotError) if people hit it often.
        val id = try {
            installFace(session, bin, seed, installed, 6, progress = progress)
        } catch (_: UploadError.NoFreeSlot) {
            throw FaceInstallError("The watch is full. Delete a face on the watch (long press the face, swipe one up) and try again.")
        }
        if (id !in installed) prefs.edit().putString("dials-$watch", (mine + id).joinToString(",")).apply()
    } finally {
        link.disconnect()
    }
}
