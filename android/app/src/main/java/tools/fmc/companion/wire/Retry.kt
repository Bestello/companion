package tools.fmc.companion.wire

import kotlinx.coroutines.delay

// Reading the watch again: after a firmware is sent (it installs and restarts for minutes) and when the link comes
// back. Pure, so the timing is tested without a watch. Port of WireKit Retry.swift.

/** Waits [everyMs], then tries; again until [attempt] says true or [limitMs] has gone by (counting the waits). */
suspend fun retry(everyMs: Long, limitMs: Long, sleep: suspend (Long) -> Unit = { delay(it) }, attempt: suspend () -> Boolean): Boolean {
    var waited = 0L
    while (waited + everyMs <= limitMs) {
        sleep(everyMs)
        waited += everyMs
        if (attempt()) return true
    }
    return false
}

/** A read of the watch on a link that came back: only when the user has read it before (not idle), none runs, no
 *  firmware restart is being waited for, and the last read began at least [gapMs] ago. */
fun recheckDue(idle: Boolean, busy: Boolean, last: Long?, now: Long, gapMs: Long = 30_000) =
    !idle && !busy && (last == null || now - last >= gapMs)
