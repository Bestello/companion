package tools.fmc.companion.wire

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** rpc-vectors.json, as ios RpcTests read it. */
class RpcTest {
    private val v by lazy { JSONObject(java.io.File(System.getProperty("vectors"), "rpc-vectors.json").readText()) }
    private fun each(name: String, f: (JSONObject) -> Unit) = v.getJSONArray(name).let { a -> (0 until a.length()).forEach { f(a.getJSONObject(it)) } }
    private fun hexList(a: JSONArray) = (0 until a.length()).map { hex(a.getString(it)).hex() }

    @Test fun requests() = each("requests") { t ->
        val r = parseRequest(hex(t.getString("hex")))
        when {
            t.optBoolean("bad") -> assertNull(r!!.method)
            t.has("rpc") -> assertNull(r)
            else -> {
                assertEquals(t.getInt("id"), r!!.id)
                assertEquals(t.getInt("method"), r.method)
                assertEquals(t.getString("args"), r.args.hex())
            }
        }
    }

    @Test fun cut() {
        each("utf8_cut") { t -> assertEquals(t.getString("hex"), utf8Cut(t.getString("text"), t.getInt("max")).hex()) }
        assertEquals(64, utf8Cut("a".repeat(64), 64).size, "a text of exactly max stays whole")
    }

    @Test fun calendar() = each("calendar") { t ->
        val i = t.getJSONObject("in")
        val e = Event(i.getInt("day"), i.getInt("start"), i.getInt("duration"), i.getBoolean("all_day"),
            i.getBoolean("tentative"), i.getInt("color"), i.getString("title"))
        assertEquals(t.getString("hex"), calendarRecord(e, i.getBoolean("show")).hex())
    }

    @Test fun calendarDates() = each("calendar_dates") { t ->
        val zone = ZoneId.of(t.getString("tz"))
        fun at(k: String) = LocalDateTime.parse(t.getString(k)).atZone(zone)
        val got = eventTimes(at("now"), at("start").toInstant(), at("end").toInstant())
        assertEquals(Triple(t.getInt("day"), t.getInt("start_min"), t.getInt("duration")), got, t.toString())
    }

    @Test fun location() = each("location") { t ->
        val i = t.getJSONObject("in")
        assertEquals(t.getString("hex"),
            locationRecord(i.getDouble("lat"), i.getDouble("lon"), i.getLong("age_min"), i.getString("place")).hex())
    }

    @Test fun weather() = each("weather") { t ->
        val i = t.getJSONObject("in")
        val r = if (i.getString("kind") == "now") {
            weatherNow(i.getDouble("temp"), i.getInt("code"), i.getDouble("wind"), i.getInt("humidity"), i.getLong("age_min"))
        } else {
            weatherDay(i.getInt("day"), i.getDouble("min"), i.getDouble("max"), i.getInt("code"), i.getInt("precip"))
        }
        assertEquals(t.getString("hex"), r.hex())
    }

    @Test fun http() {
        each("http") { t ->
            val i = t.getJSONObject("in")
            assertEquals(hexList(t.getJSONArray("records")), httpRecords(i.getInt("code"), hex(i.getString("body"))).map { it.hex() })
        }
        assertEquals(RpcStatus.TOO_LARGE, runCatching { httpRecords(200, ByteArray(2049)) }.exceptionOrNull().let { (it as RpcError).status })
        assertEquals("example.com", httpHost("https://Example.com/a?b=1".toByteArray()))
        assertNull(httpHost("http://example.com/".toByteArray()))
        assertNull(httpHost(("https://example.com/" + "a".repeat(110)).toByteArray()))
    }

    @Test fun notifications() = each("notifications") { t ->
        val i = t.getJSONObject("in")
        assertEquals(t.getString("hex"),
            notificationRecord(i.getLong("age_min"), i.getString("app"), i.getString("text"), i.getBoolean("show")).hex())
    }

    @Test fun replyMessages() = each("replies") { t ->
        val records = t.getJSONArray("records").let { a -> (0 until a.length()).map { hex(a.getString(it)) } }
        assertEquals(hexList(t.getJSONArray("messages")), replies(t.getInt("id"), t.getInt("status"), records).map { it.hex() })
    }

    @Test fun color() = assertEquals(0xF800, rgb565(0xFFFF0000.toInt()))

    /** Grants as the dispatcher reads them; `slow` makes the service miss the budget. */
    private class FakePhone(val grants: MutableMap<String, Boolean> = mutableMapOf(), var os: Boolean = true) : Phone {
        val asked = mutableListOf<String>()
        var slow = false
        override fun grants(pkg: String) = grants
        override fun osAllows(cap: String) = os
        override fun ask(pkg: String, cap: String) { asked += "$pkg $cap" }
        override suspend fun call(pkg: String, method: Int, args: ByteArray): List<ByteArray> {
            if (slow) delay(10_000)
            return listOf(bytes(method))
        }
    }

    private fun status(r: List<ByteArray>?) = r!!.first().u8(4)

    @Test fun dispatcher() = runBlocking {
        val p = FakePhone()
        val cal = hex("5231510810000205")
        assertEquals(RpcStatus.ASK, status(answer("weather-app", cal, p)))
        assertEquals(listOf("weather-app calendar"), p.asked)
        p.grants["calendar"] = false
        assertEquals(RpcStatus.DENIED, status(answer("a", cal, p)))
        p.grants["calendar"] = true
        assertEquals("52314108000001" + "10", answer("a", cal, p)!!.single().hex())
        p.os = false
        assertEquals(RpcStatus.DENIED, status(answer("a", cal, p)))
        p.os = true
        assertEquals(listOf("calendar"), p.asked.map { it.split(" ")[1] }, "no new ask once answered")
        assertEquals(RpcStatus.BAD_REQUEST, status(answer("a", hex("5231510a"), p)))
        assertEquals(RpcStatus.BAD_REQUEST, status(answer("a", hex("52315101ee"), p)))
        assertEquals(RpcStatus.BAD_REQUEST, status(answer("a", hex("5231510110"), p)), "calendar without args")
        assertNull(answer("a", hex("5231500100"), p))
        p.grants["http:example.com"] = true
        assertEquals("523141070000011040", answer("a", hex("5231510701"), p)!!.single().hex(), "caps")
        p.slow = true
        val t0 = System.currentTimeMillis()
        assertEquals(RpcStatus.UNAVAILABLE, status(answer("a", cal, p, budgetMs = 300)))
        assert(System.currentTimeMillis() - t0 < 2000)
    }
}
