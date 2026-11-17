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

suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        if (c.responseCode !in 200..299) throw ServerError(c.responseCode, "Download failed (${c.responseCode})")
        c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}
