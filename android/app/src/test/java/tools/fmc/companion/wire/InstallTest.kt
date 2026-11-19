package tools.fmc.companion.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stub's install commands, scripted: `used` slots answer INFO with a header, writes are recorded. */
class FakeInstaller : Requester {
    val used = mutableMapOf<Int, ByteArray>()
    val calls = mutableListOf<Pair<Int, ByteArray>>()
    var beginStatus = Status.OK
    /** INFO 0xFE: a stub 1 answers it as a bad slot. */
    var stub = bytes(1)

    override suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long): ByteArray {
        calls += c2 to payload
        return when (c2) {
            Cmd.INFO -> if (payload.u8(0) == STUB_SLOT) stub else bytes(0) + (used[payload.u8(0)] ?: ByteArray(0))
            Cmd.BEGIN -> bytes(beginStatus.code)
            Cmd.DATA -> buf { u8(0); le32(payload.le32(0) + payload.size - 4) }
            else -> bytes(0)
        }
    }
}

fun namedHeader(c: Char) = Vectors.header.also { it[8] = c.code.toByte() }

class InstallTest {
    private val pkg = Vectors.pkg
    private val pkgHex get() = hex(pkg.getString("hex"))

    @Test fun headerMatchesPython() {
        assertEquals(Vectors.pkgHeader, parseHeader(Vectors.header))
        assertEquals(Vectors.pkgHeader, parseHeader(pkgHex))
        assertNull(parseHeader(ByteArray(64)))
    }

    @Test fun payloadsMatchPython() = runBlocking {
        val f = FakeInstaller()
        f.used += mapOf(0 to namedHeader('x'), 1 to namedHeader('y'), 2 to namedHeader('z'))
        val seen = mutableListOf<Double>()
        assertEquals(3, install(f, pkgHex) { seen += it })
        val writes = f.calls.filter { it.first != Cmd.INFO }.map { it.second.hex() }
        val data = pkg.getJSONArray("data_hex").let { a -> List(a.length()) { a.getString(it) } }
        assertEquals(listOf(pkg.getString("begin_hex")) + data + pkg.getString("end_hex"), writes)
        assertEquals(1.0, seen.last())
    }

    @Test fun picksSameNameSlot() = runBlocking {
        val f = FakeInstaller().apply { used[5] = Vectors.header }
        assertEquals(5, install(f, pkgHex) {})
    }

    @Test fun installBusy() = runBlocking {
        val f = FakeInstaller().apply { beginStatus = Status.BUSY }
        assertFailsWith<InstallError.Busy> { install(f, pkgHex) {} }
        assertTrue(f.calls.none { it.first == Cmd.DATA }, "nothing half-written")
    }

    @Test fun noFreeSlot() = runBlocking {
        val f = FakeInstaller()
        for (i in 0 until 8) f.used[i] = namedHeader('a' + i)
        assertEquals(8, assertFailsWith<InstallError.NoFreeSlot> { install(f, pkgHex) {} }.slots)
        assertTrue(f.calls.none { it.first == Cmd.INFO && it.second.u8(0) == 8 }, "stub 1: slots 0..7 only")
    }

    @Test fun stubSevenHasSixteenSlots() = runBlocking {
        assertEquals(listOf(8, 8, 16, 16), listOf(1L, 6L, 7L, 8L).map { appSlots(it) })
        val f = FakeInstaller().apply { stub = bytes(0, 7, 0, 0, 0, 4) }
        for (i in 0 until 8) f.used[i] = namedHeader('a' + i)
        assertEquals(8, install(f, pkgHex) {}, "the first free slot past 7")
        for (i in 8 until 16) f.used[i] = namedHeader('a' + i)
        assertEquals(16, assertFailsWith<InstallError.NoFreeSlot> { install(f, pkgHex) {} }.slots)
    }

    @Test fun bytesHelpers() {
        assertContentEquals(bytes(1, 0, 0, 0x80), buf { le32(0x8000_0001L) })
        assertEquals(0x8000_0001L, bytes(1, 0, 0, 0x80).le32(0))
    }
}
