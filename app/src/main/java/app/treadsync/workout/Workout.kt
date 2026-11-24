package app.treadsync.workout

import org.json.JSONArray
import org.json.JSONObject

/** A per-second point in a workout. */
data class Sample(val tMs: Long, val distanceM: Double, val speedMps: Double)

/** A stretch where the belt was stopped mid-workout. */
data class Pause(val startMs: Long, val endMs: Long)

data class Workout(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val zoneOffsetSec: Int,
    val activeMs: Long,
    val distanceM: Double,
    val maxSpeedMps: Double,
    val kcal: Int?,
    val steps: Long?,
    val deviceName: String,
    val samples: List<Sample>,
    val pauses: List<Pause>,
    val hcSynced: Boolean = false,
    val hcError: String? = null,
    val stravaActivityId: Long? = null,
    val stravaError: String? = null,
) {
    val elapsedMs: Long get() = endMs - startMs
    val avgSpeedMps: Double get() = if (activeMs > 0) distanceM / (activeMs / 1000.0) else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startMs", startMs)
        put("endMs", endMs)
        put("zoneOffsetSec", zoneOffsetSec)
        put("activeMs", activeMs)
        put("distanceM", distanceM)
        put("maxSpeedMps", maxSpeedMps)
        put("kcal", kcal ?: JSONObject.NULL)
        put("steps", steps ?: JSONObject.NULL)
        put("deviceName", deviceName)
        put("hcSynced", hcSynced)
        put("hcError", hcError ?: JSONObject.NULL)
        put("stravaActivityId", stravaActivityId ?: JSONObject.NULL)
        put("stravaError", stravaError ?: JSONObject.NULL)
        put("samples", JSONArray().apply {
            samples.forEach { put(JSONArray().put(it.tMs).put(it.distanceM).put(it.speedMps)) }
        })
        put("pauses", JSONArray().apply {
            pauses.forEach { put(JSONArray().put(it.startMs).put(it.endMs)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Workout {
            fun optLong(k: String): Long? = if (o.isNull(k)) null else o.getLong(k)
            fun optStr(k: String): String? = if (o.isNull(k)) null else o.getString(k)
            val s = o.getJSONArray("samples")
            val p = o.getJSONArray("pauses")
            return Workout(
                id = o.getString("id"),
                startMs = o.getLong("startMs"),
                endMs = o.getLong("endMs"),
                zoneOffsetSec = o.getInt("zoneOffsetSec"),
                activeMs = o.getLong("activeMs"),
                distanceM = o.getDouble("distanceM"),
                maxSpeedMps = o.getDouble("maxSpeedMps"),
                kcal = optLong("kcal")?.toInt(),
                steps = optLong("steps"),
                deviceName = o.optString("deviceName"),
                samples = (0 until s.length()).map {
                    val a = s.getJSONArray(it)
                    Sample(a.getLong(0), a.getDouble(1), a.getDouble(2))
                },
                pauses = (0 until p.length()).map {
                    val a = p.getJSONArray(it)
                    Pause(a.getLong(0), a.getLong(1))
                },
                hcSynced = o.optBoolean("hcSynced"),
                hcError = optStr("hcError"),
                stravaActivityId = optLong("stravaActivityId"),
                stravaError = optStr("stravaError"),
            )
        }
    }
}
