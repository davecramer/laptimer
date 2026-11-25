package laptimer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import laptimer.core.OSM_ATTRIBUTION
import laptimer.core.OsmClient
import laptimer.core.ReferenceStore
import laptimer.core.Track
import laptimer.core.TrackKind
import laptimer.core.TrackStore
import laptimer.core.UrlConnectionTransport
import laptimer.core.lengthM
import java.io.File
import java.io.IOException

/** A generic name: OSM services ask clients to identify the software, not the person. */
private const val USER_AGENT = "laptimer-android/0.1"

private val osmClient by lazy { OsmClient(UrlConnectionTransport(USER_AGENT)) }

fun trackStore(context: android.content.Context) = TrackStore(File(context.filesDir, "tracks"))

/** Each track's fastest lap, for the predictive delta. */
fun referenceStore(context: android.content.Context) = ReferenceStore(File(context.filesDir, "references"))

@Composable
fun TracksTab() {
    val context = LocalContext.current
    val store = remember { trackStore(context) }
    var editing by remember { mutableStateOf<Track?>(null) }
    // Bumped after a save or delete so the list reloads.
    var version by remember { mutableIntStateOf(0) }

    val track = editing
    if (track != null) {
        TrackEditorScreen(
            initial = track,
            store = store,
            onClose = {
                editing = null
                version++
            },
        )
    } else {
        TrackList(store, version, onOpen = { editing = it }, onNew = { editing = Track(name = "") })
    }
}

@Composable
private fun TrackList(store: TrackStore, version: Int, onOpen: (Track) -> Unit, onNew: () -> Unit) {
    val loaded = remember(version) { store.loadAll() }
    Column(Modifier.padding(16.dp)) {
        Button(onClick = onNew) { Text("Add a track") }
        Spacer(Modifier.height(12.dp))
        loaded.errors.forEach { Text("Could not read $it", color = MaterialTheme.colorScheme.error) }
        if (loaded.tracks.isEmpty()) {
            Text("No tracks yet. Add one, find it on the map, then place the start/finish line.")
        }
        LazyColumn {
            items(loaded.tracks, key = { it.id }) { t ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(t) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(t.name.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleMedium)
                    Text(trackSummary(t), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun trackSummary(t: Track): String {
    val kind = if (t.kind == TrackKind.CIRCUIT) "circuit" else "point to point"
    val sectors = when (t.sectors.size) {
        0 -> "no sectors"
        1 -> "1 sector line"
        else -> "${t.sectors.size} sector lines"
    }
    val problems = t.problems()
    return if (problems.isEmpty()) "$kind, $sectors" else "$kind, $sectors. ${problems.joinToString(" ")}"
}

@Composable
private fun TrackEditorScreen(initial: Track, store: TrackStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val editor = remember(initial.id) { TrackEditor(initial, osmClient) }
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val track = editor.track
    val isNew = remember(initial.id) { store.loadAll().tracks.none { it.id == initial.id } }

    fun close() = if (editor.changed) confirmDiscard = true else onClose()
    BackHandler { if (editor.placing != null) editor.cancelPlacing() else close() }

    Column(Modifier.fillMaxSize()) {
        // Rebuilt only when what is drawn changes, not on every keystroke in the name field.
        val lines = remember(track.outline, track.start, track.finish, track.sectors, track.kind, editor.candidates, editor.selected) {
            editor.mapLines()
        }
        val dots = remember(editor.firstTap) { editor.mapDots() }
        TrackMap(
            lines = lines,
            dots = dots,
            camera = editor.camera,
            onTap = editor::onMapTap,
            onVisibleBoundsChanged = { editor.visibleBounds = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            editor.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (editor.placing != null) {
                OutlinedButton(onClick = editor::cancelPlacing) { Text("Cancel placing") }
            }

            // Finding the track.
            if (track.outline.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search for a place") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (search.isNotBlank()) scope.launch { editor.searchPlaces(search.trim()) }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !editor.busy && search.isNotBlank(),
                        onClick = { scope.launch { editor.searchPlaces(search.trim()) } },
                    ) { Text("Go") }
                }
                editor.places.forEach { p ->
                    Text(
                        p.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editor.pickPlace(p) }
                            .padding(vertical = 8.dp),
                    )
                }
                Button(enabled = !editor.busy, onClick = { scope.launch { editor.findTracks() } }) {
                    Text(if (editor.busy) "Searching..." else "Find tracks on this map")
                }
                editor.candidates.forEach { c ->
                    Text(
                        listOfNotNull(
                            editor.nameFor(c) ?: "Unnamed track",
                            c.kind,
                            "%.1f km".format(c.lengthM / 1000),
                            "start/finish marked".takeIf { c.startFinish != null },
                        ).joinToString(", "),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editor.pickCandidate(c) }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = track.name,
                onValueChange = editor::rename,
                label = { Text("Track name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((kind, label) in listOf(TrackKind.CIRCUIT to "Circuit", TrackKind.POINT_TO_POINT to "Point to point")) {
                    if (track.kind == kind) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { editor.setKind(kind) }) { Text(label) }
                    }
                }
            }

            // Timing lines.
            for (slot in editor.slots()) {
                LineRow(editor, slot)
            }
            OutlinedButton(onClick = { editor.startPlacing(Slot.NewSector) }) { Text("Add a sector line") }

            if (track.outline.isNotEmpty()) {
                Text(
                    "Track outline: %.2f km from OpenStreetMap (%s).".format(
                        track.outline.sumOf { lengthM(it) } / 1000,
                        track.source?.attribution ?: OSM_ATTRIBUTION,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = editor::clearOutline) { Text("Remove outline and search again") }
            }
            track.problems().forEach { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = track.name.isNotBlank(),
                    onClick = {
                        try {
                            store.save(track)
                            onClose()
                        } catch (e: IOException) {
                            saveError = "Could not save: ${e.message}"
                        }
                    },
                ) { Text("Save") }
                OutlinedButton(onClick = ::close) { Text("Close") }
                if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete track") }
            }
            Text(
                "Unfinished tracks can be saved. A track can time laps once it has a name and a start line.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = onClose) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${track.name.ifBlank { "this track" }}?") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(track.id)
                    referenceStore(context).delete(track.id)
                    onClose()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LineRow(editor: TrackEditor, slot: Slot) {
    val line = editor.line(slot)
    val isSelected = editor.selected == slot
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                editor.label(slot) + if (line == null) ": not placed" else ": %.0f m wide".format(line.widthM),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = line != null) { editor.select(if (isSelected) null else slot) }
                    .padding(vertical = 10.dp),
            )
            OutlinedButton(onClick = { editor.startPlacing(slot) }) { Text(if (line == null) "Place" else "Move") }
        }
        if (isSelected && line != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = editor::flipSelected) { Text("Flip") }
                OutlinedButton(onClick = { editor.changeSelectedWidth(-5.0) }) { Text("-5 m") }
                OutlinedButton(onClick = { editor.changeSelectedWidth(5.0) }) { Text("+5 m") }
                TextButton(onClick = editor::deleteSelected) { Text("Delete") }
            }
        }
    }
}
