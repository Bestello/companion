package tools.fmc.companion.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.json.JSONArray

/** ios OtaTests, on the same ota-vectors.json. */
class OtaTest {
    private val images = Vectors.ota.getJSONArray("images").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private val flash = Vectors.ota.getJSONObject("flash")
    private fun image(name: String) = inspectOtaImage(hex(images.first { it.getString("name") == name }.getString("hex")))
    private fun pairs(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }

    @Test fun gateMatchesSite() {
        for (i in images) {
            val name = i.getString("name")
            try {
                val info = inspectOtaImage(hex(i.getString("hex")))
                assertNull(i.optString("refuse", null), name)
                assertEquals(i.getString("board"), info.board, name)
                assertEquals(i.getLong("version_code"), info.versionCode, name)
            } catch (e: OtaError.NotFlashable) {
                assertEquals(i.optString("refuse"), "not a flashable image: ${e.why}", name)
            }
        }
    }

    @Test fun targetIsWatchPro2Only() {
        checkOtaTarget(image("ok"), "CMF Watch Pro 2-1A2B")
        assertFailsWith<OtaError> { checkOtaTarget(image("ok"), "CMF Watch 3 Pro-9A9D") }
        assertFailsWith<OtaError> { checkOtaTarget(image("ok"), null) }
        assertFailsWith<OtaError> { checkOtaTarget(image("watch_3_pro"), "CMF Watch Pro 2-1A2B") }
    }

    /** The site's watch, replayed: A052 after 0x9052, everything else after 0x9040. */
    private class OtaWatch(var notes: List<Pair<String, String>>) : Link {
        private var handler: ((String, ByteArray) -> Unit)? = null
        val writes = mutableListOf<Pair<String, String>>()
        private val names = mapOf(Uuid.CMD_WRITE to "fff2", OtaUuid.WRITE to "ff01")
        private val uuids = mapOf("fff1" to Uuid.CMD_NOTIFY, "ff02" to OtaUuid.NOTIFY)

        override fun onNotify(handler: (String, ByteArray) -> Unit) { this.handler = handler }

        override suspend fun write(uuid: String, data: ByteArray) {
            writes += (names[uuid] ?: uuid) to data.hex()
            val cmd = (data.be16(3).toLong() shl 16) or data.be16(9).toLong()
            val upTo = when (cmd) { StockCmd.OTA_BEGIN_REQ -> 1; StockCmd.OTA_START_REQ -> notes.size; else -> 0 }
            notes.take(upTo).forEach { (u, f) -> handler?.invoke(uuids.getValue(u), hex(f)) }
            notes = notes.drop(upTo)
        }
    }

    @Test fun flashWritesWhatTheSiteWrites() = runBlocking {
        val w = OtaWatch(pairs(flash.getJSONArray("notifications")))
        val s = StockSession(w, key = hex(flash.getString("key")))
        val seen = mutableListOf<Int>()
        s.flashFirmware(hex(flash.getString("image"))) { seen += it }
        assertEquals(pairs(flash.getJSONArray("writes")), w.writes)
        val want = flash.getJSONArray("progress")
        assertEquals((0 until want.length()).map(want::getInt), seen)
    }

    @Test fun flashStopsOnRefusal() = runBlocking {
        val key = hex(flash.getString("key"))
        val w = OtaWatch(listOf("fff1" to stockFrames(StockCmd.OTA_BEGIN_REP, bytes(0), key)[0].hex()))
        val s = StockSession(w, key = key)
        val e = assertFailsWith<OtaError.Refused> { s.flashFirmware(hex(flash.getString("image"))) {} }
        assertEquals("The watch refused the update (00).", e.message)
        assertEquals(1, w.writes.size, "nothing after the refusal, 0x9040 least of all")
    }
}
