package tools.fmc.companion

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LifecycleResumeEffect

// What watch apps asked for and what they may use (docs/miniapp-rpc.md, "Permissions" and "Personal data").

private const val NEARBY = "The watch link is not private: anyone nearby with a Bluetooth scanner could read what " +
    "the watch receives. Location goes rounded to about 10 km, with the town name."

@Composable
fun PermissionsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val grants = remember { Grants(context) }
    // the service writes requests while this is open; the OS permissions change in system settings
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(grants) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        grants.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { grants.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose {} }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        RpcService.start(context) // location adds its foreground service type
    }
    fun askOs(cap: String) {
        if (osAllows(context, cap)) return
        osPermission(cap)?.let { return ask.launch(it) }
        if (cap == "notifications") context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationWatcher::class.java).flattenToString()))
    }
    fun answer(pkg: String, cap: String, allow: Boolean) {
        grants.set(pkg, cap, allow)
        if (allow) askOs(cap)
    }

    @Suppress("UNUSED_EXPRESSION") tick // read: recomposes on every change
    val pending = grants.pending()
    val all = grants.all()
    Column {
        Bar("Permissions", back)
        LazyColumn {
            if (pending.isEmpty() && all.isEmpty()) item {
                Section(footer = NEARBY) { Text("No watch app has asked for anything yet.", color = Fmc.secondary) }
            }
            if (pending.isNotEmpty()) item {
                Section("Waiting for you", footer = NEARBY) {
                    pending.forEachIndexed { i, (pkg, cap) ->
                        if (i > 0) HorizontalDivider()
                        Text("$pkg wants ${capLabel(cap)}", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ answer(pkg, cap, false) }) { Text("Deny", color = Fmc.danger) }
                            TextButton({ answer(pkg, cap, true) }) { Text("Allow") }
                        }
                    }
                }
            }
            all.forEach { (pkg, caps) ->
                item(pkg) {
                    Section(pkg, footer = "Calendar titles and notification text go to the watch only with " +
                        "\"Show text on the watch\" on. $NEARBY") {
                        caps.toSortedMap().forEach { (cap, allowed) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(capLabel(cap).replaceFirstChar { it.uppercase() })
                                    if (allowed && !osAllows(context, cap)) Text("Off in Android settings. Tap to turn on.",
                                        Modifier.clickable { askOs(cap) }, style = MaterialTheme.typography.bodyMedium, color = Fmc.warning)
                                }
                                Switch(allowed, { answer(pkg, cap, it) })
                            }
                            HorizontalDivider()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show text on the watch", Modifier.weight(1f))
                            Switch(grants.showText(pkg), { grants.setShowText(pkg, it) })
                        }
                    }
                }
            }
        }
    }
}
