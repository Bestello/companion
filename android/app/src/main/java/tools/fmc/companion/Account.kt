package tools.fmc.companion

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// FMC login: PocketBase `users` at fmc.tools, as fmc_frontend modules/auth/model/auth.api.ts and ios/App/Account.swift.
// Email and password, plus the OAuth2 providers the server has on (Google). Sign-up stays on the site, but OAuth2
// creates an account on first use, so the app offers account deletion (Google Play's account deletion rule).

data class User(val id: String, val email: String, val name: String)

data class OAuthProvider(val name: String, val displayName: String, val authURL: String, val codeVerifier: String) {
    /** The provider's page with PocketBase's redirect and our realtime client as `state`, as the JS SDK does. */
    fun url(state: String): String {
        val (base, query) = authURL.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val kept = query.split("&").filter { it.isNotEmpty() && !it.startsWith("state=") && !it.startsWith("redirect_uri=") }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return base + "?" + (kept + "state=${enc(state)}" + "redirect_uri=${enc(Account.REDIRECT)}").joinToString("&")
    }
}

class Account(private val store: SecureStore) {
    var token: String? = store["token"]
        private set(value) {
            field = value
            store["token"] = value
        }
    var user: User? = null
        private set

    /** Checks the stored token and renews it; a rejected one signs out, offline keeps it for the next launch. */
    suspend fun restore() {
        if (token == null) return
        try {
            auth("auth-refresh", null)
        } catch (e: ServerError) {
            if (e.status in setOf(401, 403, 404)) signOut()
        } catch (_: Exception) {
        }
    }

    suspend fun providers(): List<OAuthProvider> = runCatching {
        val a = JSONObject(http("GET", api("auth-methods"))).getJSONObject("oauth2").getJSONArray("providers")
        List(a.length()) { a.getJSONObject(it) }.map {
            OAuthProvider(it.getString("name"), it.getString("displayName"), it.getString("authURL"), it.getString("codeVerifier"))
        }
    }.getOrDefault(emptyList())

    suspend fun signIn(email: String, password: String) =
        auth("auth-with-password", JSONObject().put("identity", email).put("password", password))

    /**
     * PocketBase's own OAuth2 flow, as the JS SDK: the provider redirects to fmc.tools, which hands the code to our
     * realtime connection; no URL scheme or provider client of our own. `open` shows the provider's page.
     * Cancel the coroutine to give up (the user closed the page).
     */
    suspend fun signIn(provider: OAuthProvider, open: (String) -> Unit) = coroutineScope {
        val sse = URL("$FMC/api/realtime").openConnection() as HttpURLConnection
        sse.readTimeout = 600_000
        // a blocked read only ends when the connection closes
        val closer = launch { try { awaitCancellation() } finally { sse.disconnect() } }
        try {
            val code = withContext(Dispatchers.IO) {
                var event = ""
                var clientId = ""
                sse.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("event:")) event = line.substring(6).trim()
                        if (!line.startsWith("data:")) continue
                        val data = JSONObject(line.substring(5))
                        when (event) {
                            "PB_CONNECT" -> {
                                clientId = data.getString("clientId")
                                http("POST", "$FMC/api/realtime",
                                    JSONObject().put("clientId", clientId).put("subscriptions", JSONArray().put("@oauth2")))
                                withContext(Dispatchers.Main) { open(provider.url(clientId)) }
                            }
                            "@oauth2" -> {
                                if (data.optString("state") != clientId) continue
                                val code = data.optString("code")
                                if (code.isEmpty()) {
                                    throw ServerError(0, data.optString("error").ifEmpty { null }
                                        ?.let { "${provider.displayName}: $it" } ?: "Sign-in failed")
                                }
                                return@useLines code
                            }
                        }
                    }
                    throw ServerError(0, "Sign-in was interrupted")
                }
            }
            auth("auth-with-oauth2", JSONObject().put("provider", provider.name).put("code", code)
                .put("codeVerifier", provider.codeVerifier).put("redirectURL", REDIRECT))
        } finally {
            closer.cancel()
        }
    }

    /** Deletes the FMC account (PocketBase users.deleteRule lets a user delete themselves). */
    suspend fun deleteAccount() {
        val u = user ?: return
        http("DELETE", "$FMC/api/collections/users/records/${u.id}", token = token)
        signOut()
    }

    fun signOut() {
        token = null
        user = null
    }

    private fun api(path: String) = "$FMC/api/collections/users/$path"

    private suspend fun auth(path: String, body: JSONObject?) {
        val r = JSONObject(http("POST", api(path), body ?: JSONObject(), token))
        val rec = r.getJSONObject("record")
        token = r.getString("token")
        user = User(rec.getString("id"), rec.optString("email"), rec.optString("name"))
    }

    companion object {
        const val SIGN_UP = "$FMC/register"
        const val REDIRECT = "$FMC/api/oauth2-redirect"
    }
}
