package app.fityoconnect.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fityoconnect.AppGraph
import app.fityoconnect.Link
import app.fityoconnect.RecordingService
import app.fityoconnect.sync.HealthConnectSync
import app.fityoconnect.workout.Workout
import app.fityoconnect.workout.WorkoutRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun blePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun hasBlePermissions(context: Context) = blePermissions()
    .filter { it != Manifest.permission.POST_NOTIFICATIONS }
    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val message by AppGraph.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            AppGraph.message.value = null
        }
    }
    LaunchedEffect(Unit) { AppGraph.sync.syncPending() }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("FitYo Connect", style = MaterialTheme.typography.headlineMedium) }
            item { TreadmillCard() }
            item { HealthConnectCard() }
            item { StravaCard() }
            item { SettingsCard() }
            item { Text("Workouts", style = MaterialTheme.typography.titleLarge) }
            item { WorkoutList() }
            item { DiagnosticsCard() }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TreadmillCard() {
    val context = LocalContext.current
    val live by AppGraph.live.collectAsStateWithLifecycle()
    val scanning by AppGraph.scanner.scanning.collectAsStateWithLifecycle()
    val found by AppGraph.scanner.results.collectAsStateWithLifecycle()
    var savedName by remember { mutableStateOf(AppGraph.settings.deviceName) }
    var pendingScan by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBlePermissions(context) && pendingScan) AppGraph.scanner.start()
        pendingScan = false
    }
    fun scan() {
        if (!hasBlePermissions(context)) {
            pendingScan = true
            permissionLauncher.launch(blePermissions())
        } else if (!AppGraph.scanner.isBluetoothOn()) {
            AppGraph.toast("Turn on Bluetooth first")
        } else {
            AppGraph.scanner.start()
        }
    }

    Section("Treadmill") {
        val status = when (live.link) {
            Link.DISCONNECTED -> "Not connected"
            Link.CONNECTING -> "Connecting to ${live.deviceName}..."
            Link.CONNECTED -> "Connected to ${live.deviceName}" + if (live.ftms) " (FTMS)" else " (unknown protocol, see Diagnostics)"
        }
        Text(status)
        if (live.link == Link.DISCONNECTED) {
            Text(
                "FitYo and this app can't be connected to the treadmill at the same time. Close FitYo before connecting here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        live.recorder?.takeIf { live.link != Link.DISCONNECTED }?.let { snap ->
            val phase = when (snap.phase) {
                WorkoutRecorder.Phase.IDLE -> "Waiting for the belt to start"
                WorkoutRecorder.Phase.ACTIVE -> "Recording"
                WorkoutRecorder.Phase.PAUSED -> "Paused (auto-finishes after ${AppGraph.settings.autoStopMin} min)"
            }
            Text(phase, fontWeight = FontWeight.SemiBold)
            Stat("Speed", formatSpeed(snap.speedKmh))
            Stat("Distance", formatDistance(snap.distanceM))
            Stat("Time", formatDuration(snap.activeMs))
            Stat("Steps (estimated)", snap.steps.toString())
            snap.kcal?.let { Stat("Calories", "$it kcal") }
            if (snap.phase != WorkoutRecorder.Phase.IDLE) {
                Button(onClick = { RecordingService.finishWorkout(context) }) { Text("Finish and sync now") }
            }
            if (live.ftms && live.link == Link.CONNECTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { RecordingService.instance?.startBelt() }) { Text("Start") }
                    OutlinedButton(onClick = { RecordingService.instance?.pauseBelt() }) { Text("Pause") }
                    OutlinedButton(onClick = { RecordingService.instance?.changeSpeed(-0.5) }) { Text("-") }
                    OutlinedButton(onClick = { RecordingService.instance?.changeSpeed(0.5) }) { Text("+") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live.link == Link.DISCONNECTED) {
                if (savedName.isNotEmpty()) {
                    Button(onClick = {
                        if (hasBlePermissions(context)) RecordingService.connect(context)
                        else permissionLauncher.launch(blePermissions())
                    }) { Text("Connect $savedName") }
                }
                OutlinedButton(onClick = { scan() }, enabled = !scanning) {
                    Text(if (scanning) "Scanning..." else if (savedName.isEmpty()) "Find treadmill" else "Change")
                }
            } else {
                OutlinedButton(onClick = { RecordingService.stop(context) }) { Text("Disconnect") }
            }
        }

        if (live.link == Link.DISCONNECTED && (scanning || found.isNotEmpty())) {
            HorizontalDivider()
            if (found.isEmpty()) Text("Looking for Bluetooth devices. Make sure the walking pad is on.")
            found.forEach { d ->
                TextButton(onClick = {
                    AppGraph.scanner.stop()
                    AppGraph.settings.deviceAddress = d.address
                    AppGraph.settings.deviceName = d.name
                    savedName = d.name
                    AppGraph.scanner.results.value = emptyList()
                    RecordingService.connect(context)
                }) {
                    Text("${d.name}  ${if (d.ftms) "FTMS treadmill" else ""}  ${d.rssi} dBm")
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HealthConnectCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking...") }
    var granted by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        val sdk = AppGraph.health.sdkStatus()
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            granted = false
            status = if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "Health Connect needs an update" else "Health Connect is not available"
        } else {
            granted = runCatching { AppGraph.health.hasPermissions() }.getOrDefault(false)
            status = if (granted) "Connected. Workouts are written to Health Connect." else "Permissions needed"
        }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refresh++
        scope.launch { AppGraph.sync.syncPending() }
    }

    Section("Health Connect (Google Health)") {
        Text(status)
        if (!granted) {
            Button(onClick = {
                if (AppGraph.health.available) launcher.launch(HealthConnectSync.PERMISSIONS)
                else openUri(context, "market://details?id=com.google.android.apps.healthdata")
            }) { Text(if (AppGraph.health.available) "Grant permissions" else "Get Health Connect") }
        }
    }
}

@Composable
private fun StravaCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val athlete by AppGraph.stravaAthlete.collectAsStateWithLifecycle()
    var clientId by remember { mutableStateOf(AppGraph.settings.stravaClientId) }
    var secret by remember { mutableStateOf(AppGraph.settings.stravaClientSecret) }

    Section("Strava") {
        if (athlete.isNotEmpty()) {
            Text("Connected as $athlete. Workouts upload automatically.")
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { AppGraph.strava.disconnect() }
                    AppGraph.stravaAthlete.value = ""
                }
            }) { Text("Disconnect Strava") }
        } else {
            Text(
                "Create a free API application at strava.com/settings/api with Authorization Callback Domain set to \"localhost\", then paste its Client ID and Client Secret here.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = clientId, onValueChange = { clientId = it }, label = { Text("Client ID") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it }, label = { Text("Client Secret") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = clientId.isNotBlank() && secret.isNotBlank(),
                onClick = {
                    AppGraph.settings.stravaClientId = clientId
                    AppGraph.settings.stravaClientSecret = secret
                    openUri(context, AppGraph.strava.authorizeUri().toString())
                },
            ) { Text("Connect Strava") }
        }
    }
}

@Composable
private fun SettingsCard() {
    var stride by remember { mutableStateOf(AppGraph.settings.strideCm.toString()) }
    var autoStop by remember { mutableStateOf(AppGraph.settings.autoStopMin.toString()) }
    Section("Settings") {
        OutlinedTextField(
            value = stride,
            onValueChange = { v -> stride = v.filter(Char::isDigit).take(3); stride.toIntOrNull()?.takeIf { it in 30..150 }?.let { AppGraph.settings.strideCm = it } },
            label = { Text("Step length (cm), used to estimate steps") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = autoStop,
            onValueChange = { v -> autoStop = v.filter(Char::isDigit).take(2); autoStop.toIntOrNull()?.takeIf { it in 1..30 }?.let { AppGraph.settings.autoStopMin = it } },
            label = { Text("Finish a workout after the belt stops for (minutes)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WorkoutList() {
    val workouts by AppGraph.workouts.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (workouts.isEmpty()) Text("No workouts yet. Connect the treadmill and start walking.")
        workouts.forEach { WorkoutRow(it) }
    }
}

@Composable
private fun WorkoutRow(w: Workout) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(w.startMs)), fontWeight = FontWeight.SemiBold)
            Text("${formatDistance(w.distanceM)} in ${formatDuration(w.activeMs)}" + (w.steps?.let { ", ~$it steps" } ?: ""))
            Text(
                "Health Connect: " + if (w.hcSynced) "synced" else (w.hcError ?: "pending"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Strava: " + when {
                    w.stravaActivityId != null -> "uploaded"
                    else -> w.stravaError ?: "pending"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!w.hcSynced || w.stravaActivityId == null) {
                    TextButton(onClick = { AppGraph.sync.retry(w.id) }) { Text("Retry") }
                }
                w.stravaActivityId?.takeIf { it > 0 }?.let { id ->
                    TextButton(onClick = { openUri(context, "https://www.strava.com/activities/$id") }) { Text("Open") }
                }
                TextButton(onClick = { shareFit(context, w) }) { Text("Share FIT") }
                TextButton(onClick = {
                    if (confirmDelete) {
                        AppGraph.store.delete(w.id); AppGraph.reloadWorkouts()
                    } else confirmDelete = true
                }) { Text(if (confirmDelete) "Tap to delete" else "Delete") }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val services by AppGraph.services.collectAsStateWithLifecycle()
    val log by AppGraph.log.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Section("Diagnostics") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            OutlinedButton(onClick = {
                val text = "Services:\n$services\nLog:\n${log.joinToString("\n")}"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
            }) { Text("Share") }
        }
        if (open) {
            Text(services.ifEmpty { "Connect to the treadmill to list its Bluetooth services." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(log.takeLast(80).joinToString("\n").ifEmpty { "No log yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
fun PrivacyScreen(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Privacy", style = MaterialTheme.typography.headlineSmall)
            Text(
                "FitYo Connect records workouts from your treadmill over Bluetooth. It writes each workout (exercise session, distance, steps, speed and calories) to Health Connect, and only writes, never reads. " +
                    "Workouts are stored on this phone and, if you connect Strava, uploaded to your Strava account. No data is sent anywhere else.",
            )
            Button(onClick = onClose) { Text("Close") }
        }
    }
}

private fun openUri(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        AppGraph.toast("No app can open $uri")
    }
}

private fun shareFit(context: Context, w: Workout) {
    val file = AppGraph.store.fitFile(w)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share FIT file"))
}
