package app.fityoconnect

import android.content.Context
import androidx.core.content.edit

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
