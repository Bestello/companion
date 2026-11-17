package tools.fmc.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tools.fmc.companion.wire.DataUuid
import tools.fmc.companion.wire.Link
import tools.fmc.companion.wire.Uuid
import tools.fmc.companion.wire.sha256Hex

// WireKit's Link over Android GATT. A second GATT client on the link Nothing X already holds (cmf-watch-sdk
// docs/abi-1.4.md, "Writing a host", steps 1–2): Android shares one LE link between apps' GATT clients, so no bind
// and no popup. UNTESTED ON A REAL WATCH (FMC-9, item 1). One GATT operation at a time, as Android requires.
// ponytail: foreground only, no CompanionDeviceManager and no foreground service yet; they come with the first
// feature that runs in the background (calendar).

class BleError(message: String) : Exception(message) {
    companion object {
        val off = BleError("Bluetooth is off.")
        val noWatch = BleError("No watch found. Open Nothing X and make sure the watch is connected.")
        val missing = BleError("This watch does not offer the expected services.")
        val dropped = BleError("The watch disconnected.")
    }
}

@SuppressLint("MissingPermission") // the UI asks for BLUETOOTH_CONNECT/SCAN before it creates a link
class BleLink(private val context: Context) : Link {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private val chars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val handlers = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val ops = Mutex()
    /** The GATT operation in flight and the callback that ends it; callbacks of another kind are not ours. */
    private class Step(val op: Op, val done: CompletableDeferred<Unit> = CompletableDeferred())
    private enum class Op { CONNECT, MTU, SERVICES, DESCRIPTOR, WRITE }

    @Volatile private var step: Step? = null
    @Volatile private var dropped = false
    @Volatile private var mtu = 23

    /** Keys the cached secrets of this watch; a hash, so no MAC sits in the app's files. */
    var watchId: String? = null
        private set

    /**
     * The watch Nothing X keeps connected, else the bonded one (the system connects it for us); with `scan`, as a
     * last resort the one advertising "CMF Watch…" within 10 s.
     */
    suspend fun connect(scan: Boolean = false) {
        val adapter = manager?.adapter ?: throw BleError.off
        if (!adapter.isEnabled) throw BleError.off
        fun isWatch(d: BluetoothDevice) = d.name?.startsWith(WATCH_NAME) == true
        val device = manager.getConnectedDevices(BluetoothProfile.GATT).firstOrNull(::isWatch)
            // a watch with Bluetooth calls is bonded twice under one name, once as BR/EDR: that one has no GATT
            ?: adapter.bondedDevices.firstOrNull { isWatch(it) && it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            ?: (if (scan) scan() else null)
            ?: throw BleError.noWatch
        watchId = sha256Hex(device.address).take(16)
        // connected → MTU → services → every notify on
        dropped = false
        gattStep(Op.CONNECT) { gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }
        val g = gatt ?: throw BleError.noWatch
        gattStep(Op.MTU) { g.requestMtu(247) }
        gattStep(Op.SERVICES) { g.discoverServices() }
        for (s in g.services) for (c in s.characteristics) {
            val name = c.uuid.toString().uppercase()
            if (name in KNOWN) chars[name] = c
        }
        if (REQUIRED.any { it !in chars }) throw BleError.missing
        for (name in NOTIFIES) {
            val c = chars[name] ?: continue
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD) ?: continue
            gattStep(Op.DESCRIPTOR) { writeDescriptor(g, d) }
        }
    }

    fun disconnect() {
        gatt?.run { disconnect(); close() }
        gatt = null
        step?.done?.completeExceptionally(BleError.dropped)
    }

    override fun onNotify(handler: (String, ByteArray) -> Unit) {
        handlers += handler
    }

    /** A frame over MTU-3 goes as one write with response (abi-1.4.md, step 4); Android splits it. */
    override suspend fun write(uuid: String, data: ByteArray) = ops.withLock {
        val g = gatt?.takeUnless { dropped } ?: throw BleError.dropped
        val c = chars[uuid] ?: throw BleError.missing
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (data.size > mtu - 3 || !noResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        // Android acknowledges both kinds; waiting for it is the flow control
        stepLocked(Op.WRITE) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, on) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = on
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    /** One GATT step: started by `start`, finished by its callback. */
    private suspend fun gattStep(op: Op, start: () -> Any?) = ops.withLock { stepLocked(op, start) }

    private suspend fun stepLocked(op: Op, start: () -> Any?) {
        val s = Step(op)
        step = s
        if (start() == false) throw BleError("The watch did not take the request.")
        withTimeout(STEP_MS) { s.done.await() }
    }

    private suspend fun scan(): BluetoothDevice? {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                if ((r.scanRecord?.deviceName ?: r.device.name)?.startsWith(WATCH_NAME) == true) found.complete(r.device)
            }
        }
        scanner.startScan(cb)
        return try {
            withTimeoutOrNull(10_000) { found.await() }
        } finally {
            scanner.stopScan(cb)
        }
    }

    /** Ends the step only if it waits for this kind of callback: Android 14+ sends every client of the shared link
     * `onMtuChanged` when Nothing X changes the MTU, and a write that timed out can still be acknowledged late. */
    private fun finish(op: Op, status: Int) {
        val s = step?.takeIf { it.op == op } ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) s.done.complete(Unit) else s.done.completeExceptionally(BleError("GATT error $status"))
    }

    private fun dispatch(c: BluetoothGattCharacteristic, value: ByteArray) {
        val name = c.uuid.toString().uppercase()
        handlers.forEach { it(name, value) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> finish(Op.CONNECT, BluetoothGatt.GATT_SUCCESS)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    dropped = true // the next write fails at once, not after STEP_MS
                    step?.done?.completeExceptionally(BleError.dropped)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleLink.mtu = mtu
            finish(Op.MTU, BluetoothGatt.GATT_SUCCESS) // a refused MTU leaves 23: long writes still work
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(Op.SERVICES, status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(Op.DESCRIPTOR, status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(Op.WRITE, status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            dispatch(c, value)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) dispatch(c, c.value.copyOf())
        }
    }

    private companion object {
        const val WATCH_NAME = "CMF Watch"
        const val STEP_MS = 15_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val REQUIRED = listOf(Uuid.CMD_WRITE, Uuid.CMD_NOTIFY, Uuid.SHELL_WRITE, Uuid.SHELL_NOTIFY)

        /** The stock upload pair: only the watchface install needs it. */
        val KNOWN = REQUIRED + listOf(DataUuid.WRITE, DataUuid.NOTIFY)
        val NOTIFIES = listOf(Uuid.CMD_NOTIFY, Uuid.SHELL_NOTIFY, DataUuid.NOTIFY)
    }
}
