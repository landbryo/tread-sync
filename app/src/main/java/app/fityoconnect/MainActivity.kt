package app.fityoconnect

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import app.fityoconnect.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleRedirect(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    /** Handles fityoconnect://localhost?code=... after the Strava authorization page. */
    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "fityoconnect") return
        setIntent(Intent(this, MainActivity::class.java))
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val scope = uri.getQueryParameter("scope").orEmpty()
        when {
            error != null -> AppGraph.toast("Strava authorization was cancelled ($error)")
            code == null -> AppGraph.toast("Strava did not return an authorization code")
            !scope.contains("activity:write") -> AppGraph.toast("Allow \"Upload your activities\" on the Strava page so workouts can be uploaded")
            else -> AppGraph.scope.launch {
                try {
                    val name = AppGraph.strava.exchangeCode(code)
                    AppGraph.stravaAthlete.value = name
                    AppGraph.toast("Connected to Strava as $name")
                    AppGraph.sync.syncPending()
                } catch (e: Exception) {
                    AppGraph.toast("Strava connection failed: ${e.message}")
                }
            }
        }
    }
}

/** Shown by Health Connect when the user asks why the app wants health permissions. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                app.fityoconnect.ui.PrivacyScreen(onClose = { finish() })
            }
        }
    }
}
