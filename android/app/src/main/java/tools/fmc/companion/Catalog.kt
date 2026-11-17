package tools.fmc.companion

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

// FMC catalog: PocketBase `watchfaces` at fmc.tools, as fmc_frontend modules/market/model/market.api.ts
// (loadMarketFx, loadMyFx, loadWatchfaceFx) and ios/App/Catalog.swift. Read only; likes, publishing and the editor
// stay on the site.

data class Watchface(
    val id: String, val collectionId: String, val name: String, val description: String, val preview: String,
    val bin: String, val published: Boolean, val downloads: Int, val author: String?,
) {
    val previewURL get() = file(preview)
    val binURL get() = file(bin)
    val pageURL get() = "$FMC/market/$id"

    private fun file(name: String) = if (name.isEmpty()) null else "$FMC/api/files/$collectionId/$id/$name"

    companion object {
        fun from(j: JSONObject) = Watchface(
            j.getString("id"), j.getString("collectionId"), j.optString("name"), j.optString("description"),
            j.optString("preview"), j.optString("bin"), j.optBoolean("published"), j.optInt("downloads"),
            j.optJSONObject("expand")?.optJSONObject("owner")?.optString("name"),
        )
    }
}

object Catalog {
    private const val RECORDS = "$FMC/api/collections/watchfaces/records"

    /** Published faces for the Watch Pro 2. Untagged ones count, as the site's matchesDevice: most predate `device`. */
    suspend fun all() = list("published = true && (device = '' || device = 'watch_pro_2')", "-created")

    /** The signed-in user's own faces, drafts included (loadMyFx). */
    suspend fun mine(userId: String, token: String?) =
        if (FaceLink.isRecordID(userId)) list("owner = '$userId'", "-updated", token) else emptyList()

    suspend fun face(id: String, token: String?): Watchface {
        require(FaceLink.isRecordID(id))
        return Watchface.from(JSONObject(http("GET", "$RECORDS/$id?expand=owner", token = token)))
    }

    // ponytail: whole list in 500-record pages, as the site's getFullList; a few hundred faces. Real paging when the
    // catalog grows into the thousands.
    private suspend fun list(filter: String, sort: String, token: String? = null): List<Watchface> {
        val out = mutableListOf<Watchface>()
        var page = 1
        while (true) {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val p = JSONObject(http("GET", "$RECORDS?page=$page&perPage=500&filter=${enc(filter)}&sort=${enc(sort)}&expand=owner", token = token))
            val items = p.getJSONArray("items")
            for (i in 0 until items.length()) out += Watchface.from(items.getJSONObject(i))
            if (page >= p.getInt("totalPages")) return out
            page++
        }
    }
}

/**
 * Links that open a face in the app: `fmc://install?url=…` from the Install button on fmc.tools, `fmc://install?id=…`,
 * and the site's own `https://fmc.tools/market/<id>` (an app link once the site serves assetlinks.json).
 *
 * A link only ever names a catalog record. The file to install is always that record's own `bin` from fmc.tools,
 * never a URL from the link: a web page can't make the app fetch and flash a file.
 */
object FaceLink {
    fun watchface(link: String): String? {
        val u = runCatching { URI(link) }.getOrNull() ?: return null
        val query = (u.rawQuery ?: "").split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val parts = (u.path ?: "").split("/").filter { it.isNotEmpty() }
        return when (u.scheme?.lowercase()) {
            "fmc" -> when {
                u.host != "install" -> null
                query["id"] != null -> query["id"]?.takeIf(::isRecordID)
                else -> query["url"]?.let(::fileRecord)
            }
            "https" -> if (isFMC(u.host) && parts.size == 2 && parts[0] == "market" && isRecordID(parts[1])) parts[1] else null
            else -> null
        }
    }

    /** `https://fmc.tools/api/files/<collection>/<record>/<file>` → the record. */
    private fun fileRecord(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val p = (u.path ?: "").split("/").filter { it.isNotEmpty() }
        return if (u.scheme == "https" && isFMC(u.host) && p.size == 5 && p[0] == "api" && p[1] == "files" && isRecordID(p[3])) p[3] else null
    }

    private fun isFMC(host: String?) = host?.lowercase() == "fmc.tools"

    /** PocketBase ids: 15 lowercase letters and digits. Also what makes a filter safe to build from one. */
    fun isRecordID(s: String) = s.length == 15 && s.all { it in 'a'..'z' || it in '0'..'9' }
}
