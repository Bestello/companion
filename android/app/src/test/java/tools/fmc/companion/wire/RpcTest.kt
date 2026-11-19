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

    /** Each case to fresh parts: no reply before the last message, then the joined request or BAD_REQUEST. */
    @Test fun httpRequestVectors() = each("http_request") { t ->
        val parts = RequestParts()
        val msgs = t.getJSONArray("messages").let { a -> (0 until a.length()).map { hex(a.getString(it)) } }
        var joined: ByteArray? = null
        var bad = false
        for ((i, m) in msgs.withIndex()) {
            val r = parseRequest(m)!!
            if (r.method == null) { bad = true; break }
            assertEquals(Method.HTTP_REQUEST, r.method)
            try {
                joined = parts.add("a", r.id, r.args)
                assertEquals(i < msgs.size - 1, joined == null, t.getString("note"))
            } catch (e: RpcError) {
                assertEquals(msgs.size - 1, i, t.getString("note"))
                bad = true
            }
        }
        val call = joined?.let { parseHttpCall(it) }
        if (t.optBoolean("bad")) {
            assert(bad || call == null) { t.getString("note") }
        } else {
            val q = t.getJSONObject("request")
            assertEquals(HttpCall(q.getString("method"), q.getString("url"), "", hex(q.getString("body"))), call, t.getString("note"))
        }
    }

    @Test fun headers() {
        val auth = "Authorization" to "Bearer x"
        val json = "Content-Type" to "application/json"
        assertEquals(listOf(auth, json), requestHeaders(listOf(auth), "{}".toByteArray()))
        assertEquals(listOf(auth, json), requestHeaders(listOf(auth), "[1]".toByteArray()))
        assertEquals(listOf(auth), requestHeaders(listOf(auth), "a=1".toByteArray()))
        assertEquals(listOf("content-type" to "text/plain"), requestHeaders(listOf("content-type" to "text/plain"), "{}".toByteArray()))
        assertEquals(emptyList(), requestHeaders(listOf(" " to "x"), ByteArray(0)))
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
        val writes = mutableListOf<Boolean>()
        val calls = mutableListOf<Pair<Int, ByteArray>>()
        override val state = RpcState(KeyValueStore(kotlin.io.path.createTempDirectory("rpc").toFile()))
        var slow = false
        override fun grants(pkg: String) = grants
        override fun osAllows(cap: String) = os
        override fun ask(pkg: String, cap: String, write: Boolean) { asked += "$pkg $cap"; writes += write }
        override suspend fun call(pkg: String, method: Int, args: ByteArray): List<ByteArray> {
            if (slow) delay(10_000)
            calls += method to args
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
        assertEquals("5231410700000110404160616270", answer("a", hex("5231510701"), p)!!.single().hex(), "caps")
        p.slow = true
        val t0 = System.currentTimeMillis()
        assertEquals(RpcStatus.UNAVAILABLE, status(answer("a", cal, p, budgetMs = 300)))
        assert(System.currentTimeMillis() - t0 < 2000)
    }

    @Test fun httpRequestDispatch() = runBlocking {
        val p = FakePhone()
        val payload = byteArrayOf(1, 20) + "https://example.com/".toByteArray() + "{\"a\":1}".toByteArray()
        fun part(id: Int, index: Int, total: Int, chunk: ByteArray) =
            "R1Q".toByteArray() + byteArrayOf(id.toByte(), 0x41, index.toByte(), total.toByte()) + chunk
        val one = part(4, 0, 1, payload)
        assertEquals(RpcStatus.ASK, status(answer("a", one, p)))
        assertEquals(listOf("a http:example.com"), p.asked)
        assertEquals(listOf(true), p.writes, "the ask says it can change data")
        p.grants["http:example.com"] = true
        assertEquals(RpcStatus.OK, status(answer("a", one, p)))
        assertEquals(payload.hex(), p.calls.single().second.hex(), "the service gets the joined payload")
        val a = part(5, 0, 2, payload.copyOfRange(0, 10))
        val b = part(5, 1, 2, payload.copyOfRange(10, payload.size))
        assertEquals(emptyList(), answer("a", a, p), "no reply before the last part")
        assertEquals(RpcStatus.OK, status(answer("a", b, p)))
        assertEquals(payload.hex(), p.calls.last().second.hex())
        answer("a", a, p)
        assertEquals(RpcStatus.BAD_REQUEST, status(answer("a", part(6, 1, 2, payload.copyOfRange(10, payload.size)), p)), "new id")
        assertEquals(RpcStatus.BAD_REQUEST, status(answer("a", b, p)), "the dropped request doesn't come back")
        answer("a", a, p)
        answer("b", a, p)
        assertEquals(RpcStatus.OK, status(answer("a", b, p)), "parts are per package")
    }

    @Test fun storageVectors() = runBlocking {
        val st = v.getJSONObject("storage")
        assertEquals(KeyValueStore.QUOTA, st.getInt("quota"))
        st.getJSONArray("keys").let { a -> (0 until a.length()).map(a::getJSONObject) }.forEach { k ->
            assertEquals(k.getBoolean("ok"), runCatching { storageKey(hex(k.getString("key"))) }.isSuccess, k.toString())
        }
        st.getJSONArray("get").let { a -> (0 until a.length()).map(a::getJSONObject) }.forEach { g ->
            assertEquals(hexList(g.getJSONArray("records")), valueRecords(hex(g.getString("value"))).map { it.hex() })
        }
        val p = FakePhone()
        st.getJSONArray("put").let { a -> (0 until a.length()).map(a::getJSONObject) }.forEach { c ->
            val msgs = c.getJSONArray("messages").let { a -> (0 until a.length()).map { hex(a.getString(it)) } }
            msgs.forEachIndexed { i, m ->
                val r = answer("app", m, p)!!
                if (i < msgs.size - 1) assertEquals(emptyList(), r, c.getString("note"))
                else assertEquals(replies(m.u8(3), RpcStatus.OK).map { it.hex() }, r.map { it.hex() }, c.getString("note"))
            }
            assertEquals(c.getString("value"), p.state.storage.get("app", c.getString("key"))?.hex(), c.getString("note"))
            val got = answer("app", "R1Q".toByteArray() + byteArrayOf(9, 0x60) + c.getString("key").toByteArray(), p)!!
            assertEquals(valueRecords(hex(c.getString("value"))).map { it.hex() }, got.map { it.copyOfRange(7, it.size).hex() })
        }
        suspend fun get(pkg: String, key: String) = answer(pkg, "R1Q".toByteArray() + byteArrayOf(9, 0x60) + key.toByteArray(), p)!!
        assertEquals("52314109000000", get("app", "nope").single().hex(), "missing: no records")
        assertEquals("52314109000000", get("other", "score").single().hex(), "per package")
        answer("app", "R1Q".toByteArray() + byteArrayOf(9, 0x62) + "score".toByteArray(), p)
        assertNull(p.state.storage.get("app", "score"))
        assertEquals(RpcStatus.BAD_REQUEST, status(get("app", "a/b")))
        assertEquals(emptyList(), p.asked, "no cap")
    }

    @Test fun battery() = runBlocking {
        each("battery") { t ->
            val i = t.getJSONObject("in")
            assertEquals(t.getString("hex"), batteryRecord(i.getInt("level"), i.getBoolean("charging")).hex())
        }
        val p = FakePhone()
        assertEquals("5231410900000170", answer("a", hex("5231510970"), p)!!.single().hex(), "the fake answers its method id")
        assertEquals(emptyList(), p.asked, "no cap")
    }

    @Test fun storageQuota() {
        val s = KeyValueStore(kotlin.io.path.createTempDirectory("quota").toFile())
        s.put("p", "a", ByteArray(40_000))
        assertEquals(RpcStatus.TOO_LARGE, (runCatching { s.put("p", "b", ByteArray(30_000)) }.exceptionOrNull() as RpcError).status)
        s.put("p", "a", ByteArray(60_000))
        assertEquals(60_001, s.used("p"))
        s.put("q", "b", ByteArray(30_000))
        s.clear("p")
        assertNull(s.get("p", "a"))
        assert(s.get("q", "b") != null)
    }

    @Test fun notifyAlert() = runBlocking {
        each("notify") { t ->
            val got = parseAlert(hex(t.getString("args")))
            if (t.optBoolean("bad")) assertNull(got, t.toString()) else {
                val a = t.getJSONObject("alert")
                assertEquals(PhoneAlert(a.getString("title"), a.getString("body"), a.getBoolean("sound"), a.getBoolean("find")), got)
            }
        }
        val p = FakePhone()
        var t = 0L
        p.state.now = { t }
        val m = "R1Q".toByteArray() + byteArrayOf(1, 0x51) + hex(v.getJSONArray("notify").getJSONObject(0).getString("args"))
        assertEquals(RpcStatus.ASK, status(answer("a", m, p)))
        assertEquals(listOf("a notify"), p.asked)
        p.grants["notify"] = true
        assertEquals(RpcStatus.OK, status(answer("a", m, p)))
        t += 5_000
        assertEquals(RpcStatus.UNAVAILABLE, status(answer("a", m, p)), "one per 10 s")
        assertEquals(RpcStatus.OK, status(answer("b", m, p)), "per package")
        t += 6_000
        assertEquals(RpcStatus.OK, status(answer("a", m, p)))
        assertEquals(RpcStatus.BAD_REQUEST, status(answer("a", "R1Q".toByteArray() + byteArrayOf(1, 0x51, 0, 9), p)))
    }
}
