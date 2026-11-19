package tools.fmc.companion.wire

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

// Miniapp services RPC (docs/miniapp-rpc.md): requests from a watch app, replies from the phone's services.
// Byte layouts are fixed by rpc-vectors.json, shared with iOS. Everything here is pure but `answer`, which runs a
// service through `Phone`.

object Method {
    const val CAPS = 0x01
    const val CALENDAR = 0x10
    const val LOCATION = 0x20
    const val WEATHER = 0x30
    const val HTTP = 0x40
    const val HTTP_REQUEST = 0x41
    const val NOTIFICATIONS = 0x50
}

object RpcStatus {
    const val OK = 0
    const val BAD_REQUEST = 1
    const val DENIED = 2
    const val UNAVAILABLE = 3
    const val ASK = 4
    const val TOO_LARGE = 5
}

/** A service's answer other than OK. */
class RpcError(val status: Int) : Exception("rpc status $status")

/** `method` null: an `R1Q` with an id the phone can't read further, answered `BAD_REQUEST`. */
class Request(val id: Int, val method: Int?, val args: ByteArray)

/** null: not a request (another prefix, `R1` ‖ other kind, or no id byte): dropped. */
fun parseRequest(m: ByteArray): Request? {
    if (m.size < 4 || m[0] != 'R'.code.toByte() || m[1] != '1'.code.toByte() || m[2] != 'Q'.code.toByte()) return null
    if (m.size < 5) return Request(m.u8(3), null, ByteArray(0))
    return Request(m.u8(3), m.u8(4), m.copyOfRange(5, m.size))
}

const val RECORD_MAX = 121

/** One message per record; none or an error: one message, total 0. */
fun replies(id: Int, status: Int, records: List<ByteArray> = emptyList()): List<ByteArray> {
    fun msg(index: Int, total: Int, record: ByteArray) =
        buf { add("R1A".toByteArray()); u8(id); u8(status); u8(index); u8(total); add(record) }
    if (status != RpcStatus.OK || records.isEmpty()) return listOf(msg(0, 0, ByteArray(0)))
    return records.mapIndexed { i, r -> msg(i, records.size, r) }
}

/** UTF-8 of `s`, at most `max` bytes, cut before a character that does not fit. */
fun utf8Cut(s: String, max: Int): ByteArray {
    val b = s.toByteArray()
    if (b.size <= max) return b
    var n = max
    while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
    return b.copyOf(n)
}

fun rgb565(argb: Int): Int = ((argb shr 19 and 0x1F) shl 11) or ((argb shr 10 and 0x3F) shl 5) or (argb shr 3 and 0x1F)

class Event(
    val day: Int, val start: Int, val duration: Int, val allDay: Boolean, val tentative: Boolean,
    val color: Int, val title: String,
)

/** (day from today, minute of the day, real minutes to the end) of a timed event, in `now`'s zone. */
fun eventTimes(now: ZonedDateTime, start: Instant, end: Instant): Triple<Int, Int, Int> {
    val s = start.atZone(now.zone)
    val day = ChronoUnit.DAYS.between(now.toLocalDate(), s.toLocalDate()).toInt()
    return Triple(day, s.hour * 60 + s.minute, Duration.between(start, end).toMinutes().toInt())
}

fun calendarRecord(e: Event, show: Boolean): ByteArray = buf {
    u8(e.day); le16(e.start); le16(e.duration)
    u8((if (e.allDay) 1 else 0) or (if (e.tentative) 2 else 0) or (if (show) 0 else 4))
    le16(e.color)
    if (show) add(utf8Cut(e.title, 64))
}

/** 0.1° rounded; the age in minutes, 0xFFFF for older. */
fun locationRecord(lat: Double, lon: Double, ageMin: Long, place: String): ByteArray = buf {
    le16((lat * 10).roundToInt()); le16((lon * 10).roundToInt()); le16(ageMin.coerceIn(0, 0xFFFF).toInt())
    add(utf8Cut(place, 32))
}

fun weatherNow(temp: Double, code: Int, wind: Double, humidity: Int, ageMin: Long): ByteArray = buf {
    u8(0); le16((temp * 10).roundToInt()); u8(code); u8(wind.roundToInt().coerceIn(0, 255))
    u8(humidity.coerceIn(0, 100)); u8(ageMin.coerceIn(0, 255).toInt())
}

fun weatherDay(day: Int, min: Double, max: Double, code: Int, precip: Int): ByteArray = buf {
    u8(1); u8(day); u8(min.roundToInt()); u8(max.roundToInt()); u8(code); u8(precip.coerceIn(0, 100))
}

const val HTTP_BODY_MAX = 2048

/** `code ‖ length`, then the body in record-sized chunks; `TOO_LARGE` over 2048 bytes. */
fun httpRecords(code: Int, body: ByteArray): List<ByteArray> {
    if (body.size > HTTP_BODY_MAX) throw RpcError(RpcStatus.TOO_LARGE)
    val chunks = (body.indices step RECORD_MAX).map { body.copyOfRange(it, minOf(it + RECORD_MAX, body.size)) }
    return listOf(buf { le16(code); le16(body.size) }) + chunks
}

fun notificationRecord(ageMin: Long, app: String, text: String, show: Boolean): ByteArray {
    val label = utf8Cut(app, 24)
    return buf {
        le16(ageMin.coerceIn(0, 0xFFFF).toInt()); u8(if (show) 0 else 4); u8(label.size); add(label)
        if (show) add(utf8Cut(text, 64))
    }
}

/** The lowercased host of an `https://` URL of at most `max` bytes; null for anything else. */
fun httpHost(args: ByteArray, max: Int = 123): String? {
    if (args.isEmpty() || args.size > max) return null
    val uri = runCatching { URI(String(args)) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https") return null
    return uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** One `http.request` (0x41), or `http.get` as GET with no body. `host` lowercased: the cap is `http:<host>`. */
class HttpCall(val method: String, val url: String, val host: String, val body: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?) = other is HttpCall && method == other.method && url == other.url && body.contentEquals(other.body)
    override fun hashCode() = url.hashCode()
    override fun toString() = "$method $url ${body.size} B"

    companion object {
        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
    }
}

/** The joined `http.request` payload, `method ‖ url_len ‖ url ‖ body`; null: unknown method, not https, a url past
 *  the end, GET with a body. */
fun parseHttpCall(p: ByteArray): HttpCall? {
    if (p.size < 2 || p.u8(0) >= HttpCall.METHODS.size || p.size < 2 + p.u8(1)) return null
    val urlBytes = p.copyOfRange(2, 2 + p.u8(1))
    val host = httpHost(urlBytes, max = 255) ?: return null
    val body = p.copyOfRange(2 + p.u8(1), p.size)
    if (p.u8(0) == 0 && body.isNotEmpty()) return null
    return HttpCall(HttpCall.METHODS[p.u8(0)], String(urlBytes), host, body)
}

/** The user's headers (name to value), plus `Content-Type: application/json` for a body that starts with `{` or `[`
 *  when they set none. */
fun requestHeaders(user: List<Pair<String, String>>, body: ByteArray): List<Pair<String, String>> {
    val named = user.filter { it.first.isNotBlank() }
    val json = body.firstOrNull()?.let { it == '{'.code.toByte() || it == '['.code.toByte() } == true
    if (!json || named.any { it.first.equals("content-type", ignoreCase = true) }) return named
    return named + ("Content-Type" to "application/json")
}

/** Joins the parts of `http.request` (`index ‖ total ‖ chunk`), one open request per package. */
class RequestParts {
    private class Open(val id: Int, val total: Int, val next: Int, val data: ByteArray)
    private val open = mutableMapOf<String, Open>()

    /** The joined payload after the last part, null before it. `BAD_REQUEST` for a part out of order, a new id
     *  before the last part, or total outside 1..9; the open request is dropped then. */
    @Synchronized fun add(pkg: String, id: Int, args: ByteArray): ByteArray? {
        val prev = open.remove(pkg)
        if (args.size < 2 || args.size - 2 > RECORD_MAX || args.u8(1) !in 1..9) throw RpcError(RpcStatus.BAD_REQUEST)
        val index = args.u8(0)
        val total = args.u8(1)
        val chunk = args.copyOfRange(2, args.size)
        val data = if (prev != null) {
            if (prev.id != id || prev.total != total || index != prev.next) throw RpcError(RpcStatus.BAD_REQUEST)
            prev.data + chunk
        } else {
            if (index != 0) throw RpcError(RpcStatus.BAD_REQUEST)
            chunk
        }
        if (index == total - 1) return data
        open[pkg] = Open(id, total, index + 1, data)
        return null
    }
}

/** The cap a request needs; null: unknown method or args it can't take. */
fun capOf(method: Int, args: ByteArray): String? = when (method) {
    Method.CALENDAR -> "calendar".takeIf { args.size == 3 }
    Method.LOCATION -> "location"
    Method.WEATHER -> "weather".takeIf { args.size == 1 }
    Method.HTTP -> httpHost(args)?.let { "http:$it" }
    Method.HTTP_REQUEST -> parseHttpCall(args)?.let { "http:${it.host}" }
    Method.NOTIFICATIONS -> "notifications".takeIf { args.size == 1 }
    else -> null
}

/** What the dispatcher needs from the phone: the grants, the OS permissions and the services. */
interface Phone {
    /** cap → allowed (true) or denied (false); a cap with no answer yet is missing. */
    fun grants(pkg: String): Map<String, Boolean>
    fun osAllows(cap: String): Boolean

    /** Keeps the pending request and tells the user, at most once a day per (pkg, cap). `write`: http.request, which
     *  can change data on the host (the ask says so). */
    fun ask(pkg: String, cap: String, write: Boolean)

    /** Joins http.request parts; one per phone. */
    val parts: RequestParts

    /** The records of a granted call; `RpcError` for any other status. For http.request `args` is the joined
     *  payload. */
    suspend fun call(pkg: String, method: Int, args: ByteArray): List<ByteArray>
}

/** The reply messages to one message of `pkg`; null: not a request, nothing to send. */
suspend fun answer(pkg: String, msg: ByteArray, phone: Phone, budgetMs: Long = 7000): List<ByteArray>? {
    val r = parseRequest(msg) ?: return null
    val method = r.method ?: return replies(r.id, RpcStatus.BAD_REQUEST)
    val grants = phone.grants(pkg)
    fun ok(cap: String) = grants[cap] == true && phone.osAllows(cap)
    if (method == Method.CAPS) {
        val fixed = listOf(Method.CALENDAR to "calendar", Method.LOCATION to "location", Method.WEATHER to "weather",
            Method.NOTIFICATIONS to "notifications").filter { ok(it.second) }.map { it.first }
        val http = if (grants.any { it.key.startsWith("http:") && it.value }) listOf(Method.HTTP, Method.HTTP_REQUEST) else emptyList()
        return replies(r.id, RpcStatus.OK, listOf((fixed + http).sorted().map { it.toByte() }.toByteArray()))
    }
    // http.request: no reply before its last part, then the joined payload stands for the args
    val args = if (method != Method.HTTP_REQUEST) r.args else try {
        phone.parts.add(pkg, r.id, r.args) ?: return emptyList()
    } catch (e: RpcError) {
        return replies(r.id, e.status)
    }
    val cap = capOf(method, args) ?: return replies(r.id, RpcStatus.BAD_REQUEST)
    when (grants[cap]) {
        null -> { phone.ask(pkg, cap, method == Method.HTTP_REQUEST); return replies(r.id, RpcStatus.ASK) }
        false -> return replies(r.id, RpcStatus.DENIED)
        true -> if (!phone.osAllows(cap)) return replies(r.id, RpcStatus.DENIED)
    }
    // not a child of the caller: a service stuck in blocking I/O must not hold the reply past the budget
    val run = CoroutineScope(Dispatchers.IO).async { runCatching { phone.call(pkg, method, args) } }
    val result = withTimeoutOrNull(budgetMs) { run.await() }
        ?: return replies(r.id, RpcStatus.UNAVAILABLE).also { run.cancel() }
    return result.fold({ replies(r.id, RpcStatus.OK, it) }, { replies(r.id, (it as? RpcError)?.status ?: RpcStatus.UNAVAILABLE) })
}
