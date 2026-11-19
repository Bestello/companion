package tools.fmc.companion.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** As ios RetryTests. */
class RetryTest {
    @Test fun waitsThenTriesUntilItAnswers() = runBlocking {
        var now = 0L
        val tries = mutableListOf<Long>()
        assertTrue(retry(20_000, 600_000, { now += it }) { tries += now; tries.size == 3 })
        assertEquals(listOf(20_000L, 40_000L, 60_000L), tries, "the first try after one wait: the watch is installing")
    }

    @Test fun givesUpAfterTheLimit() = runBlocking {
        var now = 0L
        var n = 0
        assertFalse(retry(20_000, 600_000, { now += it }) { n++; false })
        assertEquals(30, n)
        assertEquals(600_000L, now)
    }

    @Test fun debounces() {
        assertTrue(recheckDue(idle = false, busy = false, last = null, now = 1000_000))
        assertFalse(recheckDue(idle = true, busy = false, last = null, now = 1000_000), "never read: the user connects first")
        assertFalse(recheckDue(idle = false, busy = true, last = null, now = 1000_000), "a read, install or restart wait runs")
        assertFalse(recheckDue(idle = false, busy = false, last = 990_000, now = 1000_000))
        assertTrue(recheckDue(idle = false, busy = false, last = 970_000, now = 1000_000))
    }
}
