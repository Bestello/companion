package tools.fmc.companion

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val FMC = "https://fmc.tools"

class ServerError(val status: Int, message: String) : Exception(message)

/** One request to fmc.tools; the body as text. A non-2xx reply throws with PocketBase's own message. */
suspend fun http(method: String, url: String, json: JSONObject? = null, token: String? = null): String =
    withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            token?.let { c.setRequestProperty("Authorization", it) }
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toString().toByteArray()) }
            }
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).getString("message") }.getOrNull()
                throw ServerError(status, message ?: "Request failed ($status)")
            }
            body
        } finally {
            c.disconnect()
        }
    }

/** `first`: only that many bytes from the start (a Range request; a server that ignores it sends the whole file). */
suspend fun download(url: String, first: Int? = null): ByteArray = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        first?.let { c.setRequestProperty("Range", "bytes=0-${it - 1}") }
        if (c.responseCode !in 200..299) throw ServerError(c.responseCode, "Download failed (${c.responseCode})")
        c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}

private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

/**
 * A request for the miniapp services (and the weather): (status, body), any status. OkHttp, since HttpURLConnection
 * refuses PATCH. `User-Agent: FMC-Companion`, then `headers`; cookies from and into `cookies`. Redirects followed
 * only within the same host (301-303 turn into a GET without the body); at most `max` bytes read (+1 to tell a
 * longer body). `timeoutMs` bounds connect and each read; the dispatcher's budget bounds the whole.
 */
suspend fun fetch(
    url: String, timeoutMs: Int = 6000, max: Int = 1 shl 20, method: String = "GET", body: ByteArray = ByteArray(0),
    headers: List<Pair<String, String>> = emptyList(), cookies: CookieJar = CookieJar.NO_COOKIES,
): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
    val c = client.newBuilder().cookieJar(cookies).connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
    var u = url.toHttpUrl()
    var m = method
    var b = body
    repeat(5) {
        val req = Request.Builder().url(u).header("User-Agent", "FMC-Companion")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .method(m, if (m == "GET" || (m == "DELETE" && b.isEmpty())) null else b.toRequestBody())
            .build()
        c.newCall(req).execute().use { r ->
            val next = r.header("Location")?.let { u.resolve(it) }
            if (r.code in 300..399 && next != null) {
                if (!next.isHttps || !next.host.equals(u.host, ignoreCase = true)) return@withContext r.code to ByteArray(0)
                if (r.code in 301..303) { m = "GET"; b = ByteArray(0) }
                u = next
                return@repeat
            }
            return@withContext r.code to (r.body?.byteStream()?.use { readUpTo(it, max + 1) } ?: ByteArray(0))
        }
    }
    throw ServerError(310, "Too many redirects")
}

/** InputStream.readNBytes is API 33. */
private fun readUpTo(input: java.io.InputStream, n: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val b = ByteArray(8192)
    while (out.size() < n) {
        val k = input.read(b, 0, minOf(b.size, n - out.size()))
        if (k < 0) break
        out.write(b, 0, k)
    }
    return out.toByteArray()
}
