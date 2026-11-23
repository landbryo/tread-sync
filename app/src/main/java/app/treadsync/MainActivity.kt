package app.treadsync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.treadsync.ui.MainScreen
import app.treadsync.ui.MessageToasts
import app.treadsync.ui.SettingsScreen
import app.treadsync.ui.WorkoutDetailScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleRedirect(intent)
        setContent {
            AppTheme { AppNav() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    /** Handles treadsync://localhost?code=... after the Strava authorization page. */
    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "treadsync") return
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
            AppTheme { PrivacyScreen(onClose = { finish() }) }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Three screens: main (live + recent workouts), settings, and one workout's details. */
@Composable
private fun AppNav() {
    var screen by rememberSaveable { mutableStateOf("main") }
    var workoutId by rememberSaveable { mutableStateOf("") }
    MessageToasts()
    BackHandler(enabled = screen != "main") { screen = "main" }
    when (screen) {
        "settings" -> SettingsScreen(onBack = { screen = "main" })
        "workout" -> WorkoutDetailScreen(workoutId, onBack = { screen = "main" })
        else -> MainScreen(
            onOpenSettings = { screen = "settings" },
            onOpenWorkout = { workoutId = it; screen = "workout" },
        )
    }
}

/** Shown by Health Connect when the user asks why the app wants health permissions. */
@Composable
fun PrivacyScreen(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Privacy", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tread Sync records workouts from your treadmill over Bluetooth. It writes each workout (exercise session, distance, steps, speed and calories) to Health Connect, and only writes, never reads. " +
                    "Workouts are stored on this phone and, if you connect Strava, uploaded to your Strava account. No data is sent anywhere else.",
            )
            Button(onClick = onClose) { Text("Close") }
        }
    }
}
