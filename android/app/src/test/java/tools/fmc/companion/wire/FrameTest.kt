package tools.fmc.companion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte-for-byte equal to the Python host, same as ios VectorTests. */
class FrameTest {
    private val v = Vectors.k

    @Test fun sealOpen() {
        val s = v.getJSONObject("seal")
        val key = hex(s.getString("key"))
        assertContentEquals(hex(s.getString("cipher")), seal(key, hex(s.getString("plain"))))
        assertContentEquals(hex(s.getString("plain")), open(key, hex(s.getString("cipher"))))
        val bad = hex(s.getString("cipher")).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(open(key, bad))
        assertNull(open(ByteArray(16) { 1 }, hex(s.getString("cipher"))))
    }

    @Test fun channelKeyFromSecret() {
        val k = v.getJSONObject("channel_key")
        assertContentEquals(hex(k.getString("key")), channelKey(k.getString("secret")))
        assertNull(channelKey("zz"))
        assertNull(channelKey("z".repeat(32)))
    }

    @Test fun frameMatchesPython() {
        val f = v.getJSONObject("frame")
        val key = hex(f.getString("key"))
        val raw = hex(f.getString("hex"))
        assertContentEquals(raw, frame(f.getInt("c2"), hex(f.getString("payload")), key))
        val p = FrameParser()
        assertTrue(p.feed(raw.copyOf(5)).isEmpty())
        val got = p.feed(raw.copyOfRange(5, raw.size)).single()
        assertEquals(0xFFFF, got.c1)
        assertEquals(f.getInt("c2"), got.c2)
        assertContentEquals(hex(f.getString("payload")), open(key, got.body))
    }
}
