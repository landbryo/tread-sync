package app.treadsync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.treadsync.AppGraph
import app.treadsync.workout.Workout
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkoutDetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val workouts by AppGraph.workouts.collectAsStateWithLifecycle()
    val version by AppGraph.settings.changes.collectAsStateWithLifecycle()
    val miles = remember(version) { AppGraph.settings.useMiles }
    val w = workouts.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }

    if (w == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val title = remember(version, w.id) { AppGraph.settings.stravaTitle.ifBlank { autoTitle(w.startMs, w.zoneOffsetSec) } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workout?") },
            text = { Text("This removes it from Tread Sync only. Copies already in Strava and Health Connect stay there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    AppGraph.store.delete(w.id)
                    AppGraph.reloadWorkouts()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(w.startMs)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { shareFit(context, w) }) { Icon(Icons.Filled.Share, "Share FIT file") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Distance", distanceValue(w.distanceM, miles), distanceUnit(miles), Modifier.weight(1f))
                        StatTile("Moving time", formatDuration(w.activeMs), "", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Avg speed", speedValue(w.avgSpeedMps * 3.6, miles), speedUnit(miles), Modifier.weight(1f))
                        StatTile("Max speed", speedValue(w.maxSpeedMps * 3.6, miles), speedUnit(miles), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Steps (est.)", w.steps?.let { "%,d".format(it) } ?: "--", "", Modifier.weight(1f))
                        StatTile("Calories", w.kcal?.toString() ?: "--", "kcal", Modifier.weight(1f))
                    }
                    if (w.elapsedMs - w.activeMs > 5_000) {
                        Text(
                            "Elapsed ${formatDuration(w.elapsedMs)}, including ${w.pauses.size} pause${if (w.pauses.size == 1) "" else "s"}.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Section("Speed") { SpeedChart(w, miles) }
            }
            item {
                Section("Sync") {
                    SyncRow("Health Connect", w.hcSynced, w.hcError, if (w.hcSynced) "Saved" else w.hcError ?: "Pending")
                    SyncRow(
                        "Strava", w.stravaActivityId != null, w.stravaError,
                        if (w.stravaActivityId != null) "Uploaded" else w.stravaError ?: "Pending",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!w.hcSynced || w.stravaActivityId == null) {
                            OutlinedButton(onClick = { AppGraph.sync.retry(w.id) }) { Text("Retry sync") }
                        }
                        w.stravaActivityId?.takeIf { it > 0 }?.let { activity ->
                            OutlinedButton(onClick = { openUri(context, "https://www.strava.com/activities/$activity") }) { Text("Open in Strava") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncRow(label: String, synced: Boolean, error: String?, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SyncIcon(synced, error)
        Column {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Speed over time; paused stretches are shaded. */
@Composable
private fun SpeedChart(w: Workout, miles: Boolean) {
    val samples = w.samples
    if (samples.size < 2) {
        Text("Not enough data for a chart.")
        return
    }
    val toUnit = if (miles) 2.2369363 else 3.6 // m/s to mph or km/h
    val maxY = (samples.maxOf { it.speedMps } * toUnit * 1.15).coerceAtLeast(1.0)
    val line = Accent
    val shade = MaterialTheme.colorScheme.surfaceContainerHighest
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Top of chart: ${"%.1f".format(maxY)} ${speedUnit(miles)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val span = (w.endMs - w.startMs).coerceAtLeast(1).toFloat()
            fun x(t: Long) = (t - w.startMs) / span * size.width
            fun y(mps: Double) = size.height - (mps * toUnit / maxY).toFloat() * size.height
            for (p in w.pauses) {
                drawRect(shade, topLeft = Offset(x(p.startMs), 0f), size = Size((x(p.endMs) - x(p.startMs)).coerceAtLeast(1f), size.height))
            }
            for (i in 1..3) {
                val gy = size.height * i / 4f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            val path = Path()
            samples.forEachIndexed { i, s ->
                if (i == 0) path.moveTo(x(s.tMs), y(s.speedMps)) else path.lineTo(x(s.tMs), y(s.speedMps))
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(w.elapsedMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
