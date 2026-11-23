package app.treadsync.sync

import app.treadsync.AppGraph
import app.treadsync.workout.Workout
import app.treadsync.workout.WorkoutStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.treadsync.ui.autoTitle
import app.treadsync.ui.formatDistance
import app.treadsync.ui.formatSpeed

/** Sends saved workouts to Health Connect and Strava, remembering what already succeeded. */
class SyncManager(
    private val store: WorkoutStore,
    private val health: HealthConnectSync,
    private val strava: StravaClient,
) {
    private val mutex = Mutex()

    fun saveAndSync(w: Workout) {
        store.save(w)
        AppGraph.reloadWorkouts()
        AppGraph.log("Saved workout: ${formatDistance(w.distanceM, AppGraph.settings.useMiles)}")
        AppGraph.scope.launch { sync(w.id) }
    }

    fun syncPending() = AppGraph.scope.launch {
        store.all().filter { !it.hcSynced || it.stravaActivityId == null }.forEach { sync(it.id) }
    }

    fun retry(id: String) = AppGraph.scope.launch { sync(id) }

    suspend fun sync(id: String) = mutex.withLock {
        var w = store.load(id) ?: return@withLock

        if (!w.hcSynced) {
            w = try {
                when {
                    !health.available -> w.copy(hcError = "Health Connect is not available on this phone")
                    !health.hasPermissions() -> w.copy(hcError = "Health Connect permissions not granted")
                    else -> {
                        health.write(w)
                        AppGraph.log("Written to Health Connect")
                        w.copy(hcSynced = true, hcError = null)
                    }
                }
            } catch (e: Exception) {
                AppGraph.log("Health Connect error: ${e.message}")
                w.copy(hcError = e.message ?: e.javaClass.simpleName)
            }
            store.save(w)
            AppGraph.reloadWorkouts()
        }

        if (w.stravaActivityId == null) {
            w = if (!strava.isConnected) {
                w.copy(stravaError = "Strava not connected")
            } else try {
                val activityId = strava.upload(store.fitFile(w), title(w), describe(w), "treadsync-${w.id}.fit")
                AppGraph.log("Uploaded to Strava: activity $activityId")
                w.copy(stravaActivityId = activityId, stravaError = null)
            } catch (e: Exception) {
                AppGraph.log("Strava error: ${e.message}")
                w.copy(stravaError = e.message ?: e.javaClass.simpleName)
            }
            store.save(w)
            AppGraph.reloadWorkouts()
        }
    }

    private fun title(w: Workout): String =
        AppGraph.settings.stravaTitle.ifBlank { autoTitle(w.startMs, w.zoneOffsetSec) }

    private fun describe(w: Workout): String {
        val miles = AppGraph.settings.useMiles
        val device = w.deviceName.ifBlank { "walking pad" }
        val steps = w.steps?.let { ", about $it steps" } ?: ""
        return "${formatDistance(w.distanceM, miles)} at ${formatSpeed(w.avgSpeedMps * 3.6, miles)} average$steps, on $device. Recorded with Tread Sync."
    }
}
