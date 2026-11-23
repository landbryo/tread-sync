package app.treadsync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** App settings and Strava tokens, stored in app-private SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String) = prefs.getString(key, "") ?: ""
    private fun put(key: String, value: String) = prefs.edit { putString(key, value) }

    var deviceAddress: String
        get() = str("deviceAddress")
        set(v) = put("deviceAddress", v)
    var deviceName: String
        get() = str("deviceName")
        set(v) = put("deviceName", v)

    var strideCm: Int
        get() = prefs.getInt("strideCm", 70)
        set(v) = prefs.edit { putInt("strideCm", v) }
    var autoStopMin: Int
        get() = prefs.getInt("autoStopMin", 2)
        set(v) = prefs.edit { putInt("autoStopMin", v) }

    /** Show distances and speeds in miles (true) or kilometers (false). */
    var useMiles: Boolean
        get() = prefs.getBoolean("useMiles", true)
        set(v) = prefs.edit { putBoolean("useMiles", v) }

    /** Strava activity title. Blank means automatic ("Morning treadmill walk"). */
    var stravaTitle: String
        get() = str("stravaTitle")
        set(v) = put("stravaTitle", v.trim())

    /** Start recording automatically when the paired treadmill turns on (companion device presence). */
    var autoConnect: Boolean
        get() = prefs.getBoolean("autoConnect", false)
        set(v) = prefs.edit { putBoolean("autoConnect", v) }
    var companionAssociationId: Int
        get() = prefs.getInt("companionAssociationId", -1)
        set(v) = prefs.edit { putInt("companionAssociationId", v) }

    /** Bumped on every change so Compose screens can react to settings edits. */
    val changes = MutableStateFlow(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changes.update { it + 1 } }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    var stravaClientId: String
        get() = str("stravaClientId")
        set(v) = put("stravaClientId", v.trim())
    var stravaClientSecret: String
        get() = str("stravaClientSecret")
        set(v) = put("stravaClientSecret", v.trim())
    var stravaAccessToken: String
        get() = str("stravaAccessToken")
        set(v) = put("stravaAccessToken", v)
    var stravaRefreshToken: String
        get() = str("stravaRefreshToken")
        set(v) = put("stravaRefreshToken", v)
    var stravaExpiresAt: Long
        get() = prefs.getLong("stravaExpiresAt", 0)
        set(v) = prefs.edit { putLong("stravaExpiresAt", v) }
    var stravaAthlete: String
        get() = str("stravaAthlete")
        set(v) = put("stravaAthlete", v)

    fun clearStrava() = prefs.edit {
        remove("stravaAccessToken"); remove("stravaRefreshToken")
        remove("stravaExpiresAt"); remove("stravaAthlete")
    }
}
