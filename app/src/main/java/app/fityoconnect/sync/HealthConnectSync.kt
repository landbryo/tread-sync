package app.fityoconnect.sync

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import app.fityoconnect.workout.Workout
import java.time.Instant
import java.time.ZoneOffset

/** Writes finished workouts to Health Connect, the on-device store that Google Health reads. */
class HealthConnectSync(private val context: Context) {
    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        )
    }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)
    val available: Boolean get() = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        available && client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Inserts the workout. Client record IDs make a retry replace earlier copies instead of duplicating. */
    suspend fun write(w: Workout) {
        val start = Instant.ofEpochMilli(w.startMs)
        val end = Instant.ofEpochMilli(w.endMs.coerceAtLeast(w.startMs + 1_000))
        val zone = ZoneOffset.ofTotalSeconds(w.zoneOffsetSec)
        val device = Device(manufacturer = Build.MANUFACTURER, model = Build.MODEL, type = Device.TYPE_PHONE)
        fun meta(kind: String) = Metadata.activelyRecorded(device, "fityoconnect-${w.id}-$kind", 1L)

        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                metadata = meta("session"),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                title = "Treadmill walk",
                notes = if (w.deviceName.isNotBlank()) "Recorded from ${w.deviceName}" else null,
            ),
            DistanceRecord(
                startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                distance = Length.meters(w.distanceM), metadata = meta("distance"),
            ),
        )
        w.steps?.takeIf { it > 0 }?.let {
            records += StepsRecord(
                startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                count = it, metadata = meta("steps"),
            )
        }
        w.kcal?.takeIf { it > 0 }?.let {
            records += ActiveCaloriesBurnedRecord(
                startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                energy = Energy.kilocalories(it.toDouble()), metadata = meta("calories"),
            )
        }
        // One speed point every 5 seconds is plenty for charts and stays well under record limits.
        val speedSamples = w.samples
            .filter { it.tMs in w.startMs..w.endMs }
            .distinctBy { it.tMs / 5_000 }
            .map { SpeedRecord.Sample(Instant.ofEpochMilli(it.tMs), Velocity.metersPerSecond(it.speedMps)) }
        if (speedSamples.isNotEmpty()) {
            records += SpeedRecord(
                startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                samples = speedSamples, metadata = meta("speed"),
            )
        }
        client.insertRecords(records)
    }
}
