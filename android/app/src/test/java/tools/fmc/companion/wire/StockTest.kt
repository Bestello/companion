package tools.fmc.companion.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The watch's side of the stock session and the watchface upload, scripted (ios StockTests' StockWatch). */
class StockWatch : Link {
    val secret = hex(Vectors.face.getJSONObject("pair").getString("secret"))
    var k1: ByteArray? = null
    var key: ByteArray? = null
    private var handler: ((String, ByteArray) -> Unit)? = null
    private val parser = StockParser()
    var init2: ByteArray? = null
    var received = ByteArray(0)
    var init2Answer = 1
    var finish = listOf(1)
    var chunk = 300
    var finishes = 0

    /** The bind popup: open after the first 0x8049 that follows 0x8047. `confirmPopup` taps it once. */
    var popup = 0
    var confirmPopup = false
    var binds = 0

    /** Nothing X's frames under the old session key, answered with authFailed in the middle of an upload. */
    var nothingX = false

    override fun onNotify(handler: (String, ByteArray) -> Unit) { this.handler = handler }

    private fun reply(cmd: Long, p: ByteArray, key: ByteArray? = this.key) {
        // the watch answers on the pair the request came in on
        val data = cmd in setOf(StockCmd.WF_INIT1_REP, StockCmd.WF_INIT2_REP, StockCmd.WF_CHUNK_REQ, StockCmd.WF_FINISH_ACK1)
        for (f in stockFrames(cmd, p, key)) handler?.invoke(if (data) DataUuid.NOTIFY else Uuid.CMD_NOTIFY, f)
    }

    private fun askNext() {
        val size = init2!!.le32(9).toInt()
        if (received.size >= size) {
            finishes++
            reply(StockCmd.WF_FINISH_ACK1, bytes(finish.getOrElse(finishes - 1) { finish.last() }))
            return
        }
        reply(StockCmd.WF_CHUNK_REQ, buf {
            be32(received.size.toLong()); be32(minOf(chunk, size - received.size).toLong()); u8(received.size * 100 / size)
        })
    }

    override suspend fun write(uuid: String, data: ByteArray) {
        val cmd = (data.be16(3).toLong() shl 16) or data.be16(9).toLong()
        assertEquals(if (cmd in StockCmd.DATA) DataUuid.WRITE else Uuid.CMD_WRITE, uuid, cmd.toString(16))
        if (cmd == StockCmd.WF_CHUNK_WRITE) {
            received += data.copyOfRange(11, data.size - 4)
            if (nothingX) reply(StockCmd.AUTH_FAILED, bytes(1))
            if (data.be16(5) == data.be16(7)) askNext()
            return
        }
        // 0x8049 and 0x804B (and their replies) go under the authkey, the rest under the session key
        val auth = cmd == StockCmd.AUTH_NAME || cmd == StockCmd.AUTH_NONCE_REQ
        val (c, p) = parser.feed(data, if (auth) k1 else key) ?: return
        when (c) {
            StockCmd.AUTH_PAIR_REQ -> {
                val random2 = ByteArray(16) { 7 }
                k1 = sha256(p.copyOf(16), random2, secret).copyOf(16)
                reply(StockCmd.AUTH_PAIR_REP, random2 + sha256(random2, secret))
                key = k1
                popup = 1
                binds++
            }
            StockCmd.AUTH_NAME -> {
                if (popup == 1) { popup = 2; return } // opens the popup, no answer
                if (popup == 2 && confirmPopup) {
                    confirmPopup = false
                    popup = 0
                    k1 = ByteArray(16)
                }
                reply(StockCmd.AUTH_MAC, bytes(1), k1)
            }
            StockCmd.AUTH_NONCE_REQ -> {
                val nonce = ByteArray(16) { 9 }
                reply(StockCmd.AUTH_NONCE_REP, nonce, k1)
                key = sessionKey(nonce, k1!!)
            }
            StockCmd.AUTH_CONFIRM_REQ -> {
                popup = 0
                reply(StockCmd.AUTH_CONFIRM_REP, bytes(1))
            }
            StockCmd.WF_INIT1_REQ -> reply(StockCmd.WF_INIT1_REP, bytes(1))
            StockCmd.WF_INIT2_REQ -> {
                init2 = p
                received = ByteArray(0)
                reply(StockCmd.WF_INIT2_REP, bytes(init2Answer))
                if (init2Answer == 1) askNext()
            }
        }
    }
}

private val fv = Vectors.face
private fun faceHex(name: String) =
    hex((0 until fv.getJSONArray("faces").length()).map { fv.getJSONArray("faces").getJSONObject(it) }
        .first { it.getString("name") == name }.getString("hex"))

/** A face that passes the gate, `n` bytes long: the last image's data stretched, header ‖ … ‖ header kept. */
fun testFace(n: Int = 1000): ByteArray {
    val d = faceHex("ok")
    val body = ByteArray(n - d.size) { it.toByte() }
    return d.copyOf(d.size - 36) + body + d.copyOfRange(d.size - 36, d.size)
}

class StockTest {
    @Test fun framesMatchSite() {
        val key = hex(fv.getString("key"))
        val enc = fv.getJSONArray("encode")
        for (i in 0 until enc.length()) {
            val e = enc.getJSONObject(i)
            val cmd = (e.getLong("c1") shl 16) or e.getLong("c2")
            val want = e.getJSONArray("frames").let { a -> List(a.length()) { a.getString(it) } }
            val frames = stockFrames(cmd, hex(e.getString("payload")), key, fv.getInt("mtu"))
            assertEquals(want, frames.map { it.hex() }, cmd.toString(16))
            val p = StockParser()
            val out = frames.mapNotNull { p.feed(it, key) }
            if (cmd !in StockCmd.PLAIN) assertEquals(listOf(e.getString("payload")), out.map { it.second.hex() })
        }
    }

    @Test fun pairMathMatchesSite() {
        val p = fv.getJSONObject("pair")
        fun h(k: String) = hex(p.getString(k))
        assertContentEquals(h("request"), pairRequest(h("random1"), h("secret")))
        assertContentEquals(h("k1"), pairKey(h("random1"), h("reply"), h("secret")))
        val forged = h("reply").also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(pairKey(h("random1"), forged, h("secret")), "a reply without the secret")
        assertContentEquals(h("session"), sessionKey(h("nonce"), h("k1")))
    }

    @Test fun dialIdMatchesSite() {
        val ids = fv.getJSONObject("dial_id")
        assertTrue(ids.length() > 0)
        for (seed in ids.keys()) assertEquals(ids.getLong(seed), dialId(seed), seed)
    }

    @Test fun faceGateMatchesSite() {
        val want = mapOf(
            "ok" to FaceVerdict.Ok, "main_no_preview" to FaceVerdict.Ok,
            "aod_no_preview" to FaceVerdict.Refuse(FaceRefusal.AodWithoutPreview),
            "too_big" to FaceVerdict.Refuse(FaceRefusal.ImageTooBig(1386, 1386)),
            "needs_confirm" to FaceVerdict.Confirm(576, 530),
        )
        val faces = fv.getJSONArray("faces")
        for (i in 0 until faces.length()) {
            val f = faces.getJSONObject(i)
            val v = checkFace(hex(f.getString("hex")))
            val name = f.getString("name")
            assertEquals(want[name], v, name)
            // the site's own outcome, so a changed gate there fails here
            val refuse = !f.isNull("refuse") && f.has("refuse")
            val confirm = f.has("confirm") && !f.isNull("confirm")
            when (v) {
                is FaceVerdict.Refuse -> assertTrue(refuse, name)
                is FaceVerdict.Confirm -> assertTrue(!refuse && confirm, name)
                FaceVerdict.Ok -> assertTrue(!refuse && !confirm, name)
            }
        }
        assertEquals(FaceVerdict.Refuse(FaceRefusal.NotAWatchface), checkFace(ByteArray(100)))
    }

    private suspend fun paired(w: StockWatch) = StockSession(w).also { it.pair(w.secret) }

    @Test fun pairsAndUploads() = runBlocking {
        val w = StockWatch()
        val s = StockSession(w)
        val k1 = s.pair(w.secret)
        assertContentEquals(w.k1, k1)
        val bin = testFace()
        val seen = mutableListOf<Int>()
        val id = installFace(s, bin, "rec1", listOf(273, 274), 6) { seen += it }
        assertEquals(dialId("rec1"), id)
        assertContentEquals(uploadInit(0, id, bin.size), w.init2)
        assertContentEquals(bin, w.received)
        assertEquals(100, seen.last())
    }

    @Test fun reinstallReplacesItself() = runBlocking {
        val w = StockWatch()
        val s = paired(w)
        val id = dialId("rec1")
        installFace(s, testFace(), "rec1", listOf(1, 2, 3, 4, 5, id), 6) {}
        assertContentEquals(uploadInit(id, id, testFace().size), w.init2)
    }

    @Test fun fullWatchAsksBeforeSending() = runBlocking {
        val w = StockWatch()
        val s = paired(w)
        assertFailsWith<UploadError.NoFreeSlot> { installFace(s, testFace(), "new", listOf(1, 2, 3, 4, 5, 6), 6) {} }
        assertNull(w.init2, "nothing shipped to be told no")
    }

    @Test fun appendRefusedAtFinishAsksForSlot() = runBlocking {
        val w = StockWatch().apply { finish = listOf(0x0A) }
        val s = paired(w)
        assertFailsWith<UploadError.NoFreeSlot> { installFace(s, testFace(), "new", emptyList(), 6) {} }
        Unit
    }

    @Test fun staleGuessRetriesAsAppend() = runBlocking {
        val w = StockWatch().apply { finish = listOf(0x0A, 1) }
        val s = paired(w)
        val id = dialId("rec1")
        installFace(s, testFace(), "rec1", listOf(id), 6) {}
        assertContentEquals(uploadInit(0, id, testFace().size), w.init2)
        assertEquals(2, w.finishes)
    }

    @Test fun installedDials() {
        val p = buf { add(bytes(1, 5, 6, 7)); le32(273); le32(0xFFFF_FFFFL); le32(12345) }
        assertEquals(listOf(273L, 12345L), parseInstalledDials(p))
        assertEquals(DialCount(6, 7), parseDialCount(p))
        assertEquals(null, parseDialCount(bytes(1, 0, 9, 7)))
        assertEquals(null, parseDialCount(bytes(1, 0)))
    }

    @Test fun confirmedPopupBindsAgain() = runBlocking {
        val w = StockWatch().apply { confirmPopup = true }
        val k1 = StockSession(w).pair(w.secret)
        assertEquals(2, w.binds)
        assertContentEquals(w.k1, k1)
        assertFalse(k1.contentEquals(ByteArray(16)))
    }

    @Test fun storedKeyAuthenticatesWithoutPopup() = runBlocking {
        val w = StockWatch()
        val k1 = StockSession(w).pair(w.secret)
        val s = StockSession(w)
        s.authenticate(k1)
        assertEquals(1, w.binds)
        assertNotNull(installFace(s, testFace(), "rec1", emptyList(), 6) {})
        Unit
    }

    @Test fun nothingXAuthFailedDoesNotStopUpload() = runBlocking {
        val w = StockWatch()
        val s = paired(w)
        w.nothingX = true
        val bin = testFace(2000)
        installFace(s, bin, "rec1", emptyList(), 6) {}
        assertContentEquals(bin, w.received)
    }
}
