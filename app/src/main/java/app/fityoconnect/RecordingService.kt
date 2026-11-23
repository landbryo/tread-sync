package app.fityoconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.fityoconnect.ble.TreadmillConnection
import app.fityoconnect.ble.TreadmillData
import app.fityoconnect.ui.formatDistance
import app.fityoconnect.ui.formatDuration
import app.fityoconnect.workout.WorkoutRecorder

/**
 * Foreground service that keeps the treadmill connected and records workouts while the
 * screen is off. Finished workouts are saved and synced automatically.
 */
class RecordingService : Service(), TreadmillConnection.Listener {
    companion object {
        private const val ACTION_CONNECT = "connect"
        private const val ACTION_STOP = "stop"
        private const val ACTION_FINISH = "finish"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: RecordingService? = null
            private set

        fun connect(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            instance?.shutdown()
        }

        fun finishWorkout(context: Context) {
            instance?.finishNow()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var connection: TreadmillConnection
    private lateinit var recorder: WorkoutRecorder
    private var lastNotifyMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            recorder.tick(System.currentTimeMillis())?.let { AppGraph.sync.saveAndSync(it) }
            publish()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val settings = AppGraph.settings
        connection = TreadmillConnection(this, this)
        recorder = WorkoutRecorder(
            autoStopMs = { settings.autoStopMin.coerceAtLeast(1) * 60_000L },
            strideM = { settings.strideCm / 100.0 },
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Treadmill recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Connecting to treadmill"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        when (intent?.action) {
            ACTION_CONNECT, null -> {
                val address = AppGraph.settings.deviceAddress
                if (address.isEmpty()) {
                    shutdown()
                } else {
                    recorder.deviceName = AppGraph.settings.deviceName
                    connection.connect(address)
                    main.removeCallbacks(ticker)
                    main.post(ticker)
                }
            }
            ACTION_STOP -> shutdown()
            ACTION_FINISH -> finishNow()
        }
        return START_NOT_STICKY
    }

    fun finishNow() = main.post {
        val w = recorder.finish()
        if (w != null) AppGraph.sync.saveAndSync(w) else AppGraph.log("Workout too short to save")
        publish()
    }

    fun shutdown() = main.post {
        recorder.finish()?.let { AppGraph.sync.saveAndSync(it) }
        connection.disconnect()
        main.removeCallbacks(ticker)
        AppGraph.live.value = LiveState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun startBelt() = connection.startBelt()
    fun pauseBelt() = connection.pauseBelt()
    fun changeSpeed(deltaKmh: Double) {
        val current = recorder.snapshot().speedKmh
        connection.setSpeedKmh((current + deltaKmh).coerceIn(0.5, 12.0))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recorder.finish()?.let { AppGraph.sync.saveAndSync(it) }
        connection.disconnect()
        instance = null
        super.onDestroy()
    }

    // TreadmillConnection.Listener (called on the main thread)

    override fun onState(state: TreadmillConnection.State, ftms: Boolean) {
        if (state != TreadmillConnection.State.CONNECTED) recorder.onLinkLost(System.currentTimeMillis())
        val link = when (state) {
            TreadmillConnection.State.CONNECTED -> Link.CONNECTED
            TreadmillConnection.State.CONNECTING -> Link.CONNECTING
            TreadmillConnection.State.DISCONNECTED -> Link.DISCONNECTED
        }
        AppGraph.live.value = AppGraph.live.value.copy(link = link, ftms = ftms, deviceName = AppGraph.settings.deviceName)
        publish(force = true)
    }

    override fun onData(data: TreadmillData) {
        recorder.onData(System.currentTimeMillis(), data)
    }

    override fun onLog(line: String) = AppGraph.log(line)

    override fun onServices(dump: String) {
        AppGraph.services.value = dump
    }

    private fun publish(force: Boolean = false) {
        val snap = recorder.snapshot()
        AppGraph.live.value = AppGraph.live.value.copy(recorder = snap)
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs < 5_000) return
        lastNotifyMs = now
        val text = when {
            AppGraph.live.value.link != Link.CONNECTED -> "Connecting to ${AppGraph.settings.deviceName.ifBlank { "treadmill" }}"
            snap.phase == WorkoutRecorder.Phase.IDLE -> "Connected. Start walking to record."
            else -> "${formatDistance(snap.distanceM)} in ${formatDuration(snap.activeMs)}" +
                if (snap.phase == WorkoutRecorder.Phase.PAUSED) " (paused)" else ""
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("FitYo Connect")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
