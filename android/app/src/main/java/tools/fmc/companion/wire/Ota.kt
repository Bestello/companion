package tools.fmc.companion.wire

// The full firmware update over the stock session: port of ios/WireKit Ota.swift, itself of fmc_frontend device/lib
// ota-image.ts (inspectOtaImage, checkOtaTarget) and ble.ts flashFirmware; cmf-watch-sdk docs/ota.md. The sequence and
// why each command goes on its characteristic are in Ota.swift.

sealed class OtaError(message: String) : Exception(message) {
    /** The image fails a check (ota-image.ts): nothing was sent. */
    data class NotFlashable(val why: String) : OtaError("Not a flashable image: $why.")
    class Refused(message: String) : OtaError(message)
    class Rejected(message: String) : OtaError(message)
    class Stopped(message: String) : OtaError(message)
    data object Stalled : OtaError("The watch stopped asking for data.")
}

/** The board the Watch Pro 2's images are built for, the one watch this update has ever run against. */
const val OTA_BOARD = "jx402_01_3089c"
private val KNOWN_BOARDS = setOf(OTA_BOARD, "jx402_08")

data class OtaImage(val board: String, val version: String, val versionCode: Long?)

/**
 * `inspectOtaImage`: throws [OtaError.NotFlashable] with the site's reason when the watch shouldn't be offered [d]. The
 * watch has no signature check, only these CRCs.
 */
fun inspectOtaImage(d: ByteArray): OtaImage {
    fun need(ok: Boolean, why: () -> String) { if (!ok) throw OtaError.NotFlashable(why()) }
    fun ascii(off: Int, len: Int): String {
        val s = d.copyOfRange(off, minOf(off + len, d.size))
        return String(s.copyOf(s.indexOf(0).takeIf { it >= 0 } ?: s.size), Charsets.UTF_8)
    }
    fun sub(a: Int, b: Int) = d.copyOfRange(a, b)
    // no copies of a 57 MB image
    fun crc(a: Int, b: Int) = java.util.zip.CRC32().apply { update(d, a, b - a) }.value
    val hdr = 0x400
    val table = 0x200
    need(d.size >= hdr) { "too small" }
    need(ascii(0, 4) == "AOTA") { "no AOTA magic" }
    need(crc(8, hdr) == d.le32(4)) { "header checksum" }
    val count = d.le32(0x0C)
    need(count in 1..((hdr - table) / 32).toLong()) { "entry count $count" }
    val start = d.le16(0x12)
    val end = d.le32(0x14)
    need(start >= hdr && start <= end && end <= d.size) { "data bounds" }
    need(crc(start, end.toInt()) == d.le32(0x18)) { "data checksum" }
    val board = ascii(0x60, 28)
    need(board in KNOWN_BOARDS) { "built for board \"$board\", which no known watch has" }

    class Entry(val name: String, val off: Long, val size: Long, val flags: Long, val crc: Long)
    val entries = (0 until count.toInt()).map { i ->
        val o = table + i * 32
        Entry(ascii(o, 16), d.le32(o + 16), d.le32(o + 20), d.le32(o + 24), d.le32(o + 28))
    }
    val seen = mutableSetOf<String>()
    var prev = start.toLong()
    for (e in entries.sortedBy { it.off }) {
        need(e.name.isNotEmpty() && e.name !in seen && e.flags == 0L && e.size > 0 && e.off >= prev &&
            e.off + e.size <= end) { "entry \"${e.name}\" out of place" }
        need(crc(e.off.toInt(), (e.off + e.size).toInt()) == e.crc) { "checksum of ${e.name}" }
        seen += e.name
        prev = e.off + e.size
    }
    val x = entries.firstOrNull { it.name == "ota.xml" }?.takeIf { it.size <= 65536 }
        ?: throw OtaError.NotFlashable("no ota.xml")
    val xml = String(sub(x.off.toInt(), (x.off + x.size).toInt()), Charsets.UTF_8)
    // JS Number(): the real manifests write sizes in hex
    fun number(s: String) = if (s.startsWith("0x")) s.drop(2).toLongOrNull(16) else s.toLongOrNull()
    // ponytail: a manifest this regular is read by tag name, as the site does; a disagreeing table is refused below
    fun tag(name: String, from: String = xml) =
        Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(from)?.groupValues?.get(1)
    need(tag("board_name") == board) { "ota.xml names another board" }
    val declared = Regex("<partition>([\\s\\S]*?)</partition>").findAll(xml).map {
        (tag("file_name", it.groupValues[1]) ?: "") to tag("file_size", it.groupValues[1])?.let(::number)
    }.toList()
    val parts = entries.filter { it.name != "ota.xml" }
    need(declared.size == parts.size && parts.all { p -> declared.any { it.first == p.name && it.second == p.size } }) {
        "ota.xml doesn't match the file table"
    }
    need("TEMP.bin" in seen) { "no TEMP.bin — nothing for the watch to install" }
    // the stock receiver asks for the AGPS record too: an image without it stalls a real watch mid-transfer
    val e = end.toInt()
    val trailer = d.size - e
    need(trailer > 24 && ascii(e, 4) == "AGPS" && d.le32(e + 8) + (d.le32(e + 12) shl 32) == (trailer - 24).toLong()) {
        "no AGPS trailer (an emulator-only image?)"
    }
    return OtaImage(board, ascii(0x40, 32), tag("version_code")?.let(::number))
}

/**
 * `checkOtaTarget` for the one record the app offers (the Watch Pro 2's patched firmware): an image [inspectOtaImage]
 * passed is built for the Watch Pro 2, and the watch on the line advertises itself as one.
 */
fun checkOtaTarget(info: OtaImage, watchName: String?) {
    if (info.board != OTA_BOARD) throw OtaError.NotFlashable("built for board \"${info.board}\", not the CMF Watch Pro 2's")
    if (watchName?.contains("watch pro 2", ignoreCase = true) != true) {
        throw OtaError.Refused("Firmware updates are for the CMF Watch Pro 2 only, this is “${watchName ?: "an unnamed watch"}”.")
    }
}

/** The bytes per 0x9042 frame every hardware flash so far used; not derived from the MTU. */
private const val OTA_CHUNK = 224

private suspend fun StockSession.sendOta(cmd: Long, payload: ByteArray) {
    for (f in stockFrames(cmd, payload, key, mtu, OTA_CHUNK)) link.write(OtaUuid.WRITE, f)
}

/**
 * `flashFirmware`: [image] must have passed [checkOtaTarget]. [progress]: the watch's own percent (it skips what it
 * already has, so bytes sent say little), 100 once it has the image and was told to install it.
 */
suspend fun StockSession.flashFirmware(image: ByteArray, progress: (Int) -> Unit) {
    inbox.drain()
    send(StockCmd.OTA_BEGIN_REQ, bytes(MARKER))
    val (_, r1) = next(setOf(StockCmd.OTA_BEGIN_REP), 6000)
    // the watch's 0x9052 handler (cmf-watch-utils decomp FUN_100d343c): 0c battery under 20 %, 0a busy
    when (r1.firstOrNull()?.toInt()) {
        1 -> {}
        0x0C -> throw OtaError.Refused("The watch battery is below 20 %. Charge it and try again.")
        else -> throw OtaError.Refused("The watch refused the update (${r1.hex()}).")
    }
    sendOta(StockCmd.OTA_START_REQ, bytes(0x01, 0x00, 0x00, 0x5A))
    val (_, r2) = next(setOf(StockCmd.OTA_START_REP), 5000)
    if (r2.firstOrNull()?.toInt() != 1) throw OtaError.Refused("The watch refused to start (${r2.hex()}).")
    var served = 0L
    val wanted = setOf(StockCmd.OTA_CHUNK_REQ, StockCmd.OTA_VERDICT, StockCmd.OTA_STATUS_43, StockCmd.OTA_STATUS_44)
    while (true) {
        // the watch writes NAND between ranges; two minutes of silence is the reference client's "gone"
        val (cmd, p) = try {
            next(wanted, 120_000)
        } catch (_: WireError.Timeout) {
            throw OtaError.Stalled
        }
        when (cmd) {
            StockCmd.OTA_VERDICT -> {
                // without the commit the update stays staged and is never installed
                runCatching { send(StockCmd.OTA_COMMIT, bytes(MARKER)) }
                if (p.firstOrNull()?.toInt() != 1) throw OtaError.Rejected("The watch rejected the image (${p.hex()}).")
                progress(100)
                return
            }
            StockCmd.OTA_CHUNK_REQ -> {
                if (p.size < 8) continue
                val off = p.be32(0)
                val len = p.be32(4)
                // a zero range is the other way the watch says "that's all"
                if (off == 0L && len == 0L) {
                    runCatching { send(StockCmd.OTA_COMMIT, bytes(MARKER)) }
                    progress(100)
                    return
                }
                if (off >= image.size) continue
                val end = minOf(off + len, image.size.toLong())
                sendOta(StockCmd.OTA_CHUNK_WRITE, image.copyOfRange(off.toInt(), end.toInt()))
                served += end - off
                progress(minOf(if (p.size > 8) p.u8(8) else (served * 100 / image.size).toInt(), 99))
            }
            else -> throw OtaError.Stopped("The watch stopped the update (%04X %s).".format(cmd and 0xFFFF, p.hex()))
        }
    }
}
