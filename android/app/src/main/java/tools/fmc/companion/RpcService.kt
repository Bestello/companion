package tools.fmc.companion

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tools.fmc.companion.wire.Cmd
import tools.fmc.companion.wire.KSession
import tools.fmc.companion.wire.Status
import tools.fmc.companion.wire.WireError
import tools.fmc.companion.wire.answer
import tools.fmc.companion.wire.bytes
import tools.fmc.companion.wire.parseHeader
import tools.fmc.companion.wire.parseRequest
import tools.fmc.companion.wire.u8

// Holds the link and answers the miniapps' requests (docs/miniapp-rpc.md) while it runs: a foreground service of
// type connectedDevice, plus location once that is allowed (Android gives an FGS location only by its type).
// One request at a time, in order. A dropped link or a dead session: a new one 30 s later.

class RpcService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this, "service", "Watch app services", NotificationManager.IMPORTANCE_LOW)
        val n = NotificationCompat.Builder(this, "service")
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("Watch apps can reach your phone")
            .setContentText("Calendar, weather and the rest, as you allowed them.")
            .setContentIntent(permissionsIntent(this))
            .setOngoing(true)
            .build()
        val location = if (granted(this, Manifest.permission.ACCESS_COARSE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or location)
        } catch (e: Exception) {
            // no Bluetooth permission, or started from the background where Android forbids it
            Log.w(TAG, "can't run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) job = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        val store = SecureStore(this)
        val phone = PhoneServices(this, Grants(this))
        while (scope.isActive) {
            val link = BleLink(this)
            try {
                link.connect()
                val account = "k-${link.watchId}"
                val session = try {
                    KSession.open(link, store[account] ?: throw WireError.Timeout("no cached K")).first
                } catch (e: WireError) {
                    // K changes with a factory reset: read it again
                    KSession.open(link).also { store[account] = it.second }.first
                }
                Log.i(TAG, "listening")
                listen(session, phone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link: ${e.message}")
            } finally {
                link.disconnect()
            }
            delay(30_000)
        }
    }

    private suspend fun listen(s: KSession, phone: PhoneServices) {
        // slot → package. ponytail: read once per session (INFO drops an install in progress, so not per request);
        // an app installed into a used slot while this runs keeps the old name until the next reconnect.
        val names = mutableMapOf<Int, String>()
        while (true) {
            val (slot, m) = try {
                s.recvFrom(60_000)
            } catch (_: WireError.Timeout) {
                s.ping() // registers again (a rebooted watch forgets us) and throws on a dead link
                continue
            }
            if (parseRequest(m) == null) continue
            val pkg = names[slot] ?: packageIn(s, slot)?.also { names[slot] = it } ?: continue
            val replies = answer(pkg, m, phone) ?: continue
            try {
                for (r in replies) s.send(r)
            } catch (e: WireError) {
                Log.w(TAG, "reply to $pkg: ${e.message}") // the app closed meanwhile
            }
        }
    }

    private suspend fun packageIn(s: KSession, slot: Int): String? {
        val r = s.call(Cmd.INFO, bytes(slot), 4000)
        return if (r.isNotEmpty() && r.u8(0) == Status.OK.code) parseHeader(r.copyOfRange(1, r.size))?.name else null
    }

    companion object {
        private const val TAG = "rpc"

        fun start(context: Context) {
            if (!granted(context, Manifest.permission.BLUETOOTH_CONNECT)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RpcService::class.java)) }
        }

        fun stop(context: Context) = context.stopService(Intent(context, RpcService::class.java))
    }
}

fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
