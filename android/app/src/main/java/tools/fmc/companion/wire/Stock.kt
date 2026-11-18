package tools.fmc.companion.wire

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.channels.Channel as Queue

// The stock session (Nothing X's protocol) and the watchface upload over it: port of fmc_frontend device/lib
// ble-protocol.ts (Codec) and ble.ts (pair, authenticate, uploadWatchface); docs/cmf-protocol.md §9.5.
// Port of ios/WireKit Stock.swift.
//
// Not K: watchface commands are sealed with the stock session key, and pair/authenticate replace the authkey and
// session key the watch keeps for Nothing X (cmf-watch-sdk docs/recon.md, "Bind popup, authkey and session key").
// Two stock sessions can't share the link: Nothing X has to be closed first, as with Bluefy (FMC-8, item 2).
// Channels as on the site: auth on fff2/fff1 (`send`), the upload on ffe1/ffe2 (`sendData`).

object StockCmd {
    private fun k(c2: Int): Long = 0xFFFF_0000L or c2.toLong()
    val AUTH_PAIR_REQ = k(0x8047); val AUTH_PAIR_REP = k(0x0048); val AUTH_NAME = k(0x8049); val AUTH_MAC = k(0x0049)
    val AUTH_NONCE_REQ = k(0x804B); val AUTH_NONCE_REP = k(0x004C); val AUTH_CONFIRM_REQ = k(0x804D)
    val AUTH_CONFIRM_REP = k(0x0004); val AUTH_FAILED = k(0xA061)
    val WF_INIT1_REQ = k(0x8052); val WF_INIT1_REP = k(0x0052); val WF_INIT2_REQ = k(0x9075); val WF_INIT2_REP = k(0xA075)
    val WF_CHUNK_REQ = k(0xA064); val WF_CHUNK_WRITE = k(0x9064); val WF_FINISH_ACK1 = k(0xA065)
    val WF_FINISH_ACK2 = k(0x9065); val WF_INSTALLED = k(0xA055)
    /** get (`00`) or set (`01 ‖ active ‖ count ‖ 00 ‖ ids`) the dial list (cmf-watch-sdk docs/recon.md, "Stock watch faces") */
    val WF_LIST = k(0x9055)
    /** ble-protocol.ts `time`, `fwGet` */
    val TIME = k(0x8004); val FW_GET = k(0x8006)
    /** The firmware update (ble.ts flashFirmware, cmf-watch-sdk docs/ota.md). a043/a044 end it, meaning undocumented. */
    val OTA_BEGIN_REQ = k(0x9052); val OTA_BEGIN_REP = k(0xA052); val OTA_START_REQ = k(0x9040); val OTA_START_REP = k(0xA040)
    val OTA_CHUNK_REQ = k(0xA042); val OTA_CHUNK_WRITE = k(0x9042); val OTA_VERDICT = k(0xA041); val OTA_COMMIT = k(0x9041)
    val OTA_STATUS_43 = k(0xA043); val OTA_STATUS_44 = k(0xA044)

    /** Sent and read in the clear (body ‖ crc32le); `AUTH_FAILED` because the watch could not decrypt. */
    val PLAIN = setOf(AUTH_PAIR_REQ, AUTH_PAIR_REP, WF_CHUNK_WRITE, OTA_CHUNK_WRITE, AUTH_FAILED)

    /** What goes over the data pair (ffe1); everything else goes over fff2. */
    val DATA = setOf(WF_INIT1_REQ, WF_INIT2_REQ, WF_CHUNK_WRITE, WF_FINISH_ACK2)

    /** Not a command: a `0x0049` that opens only with the all-zero key (the bind popup was confirmed). */
    const val ZERO_KEY_REPLY = 0L
}

const val MARKER = 0xA5

/** `UUID.dataWrite` / `dataRead`: the stock session's pair. */
object DataUuid {
    const val WRITE = "02F00000-0000-0000-0000-00000000FFE1"
    const val NOTIFY = "02F00000-0000-0000-0000-00000000FFE2"
}

/** `UUID.otaWrite` / `otaRead`: the firmware update's pair. */
object OtaUuid {
    const val WRITE = "02F00000-0000-0000-0000-00000000FF01"
    const val NOTIFY = "02F00000-0000-0000-0000-00000000FF02"
}

/**
 * `Codec.encode`: frames of at most `mtu` bytes; sealed chunks of `(mtu-11)/16*16 - 5` plain bytes, plain chunks of
 * `mtu - 20`, or [plainSize] (the firmware's 224, proven on hardware). Header: F5, length, c1, count, index, c2 (big endian).
 */
fun stockFrames(cmd: Long, payload: ByteArray, key: ByteArray?, mtu: Int = 180, plainSize: Int? = null): List<ByteArray> {
    val isPlain = cmd in StockCmd.PLAIN
    val size = if (isPlain) plainSize ?: (mtu - 20) else (mtu - 11) / 16 * 16 - 5
    val chunks = if (payload.isEmpty()) listOf(ByteArray(0)) else (payload.indices step size).map { off ->
        val ch = payload.copyOfRange(off, minOf(off + size, payload.size))
        if (isPlain) ch + buf { le32(crc32(ch)) } else seal(requireNotNull(key), ch)
    }
    return chunks.mapIndexed { i, ch ->
        buf {
            u8(0xF5); be16(ch.size); be16((cmd shr 16).toInt()); be16(chunks.size); be16(i + 1)
            be16((cmd and 0xFFFF).toInt()); add(ch)
        }
    }
}

sealed class StockFailure : Exception() {
    data object BadFrame : StockFailure()
    data class CannotOpen(val cmd: Long) : StockFailure()
}

/** `Codec.decode`: one notification is one frame; multi-frame commands are reassembled per command. */
class StockParser {
    private class Partial(var expected: Int, var data: ByteArray)

    private val bufs = mutableMapOf<Long, Partial>()

    /** The whole command once its last frame is in, else null. */
    fun feed(f: ByteArray, key: ByteArray?): Pair<Long, ByteArray>? {
        if (f.size < 11 || f.u8(0) != 0xF5) throw StockFailure.BadFrame
        val len = f.be16(1)
        val cmd = (f.be16(3).toLong() shl 16) or f.be16(9).toLong()
        val count = f.be16(5)
        val index = f.be16(7)
        var payload = ByteArray(0)
        if (len > 0) {
            val rest = f.copyOfRange(11, f.size)
            if (cmd in StockCmd.PLAIN) {
                // body ‖ crc32le
                payload = rest.copyOf(minOf(maxOf(len - 4, 0), rest.size))
            } else {
                val body = rest.copyOf(minOf(len, rest.size))
                if (key == null || body.size != len) throw StockFailure.CannotOpen(cmd)
                payload = open(key, body) ?: throw StockFailure.CannotOpen(cmd)
            }
        }
        if (count <= 1) return cmd to payload
        val b = bufs[cmd] ?: Partial(1, ByteArray(0))
        if (index != b.expected) {
            if (index != 1) return null
            b.data = ByteArray(0)
        }
        b.data += payload
        b.expected = index + 1
        bufs[cmd] = b
        if (index != count) return null
        bufs.remove(cmd)
        return cmd to b.data
    }
}

internal fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }

/** `pair()`: the request for random1, and k1 once the watch's reply proved it knows the secret too. */
fun pairRequest(random1: ByteArray, secret: ByteArray) = random1 + sha256(random1, secret)

fun pairKey(random1: ByteArray, reply: ByteArray, secret: ByteArray): ByteArray? {
    if (reply.size < 48) return null
    val random2 = reply.copyOf(16)
    if (!reply.copyOfRange(16, 48).contentEquals(sha256(random2, secret))) return null
    return sha256(random1, random2, secret).copyOf(16)
}

fun sessionKey(nonce: ByteArray, k1: ByteArray) = sha256(nonce, k1).copyOf(16)

/** `a055`: built-ins ‖ FFFFFFFF ‖ gallery ids after a 4-byte header, flattened. */
fun parseInstalledDials(p: ByteArray): List<Long> =
    (4 until p.size - 3 step 4).map { p.le32(it) }.filter { it != 0xFFFF_FFFFL }

/**
 * `a055`'s 4-byte header, read as `01 ‖ active? ‖ count ‖ capacity`. Unconfirmed reading from five samples
 * (watch_health docs/cmf-protocol.md §9.5d-h, fmc_frontend ble.ts): `count` matched the dials on the watch side-loaded
 * ones included (which the id list leaves out), `capacity` was always 7. null when it doesn't look like that.
 */
data class DialCount(val count: Int, val capacity: Int)

fun parseDialCount(p: ByteArray): DialCount? {
    if (p.size < 4) return null
    val count = p[2].toInt() and 0xFF
    val capacity = p[3].toInt() and 0xFF
    return if (capacity > 0 && count <= capacity) DialCount(count, capacity) else null
}

/** `init2`: ctr_type 3 ‖ old_wf_id ‖ new_wf_id ‖ size, little endian. */
fun uploadInit(old: Long, new: Long, size: Int) = buf { u8(3); le32(old); le32(new); le32(size.toLong()) }

sealed class UploadError(message: String) : Exception(message) {
    /** The watch has no free slot: ask which dial to replace. */
    data object NoFreeSlot : UploadError("no free slot")

    /** The watch refused to replace this dial (it isn't there, or won't take it as a target). */
    data class StaleSlot(val id: Long) : UploadError("the watch refused to replace dial $id")
    data class Rejected(val why: String) : UploadError(why)
    data object Stalled : UploadError("the watch stopped asking for data")
    data object AuthFailed : UploadError("the watch refused the key")

    /** The bind popup was confirmed on the watch, which zeroed the new authkey: bind again without touching it. */
    data object Rebind : UploadError("bind again")
}

/** One stock session over the GATT link: auth on fff2 (replies on fff1), the upload on ffe1 (replies on ffe2). */
class StockSession(internal val link: Link, internal val mtu: Int = 180, key: ByteArray? = null) {
    @Volatile internal var key: ByteArray? = key
    private val parser = StockParser()
    internal val inbox = Queue<Pair<Long, ByteArray>>(Queue.UNLIMITED)

    /** Latest `a055`: the dials the watch reported (side-loaded ones are missing from it). */
    @Volatile var reported: List<Long>? = null
    /** Every entry of the latest `a055`, the photo face (FFFFFFFF) included: what a set sends back. */
    @Volatile var listed: List<Long>? = null
    /** Latest `a055` header: how many dials, side-loaded included, and how many fit ([parseDialCount]). */
    @Volatile var dialCount: DialCount? = null
    /** Latest `a055` header, raw, for field reports. */
    @Volatile var dialHeader: ByteArray? = null
        private set

    init {
        link.onNotify { uuid, value ->
            // K frames on fff1 don't open with F5: the parser drops them
            if (uuid.uppercase() in setOf(DataUuid.NOTIFY, OtaUuid.NOTIFY, Uuid.CMD_NOTIFY)) feed(value)
        }
    }

    @Synchronized
    private fun feed(f: ByteArray) {
        val got = try {
            parser.feed(f, key)
        } catch (e: StockFailure.CannotOpen) {
            // recon.md, "Bind popup…": confirming the popup zeroes the authkey and the reply comes under zero
            if (e.cmd == StockCmd.AUTH_MAC && runCatching { StockParser().feed(f, ByteArray(16)) }.getOrNull() != null) {
                inbox.trySend(StockCmd.ZERO_KEY_REPLY to ByteArray(0))
            }
            return
        } catch (_: StockFailure) {
            return
        } ?: return
        // a set's ack is a055 with the single byte 00
        if (got.first == StockCmd.WF_INSTALLED && got.second.size >= 4) {
            listed = (4 until got.second.size - 3 step 4).map { got.second.le32(it) }
            reported = parseInstalledDials(got.second)
            dialHeader = got.second.copyOf(minOf(4, got.second.size))
            dialCount = parseDialCount(got.second)
        }
        inbox.trySend(got)
    }

    internal suspend fun send(cmd: Long, payload: ByteArray) {
        val uuid = if (cmd in StockCmd.DATA) DataUuid.WRITE else Uuid.CMD_WRITE
        for (f in stockFrames(cmd, payload, key, mtu)) link.write(uuid, f)
    }

    /**
     * The next command out of `wanted`. `AUTH_FAILED` ends the wait only during auth: on the shared link it also
     * answers Nothing X's frames under the key we replaced, which must not cut an upload short.
     */
    internal suspend fun next(wanted: Set<Long>, timeoutMs: Long = 8000, auth: Boolean = false): Pair<Long, ByteArray> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val m = (if (left > 0) inbox.next(left) else null)
                ?: throw WireError.Timeout("no " + wanted.joinToString("/") { "%04X".format(it and 0xFFFF) })
            if (auth && m.first == StockCmd.AUTH_FAILED) throw UploadError.AuthFailed
            if (auth && m.first == StockCmd.ZERO_KEY_REPLY) throw UploadError.Rebind
            if (m.first in wanted) return m
        }
    }

    /**
     * A new bind: the watch shows its "Paired" popup, and the authkey Nothing X knows is replaced. As the SDK's
     * host (`link.py` `_bind`): the first `0x8049` only opens the popup, the second is answered; a confirmed popup
     * zeroes the authkey, so that attempt starts over.
     */
    suspend fun pair(secret: ByteArray, attempts: Int = 3): ByteArray {
        for (attempt in 1..attempts) {
            key = null
            inbox.drain()
            val random1 = ByteArray(16).also(SecureRandom()::nextBytes)
            send(StockCmd.AUTH_PAIR_REQ, pairRequest(random1, secret))
            val (_, reply) = next(setOf(StockCmd.AUTH_PAIR_REP), auth = true)
            val k1 = pairKey(random1, reply, secret) ?: throw UploadError.Rejected("watch signature mismatch")
            key = k1
            send(StockCmd.AUTH_NAME, PHONE)
            try {
                try {
                    name(2000)
                } catch (_: WireError.Timeout) {
                    name(8000)
                }
                openSession(k1)
                return k1
            } catch (e: UploadError.Rebind) {
                if (attempt == attempts) throw e
            }
        }
        throw UploadError.Rebind
    }

    private suspend fun name(timeoutMs: Long) {
        send(StockCmd.AUTH_NAME, PHONE)
        next(setOf(StockCmd.AUTH_MAC), timeoutMs, auth = true)
    }

    /** Opens a session with a known authkey: replaces the session key the watch keeps (and saves to NVRAM). */
    suspend fun authenticate(k1: ByteArray) {
        key = k1
        inbox.drain()
        name(8000)
        openSession(k1)
    }

    /**
     * The dials on the watch. a055 comes with the housekeeping after the time and fwGet the site sends right after
     * auth (ble.ts fetchInfo), not on auth alone; waits up to [timeoutMs] for it.
     */
    suspend fun installedDials(timeoutMs: Long = 4000): List<Long>? {
        if (reported == null) {
            val now = System.currentTimeMillis() / 1000
            val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() // ms, as tz * 1000
            runCatching { send(StockCmd.TIME, buf { be32(now); be32(tz and 0xFFFF_FFFFL) }) }
            runCatching { send(StockCmd.FW_GET, ByteArray(0)) }
            runCatching { next(setOf(StockCmd.WF_INSTALLED), timeoutMs) }
        }
        return reported
    }

    /**
     * Sets the dial list: the watch keeps the given ids it has, in this order, drops the rest, and shows `ids[active]`.
     * It can't add a face. Reads the list again (the ack says nothing about what matched).
     */
    suspend fun setDials(ids: List<Long>, active: Int) {
        send(StockCmd.WF_LIST, buf { u8(1); u8(active); u8(ids.size); u8(0); ids.forEach { le32(it) } })
        next(setOf(StockCmd.WF_INSTALLED), 4000)
        reported = null
        send(StockCmd.WF_LIST, bytes(0))
        next(setOf(StockCmd.WF_INSTALLED), 4000)
    }

    private suspend fun openSession(k1: ByteArray) {
        send(StockCmd.AUTH_NONCE_REQ, bytes(MARKER))
        val (_, nonce) = next(setOf(StockCmd.AUTH_NONCE_REP), auth = true)
        key = sessionKey(nonce, k1)
        send(StockCmd.AUTH_CONFIRM_REQ, bytes(MARKER))
        next(setOf(StockCmd.AUTH_CONFIRM_REP), auth = true)
    }

    /** One upload attempt: init1, init2, then the watch pulls ranges until it says yes or no. */
    suspend fun upload(bin: ByteArray, old: Long, new: Long, progress: (Int) -> Unit) {
        inbox.drain()
        send(StockCmd.WF_INIT1_REQ, bytes(MARKER))
        val (_, r1) = next(setOf(StockCmd.WF_INIT1_REP))
        if (r1.firstOrNull()?.toInt() != 1) throw UploadError.Rejected("init1 ${r1.hex()}")
        send(StockCmd.WF_INIT2_REQ, uploadInit(old, new, bin.size))
        val (_, r2) = next(setOf(StockCmd.WF_INIT2_REP))
        if (r2.firstOrNull()?.toInt() != 1) throw if (old == 0L) UploadError.NoFreeSlot else UploadError.StaleSlot(old)
        while (true) {
            val (cmd, p) = try {
                next(setOf(StockCmd.WF_CHUNK_REQ, StockCmd.WF_FINISH_ACK1), 30_000)
            } catch (_: WireError.Timeout) {
                throw UploadError.Stalled
            }
            if (cmd == StockCmd.WF_FINISH_ACK1) {
                runCatching { send(StockCmd.WF_FINISH_ACK2, bytes(MARKER)) }
                when (p.firstOrNull()?.toInt()) {
                    1 -> { progress(100); return }
                    // 0x0A, "stored but not activated": on an append the only answer a full watch gives
                    0x0A -> throw if (old == 0L) UploadError.NoFreeSlot else UploadError.StaleSlot(old)
                    else -> throw UploadError.Rejected("finish ${p.hex()}")
                }
            }
            if (p.size < 9) throw UploadError.Rejected("short chunk request ${p.hex()}")
            val off = p.be32(0)
            val len = p.be32(4)
            if (off + len > bin.size) throw UploadError.Rejected("chunk out of bounds $off+$len")
            progress(p.u8(8))
            send(StockCmd.WF_CHUNK_WRITE, bin.copyOfRange(off.toInt(), (off + len).toInt()))
        }
    }

    private companion object {
        val PHONE = bytes(MARKER) + "fmc".toByteArray()
    }
}

/**
 * `uploadWatchface`'s slot policy. `installed`: what the watch reported plus what this app put there; `slot`: the
 * dial the user picked to replace. Returns the new dial id.
 */
suspend fun installFace(
    s: StockSession, bin: ByteArray, seed: String?, installed: List<Long>, capacity: Int, slot: Long? = null,
    progress: (Int) -> Unit,
): Long {
    val name = faceName(bin)
    if (name == null || bin.size > 32 shl 20) throw UploadError.Rejected("not a watchface")
    val id = dialId(seed ?: name)
    val old = slot ?: if (id in installed) id else 0L
    if (old == 0L && installed.size >= capacity) throw UploadError.NoFreeSlot
    try {
        s.upload(bin, old, id, progress)
    } catch (e: UploadError.StaleSlot) {
        if (e.id != old) throw e
        // a dial a055 listed is no guess: the watch has it and won't take it as a target; the user picks another
        if (old in (s.reported ?: emptyList())) throw e
        // a guessed target the watch doesn't have (deleted on the watch, a reset): append instead
        if (installed.count { it != old } >= capacity) throw UploadError.NoFreeSlot
        try {
            s.upload(bin, 0, id, progress)
        } catch (e2: UploadError.NoFreeSlot) {
            // 0A again on the append after the user's pick isn't about slots: no second slot dialog
            if (slot != null) throw UploadError.Rejected("stored but not activated") else throw e2
        }
    }
    return id
}
