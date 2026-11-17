package de.thomas.soundboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(vm: BoardViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val kind = vm.kind
    var viewing by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var moving by remember { mutableStateOf<Entry?>(null) }
    var coverFor by remember { mutableStateOf<Entry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val hasLibrary = vm.library != null
    val searching = vm.searchQuery != null

    // Systemdialog zur Auswahl des Speicherordners
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setRoot(uri)
    }
    // Systemdialog zur Dateiauswahl (Storage Access Framework), Mehrfachauswahl möglich
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importAll(uris)
    }
    // Android-Fotoauswahl für Coverbilder, braucht keine Berechtigung
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val entry = coverFor
        coverFor = null
        if (uri != null && entry != null) vm.setCover(entry, uri)
    }

    // Zurück-Taste: erst die Suche schließen, dann eine Ordnerebene hoch, sonst App verlassen
    BackHandler(enabled = searching || vm.path.isNotEmpty()) {
        if (searching) vm.closeSearch() else vm.goUp()
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    val share: (Entry) -> Unit = { entry ->
        scope.launch {
            runCatching { shareFile(context, vm.copyForSharing(entry)) }
                .onFailure { vm.message = "Teilen fehlgeschlagen" }
        }
    }

    Scaffold(
        modifier = modifier,
        // Systemleisten-Abstände kommen schon von außen (Reiter-Leiste)
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (searching) {
                SearchBar(
                    query = vm.searchQuery.orEmpty(),
                    onQueryChange = { vm.searchQuery = it },
                    onClose = vm::closeSearch,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(vm.currentFolder?.title ?: kind.plural, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (vm.path.isNotEmpty()) {
                            IconButton(onClick = vm::goUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                            }
                        }
                    },
                    actions = {
                        if (hasLibrary) {
                            IconButton(onClick = vm::openSearch) {
                                Icon(Icons.Default.Search, contentDescription = "Suchen")
                            }
                            IconButton(onClick = { creatingFolder = true }) {
                                Icon(painterResource(R.drawable.ic_create_new_folder), contentDescription = "Neuer Ordner")
                            }
                            Box {
                                IconButton(onClick = { overflowOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Mehr")
                                }
                                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Aktualisieren") },
                                        onClick = { overflowOpen = false; vm.refresh() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Speicherordner ändern") },
                                        onClick = { overflowOpen = false; folderPicker.launch(null) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (hasLibrary && !searching) {
                FloatingActionButton(onClick = { filePicker.launch(kind.pickerMimeTypes) }) {
                    Icon(Icons.Default.Add, contentDescription = "${kind.plural} hinzufügen")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val results = vm.searchResults
        // In der Suche Treffer aus allen Ordnern, sonst der Inhalt des geöffneten Ordners
        val hits = if (searching) results.orEmpty() else vm.entries.map { SearchHit(it, vm.path) }
        when {
            !hasLibrary -> SetupHint(kind, Modifier.padding(padding), onPick = { folderPicker.launch(null) })

            searching && results == null -> CenteredText("Durchsuche Ordner…", Modifier.padding(padding))

            searching && hits.isEmpty() -> CenteredText("Nichts gefunden.", Modifier.padding(padding))

            hits.isEmpty() -> CenteredText(
                if (vm.path.isEmpty()) {
                    when (kind) {
                        MediaKind.SOUND ->
                            "Noch keine Sounds.\n\nTippe auf +, um MP3- oder Opus-Dateien hinzuzufügen, " +
                                "teile eine Sprachnachricht aus WhatsApp an diese App " +
                                "oder kopiere Dateien und Ordner am PC in den Speicherordner."
                        MediaKind.GIF ->
                            "Noch keine GIFs.\n\nTippe auf +, um GIFs oder kurze MP4-Videos hinzuzufügen, " +
                                "teile sie aus einer anderen App an diese App " +
                                "oder kopiere Dateien und Ordner am PC in den Speicherordner."
                    }
                } else {
                    "Dieser Ordner ist leer."
                },
                Modifier.padding(padding),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                // unten extra Platz, damit der +-Button die letzte Reihe nicht verdeckt
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(hits, key = { it.entry.uri.toString() }) { hit ->
                    val entry = hit.entry
                    EntryCard(
                        entry = entry,
                        kind = kind,
                        isPlaying = vm.playing == entry.uri,
                        // in der Suche zeigen, wo der Treffer liegt
                        subtitle = if (searching) {
                            hit.ancestors.joinToString(" / ") { it.title }.ifEmpty { vm.library?.root?.title }
                        } else {
                            null
                        },
                        onTap = {
                            when {
                                entry.isFolder && searching -> vm.openSearchHit(hit)
                                entry.isFolder -> vm.openFolder(entry)
                                kind == MediaKind.SOUND -> vm.togglePlay(entry)
                                else -> viewing = entry
                            }
                        },
                        onShare = { share(entry) },
                        onRename = { renaming = entry },
                        onMove = { moving = entry },
                        onSetCover = {
                            coverFor = entry
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemoveCover = { vm.removeCover(entry) },
                        onDelete = { deleting = entry },
                    )
                }
            }
        }
    }

    viewing?.let { entry ->
        MediaViewer(entry, onShare = { share(entry) }, onDismiss = { viewing = null })
    }

    if (creatingFolder) {
        NameDialog(
            title = "Neuer Ordner",
            initial = "",
            onConfirm = { vm.createFolder(it); creatingFolder = false },
            onDismiss = { creatingFolder = false },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Umbenennen",
            initial = entry.title,
            onConfirm = { vm.rename(entry, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    moving?.let { entry ->
        MoveDialog(entry, vm, onDismiss = { moving = null })
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Löschen?") },
            text = {
                Text(
                    if (entry.isFolder) "Der Ordner „${entry.title}“ und alles darin wird endgültig gelöscht."
                    else "„${entry.title}“ wird endgültig gelöscht."
                )
            },
            confirmButton = { TextButton(onClick = { vm.delete(entry); deleting = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() } // Tastatur gleich öffnen

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Suche schließen")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("In allen Ordnern suchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Suchtext löschen")
                }
            }
        },
    )
}

@Composable
private fun SetupHint(kind: MediaKind, modifier: Modifier, onPick: () -> Unit) {
    val (what, parent, folder) = when (kind) {
        MediaKind.SOUND -> Triple("deine Sounds", "Music", "Soundboard")
        MediaKind.GIF -> Triple("deine GIFs und kurzen Videos", "Pictures", "GIFs")
    }
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Wähle einen Ordner auf deinem Handy, in dem $what liegen sollen.\n\n" +
                "Tipp: Lege im Auswahldialog z. B. unter „$parent“ einen neuen Ordner " +
                "„$folder“ an. Unterordner darin erscheinen in der App als Ordner. " +
                "Du kannst sie auch am PC anlegen und befüllen.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick) { Text("Ordner auswählen") }
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EntryCard(
    entry: Entry,
    kind: MediaKind,
    isPlaying: Boolean,
    subtitle: String?,
    onTap: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // GIFs/Videos zeigen sich selbst als loopende Vorschau, gestaltet wie ein Coverbild
    val animated = !entry.isFolder && kind == MediaKind.GIF
    val hasCover = entry.cover != null || animated
    val canHaveCover = entry.isFolder || kind.fileCovers

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(CardDefaults.shape)
                // mit Cover sieht man die Hintergrundfarbe nicht, daher ein Rahmen beim Abspielen
                .then(if (isPlaying && hasCover) Modifier.border(3.dp, colors.primary, CardDefaults.shape) else Modifier)
                .combinedClickable(onClick = onTap, onLongClick = { menuOpen = true }),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    entry.isFolder -> colors.secondaryContainer
                    isPlaying -> colors.primaryContainer
                    else -> colors.surfaceVariant
                },
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (hasCover) {
                    if (animated) AnimatedPreview(entry, crop = true, Modifier.matchParentSize())
                    else CoverImage(entry, Modifier.matchParentSize())
                    // dunkler Verlauf nach unten, damit die Schrift auf jedem Bild lesbar bleibt
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.3f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.8f),
                            ),
                        ),
                    )
                }
                CompositionLocalProvider(LocalContentColor provides if (hasCover) Color.White else LocalContentColor.current) {
                    Row(
                        Modifier.fillMaxSize().padding(start = 12.dp),
                        verticalAlignment = if (hasCover) Alignment.Bottom else Alignment.CenterVertically,
                    ) {
                        val iconModifier = Modifier.padding(bottom = if (hasCover) 12.dp else 0.dp)
                        if (entry.isFolder) {
                            Icon(
                                painterResource(R.drawable.ic_folder),
                                contentDescription = "Ordner",
                                modifier = iconModifier.padding(end = 8.dp).size(24.dp),
                            )
                        } else if (isPlaying) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Spielt",
                                modifier = iconModifier.padding(end = 4.dp).size(20.dp),
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(end = if (entry.isFolder) 12.dp else 0.dp)
                                .padding(bottom = if (hasCover) 10.dp else 0.dp),
                        ) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = if (subtitle != null || hasCover) 2 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = LocalContentColor.current.copy(alpha = 0.75f),
                                )
                            }
                        }
                        if (!entry.isFolder) {
                            IconButton(onClick = onShare) {
                                Icon(Icons.Default.Share, contentDescription = "Teilen")
                            }
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Umbenennen") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Verschieben") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_move), contentDescription = null) },
                onClick = { menuOpen = false; onMove() },
            )
            if (canHaveCover) {
                DropdownMenuItem(
                    text = { Text(if (entry.cover != null) "Coverbild ändern" else "Coverbild festlegen") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_image), contentDescription = null) },
                    onClick = { menuOpen = false; onSetCover() },
                )
            }
            if (canHaveCover && entry.cover != null) {
                DropdownMenuItem(
                    text = { Text("Coverbild entfernen") },
                    leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                    onClick = { menuOpen = false; onRemoveCover() },
                )
            }
            DropdownMenuItem(
                text = { Text("Löschen") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/** Großansicht eines GIFs/Videos. Tippen irgendwo oder Zurück schließt sie. */
@Composable
private fun MediaViewer(entry: Entry, onShare: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            AnimatedPreview(entry, crop = false, Modifier.fillMaxSize())
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                    Text(
                        entry.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Teilen")
                    }
                }
            }
        }
    }
}

/** Lädt das Coverbild im Hintergrund, beim Scrollen kommt es aus dem Zwischenspeicher. */
@Composable
private fun CoverImage(entry: Entry, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(CoverCache.get(entry), CoverCache.key(entry)) {
        if (value == null) value = withContext(Dispatchers.IO) { CoverCache.load(context, entry) }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}

/** Ordner-Browser zur Auswahl des Ziels beim Verschieben. */
@Composable
private fun MoveDialog(entry: Entry, vm: BoardViewModel, onDismiss: () -> Unit) {
    val root = vm.library?.root ?: return
    var stack by remember { mutableStateOf(listOf(root)) }
    val folder = stack.last()
    var subfolders by remember { mutableStateOf<List<Entry>?>(null) }

    LaunchedEffect(folder) {
        subfolders = null
        // einen Ordner nicht in sich selbst verschieben
        subfolders = vm.listFolders(folder).filter { it.uri != entry.uri }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„${entry.title}“ verschieben") },
        text = {
            Column {
                Text(
                    "Ziel: " + stack.joinToString(" / ") { it.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (stack.size > 1) {
                        item {
                            FolderRow("Eine Ebene hoch", { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }) {
                                stack = stack.dropLast(1)
                            }
                        }
                    }
                    items(subfolders.orEmpty(), key = { it.uri.toString() }) { sub ->
                        FolderRow(sub.title, { Icon(painterResource(R.drawable.ic_folder), null) }) {
                            stack = stack + sub
                        }
                    }
                    if (subfolders?.isEmpty() == true) {
                        item {
                            Text(
                                "Keine Unterordner",
                                Modifier.padding(vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.move(entry, folder); onDismiss() },
                // dort liegt es ja schon
                enabled = folder.uri != entry.parent,
            ) { Text("Hierher verschieben") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun FolderRow(text: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
