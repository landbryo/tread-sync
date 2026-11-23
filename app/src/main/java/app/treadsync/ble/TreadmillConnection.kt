package app.treadsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Holds the GATT connection to the treadmill, reconnecting while [connect] is in effect.
 *
 * If the treadmill exposes FTMS it subscribes to Treadmill Data. Otherwise it subscribes to
 * every notifying characteristic and logs raw bytes, so a proprietary protocol can be decoded.
 * All GATT work runs on the main thread through a one-at-a-time operation queue.
 */
@SuppressLint("MissingPermission") // callers request BLUETOOTH_CONNECT first
class TreadmillConnection(private val context: Context, private val listener: Listener) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onState(state: State, ftms: Boolean)
        fun onData(data: TreadmillData)
        fun onLog(line: String)
        fun onServices(dump: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var wanted = false
    private var retryDelayMs = 3_000L
    private var ftms = false
    private var lastDataLogMs = 0L

    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private val opTimeout = Runnable { busy = false; next() }

    fun connect(address: String) {
        wanted = true
        if (this.address == address && gatt != null) return
        close()
        this.address = address
        retryDelayMs = 3_000L
        open()
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        close()
        listener.onState(State.DISCONNECTED, false)
    }

    private fun open() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val addr = address
        if (adapter == null || !adapter.isEnabled || addr == null) {
            listener.onLog("Bluetooth is off; retrying")
            scheduleReconnect()
            return
        }
        listener.onState(State.CONNECTING, false)
        listener.onLog("Connecting to $addr")
        gatt = adapter.getRemoteDevice(addr).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun close() {
        ops.clear()
        busy = false
        main.removeCallbacks(opTimeout)
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        ftms = false
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        main.postDelayed({ if (wanted && gatt == null) open() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
    }

    // FTMS control point commands. The machine must grant control (op 0x00) first.
    fun startBelt() = control(byteArrayOf(0x07))
    fun pauseBelt() = control(byteArrayOf(0x08, 0x02))
    fun setSpeedKmh(kmh: Double) {
        val v = (kmh * 100).toInt().coerceIn(0, 0xFFFF)
        control(byteArrayOf(0x02, (v and 0xFF).toByte(), (v shr 8).toByte()))
    }

    private fun control(command: ByteArray) = main.post {
        val cp = gatt?.getService(Uuids.FTMS)?.getCharacteristic(Uuids.FTMS_CONTROL_POINT) ?: run {
            listener.onLog("No FTMS control point on this treadmill")
            return@post
        }
        enqueue { write(cp, byteArrayOf(0x00)) }
        enqueue { write(cp, command) }
    }

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!busy) next()
    }

    private fun next() {
        main.removeCallbacks(opTimeout)
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            if (op()) {
                busy = true
                main.postDelayed(opTimeout, 3_000)
                return
            }
        }
        busy = false
    }

    private fun opDone() = main.post { busy = false; next() }

    private fun write(c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun subscribe(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        val d = c.getDescriptor(Uuids.CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun onServicesReady(g: BluetoothGatt) {
        listener.onServices(dump(g))
        val service = g.getService(Uuids.FTMS)
        val data = service?.getCharacteristic(Uuids.TREADMILL_DATA)
        ftms = data != null
        if (data != null) {
            listener.onLog("FTMS treadmill found; subscribing to Treadmill Data")
            enqueue { subscribe(data) }
            service.getCharacteristic(Uuids.FTMS_CONTROL_POINT)?.let { cp -> enqueue { subscribe(cp) } }
            service.getCharacteristic(Uuids.FTMS_STATUS)?.let { st -> enqueue { subscribe(st) } }
        } else {
            listener.onLog("No FTMS service. Logging every notifying characteristic for diagnosis.")
            g.services.flatMap { it.characteristics }
                .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                .forEach { c -> enqueue { subscribe(c) } }
        }
        listener.onState(State.CONNECTED, ftms)
    }

    private fun onValue(uuid: UUID, value: ByteArray) = main.post {
        if (uuid == Uuids.TREADMILL_DATA) {
            FtmsParser.parseTreadmillData(value)?.let(listener::onData)
            val now = System.currentTimeMillis()
            if (now - lastDataLogMs > 10_000) {
                lastDataLogMs = now
                listener.onLog("2ACD ${value.toHex()}")
            }
        } else {
            listener.onLog("${Uuids.shortName(uuid)} ${value.toHex()}")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Connected; discovering services")
                    retryDelayMs = 3_000L
                    main.postDelayed({ gatt?.discoverServices() }, 300)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Disconnected (status $status)")
                    close()
                    listener.onState(if (wanted) State.CONNECTING else State.DISCONNECTED, false)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (g == gatt && status == BluetoothGatt.GATT_SUCCESS) onServicesReady(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onLog("Subscribe failed for ${Uuids.shortName(d.characteristic.uuid)} (status $status)") }
            }
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onValue(c.uuid, value)
        }

        @Deprecated("Used before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                onValue(c.uuid, c.value ?: return)
            }
        }
    }

    private fun dump(g: BluetoothGatt): String = buildString {
        appendLine("Device: ${g.device.name ?: "?"} (${g.device.address})")
        for (s in g.services) {
            appendLine("Service ${Uuids.shortName(s.uuid)}")
            for (c in s.characteristics) {
                val p = c.properties
                val props = listOfNotNull(
                    "read".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
                    "write".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
                    "writeNoResp".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
                    "notify".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
                    "indicate".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
                )
                appendLine("  Char ${Uuids.shortName(c.uuid)} [${props.joinToString(",")}]")
            }
        }
    }
}
