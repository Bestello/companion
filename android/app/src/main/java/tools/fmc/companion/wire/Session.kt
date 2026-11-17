package tools.fmc.companion.wire

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel as Queue

// A session keyed with K (docs/abi-1.4.md) over a GATT link: no bind, no popup, next to Nothing X on the shared
// connection. Mirrors link.Session.open_channel + channel.send/recv/ping of the Python host; port of
// ios/WireKit Session.swift and Channel.swift. Times are milliseconds.

object Uuid {
    const val SHELL_WRITE = "77D4FF01-2FE2-2334-0D35-9CCD078F529C"
    const val SHELL_NOTIFY = "77D4FF02-2FE2-2334-0D35-9CCD078F529C"
    const val CMD_WRITE = "0000FFF2-0000-1000-8000-00805F9B34FB"
    const val CMD_NOTIFY = "0000FFF1-0000-1000-8000-00805F9B34FB"
}

/** GATT as the sessions need it. BLE in the app; a fake stub in the tests. */
interface Link {
    /** Writes a characteristic (the BLE side picks write-with-response for frames over MTU-3). */
    suspend fun write(uuid: String, data: ByteArray)

    /** Called for every notification, in order, on one thread: (characteristic uuid, value). */
    fun onNotify(handler: (String, ByteArray) -> Unit)
}

object Cmd {
    const val INFO = 0x90A0
    const val BEGIN = 0x90A1
    const val DATA = 0x90A2
    const val END = 0x90A3
    const val DELETE = 0x90A4
    const val MSG = 0x90A5
    const val PING = 0x90A7
    const val EVENT = 0xB0A6
}

enum class Status { OK, BAD_REQUEST, IO, SEQUENCE, CRC, BUSY, INVALID, NO_APP, FULL, PAUSED;

    val code get() = ordinal
}

sealed class WireError(message: String) : Exception(message) {
    data class Timeout(val what: String) : WireError(what)
    data object NoApp : WireError("no app running on the watch")
    data class Refused(val status: Int) : WireError("refused (%02X)".format(status))
}

/** App-level messages to the running watch app. */
interface Channel {
    suspend fun send(data: ByteArray)

    /** The next message of the app; `WireError.Timeout` when none comes in time. */
    suspend fun recv(timeoutMs: Long): ByteArray
}

/** What install and the loader check need from a session: one request, the plain reply. */
fun interface Requester {
    suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long): ByteArray
}

suspend fun Requester.call(c2: Int, payload: ByteArray) = call(c2, payload, 6000)

internal fun <T> Queue<T>.drain() {
    while (tryReceive().isSuccess) {}
}

internal suspend fun <T> Queue<T>.next(timeoutMs: Long): T? =
    if (timeoutMs <= 0) tryReceive().getOrNull() else withTimeoutOrNull(timeoutMs) { receive() }

class KSession private constructor(private val link: Link, val key: ByteArray, var pollMs: Long?) :
    Channel, Requester {
    private val frames = Queue<Pair<Int, ByteArray>>(Queue.UNLIMITED)
    private val events = Queue<ByteArray>(Queue.UNLIMITED)
    private val parser = FrameParser()

    companion object {
        /**
         * Reads the pairing secret (or takes the cached one), subscribes and registers with one PING, so the stub
         * sends this host the app's messages. `register = false` skips the PING: a stock watch never answers it,
         * and `readLoader` tells stock firmware apart faster with INFO.
         */
        suspend fun open(link: Link, secretHex: String? = null, pollMs: Long? = null, register: Boolean = true):
            Pair<KSession, String> {
            val shell = Queue<ByteArray>(Queue.UNLIMITED)
            // written here, read on the binder thread: the atomic publishes it (Swift: SessionBox)
            val session = AtomicReference<KSession?>()
            link.onNotify { uuid, value ->
                when (uuid.uppercase()) {
                    Uuid.SHELL_NOTIFY -> shell.trySend(value)
                    Uuid.CMD_NOTIFY -> session.get()?.feed(value)
                }
            }
            var secret = secretHex
            var tries = 0
            while (secret == null) {
                tries++
                link.write(Uuid.SHELL_WRITE, "AT GETSECRET\r\n".toByteArray())
                var reply = ""
                while (listOf("OK", "ERROR", "FAIL").none { reply.contains(it) }) {
                    val d = shell.next(6000) ?: break
                    reply += String(d)
                }
                val t = reply.trim()
                if (t.startsWith("GETSECRET:") && t.endsWith(",OK")) {
                    secret = t.removePrefix("GETSECRET:").removeSuffix(",OK")
                } else if (tries == 3) {
                    throw WireError.Timeout("no pairing secret from the watch shell")
                }
            }
            val key = channelKey(secret) ?: throw WireError.Refused(0xFF)
            val s = KSession(link, key, pollMs)
            session.set(s)
            if (register) s.ping()
            return s to secret
        }
    }

    @Synchronized
    internal fun feed(value: ByteArray) {
        for (f in parser.feed(value)) {
            if (f.c1 != SYSTEM) continue
            // another host's frames (Nothing X, the stock session) do not open with K: dropped where they are read
            if (f.c2 == Cmd.EVENT) events.trySend(f.body) else frames.trySend(f.c2 to f.body)
        }
    }

    /** One system request; the reply that opens with K. */
    private suspend fun request(c2: Int, payload: ByteArray, timeoutMs: Long = 6000): ByteArray {
        frames.drain()
        link.write(Uuid.CMD_WRITE, frame(c2, payload, key))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val (got, body) = (if (left > 0) frames.next(left) else null)
                ?: throw WireError.Timeout("no reply to %04X".format(c2))
            if (got == c2 or 0xA000) open(key, body)?.let { return it }
        }
    }

    /** (running slot, flags): bit 0 ABI 1.1 app, bit 1 paused. */
    suspend fun ping(): Pair<Int, Int> {
        val r = request(Cmd.PING, bytes(0))
        if (r.size != 3 || r.u8(0) != Status.OK.code) throw WireError.Refused(r.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF)
        return r.u8(1) to r.u8(2)
    }

    /** One message to the running app; FULL and PAUSED are retried for 10 s. */
    override suspend fun send(data: ByteArray) {
        val deadline = System.currentTimeMillis() + 10_000
        var delayMs = 20L
        while (true) {
            val r = request(Cmd.MSG, bytes(0xFF) + data)
            val s = if (r.isEmpty()) 0xFF else r.u8(0)
            when (s) {
                Status.OK.code -> return
                Status.NO_APP.code -> throw WireError.NoApp
                Status.FULL.code, Status.PAUSED.code -> {
                    if (System.currentTimeMillis() + delayMs >= deadline) throw WireError.Refused(s)
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, 500)
                }
                else -> throw WireError.Refused(s)
            }
        }
    }

    override suspend fun recv(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw WireError.Timeout("no message")
            val body = events.next(pollMs?.let { minOf(it, left) } ?: left)
            if (body != null) {
                val plain = open(key, body)
                if (plain != null && plain.size >= 3) return plain.copyOfRange(2, plain.size)
                continue
            }
            if (pollMs != null) runCatching { ping() }
        }
    }

    /** Raw request for install and the loader check (INFO, BEGIN, DATA, END, DELETE). */
    override suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long) = request(c2, payload, timeoutMs)
}
