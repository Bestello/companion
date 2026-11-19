package tools.fmc.companion

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

// FMC catalog: PocketBase `watchfaces` at fmc.tools, as fmc_frontend modules/market/model/market.api.ts
// (loadMarketFx, loadMyFx, loadWatchfaceFx, toggleLikeFx) and ios/App/Catalog.swift.
//
// Light on the server and on the phone's data: a list comes in pages of CatalogQuery.PER_PAGE, filtered and sorted by
// PocketBase, the next page when the grid nears its end. Like counts come from the face's own `likes_count`, the heart
// from the signed-in user's likes alone. The first page of All waits on disk (AppModel), cards show the 300×300 thumb,
// the face page the preview itself.

data class Watchface(
    val id: String, val collectionId: String, val name: String, val description: String, val preview: String,
    val bin: String, val published: Boolean, val downloads: Int, val author: String?,
    val created: String = "", val type: String = "", val authorAvatar: String? = null, val ownerId: String? = null,
    /** "watch_pro_2", "watch_3_pro", …; "" for most, which count as Watch Pro 2 (the site's matchesDevice) */
    val device: String = "",
    /** Kept by the server's hooks; null from a server without the field (fmc_pocketbase 1755000000). */
    val likesCount: Int? = null,
) {
    /** Made for another watch: the name to warn with, null for the Watch Pro 2 and untagged faces. */
    val otherDevice get() = device.takeIf { it.isNotEmpty() && it != "watch_pro_2" }?.let { DEVICES[it] ?: it }

    val previewURL get() = file(preview)
    /** For cards and tiles. A server without the thumb size sends the preview itself. */
    val thumbURL get() = previewURL?.let { "$it?thumb=${CatalogQuery.THUMB}" }
    val binURL get() = file(bin)
    val pageURL get() = "$FMC/market/$id"

    private fun file(name: String) = if (name.isEmpty()) null else "$FMC/api/files/$collectionId/$id/$name"

    companion object {
        fun from(j: JSONObject) = Watchface(
            j.getString("id"), j.getString("collectionId"), j.optString("name"), j.optString("description"),
            j.optString("preview"), j.optString("bin"), j.optBoolean("published"), j.optInt("downloads"),
            j.optJSONObject("expand")?.optJSONObject("owner")?.optString("name"),
            j.optString("created"), j.optString("type"),
            j.optJSONObject("expand")?.optJSONObject("owner")?.let { o ->
                o.optString("avatar").takeIf { it.isNotEmpty() }?.let { "$FMC/api/files/users/${o.getString("id")}/$it?thumb=100x100" }
            },
            j.optString("owner").takeIf { it.isNotEmpty() },
            j.optString("device"),
            if (j.has("likes_count")) j.optInt("likes_count") else null,
        )

        /** fmc_frontend deviceLabel */
        val DEVICES = mapOf("watch_pro" to "Watch Pro", "watch_3_pro" to "Watch 3 Pro")
    }
}

/** A face as the Watch tab needs it, to name a dial on the watch and show its picture. */
data class DialFace(val id: String, val collectionId: String, val name: String, val preview: String) {
    val thumbURL get() = if (preview.isEmpty()) null else "$FMC/api/files/$collectionId/$id/$preview?thumb=${CatalogQuery.THUMB}"

    fun json(): JSONObject = JSONObject().put("id", id).put("collectionId", collectionId).put("name", name).put("preview", preview)

    companion object {
        fun from(j: JSONObject) = DialFace(j.getString("id"), j.getString("collectionId"), j.optString("name"), j.optString("preview"))
    }
}

enum class Shelf { All, Liked, Mine }

/** fmc_frontend market.svelte SORT_OPTIONS (Top creators, a shelf of rails, left out) */
enum class CatalogSort { New, Popular, Downloads }

/** One list of the catalog as PocketBase is asked for it. Same cases as ios CatalogQueryTests. */
data class CatalogQuery(
    val shelf: Shelf = Shelf.All, val sort: CatalogSort = CatalogSort.New, val search: String = "",
    /** The signed-in user's record id: Liked and Mine need it. */
    val user: String? = null,
) {
    /** null: a shelf that needs a user, signed out. */
    val filter: String?
        get() {
            var f = when (shelf) {
                Shelf.All -> "published = true"
                Shelf.Liked, Shelf.Mine -> {
                    val u = user?.takeIf(FaceLink::isRecordID) ?: return null
                    if (shelf == Shelf.Mine) "owner = '$u'" else "published = true && likes_via_watchface.user ?= '$u'"
                }
            }
            val q = search.trim()
            if (q.isNotEmpty()) f += " && (name ~ ${quote(q)} || owner.name ~ ${quote(q)})"
            return f
        }

    /** [likesCount]: the server has watchfaces.likes_count; without it Popular falls back to downloads. The id breaks
     *  ties, so a face can't show on two pages or on none. */
    fun order(likesCount: Boolean = true) = when {
        sort == CatalogSort.New -> "-created,id"
        sort == CatalogSort.Popular && likesCount -> "-likes_count,-downloads,id"
        else -> "-downloads,-created,id"
    }

    /** The disk file the first page waits in: All only, unsearched, one per sort. */
    val cacheName get() = if (shelf == Shelf.All && search.isBlank()) "catalog-${sort.name.lowercase()}" else null

    companion object {
        const val PER_PAGE = 40
        /** fmc_pocketbase watchfaces.preview thumbs: 300x300 and 150x150 */
        const val THUMB = "300x300"
        /** Only the owner's name, id and avatar of the expanded owner: a card shows no more. */
        const val FIELDS = "*,expand.owner.id,expand.owner.name,expand.owner.avatar"

        /** A PocketBase filter string literal (the JS SDK's pb.filter): backslashes and quotes escaped. */
        fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

        /** The grid is near its end: the cell at [index] of [count] loaded is in the last few. */
        fun nearEnd(index: Int, count: Int) = index >= count - 8
    }
}

object Catalog {
    private const val RECORDS = "$FMC/api/collections/watchfaces/records"
    private const val LIKES = "$FMC/api/collections/likes/records"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** One page: its faces and the raw reply (the first page is kept on disk as it came). */
    suspend fun page(q: CatalogQuery, page: Int, likesCount: Boolean, token: String?): Pair<List<Watchface>, String> {
        val filter = q.filter ?: return emptyList<Watchface>() to "{\"items\":[]}"
        val body = http("GET", "$RECORDS?page=$page&perPage=${CatalogQuery.PER_PAGE}&skipTotal=1&filter=${enc(filter)}" +
            "&sort=${enc(q.order(likesCount))}&expand=owner&fields=${enc(CatalogQuery.FIELDS)}", token = token)
        return parse(body) to body
    }

    fun parse(body: String): List<Watchface> {
        val items = JSONObject(body).getJSONArray("items")
        return (0 until items.length()).map { Watchface.from(items.getJSONObject(it)) }
    }

    /** Every record a filter matches, page after page until a short one. For short lists: a user's likes, an author's
     *  faces, the dial index. */
    private suspend fun all(url: String, filter: String?, fields: String, sort: String? = null, expand: String? = null,
                            perPage: Int = 200, token: String? = null): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            var u = "$url?page=$page&perPage=$perPage&skipTotal=1&fields=${enc(fields)}"
            filter?.let { u += "&filter=${enc(it)}" }
            sort?.let { u += "&sort=${enc(it)}" }
            expand?.let { u += "&expand=$it" }
            val items = JSONObject(http("GET", u, token = token)).getJSONArray("items")
            for (i in 0 until items.length()) out += items.getJSONObject(i)
            if (items.length() < perPage) return out
            page++
        }
    }

    /** The signed-in user's own likes: face id → like id. */
    suspend fun myLikes(userId: String, token: String?): Map<String, String> =
        if (!FaceLink.isRecordID(userId)) emptyMap()
        else all(LIKES, "user = '$userId'", "id,watchface", token = token).associate { it.getString("watchface") to it.getString("id") }

    /** A server without likes_count: the counts of these faces from their likes. */
    suspend fun countLikes(faces: List<Watchface>): Map<String, Int> {
        if (faces.isEmpty()) return emptyMap()
        val n = faces.associate { it.id to 0 }.toMutableMap()
        all(LIKES, faces.joinToString(" || ") { "watchface = '${it.id}'" }, "watchface").forEach {
            val f = it.getString("watchface")
            n[f] = (n[f] ?: 0) + 1
        }
        return n
    }

    /** The new like's id. */
    suspend fun like(user: String, face: String, token: String?): String =
        JSONObject(http("POST", LIKES, JSONObject().put("user", user).put("watchface", face), token)).optString("id")

    suspend fun unlike(id: String, token: String?) {
        http("DELETE", "$LIKES/$id", token = token)
    }

    /** An author's published faces, all of them: their page adds up downloads and likes. */
    suspend fun author(id: String, token: String?): List<Watchface> =
        if (!FaceLink.isRecordID(id)) emptyList()
        else all(RECORDS, "published = true && owner = '$id'", CatalogQuery.FIELDS, "-created,id", "owner", token = token).map(Watchface::from)

    /** Every face the user may see, slim: to name the dials on the watch. */
    suspend fun dialIndex(token: String?): List<DialFace> =
        all(RECORDS, null, "id,collectionId,name,preview", perPage = 500, token = token).map(DialFace::from)

    suspend fun face(id: String, token: String?): Watchface {
        require(FaceLink.isRecordID(id))
        return Watchface.from(JSONObject(http("GET", "$RECORDS/$id?expand=owner", token = token)))
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
