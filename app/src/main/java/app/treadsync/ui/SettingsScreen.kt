package app.treadsync.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.treadsync.AppGraph
import app.treadsync.Link
import app.treadsync.RecordingService
import app.treadsync.ble.TreadmillCompanion
import app.treadsync.sync.HealthConnectSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item { UnitsSection() }
            item { TreadmillSection() }
            item { HealthConnectSection() }
            item { StravaSection() }
            item { RecordingSection() }
            item { DiagnosticsSection() }
        }
    }
}

@Composable
private fun UnitsSection() {
    val version by AppGraph.settings.changes.collectAsStateWithLifecycle()
    val miles = remember(version) { AppGraph.settings.useMiles }
    Section("Units") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Imperial (mi, mph)", miles, Modifier.weight(1f)) { AppGraph.settings.useMiles = true }
            ChoiceButton("Metric (km, km/h)", !miles, Modifier.weight(1f)) { AppGraph.settings.useMiles = false }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun TreadmillSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by AppGraph.live.collectAsStateWithLifecycle()
    val scanning by AppGraph.scanner.scanning.collectAsStateWithLifecycle()
    val found by AppGraph.scanner.results.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(AppGraph.settings.deviceName) }
    var address by remember { mutableStateOf(AppGraph.settings.deviceAddress) }
    var autoConnect by remember { mutableStateOf(AppGraph.settings.autoConnect) }
    val companionSupported = remember { TreadmillCompanion.supported(context) }
    var pairedHandled by remember { mutableStateOf(false) }

    fun onPaired(ok: Boolean, error: String?) {
        if (ok) {
            if (pairedHandled) return
            pairedHandled = true
            AppGraph.settings.autoConnect = true
            autoConnect = true
            TreadmillCompanion.observe(context, AppGraph.settings.deviceAddress, true)
            AppGraph.toast("Paired. Tread Sync will start when the treadmill turns on.")
        } else {
            AppGraph.settings.autoConnect = false
            autoConnect = false
            error?.let { AppGraph.toast("Auto-connect not set up: $it") }
        }
        if (AppGraph.live.value.link == Link.DISCONNECTED && hasBlePermissions(context)) RecordingService.connect(context)
    }

    val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onPaired(true, null) else onPaired(false, "pairing was cancelled")
    }

    fun pair() {
        pairedHandled = false
        TreadmillCompanion.associate(
            context, AppGraph.settings.deviceAddress,
            launch = { sender -> senderLauncher.launch(IntentSenderRequest.Builder(sender).build()) },
            done = ::onPaired,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBlePermissions(context)) AppGraph.scanner.start()
    }
    fun scan() {
        when {
            !hasBlePermissions(context) -> permissionLauncher.launch(blePermissions())
            !AppGraph.scanner.isBluetoothOn() -> AppGraph.toast("Turn on Bluetooth first")
            else -> {
                if (live.link != Link.DISCONNECTED) RecordingService.stop()
                AppGraph.scanner.start()
            }
        }
    }

    Section("Treadmill") {
        if (address.isEmpty()) {
            Text("No treadmill yet. Turn on the walking pad, close other treadmill apps, then search.")
        } else {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; if (it.isNotBlank()) AppGraph.settings.deviceName = it.trim() },
                label = { Text("Name (shown in the app and on Strava)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Bluetooth address $address", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { scan() }, enabled = !scanning) {
            Text(if (scanning) "Searching..." else if (address.isEmpty()) "Find treadmill" else "Change treadmill")
        }
        if (scanning || found.isNotEmpty()) {
            HorizontalDivider()
            if (found.isEmpty()) Text("Looking for Bluetooth devices...")
            found.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        AppGraph.scanner.stop()
                        AppGraph.scanner.results.value = emptyList()
                        if (address.isNotEmpty() && address != d.address) TreadmillCompanion.observe(context, address, false)
                        AppGraph.settings.deviceAddress = d.address
                        AppGraph.settings.deviceName = d.name
                        address = d.address
                        name = d.name
                        if (companionSupported) pair() else RecordingService.connect(context)
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text(if (d.ftms) "Treadmill (FTMS)" else "Bluetooth device", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (address.isNotEmpty()) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start automatically", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (companionSupported) "Connect and record whenever the treadmill turns on, without opening the app. Turn off to use another treadmill app."
                        else "Needs Android 12 or newer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoConnect, enabled = companionSupported,
                    onCheckedChange = { on ->
                        if (!on) {
                            autoConnect = false
                            AppGraph.settings.autoConnect = false
                            TreadmillCompanion.observe(context, address, false)
                        } else if (TreadmillCompanion.isAssociated(context, address)) {
                            autoConnect = true
                            AppGraph.settings.autoConnect = true
                            TreadmillCompanion.observe(context, address, true)
                        } else {
                            // Pairing needs the treadmill advertising, which it stops doing while connected.
                            scope.launch {
                                if (AppGraph.live.value.link != Link.DISCONNECTED) {
                                    RecordingService.stop()
                                    delay(1_500)
                                }
                                pair()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HealthConnectSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking...") }
    var granted by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        val sdk = AppGraph.health.sdkStatus()
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            granted = false
            status = if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "Health Connect needs an update" else "Health Connect is not available"
        } else {
            granted = runCatching { AppGraph.health.hasPermissions() }.getOrDefault(false)
            status = if (granted) "Connected. Workouts are written to Health Connect, where Google Health reads them." else "Permission needed to save workouts."
        }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refresh++
        scope.launch { AppGraph.sync.syncPending() }
    }

    Section("Health Connect") {
        Text(status)
        if (!granted) {
            Button(onClick = {
                if (AppGraph.health.available) launcher.launch(HealthConnectSync.PERMISSIONS)
                else openUri(context, "market://details?id=com.google.android.apps.healthdata")
            }) { Text(if (AppGraph.health.available) "Grant permission" else "Get Health Connect") }
        }
    }
}

@Composable
private fun StravaSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val athlete by AppGraph.stravaAthlete.collectAsStateWithLifecycle()
    var clientId by remember { mutableStateOf(AppGraph.settings.stravaClientId) }
    var secret by remember { mutableStateOf(AppGraph.settings.stravaClientSecret) }
    var title by remember { mutableStateOf(AppGraph.settings.stravaTitle) }

    Section("Strava") {
        if (athlete.isNotEmpty()) {
            Text("Connected as $athlete. Workouts upload automatically.")
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; AppGraph.settings.stravaTitle = it },
                label = { Text("Activity title") },
                placeholder = { Text("Automatic, e.g. Morning treadmill walk") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
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
private fun RecordingSection() {
    val version by AppGraph.settings.changes.collectAsStateWithLifecycle()
    val miles = remember(version) { AppGraph.settings.useMiles }
    var stride by remember(miles) {
        val cm = AppGraph.settings.strideCm
        mutableStateOf(if (miles) (cm / 2.54).roundToInt().toString() else cm.toString())
    }
    var autoStop by remember { mutableStateOf(AppGraph.settings.autoStopMin.toString()) }

    Section("Recording") {
        OutlinedTextField(
            value = stride,
            onValueChange = { v ->
                stride = v.filter(Char::isDigit).take(3)
                val n = stride.toIntOrNull() ?: return@OutlinedTextField
                val cm = if (miles) (n * 2.54).roundToInt() else n
                if (cm in 30..150) AppGraph.settings.strideCm = cm
            },
            label = { Text(if (miles) "Step length (inches)" else "Step length (cm)") },
            supportingText = {
                Text(
                    "Used to estimate steps. Count steps for one minute at your usual speed, then length = " +
                        if (miles) "mph × 1056 ÷ steps." else "km/h × 1667 ÷ steps.",
                )
            },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = autoStop,
            onValueChange = { v ->
                autoStop = v.filter(Char::isDigit).take(2)
                autoStop.toIntOrNull()?.takeIf { it in 1..30 }?.let { AppGraph.settings.autoStopMin = it }
            },
            label = { Text("Finish workout after the belt stops for (minutes)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    val services by AppGraph.services.collectAsStateWithLifecycle()
    val log by AppGraph.log.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Section("Diagnostics") {
        Text("Bluetooth details and a recent event log, for troubleshooting.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            OutlinedButton(onClick = {
                val text = "Services:\n$services\nLog:\n${log.joinToString("\n")}"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
            }) { Text("Share") }
        }
        if (open) {
            Text(services.ifEmpty { "Connect to the treadmill to list its Bluetooth services." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(log.takeLast(80).joinToString("\n").ifEmpty { "No log yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
