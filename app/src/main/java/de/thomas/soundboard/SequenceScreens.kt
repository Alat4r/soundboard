package de.thomas.soundboard

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Liste aller Startsequenzen; hier wird auch die aktive gewählt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceListScreen(
    intro: IntroViewModel,
    gifs: BoardViewModel,
    onEdit: (StartSequence) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StartSequence?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Startsequenzen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { picking = true }) {
                Icon(Icons.Default.Add, contentDescription = "Startsequenz hinzufügen")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("Keine Startanimation") },
                    leadingContent = {
                        RadioButton(selected = intro.state.activeId == null, onClick = { intro.setActive(null) })
                    },
                    modifier = Modifier.clickable { intro.setActive(null) },
                )
            }
            items(intro.state.sequences, key = { it.id }) { sequence ->
                ListItem(
                    headlineContent = { Text(sequence.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Animation: ${sequence.style.label}") },
                    leadingContent = {
                        RadioButton(
                            selected = sequence.id == intro.state.activeId,
                            onClick = { intro.setActive(sequence.id) },
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { deleting = sequence }) {
                            Icon(Icons.Default.Delete, contentDescription = "Löschen")
                        }
                    },
                    modifier = Modifier.clickable { onEdit(sequence) },
                )
            }
            item {
                Text(
                    "Der Kreis links legt fest, welche Startsequenz beim Öffnen der App läuft. " +
                        "Tippe auf eine Sequenz, um Bildausschnitt, Startpunkt und Animation einzustellen. " +
                        "Mit + legst du eine neue aus einem Video im GIF-Reiter an.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (picking) {
        GifPickerDialog(
            gifs = gifs,
            onPick = { entry ->
                picking = false
                importing = true
                scope.launch {
                    val created = runCatching { intro.add(entry) }.getOrNull()
                    importing = false
                    if (created != null) onEdit(created)
                }
            },
            onDismiss = { picking = false },
        )
    }

    if (importing) {
        Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
    }

    deleting?.let { sequence ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Löschen?") },
            text = {
                Text("Die Startsequenz „${sequence.name}“ wird gelöscht. Das Original im GIF-Reiter bleibt erhalten.")
            },
            confirmButton = {
                TextButton(onClick = { intro.delete(sequence); deleting = null }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
}

/** Auswahl eines GIFs/Videos aus dem GIF-Reiter, mit Ordnernavigation und loopender Vorschau. */
@Composable
private fun GifPickerDialog(gifs: BoardViewModel, onPick: (Entry) -> Unit, onDismiss: () -> Unit) {
    val root = gifs.library?.root
    if (root == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Kein GIF-Ordner") },
            text = { Text("Richte zuerst im GIF-Reiter einen Speicherordner ein.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    var stack by remember { mutableStateOf(listOf(root)) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    LaunchedEffect(stack.last()) {
        entries = null
        entries = gifs.listEntries(stack.last())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Video wählen", style = MaterialTheme.typography.titleLarge)
                        Text(
                            stack.joinToString(" / ") { it.title },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
                }
                if (stack.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().clickable { stack = stack.dropLast(1) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Eine Ebene hoch")
                    }
                }
                when {
                    entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    entries!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Dieser Ordner ist leer.")
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries!!, key = { it.uri.toString() }) { entry ->
                            if (entry.isFolder) {
                                Card(onClick = { stack = stack + entry }, modifier = Modifier.height(96.dp)) {
                                    Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            } else {
                                Card(onClick = { onPick(entry) }, modifier = Modifier.height(96.dp)) {
                                    Box(Modifier.fillMaxSize()) {
                                        AnimatedPreview(entry, crop = true, Modifier.matchParentSize())
                                        Text(
                                            entry.title,
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.6f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Editor einer Startsequenz. Das Video liegt bildschirmfüllend genau so wie beim echten Start.
 * Ziehen verschiebt, zwei Finger zoomen, Tippen setzt den Startpunkt der Animation.
 */
@Composable
fun SequenceEditor(
    original: StartSequence,
    videoUri: Uri,
    onSave: (StartSequence) -> Unit,
    onClose: () -> Unit,
    previewContent: @Composable () -> Unit,
) {
    var draft by remember(original.id) { mutableStateOf(original) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var mediaSize by remember { mutableStateOf(IntSize.Zero) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<IntroMediaView>(1) }

    val leave = { if (draft != original) confirmLeave = true else onClose() }
    BackHandler { leave() }

    val sizesKnown = mediaSize != IntSize.Zero && viewSize != IntSize.Zero
    // Wie weit man herauszoomen kann, bis das ganze Video (mit schwarzen Rändern) zu sehen ist
    val minZoom = if (sizesKnown) {
        Framing.minZoom(
            viewSize.width.toFloat(), viewSize.height.toFloat(),
            mediaSize.width.toFloat(), mediaSize.height.toFloat(),
        )
    } else {
        1f
    }

    /** Neuer Ausschnitt nach Zoom-Änderung und/oder Verschieben um [dx]/[dy] Pixel. */
    fun reframe(zoom: Float, dx: Float = 0f, dy: Float = 0f) {
        if (!sizesKnown) return
        val f = draft.framing
        val newZoom = zoom.coerceIn(minZoom, Framing.MAX_ZOOM)
        val p = Framing(newZoom, f.panX, f.panY).place(
            viewSize.width.toFloat(), viewSize.height.toFloat(),
            mediaSize.width.toFloat(), mediaSize.height.toFloat(),
        )
        // excess > 0: Video ragt über den Rand (Ausschnitt wählen),
        // excess < 0: Video ist kleiner (innerhalb der schwarzen Ränder verschieben)
        fun pan(old: Float, delta: Float, excess: Float) =
            if (kotlin.math.abs(excess) > 1f) (old - delta / excess).coerceIn(0f, 1f) else 0.5f
        draft = draft.copy(
            framing = Framing(newZoom, pan(f.panX, dx, p.excessX), pan(f.panY, dy, p.excessY)),
        )
    }

    // Zeitleiste mitlaufen lassen, solange das Video spielt
    LaunchedEffect(playing) {
        while (playing) {
            position = (player[0]?.position ?: 0).toFloat()
            delay(100)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        AndroidView(
            factory = {
                IntroMediaView(it).apply {
                    looping = true
                    autoPlay = false
                    onMediaSize = { w, h -> mediaSize = IntSize(w, h) }
                    onPrepared = { d ->
                        duration = d
                        // Am Ende anfangen: dort startet die Animation, dort setzt man den Punkt
                        if (d > 0) {
                            val end = (d - 50).coerceAtLeast(0)
                            seekTo(end)
                            position = end.toFloat()
                        }
                    }
                    player[0] = this
                }
            },
            update = {
                it.framing = draft.framing
                it.setSource(videoUri, draft.isGif)
            },
            onRelease = { it.release() },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesten: verschieben/zoomen und Startpunkt setzen
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        reframe(draft.framing.zoom * zoom, pan.x, pan.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        draft = draft.copy(pointX = tap.x / size.width, pointY = tap.y / size.height)
                    }
                },
        )

        // Markierung des Startpunkts
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * draft.pointX, size.height * draft.pointY)
            val r = 18.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.6f), r + 2.dp.toPx(), c, style = Stroke(5.dp.toPx()))
            drawCircle(Color.White, r, c, style = Stroke(3.dp.toPx()))
            drawLine(Color.White, c - Offset(r * 1.6f, 0f), c + Offset(r * 1.6f, 0f), 2.dp.toPx())
            drawLine(Color.White, c - Offset(0f, r * 1.6f), c + Offset(0f, r * 1.6f), 2.dp.toPx())
        }

        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (controlsVisible) {
                // Kopfzeile
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                    Text(
                        draft.name,
                        modifier = Modifier.weight(1f).clickable { renaming = true },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { player[0]?.pause(); playing = false; previewing = true }) {
                        Text("Vorschau", color = Color.White)
                    }
                    TextButton(onClick = { onSave(draft); onClose() }) {
                        Text("Speichern", color = Color.White)
                    }
                }

                // Bedienfeld unten
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Ziehen: verschieben · Zwei Finger: zoomen · Tippen: Startpunkt",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (duration > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (playing) player[0]?.pause() else player[0]?.play()
                                playing = !playing
                            }) {
                                if (playing) {
                                    Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen")
                                }
                            }
                            Slider(
                                value = position.coerceIn(0f, duration.toFloat()),
                                onValueChange = {
                                    position = it
                                    playing = false
                                    player[0]?.pause()
                                    player[0]?.seekTo(it.toInt())
                                },
                                valueRange = 0f..duration.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                playing = false
                                player[0]?.pause()
                                val end = (duration - 50).coerceAtLeast(0)
                                player[0]?.seekTo(end)
                                position = end.toFloat()
                            }) { Text("Ende", color = Color.White) }
                        }
                    } else if (draft.isGif) {
                        Text(
                            "Bei GIFs läuft die Vorschau hier in Schleife, Spulen geht nur bei Videos.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(RevealStyle.entries) { style ->
                            FilterChip(
                                selected = draft.style == style,
                                onClick = { draft = draft.copy(style = style) },
                                label = { Text(style.label) },
                                colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                            )
                        }
                    }
                    // Größe: links ganzes Video mit Rändern, Mitte randlos, rechts hineingezoomt
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Größe", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = draft.framing.zoom.coerceIn(minZoom, Framing.MAX_ZOOM),
                            onValueChange = { reframe(it) },
                            valueRange = minZoom..Framing.MAX_ZOOM,
                            enabled = sizesKnown && minZoom < Framing.MAX_ZOOM,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                    }
                    Row {
                        TextButton(onClick = { draft = draft.copy(framing = Framing(zoom = minZoom)) }) {
                            Text("Ganz zeigen", color = Color.White)
                        }
                        TextButton(onClick = { draft = draft.copy(framing = Framing()) }) {
                            Text("Ausfüllen", color = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { controlsVisible = false }) {
                            Text("Ausblenden", color = Color.White)
                        }
                    }
                }
            } else {
                // Alles ausgeblendet: nur ein kleiner Knopf zum Wiederholen
                TextButton(
                    onClick = { controlsVisible = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small),
                ) { Text("Bedienelemente", color = Color.White) }
            }
        }
    }

    if (previewing) {
        Dialog(
            onDismissRequest = { previewing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                StartAnimation(
                    sequence = draft,
                    videoUri = videoUri,
                    onFinished = {
                        // App kurz zeigen, dann zurück in den Editor
                        scope.launch {
                            delay(1200)
                            previewing = false
                        }
                    },
                ) { previewContent() }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Umbenennen",
            initial = draft.name,
            onConfirm = { draft = draft.copy(name = it.trim()); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Änderungen speichern?") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onSave(draft); onClose() }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Verwerfen") }
            },
        )
    }
}
