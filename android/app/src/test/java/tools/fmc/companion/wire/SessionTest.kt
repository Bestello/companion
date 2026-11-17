package tools.fmc.companion.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stub as a K host sees it: answers AT GETSECRET, PING and MSG under K; notifications cut in 20-byte pieces
 * (MTU 23); `foreign` adds a stock-keyed frame before every reply, like Nothing X's traffic on the shared link.
 */
class FakeStub : Link {
    val secret = "00112233445566778899aabbccddeeff"
    val key = channelKey(secret)!!
    private var handler: ((String, ByteArray) -> Unit)? = null
    val inbox = mutableListOf<ByteArray>()
    var status = Status.OK
    var foreign = false
    var pings = 0

    override fun onNotify(handler: (String, ByteArray) -> Unit) { this.handler = handler }

    fun notify(uuid: String, value: ByteArray) {
        for (off in value.indices step 20) handler?.invoke(uuid, value.copyOfRange(off, minOf(off + 20, value.size)))
    }

    /** An app message sent unsolicited (docs/abi-1.4.md). */
    fun event(data: ByteArray, seq: Int = 0) = notify(Uuid.CMD_NOTIFY, frame(Cmd.EVENT, bytes(seq, 0) + data, key))

    override suspend fun write(uuid: String, data: ByteArray) {
        if (uuid == Uuid.SHELL_WRITE) {
            notify(Uuid.SHELL_NOTIFY, "GETSECRET:$secret,OK\r\n".toByteArray())
            return
        }
        val f = FrameParser().feed(data).firstOrNull() ?: return
        val plain = open(key, f.body) ?: return
        if (foreign) notify(Uuid.CMD_NOTIFY, frame(f.c2 or 0xA000, bytes(9, 9, 9), ByteArray(16)))
        when (f.c2) {
            Cmd.PING -> {
                pings++
                notify(Uuid.CMD_NOTIFY, frame(f.c2 or 0xA000, bytes(0, 2, 1), key))
            }
            Cmd.MSG -> {
                if (status == Status.OK) inbox += plain.copyOfRange(1, plain.size)
                notify(Uuid.CMD_NOTIFY, frame(f.c2 or 0xA000, bytes(status.code), key))
            }
        }
    }
}

class SessionTest {
    @Test fun openReadsSecretAndRegisters() = runBlocking {
        val stub = FakeStub()
        val (s, secret) = KSession.open(stub)
        assertEquals(stub.secret, secret)
        assertContentEquals(stub.key, s.key)
        assertEquals(1, stub.pings, "one PING registers the K host")
        assertEquals(2 to 1, s.ping())
    }

    @Test fun sendAndUnsolicitedRecv() = runBlocking {
        val stub = FakeStub().apply { foreign = true }
        val (s, _) = KSession.open(stub, stub.secret)
        s.send("w1Q\u0000".toByteArray())
        assertContentEquals("w1Q\u0000".toByteArray(), stub.inbox.single())
        stub.notify(Uuid.CMD_NOTIFY, frame(Cmd.EVENT, bytes(0, 0, 1, 2, 3), ByteArray(16)))
        stub.event("w1R1234".toByteArray())
        assertContentEquals("w1R1234".toByteArray(), s.recv(2000))
        val pings = stub.pings
        assertEquals(WireError.Timeout("no message"), assertFailsWith<WireError.Timeout> { s.recv(300) })
        assertEquals(pings, stub.pings, "no polling unless asked")
    }

    @Test fun noAppIsReported() = runBlocking {
        val stub = FakeStub().apply { status = Status.NO_APP }
        val (s, _) = KSession.open(stub, stub.secret)
        assertFailsWith<WireError.NoApp> { s.send(bytes(1)) }
        Unit
    }

    @Test fun pollingPingsWhileWaiting() = runBlocking {
        val stub = FakeStub()
        val (s, _) = KSession.open(stub, stub.secret, pollMs = 50)
        runCatching { s.recv(300) }
        assertTrue(stub.pings >= 3)
    }

    @Test fun openWithoutRegisterSendsNoPing() = runBlocking {
        val stub = FakeStub()
        KSession.open(stub, register = false)
        assertEquals(0, stub.pings, "a stock watch never answers PING")
    }
}
