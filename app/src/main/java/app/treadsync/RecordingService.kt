package app.treadsync

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
import app.treadsync.ble.TreadmillConnection
import app.treadsync.ble.TreadmillData
import app.treadsync.ui.formatDistance
import app.treadsync.ui.formatDuration
import app.treadsync.workout.WorkoutRecorder

/**
 * Foreground service that keeps the treadmill connected and records workouts while the
 * screen is off. Finished workouts are saved and synced automatically. If the treadmill
 * stays unreachable with no workout in progress, the service stops itself.
 */
class RecordingService : Service(), TreadmillConnection.Listener {
    companion object {
        private const val ACTION_CONNECT = "connect"
        private const val ACTION_STOP = "stop"
        private const val ACTION_FINISH = "finish"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val GIVE_UP_MS = 5 * 60_000L

        @Volatile
        var instance: RecordingService? = null
            private set

        fun connect(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop() {
            instance?.shutdown()
        }

        fun finishWorkout() {
            instance?.finishNow()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var connection: TreadmillConnection
    private lateinit var recorder: WorkoutRecorder
    private var lastNotifyMs = 0L
    private var unreachableSinceMs = System.currentTimeMillis()

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            recorder.tick(now)?.let { AppGraph.sync.saveAndSync(it) }
            if (AppGraph.live.value.link == Link.CONNECTED) {
                unreachableSinceMs = now
            } else if (recorder.phase == WorkoutRecorder.Phase.IDLE && now - unreachableSinceMs > GIVE_UP_MS) {
                AppGraph.log("Treadmill unreachable for ${GIVE_UP_MS / 60_000} minutes; stopping")
                shutdown()
                return
            }
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
            this, NOTIFICATION_ID, buildNotification("Connecting to treadmill", false),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        when (intent?.action) {
            ACTION_CONNECT, null -> {
                val address = AppGraph.settings.deviceAddress
                if (address.isEmpty()) {
                    shutdown()
                } else {
                    recorder.deviceName = AppGraph.settings.deviceName
                    unreachableSinceMs = System.currentTimeMillis()
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

    fun finishNow() {
        main.post { finishOnMain() }
    }

    private fun finishOnMain() {
        val w = recorder.finish()
        if (w != null) AppGraph.sync.saveAndSync(w) else AppGraph.toast("Workout too short to save (under 1 minute)")
        publish(force = true)
    }

    fun shutdown() {
        main.post { shutdownOnMain() }
    }

    private fun shutdownOnMain() {
        recorder.finish()?.let { AppGraph.sync.saveAndSync(it) }
        connection.disconnect()
        main.removeCallbacks(ticker)
        AppGraph.live.value = LiveState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun startBelt() = connection.startBelt()
    fun pauseBelt() = connection.pauseBelt()
    private var targetKmh = 0.0
    private var targetSetMs = 0L

    /** Rapid taps build on the last requested speed rather than the belt's (lagging) reported speed. */
    fun changeSpeed(deltaKmh: Double) {
        main.post { applySpeed(deltaKmh) }
    }

    private fun applySpeed(deltaKmh: Double) {
        val now = System.currentTimeMillis()
        val base = if (now - targetSetMs < 3_000) targetKmh else recorder.snapshot().speedKmh
        targetKmh = (base + deltaKmh).coerceIn(0.5, 12.0)
        targetSetMs = now
        connection.setSpeedKmh(targetKmh)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recorder.finish()?.let { AppGraph.sync.saveAndSync(it) }
        connection.disconnect()
        AppGraph.live.value = LiveState()
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
        val inWorkout = snap.phase != WorkoutRecorder.Phase.IDLE
        val text = when {
            AppGraph.live.value.link != Link.CONNECTED && !inWorkout -> "Connecting to ${AppGraph.settings.deviceName.ifBlank { "treadmill" }}"
            !inWorkout -> "Connected. Start walking to record."
            else -> "${formatDistance(snap.distanceM, AppGraph.settings.useMiles)} in ${formatDuration(snap.activeMs)}" +
                if (snap.phase == WorkoutRecorder.Phase.PAUSED) " (paused)" else ""
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, inWorkout))
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String, inWorkout: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("Tread Sync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (inWorkout) addAction(0, "Finish", serviceIntent(ACTION_FINISH, 1)) }
            .addAction(0, "Disconnect", serviceIntent(ACTION_STOP, 2))
            .build()
    }
}
