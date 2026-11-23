package app.treadsync.workout

import app.treadsync.fit.FitEncoder
import org.json.JSONObject
import java.io.File

/** Saves each workout as JSON (plus its FIT file) in app-private storage. */
class WorkoutStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    @Synchronized
    fun save(w: Workout) {
        val tmp = File(dir, "${w.id}.json.tmp")
        tmp.writeText(w.toJson().toString())
        tmp.renameTo(File(dir, "${w.id}.json"))
    }

    @Synchronized
    fun load(id: String): Workout? = File(dir, "$id.json").takeIf { it.exists() }?.let {
        runCatching { Workout.fromJson(JSONObject(it.readText())) }.getOrNull()
    }

    @Synchronized
    fun all(): List<Workout> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { runCatching { Workout.fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.startMs }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.fit").delete()
    }

    /** Writes (or rewrites) the workout's FIT file and returns it. */
    @Synchronized
    fun fitFile(w: Workout): File = File(dir, "${w.id}.fit").also { it.writeBytes(FitEncoder.encode(w)) }
}
