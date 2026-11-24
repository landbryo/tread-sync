package app.treadsync.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.treadsync.AppGraph
import app.treadsync.Link
import app.treadsync.R
import app.treadsync.RecordingService
import app.treadsync.workout.Workout
import app.treadsync.workout.WorkoutRecorder
import java.text.DateFormat
import java.util.Date

internal fun blePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

internal fun hasBlePermissions(context: Context) = blePermissions()
    .filter { it != Manifest.permission.POST_NOTIFICATIONS }
    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Shows queued messages from anywhere in the app as toasts. */
@Composable
fun MessageToasts() {
    val context = LocalContext.current
    val message by AppGraph.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            AppGraph.message.value = null
        }
    }
}

@Composable
fun MainScreen(onOpenSettings: () -> Unit, onOpenWorkout: (String) -> Unit) {
    val context = LocalContext.current
    val live by AppGraph.live.collectAsStateWithLifecycle()
    val workouts by AppGraph.workouts.collectAsStateWithLifecycle()
    val settingsVersion by AppGraph.settings.changes.collectAsStateWithLifecycle()
    val miles = remember(settingsVersion) { AppGraph.settings.useMiles }
    val deviceName = remember(settingsVersion) { AppGraph.settings.deviceName }
    LaunchedEffect(Unit) { AppGraph.sync.syncPending() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBlePermissions(context)) RecordingService.connect(context)
    }
    fun connect() {
        if (hasBlePermissions(context)) RecordingService.connect(context) else permissionLauncher.launch(blePermissions())
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tread Sync", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
            }
            item {
                if (deviceName.isEmpty()) {
                    SetupCard(onOpenSettings)
                } else {
                    LiveCard(live, miles, deviceName, onConnect = ::connect)
                }
            }
            if (live.link == Link.CONNECTED && live.ftms) {
                item { ControlsCard(live, miles) }
            }
            item {
                Text("Recent workouts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            }
            if (workouts.isEmpty()) {
                item { Text("No workouts yet. Start walking while connected and they appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(workouts, key = { it.id }) { w -> WorkoutRow(w, miles) { onOpenWorkout(w.id) } }
        }
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Section("Get started") {
        Text("Pair your treadmill, then connect Health Connect and Strava. It only takes a minute.")
        Button(onClick = onOpenSettings) { Text("Open settings") }
    }
}

@Composable
private fun LiveCard(live: app.treadsync.LiveState, miles: Boolean, deviceName: String, onConnect: () -> Unit) {
    val snap = live.recorder ?: WorkoutRecorder.Snapshot(WorkoutRecorder.Phase.IDLE, 0.0, 0.0, 0, 0, null)
    val connected = live.link != Link.DISCONNECTED
    val (statusText, statusColor) = when {
        live.link == Link.DISCONNECTED -> "Not connected" to MaterialTheme.colorScheme.outline
        live.link == Link.CONNECTING && snap.phase == WorkoutRecorder.Phase.IDLE -> "Connecting to $deviceName" to Color(0xFFE0A100)
        snap.phase == WorkoutRecorder.Phase.ACTIVE -> "Recording" to Accent
        snap.phase == WorkoutRecorder.Phase.PAUSED -> "Paused" to Color(0xFFE0A100)
        else -> "Ready. Start walking to record" to Good
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusChip(statusText, statusColor)
            Text(
                formatDuration(snap.activeMs),
                fontSize = 64.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                color = if (snap.phase == WorkoutRecorder.Phase.ACTIVE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Distance", distanceValue(snap.distanceM, miles), distanceUnit(miles), Modifier.weight(1f))
                StatTile("Speed", speedValue(snap.speedKmh, miles), speedUnit(miles), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Steps (est.)", "%,d".format(snap.steps), "", Modifier.weight(1f))
                StatTile("Calories", snap.kcal?.toString() ?: "--", "kcal", Modifier.weight(1f))
            }
            if (snap.phase == WorkoutRecorder.Phase.PAUSED) {
                Text(
                    "Finishes automatically after ${AppGraph.settings.autoStopMin} min stopped.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snap.phase != WorkoutRecorder.Phase.IDLE) {
                    Button(
                        onClick = { RecordingService.finishWorkout() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_flag), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Finish and sync")
                    }
                }
                if (connected) {
                    OutlinedButton(onClick = { RecordingService.stop() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect, modifier = Modifier.weight(1f).height(52.dp)) { Text("Connect $deviceName") }
                }
            }
            if (!connected) {
                Text(
                    "Close other treadmill apps (like FitYo) first: the treadmill accepts only one connection.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlsCard(live: app.treadsync.LiveState, miles: Boolean) {
    val snap = live.recorder ?: return
    val moving = snap.speedKmh > 0.0
    // One tap changes speed by 0.1 in the displayed unit.
    val stepKmh = if (miles) 0.1609344 else 0.1
    Section("Treadmill controls") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalIconButton(onClick = { RecordingService.instance?.changeSpeed(-stepKmh) }, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(R.drawable.ic_remove), "Slower", Modifier.size(32.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(speedValue(snap.speedKmh, miles), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(speedUnit(miles), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { RecordingService.instance?.changeSpeed(stepKmh) }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Add, "Faster", Modifier.size(32.dp))
            }
        }
        FilledTonalButton(
            onClick = { if (moving) RecordingService.instance?.pauseBelt() else RecordingService.instance?.startBelt() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (moving) Icon(painterResource(R.drawable.ic_pause), null, Modifier.size(24.dp)) else Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (moving) "Pause belt" else "Start belt", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WorkoutRow(w: Workout, miles: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(w.startMs)), fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatDistance(w.distanceM, miles)}  ·  ${formatDuration(w.activeMs)}" + (w.steps?.let { "  ·  %,d steps".format(it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Health", style = MaterialTheme.typography.labelSmall)
                    SyncIcon(w.hcSynced, w.hcError)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Strava", style = MaterialTheme.typography.labelSmall)
                    SyncIcon(w.stravaActivityId != null, w.stravaError)
                }
            }
        }
    }
}
