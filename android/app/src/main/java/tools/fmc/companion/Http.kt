package tools.fmc.companion

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
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

/**
 * A GET for the miniapp services: (status, body), any status. No cookies (none are set app-wide),
 * `User-Agent: FMC-Companion`, redirects followed only within the same host, at most `max` bytes read (+1 to tell
 * a longer body). `timeoutMs` bounds connect and each read; the dispatcher's budget bounds the whole.
 */
suspend fun fetch(url: String, timeoutMs: Int = 6000, max: Int = 1 shl 20): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
    var u = URL(url)
    repeat(5) {
        val c = u.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "FMC-Companion")
            val status = c.responseCode
            val next = c.getHeaderField("Location")?.let { URL(u, it) }
            if (status in 300..399 && next != null) {
                if (next.protocol != "https" || !next.host.equals(u.host, ignoreCase = true)) return@withContext status to ByteArray(0)
                u = next
                return@repeat
            }
            val body = (if (status < 400) c.inputStream else c.errorStream)?.use { readUpTo(it, max + 1) } ?: ByteArray(0)
            return@withContext status to body
        } finally {
            c.disconnect()
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
