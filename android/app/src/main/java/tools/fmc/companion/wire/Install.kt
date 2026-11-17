package tools.fmc.companion.wire

// Installing an app package from the phone over K (src/wp2apps/install.py): INFO, BEGIN, DATA…, END.
// Port of ios/WireKit Install.swift.

const val SLOTS = 8
const val LOADER_SLOT = 0xFF
private const val HEADER_SIZE = 64
private const val DATA_MAX = 128

/** `crc`: CRC-32 of the image, tells two builds with the same version apart (the loader's version stays 1). */
data class PackageHeader(val kind: Int, val name: String, val version: Long, val crc: Long = 0)

sealed class InstallError(message: String) : Exception(message) {
    data object Busy : InstallError("the app is running on the watch")
    data object NoFreeSlot : InstallError("no free slot on the watch")
    data object NotAPackage : InstallError("not an app package")
    data class Refused(val status: Int) : InstallError("refused (%02X)".format(status))
}

/** The package header (`package.HEADER`: magic "WP2A", abi, kind, abi_minor, name[24], version, size, entry,
 * relocations, crc, …). */
fun parseHeader(d: ByteArray): PackageHeader? {
    if (d.size < HEADER_SIZE || !d.copyOf(4).contentEquals("WP2A".toByteArray())) return null
    val name = d.copyOfRange(8, 32).takeWhile { it != 0.toByte() }.toByteArray()
    return PackageHeader(d.u8(6), String(name), d.le32(32), d.le32(48))
}

private fun check(r: ByteArray): ByteArray {
    if (r.isEmpty()) throw InstallError.Refused(0xFF)
    if (r.u8(0) == Status.BUSY.code) throw InstallError.Busy
    if (r.u8(0) != Status.OK.code) throw InstallError.Refused(r.u8(0))
    return r.copyOfRange(1, r.size)
}

/** Headers of the occupied slots (0…7 and the loader). */
suspend fun installed(s: Requester): Map<Int, PackageHeader> {
    val out = sortedMapOf<Int, PackageHeader>()
    for (slot in (0 until SLOTS) + LOADER_SLOT) {
        parseHeader(check(s.call(Cmd.INFO, bytes(slot))))?.let { out[slot] = it }
    }
    return out
}

/**
 * Installs an app package into the slot of the same name, else the first free one, and a loader package (kind 1)
 * into slot L; returns the slot. `Busy` while that app (or the loader's menu) runs on the watch.
 */
suspend fun install(s: Requester, pkg: ByteArray, progress: (Double) -> Unit): Int {
    val header = parseHeader(pkg)?.takeIf { it.kind == 0 || it.kind == 1 } ?: throw InstallError.NotAPackage
    val slot = if (header.kind == 1) LOADER_SLOT else {
        val used = installed(s)
        used.entries.firstOrNull { it.key < SLOTS && it.value.name == header.name }?.key
            ?: (0 until SLOTS).firstOrNull { it !in used }
            ?: throw InstallError.NoFreeSlot
    }
    check(s.call(Cmd.BEGIN, buf { u8(slot); le32(pkg.size.toLong()); le32(crc32(pkg)) }))
    for (off in pkg.indices step DATA_MAX) {
        val end = minOf(off + DATA_MAX, pkg.size)
        val r = check(s.call(Cmd.DATA, buf { le32(off.toLong()); add(pkg.copyOfRange(off, end)) }))
        if (r.size != 4 || r.le32(0) != end.toLong()) throw InstallError.Refused(0xFE)
        progress(end.toDouble() / pkg.size)
    }
    check(s.call(Cmd.END, bytes(0)))
    return slot
}
