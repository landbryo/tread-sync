package app.treadsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Short Bluetooth LE scan that lists nearby named devices, FTMS treadmills first. */
@SuppressLint("MissingPermission") // callers request BLUETOOTH_SCAN / location first
class BleScanner(private val context: Context) {
    data class Found(val address: String, val name: String, val rssi: Int, val ftms: Boolean)

    val results = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)

    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val ftms = result.scanRecord?.serviceUuids?.any { it.uuid == Uuids.FTMS } == true
            val found = Found(result.device.address, name, result.rssi, ftms)
            results.update { list ->
                (list.filterNot { it.address == found.address } + found)
                    .sortedWith(compareByDescending<Found> { it.ftms }.thenByDescending { it.rssi })
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
        }
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun start() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        stop()
        results.value = emptyList()
        scanning.value = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        handler.postDelayed({ stop() }, 12_000)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (!scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning.value = false
    }
}
