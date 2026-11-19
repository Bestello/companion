package tools.fmc.companion.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A watch for INFO only: `stub` is the reply to slot 0xFE (null = stock, never answers). */
class FakeWatch : Requester {
    var stub: ByteArray? = null
    val used = mutableMapOf<Int, ByteArray>()

    override suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long): ByteArray {
        assertEquals(Cmd.INFO, c2, "read only")
        val slot = payload.u8(0)
        if (slot == STUB_SLOT) return stub ?: throw WireError.Timeout("no reply to 90A0")
        return bytes(0) + (used[slot] ?: ByteArray(0))
    }
}

class LoaderTest {
    @Test fun stockFirmwareIsNull() = runBlocking { assertNull(readLoader(FakeWatch())) }

    @Test fun stubOneHasNoVersion() = runBlocking {
        val w = FakeWatch().apply { stub = bytes(1) }
        assertEquals(Stub(1, null), readLoader(w)?.stub)
    }

    @Test fun readsStubLoaderAndSlots() = runBlocking {
        val w = FakeWatch().apply { stub = bytes(0, 5, 0, 0, 0, 3) }
        w.used[2] = Vectors.header
        w.used[LOADER_SLOT] = Vectors.header.also { it[6] = 1 }
        val s = readLoader(w)!!
        assertEquals(Stub(5, 3), s.stub)
        assertEquals(1, s.loader?.kind)
        assertEquals(mapOf(2 to Vectors.pkgHeader), s.apps)
    }

    @Test fun stubSevenReadsSixteenSlots() = runBlocking {
        val w = FakeWatch().apply { stub = bytes(0, 7, 0, 0, 0, 4) }
        w.used[15] = Vectors.header
        assertEquals(mapOf(15 to Vectors.pkgHeader), readLoader(w)!!.apps)
        w.stub = bytes(0, 6, 0, 0, 0, 4)
        assertEquals(emptyMap(), readLoader(w)!!.apps, "stub 6: slot 15 isn't read")
    }

    @Test fun garbledStubReplyThrows() = runBlocking {
        val w = FakeWatch().apply { stub = bytes(0, 5) }
        assertEquals(WireError.Refused(0), assertFailsWith<WireError.Refused> { readLoader(w) })
        Unit
    }
}
