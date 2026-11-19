package tools.fmc.companion.wire

import java.io.File

// storage (docs/miniapp-rpc.md, 0x60–0x62): a watch app's key/value store on the phone, past the watch's 4 KiB.
// One file per key under <root>/<package as hex>/; the app gives a root kept out of backups. Port of WireKit Storage.swift.

/** A key: 1–16 bytes of `A–Z a–z 0–9 . _ -` (safe as a file name); BAD_REQUEST otherwise. */
fun storageKey(d: ByteArray): String {
    val ok = d.size in 1..16 && d.all { b ->
        val c = b.toInt() and 0xFF
        c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A || c == 0x2E || c == 0x5F || c == 0x2D
    }
    if (!ok) throw RpcError(RpcStatus.BAD_REQUEST)
    return String(d, Charsets.US_ASCII)
}

/** `length:u16`, then the value in 121-byte chunks. */
fun valueRecords(value: ByteArray): List<ByteArray> =
    listOf(buf { le16(value.size) }) + (value.indices step RECORD_MAX).map { value.copyOfRange(it, minOf(it + RECORD_MAX, value.size)) }

class KeyValueStore(private val root: File) {
    private fun dir(pkg: String) = File(root, pkg.toByteArray().hex())

    fun get(pkg: String, key: String): ByteArray? = File(dir(pkg), key).takeIf { it.isFile }?.readBytes()

    /** TOO_LARGE when the package would go over its quota. */
    @Synchronized fun put(pkg: String, key: String, value: ByteArray) {
        val others = used(pkg) - (get(pkg, key)?.let { it.size + key.length } ?: 0)
        if (others + key.length + value.size > QUOTA) throw RpcError(RpcStatus.TOO_LARGE)
        dir(pkg).mkdirs()
        val tmp = File(dir(pkg), "$key.tmp~")
        tmp.writeBytes(value)
        tmp.renameTo(File(dir(pkg), key))
    }

    fun delete(pkg: String, key: String) { File(dir(pkg), key).delete() }

    /** Delete from the Watch. */
    fun clear(pkg: String) { dir(pkg).deleteRecursively() }

    /** Bytes of keys and values the package holds. */
    fun used(pkg: String): Int = dir(pkg).listFiles()?.sumOf { it.name.length + it.length().toInt() } ?: 0

    companion object {
        /** Keys and values together, per package. */
        const val QUOTA = 64 * 1024
    }
}
