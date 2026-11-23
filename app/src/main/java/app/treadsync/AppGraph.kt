package app.treadsync

import android.app.Application
import android.content.Context
import app.treadsync.ble.BleScanner
import app.treadsync.sync.HealthConnectSync
import app.treadsync.sync.StravaClient
import app.treadsync.sync.SyncManager
import app.treadsync.workout.Workout
import app.treadsync.workout.WorkoutRecorder
import app.treadsync.workout.WorkoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TreadSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

enum class Link { DISCONNECTED, CONNECTING, CONNECTED }

data class LiveState(
    val link: Link = Link.DISCONNECTED,
    val deviceName: String = "",
    val ftms: Boolean = false,
    val recorder: WorkoutRecorder.Snapshot? = null,
)

/** Hand-wired singletons shared by the UI and the recording service. */
object AppGraph {
    lateinit var app: Context
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings by lazy { Settings(app) }
    val store by lazy { WorkoutStore(File(app.filesDir, "workouts")) }
    val scanner by lazy { BleScanner(app) }
    val health by lazy { HealthConnectSync(app) }
    val strava by lazy { StravaClient(settings) }
    val sync by lazy { SyncManager(store, health, strava) }

    val live = MutableStateFlow(LiveState())
    val workouts = MutableStateFlow<List<Workout>>(emptyList())
    val stravaAthlete = MutableStateFlow("")
    val services = MutableStateFlow("")
    val log = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        app = context.applicationContext
        stravaAthlete.value = settings.stravaAthlete
        scope.launch { reloadWorkouts() }
    }

    fun reloadWorkouts() {
        workouts.value = store.all()
    }

    fun log(line: String) {
        val stamped = "${timeFmt.format(Date())} $line"
        log.update { (it + stamped).takeLast(400) }
    }

    fun toast(msg: String) {
        message.value = msg
    }
}
