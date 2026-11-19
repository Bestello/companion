package tools.fmc.companion.wire

// What the App Loader keeps on the watch, read only: the stub's release, the loader and the app in each slot
// (fmc_frontend device/lib ble.ts readWp2, wp2.ts; cmf-watch-sdk docs/abi-1.4.md "Stub version").
// Port of ios/WireKit Loader.swift.

/** INFO for this slot answers the stub's release instead of a header (stub 2+). */
const val STUB_SLOT = 0xFE

/** `version` is 1 for every stub from before the release was readable; `abiMinor` null on stub 1. */
data class Stub(val version: Long, val abiMinor: Int?)

/** `apps`: slot → app. */
data class LoaderState(val stub: Stub, val loader: PackageHeader?, val apps: Map<Int, PackageHeader>)

/** `status ‖ version:u32le ‖ abi_minor`; an older stub reads 0xFE as a bad slot (`01`). */
fun parseStubReply(r: ByteArray): Stub? {
    if (r.isNotEmpty() && r.u8(0) == Status.BAD_REQUEST.code) return Stub(1, null)
    if (r.size != 6 || r.u8(0) != Status.OK.code) return null
    return Stub(r.le32(1), r.u8(5))
}

/**
 * null: no stub answered (stock firmware, or a stub without K, anything before fw-wp2.bin of 2026-11-13).
 * INFO only reads headers; it also drops an install the stub had open, and nothing here opens one.
 */
suspend fun readLoader(s: Requester, probeMs: Long = 2500): LoaderState? {
    val reply = try {
        // a stub answers at once; stock stays silent
        s.call(Cmd.INFO, bytes(STUB_SLOT), probeMs)
    } catch (_: WireError.Timeout) {
        return null
    }
    val stub = parseStubReply(reply) ?: throw WireError.Refused(if (reply.isEmpty()) 0xFF else reply.u8(0))
    suspend fun read(slot: Int): PackageHeader? {
        val r = s.call(Cmd.INFO, bytes(slot), 4000)
        return if (r.isNotEmpty() && r.u8(0) == Status.OK.code) parseHeader(r.copyOfRange(1, r.size)) else null
    }
    val apps = sortedMapOf<Int, PackageHeader>()
    for (slot in 0 until appSlots(stub.version)) read(slot)?.let { apps[slot] = it }
    return LoaderState(stub, read(LOADER_SLOT), apps)
}
