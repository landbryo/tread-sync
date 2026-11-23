package app.treadsync.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import app.treadsync.AppGraph
import app.treadsync.Link
import app.treadsync.RecordingService

/**
 * Pairs the treadmill with Android's Companion Device Manager so the system wakes the app
 * when the treadmill turns on (starts advertising) and recording begins without opening it.
 * Needs Android 12 or newer.
 */
object TreadmillCompanion {
    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

    @Suppress("DEPRECATION")
    fun isAssociated(context: Context, address: String): Boolean {
        if (!supported(context) || address.isEmpty()) return false
        val cdm = cdm(context) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
        } else {
            cdm.associations.any { it.equals(address, ignoreCase = true) }
        }
    }

    /**
     * Asks the system to show its pairing confirmation for [address]. [launch] receives the
     * IntentSender to start for result; [done] is called with true once the association exists.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun associate(context: Context, address: String, launch: (IntentSender) -> Unit, done: (Boolean, String?) -> Unit) {
        if (!supported(context)) return done(false, "Needs Android 12 or newer")
        val cdm = cdm(context) ?: return done(false, "Companion device service unavailable")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) = done(true, null)
            override fun onFailure(error: CharSequence?) = done(false, error?.toString() ?: "Pairing failed")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(request, context.mainExecutor, callback)
        } else {
            cdm.associate(request, callback, null)
        }
    }

    /** Begin (or stop) receiving appear events for the saved treadmill. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun observe(context: Context, address: String, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !isAssociated(context, address)) return false
        val cdm = cdm(context) ?: return false
        return runCatching {
            if (enabled) cdm.startObservingDevicePresence(address) else cdm.stopObservingDevicePresence(address)
        }.onFailure { AppGraph.log("Presence observation failed: ${it.message}") }.isSuccess
    }

    internal fun onAppeared(context: Context, address: String?) {
        val settings = AppGraph.settings
        AppGraph.log("Treadmill appeared (${address ?: "?"})")
        if (!settings.autoConnect) return
        if (address != null && !address.equals(settings.deviceAddress, ignoreCase = true)) return
        if (AppGraph.live.value.link != Link.DISCONNECTED) return
        runCatching { RecordingService.connect(context) }
            .onFailure { AppGraph.log("Could not start recording service: ${it.message}") }
    }
}

/** Woken by the system when the paired treadmill comes into range. */
@RequiresApi(31)
class TreadmillPresenceService : CompanionDeviceService() {
    @Deprecated("Android 12 callback")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) TreadmillCompanion.onAppeared(this, address)
    }

    @Deprecated("Android 12 callback")
    override fun onDeviceDisappeared(address: String) = Unit

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        TreadmillCompanion.onAppeared(this, associationInfo.deviceMacAddress?.toString())
    }

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = Unit
}
