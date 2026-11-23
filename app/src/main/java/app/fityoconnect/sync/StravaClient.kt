package app.fityoconnect.sync

import android.net.Uri
import app.fityoconnect.Settings
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Strava OAuth and FIT upload. Blocking calls: run them off the main thread.
 * Uses the user's own Strava API application (client ID and secret entered in the app).
 */
class StravaClient(private val settings: Settings) {
    companion object {
        const val REDIRECT_URI = "fityoconnect://localhost"
        private const val API = "https://www.strava.com/api/v3"
        private const val TOKEN_URL = "https://www.strava.com/oauth/token"
    }

    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    val isConfigured: Boolean get() = settings.stravaClientId.isNotEmpty() && settings.stravaClientSecret.isNotEmpty()
    val isConnected: Boolean get() = settings.stravaRefreshToken.isNotEmpty()

    fun authorizeUri(): Uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", settings.stravaClientId)
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "activity:write,read")
        .build()

    /** Exchanges the OAuth code from the redirect for tokens. Returns the athlete's name. */
    fun exchangeCode(code: String): String {
        val json = postToken(
            "grant_type" to "authorization_code",
            "code" to code,
        )
        saveTokens(json)
        val athlete = json.optJSONObject("athlete")
        val name = listOfNotNull(athlete?.optString("firstname"), athlete?.optString("lastname"))
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Strava athlete" }
        settings.stravaAthlete = name
        return name
    }

    fun disconnect() {
        val token = settings.stravaAccessToken
        if (token.isNotEmpty()) {
            runCatching {
                val req = Request.Builder().url("https://www.strava.com/oauth/deauthorize")
                    .post(FormBody.Builder().add("access_token", token).build()).build()
                http.newCall(req).execute().close()
            }
        }
        settings.clearStrava()
    }

    /** Uploads a FIT file and waits for Strava to process it. Returns the activity ID. */
    fun upload(fit: File, name: String, description: String, externalId: String): Long {
        val token = accessToken()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("data_type", "fit")
            .addFormDataPart("name", name)
            .addFormDataPart("description", description)
            .addFormDataPart("trainer", "1")
            .addFormDataPart("external_id", externalId)
            .addFormDataPart("file", fit.name, fit.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        var json = call(Request.Builder().url("$API/uploads").header("Authorization", "Bearer $token").post(body).build())
        val uploadId = json.getLong("id")
        repeat(30) {
            result(json)?.let { return it }
            Thread.sleep(2_000)
            json = call(Request.Builder().url("$API/uploads/$uploadId").header("Authorization", "Bearer $token").get().build())
        }
        throw IOException("Strava is still processing the upload. Tap Retry later.")
    }

    private fun result(json: JSONObject): Long? {
        if (!json.isNull("activity_id")) return json.getLong("activity_id")
        val error = if (json.isNull("error")) "" else json.getString("error")
        if (error.isNotEmpty()) {
            // Strava rejects re-uploads with "... duplicate of <a href='/activities/123'>...": that counts as done.
            Regex("duplicate of.*?(\\d{5,})").find(error)?.let { return it.groupValues[1].toLong() }
            throw IOException("Strava: $error")
        }
        return null
    }

    private fun accessToken(): String {
        if (!isConnected) throw IOException("Strava is not connected")
        if (System.currentTimeMillis() / 1000 < settings.stravaExpiresAt - 120) return settings.stravaAccessToken
        saveTokens(postToken("grant_type" to "refresh_token", "refresh_token" to settings.stravaRefreshToken))
        return settings.stravaAccessToken
    }

    private fun postToken(vararg params: Pair<String, String>): JSONObject {
        if (!isConfigured) throw IOException("Enter your Strava client ID and secret first")
        val form = FormBody.Builder()
            .add("client_id", settings.stravaClientId)
            .add("client_secret", settings.stravaClientSecret)
        params.forEach { (k, v) -> form.add(k, v) }
        return call(Request.Builder().url(TOKEN_URL).post(form.build()).build())
    }

    private fun saveTokens(json: JSONObject) {
        settings.stravaAccessToken = json.getString("access_token")
        settings.stravaRefreshToken = json.getString("refresh_token")
        settings.stravaExpiresAt = json.getLong("expires_at")
    }

    private fun call(req: Request): JSONObject = http.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            throw IOException("Strava HTTP ${resp.code}${if (msg.isNotEmpty()) ": $msg" else ""}")
        }
        JSONObject(text)
    }
}
