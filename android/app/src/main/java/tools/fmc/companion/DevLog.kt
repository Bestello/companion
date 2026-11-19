package tools.fmc.companion

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Developer screen: what the RPC service did (link up and down, each request with its package, method and
// status), kept in files/rpc.log so what happened with no screen open shows up later.
// ponytail: appended per line, rewritten on clear only; the last 500 lines in memory.

object DevLog {
    val lines = mutableStateListOf<String>()
    private var file: File? = null

    @Synchronized
    private fun open(context: Context): File = file ?: File(context.filesDir, "rpc.log").also {
        file = it
        if (it.exists()) lines.addAll(it.readLines().takeLast(500))
    }

    /** The lines, read from the file on first use. */
    fun lines(context: Context): List<String> { open(context); return lines }

    @Synchronized
    fun log(context: Context, text: String) {
        val f = open(context)
        val me = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val state = if (me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "fg" else "BG"
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $state $text"
        lines.add(line)
        if (lines.size > 500) lines.subList(0, lines.size - 500).clear()
        runCatching { f.appendText(line + "\n") }
    }

    @Synchronized
    fun clear(context: Context) {
        open(context).delete()
        lines.clear()
    }

    private val methods = mapOf(0x01 to "caps", 0x10 to "calendar.events", 0x11 to "reminders", 0x12 to "reminders.complete", 0x20 to "location", 0x30 to "weather", 0x41 to "http.request", 0x60 to "storage.get", 0x61 to "storage.put", 0x62 to "storage.delete", 0x70 to "phone.battery", 0x51 to "notify",
        0x40 to "http.get", 0x50 to "notifications")
    private val statuses = listOf("OK", "BAD_REQUEST", "DENIED", "UNAVAILABLE", "ASK", "TOO_LARGE")

    /** "‹package› ‹method› → ‹STATUS›" for "R1Q id method …" in and "R1A id status index total" out. */
    fun request(pkg: String, request: ByteArray, replies: List<ByteArray>): String {
        val method = request.getOrNull(4)?.let { methods[it.toInt() and 0xFF] } ?: "?"
        val status = replies.firstOrNull()?.getOrNull(4)?.let { (it.toInt() and 0xFF).let { s -> statuses.getOrNull(s) ?: "$s" } } ?: "?"
        return "$pkg $method → $status"
    }
}

@Composable
fun DeveloperScreen(back: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { DevLog.lines(context) }
    var clearing by remember { mutableStateOf(false) }
    Column {
        Bar("Developer", back) {
            IconButton({
                // ponytail: shared as text, no FileProvider; a file share if the log outgrows a share sheet
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "rpc.log").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")), null))
            }, enabled = lines.isNotEmpty()) { Icon(Icons.Default.Share, "Share") }
            IconButton({ clearing = true }, enabled = lines.isNotEmpty()) { Icon(Icons.Default.Delete, "Clear") }
        }
        LazyColumn {
            item {
                Section("RPC log", footer = "BG: the app was in the background when it happened.") {
                    if (lines.isEmpty()) Text("Nothing yet. Requests from watch apps show up here.", color = Fmc.secondary)
                    SelectionContainer {
                        Column {
                            lines.asReversed().forEach {
                                Text(it, Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Clear the RPC log?") },
        confirmButton = { TextButton({ clearing = false; DevLog.clear(context) }) { Text("Clear", color = Fmc.danger) } },
        dismissButton = { TextButton({ clearing = false }) { Text("Cancel") } },
    )
}
