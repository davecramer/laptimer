package laptimer.app

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import laptimer.core.LogEnd
import laptimer.core.SessionExport
import laptimer.core.SessionSummary
import laptimer.core.Track
import laptimer.core.formatDelta
import laptimer.core.formatLapTime
import laptimer.core.summarizeSession
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Summaries take a second or so to read for a long session, so each is
 * kept while the file is unchanged.
 */
private object SummaryCache {
    private data class Key(val path: String, val length: Long, val modified: Long, val trackId: String?)
    private val cache = HashMap<Key, SessionSummary>()

    /** Reads the session, timing it on [track] if given. Blocks; call it off the main thread. */
    @Synchronized
    fun get(file: File, track: Track? = null): SessionSummary {
        // A track's lines can change, so its contents are part of the key, not just its id.
        val key = Key(file.path, file.length(), file.lastModified(), track?.let { it.id + it.hashCode() })
        return cache.getOrPut(key) { summarizeSession(file, track) }
    }
}

private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun SessionsTab() {
    val context = LocalContext.current
    var open by remember { mutableStateOf<File?>(null) }
    // Bumped after a delete so the list reloads.
    var version by remember { mutableIntStateOf(0) }
    val file = open
    if (file != null) {
        SessionDetail(file, onClose = { open = null }, onDeleted = { open = null; version++ })
        return
    }
    val files = remember(version) {
        RecordingService.sessionDirectory(context).listFiles { f -> f.isFile && f.name.endsWith(".ltlog") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
    }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (files.isEmpty()) Text("No sessions yet. Record one on the Record tab.")
        for (f in files) {
            SessionRow(f, onOpen = { open = f })
            HorizontalDivider()
        }
    }
}

@Composable
private fun SessionRow(file: File, onOpen: () -> Unit) {
    val summary by produceState<SessionSummary?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { SummaryCache.get(file) }.getOrNull() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
    ) {
        val s = summary
        Text(s?.startUtc?.let { DATE.format(it) } ?: file.name, style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                s == null -> "Reading..."
                else -> sessionLine(s, file)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun sessionLine(s: SessionSummary, file: File): String = buildList {
    add(s.track?.name ?: "no track")
    s.timing?.let { t ->
        add(if (t.laps.size == 1) "1 lap" else "${t.laps.size} laps")
        t.bestLap?.let { add("best ${formatLapTime(it.timeS)}") }
    }
    s.durationS?.let { add(duration(it)) }
    add("${file.length() / 1024} KB")
}.joinToString(", ")

private fun duration(s: Double): String {
    val total = s.toLong()
    return if (total >= 3600) "%d h %02d min".format(total / 3600, total / 60 % 60) else "${total / 60} min ${total % 60} s"
}

@Composable
private fun SessionDetail(file: File, onClose: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    val tracks = remember { trackStore(context).loadAll().tracks.filter { it.problems().isEmpty() } }
    // Null means the track stored in the session.
    var override by remember { mutableStateOf<Track?>(null) }
    var pickTrack by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    LaunchedEffect(file, override) {
        summary = null
        summary = try {
            withContext(Dispatchers.IO) { SummaryCache.get(file, override) }
        } catch (e: IOException) {
            message = "Could not read the session: ${e.message}"
            null
        } catch (e: IllegalArgumentException) {
            // Not a session log, or a newer format.
            message = "Could not read the session: ${e.message}"
            null
        }
    }

    fun share(name: String, mime: String, write: (java.io.Writer) -> Unit) = scope.launch {
        message = null
        try {
            val out = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, name).also { f -> f.bufferedWriter().use(write) }
            }
            shareFile(context, out, mime)
        } catch (e: IOException) {
            message = "Export failed: ${e.message}"
        }
    }

    val base = file.name.removeSuffix(".ltlog")
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onClose) { Text("< Sessions") }
        val s = summary
        Text(s?.startUtc?.let { DATE.format(it) } ?: file.name, style = MaterialTheme.typography.titleLarge)
        if (s == null) {
            Text(message ?: "Reading...")
            return@Column
        }
        Text(listOfNotNull(s.device, s.durationS?.let { duration(it) }, "${s.fixes} fixes").joinToString(", "))
        s.canProfile?.let { p ->
            Text("CAN data (${p.name}): ${s.canSignalsSeen.size} of ${p.signals.size} signals, ${s.canValues} values")
        }
        if (s.obdChannels.isNotEmpty()) {
            Text("Engine data: " + s.obdChannels.sortedBy { it.ordinal }.joinToString(", ") { it.label } + " (${s.obdReadings} readings)")
        }
        if (s.logEnd != LogEnd.CLEAN) {
            // The app was stopped mid-write; everything before that point is intact.
            Text("The file ends early (the app stopped while recording). Data up to then is complete.")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // Which track the laps are timed on.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Timed on: " + (s.track?.name ?: "no track") + if (override != null) " (chosen)" else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (tracks.isNotEmpty()) TextButton(onClick = { pickTrack = !pickTrack }) { Text("Change") }
        }
        if (pickTrack) {
            TextButton(onClick = { override = null; pickTrack = false }) { Text("The track stored in the session") }
            for (t in tracks) TextButton(onClick = { override = t; pickTrack = false }) { Text(t.name) }
        }

        val timing = s.timing
        if (timing == null) {
            Text("This session was recorded without a track. Pick one above to time its laps.")
        } else if (timing.laps.isEmpty()) {
            Text("No complete laps.")
        } else {
            LapTable(s)
        }

        Spacer(Modifier.height(8.dp))
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = timing != null && timing.laps.isNotEmpty(), onClick = {
                share("$base-laps.csv", "text/csv") { SessionExport.writeLapsCsv(s, it) }
            }) { Text("Laps CSV") }
            OutlinedButton(onClick = {
                share("$base-data.csv", "text/csv") { SessionExport.writeDataCsv(file, override, it, s.canProfile) }
            }) { Text("Data CSV") }
            OutlinedButton(onClick = {
                share("$base.vbo", "application/octet-stream") { SessionExport.writeVbo(file, s, it) }
            }) { Text("VBO") }
        }
        Text(
            "VBO opens in Racelogic Circuit Tools. Set the start/finish line there; it isn't included in the file.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { shareFile(context, file, "application/octet-stream") }) { Text("Share session file") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("The recording is removed from the phone. Exports already shared are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (file.delete()) onDeleted() else message = "Could not delete ${file.name}"
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Every lap: time, gap to the best, and sector times, best sectors in green. */
@Composable
private fun LapTable(s: SessionSummary) {
    val timing = s.timing ?: return
    val best = timing.bestLap
    val bestSectors = timing.bestSectorsS
    val green = Color(0xFF2E7D32)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (lap in timing.laps) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("%2d".format(lap.number), fontFamily = FontFamily.Monospace)
                Text(
                    formatLapTime(lap.timeS).padStart(8),
                    fontFamily = FontFamily.Monospace,
                    color = if (lap == best) green else Color.Unspecified,
                )
                Text(
                    when {
                        lap.hadGap -> "GPS gap"
                        best == null || lap == best -> "best".takeIf { lap == best } ?: ""
                        else -> formatDelta(lap.timeS - best.timeS)
                    }.padEnd(9),
                    fontFamily = FontFamily.Monospace,
                )
                if (bestSectors.size > 1) {
                    lap.sectorTimesS.forEachIndexed { i, t ->
                        val isBest = t != null && bestSectors[i] != null && t <= bestSectors[i]!! + 0.0005
                        Text(
                            t?.let { formatLapTime(it, 2) } ?: "-",
                            fontFamily = FontFamily.Monospace,
                            color = if (isBest && !lap.hadGap) green else Color.Unspecified,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        best?.let { Text("Best ${formatLapTime(it.timeS)} (lap ${it.number})", fontFamily = FontFamily.Monospace) }
        if (bestSectors.size > 1) {
            timing.optimalLapS?.let { Text("Optimal ${formatLapTime(it)} from the best sectors", fontFamily = FontFamily.Monospace) }
        }
    }
}

/** Opens Android's share sheet for [file]: Drive, email, Files and so on. */
private fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, file.name))
}
