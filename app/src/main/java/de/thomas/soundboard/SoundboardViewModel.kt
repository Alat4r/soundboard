package de.thomas.soundboard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hält den Zustand des Bildschirms (Speicherordner, geöffneter Unterordner,
 * Inhalt, gerade spielender Sound, Meldungen). Überlebt Bildschirmdrehungen.
 */
class SoundboardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val player = SoundPlayer()
    private var pendingImports = emptyList<Uri>()

    /** null, solange noch kein Speicherordner gewählt wurde. */
    var library by mutableStateOf(loadLibrary())
        private set
    /** Die geöffneten Unterordner unterhalb des Speicherordners, leer = oberste Ebene. */
    var path by mutableStateOf(emptyList<Entry>())
        private set
    var entries by mutableStateOf(emptyList<Entry>())
        private set
    var playing by mutableStateOf<Uri?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    val currentFolder: Entry? get() = path.lastOrNull() ?: library?.root

    private fun loadLibrary(): SoundLibrary? {
        val uri = prefs.getString(KEY_TREE_URI, null)?.toUri() ?: return null
        val granted = getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return if (granted) runCatching { SoundLibrary(getApplication(), uri) }.getOrNull() else null
    }

    fun setRoot(treeUri: Uri) {
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(treeUri, flags)
        library?.treeUri?.takeIf { it != treeUri }?.let { old ->
            runCatching { app.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.edit { putString(KEY_TREE_URI, treeUri.toString()) }

        stopPlayback()
        closeSearch()
        val lib = SoundLibrary(app, treeUri)
        library = lib
        path = emptyList()
        entries = emptyList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { migrateOldSounds(lib) }
            if (pendingImports.isNotEmpty()) {
                importAll(pendingImports)
                pendingImports = emptyList()
            } else {
                refresh()
            }
        }
    }

    /** Die erste Version der App hat Sounds im privaten Speicher abgelegt: in den neuen Ordner umziehen. */
    private fun migrateOldSounds(lib: SoundLibrary) {
        val oldDir = File(getApplication<Application>().filesDir, "sounds")
        oldDir.listFiles()?.forEach { file ->
            if (runCatching { lib.importFile(lib.root, file) }.getOrDefault(false)) file.delete()
        }
        oldDir.delete() // klappt nur, wenn er leer ist
    }

    fun refresh() {
        val lib = library ?: return
        val folder = currentFolder ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { lib.list(folder) } }
            if (currentFolder != folder) return@launch // inzwischen woanders hin navigiert
            result
                .onSuccess { entries = it }
                .onFailure {
                    if (path.isNotEmpty()) {
                        // Ordner wurde vermutlich gelöscht, z. B. am PC
                        path = emptyList()
                        refresh()
                    } else {
                        library = null
                        message = "Speicherordner nicht erreichbar, bitte neu auswählen"
                    }
                }
        }
    }

    fun openFolder(folder: Entry) {
        stopPlayback()
        path = path + folder
        refresh()
    }

    fun goUp() {
        stopPlayback()
        path = path.dropLast(1)
        refresh()
    }

    fun importAll(uris: List<Uri>) {
        val lib = library
        val folder = currentFolder
        if (lib == null || folder == null) {
            pendingImports = uris
            message = "Wähle zuerst einen Speicherordner"
            return
        }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.count { uri -> runCatching { lib.import(folder, uri) }.getOrDefault(false) }
            }
            refresh()
            val failed = uris.size - imported
            message = when {
                failed > 0 -> "$failed Datei(en) konnten nicht importiert werden"
                imported == 1 -> "1 Sound hinzugefügt"
                else -> "$imported Sounds hinzugefügt"
            }
        }
    }

    fun createFolder(name: String) = modify("Ordner konnte nicht angelegt werden") { lib ->
        lib.createFolder(currentFolder ?: return@modify false, name)
    }

    fun rename(entry: Entry, newName: String) {
        if (playing == entry.uri) stopPlayback()
        modify("Umbenennen fehlgeschlagen") { it.rename(entry, newName) }
    }

    fun delete(entry: Entry) {
        if (playing == entry.uri) stopPlayback()
        modify("Löschen fehlgeschlagen") { it.delete(entry) }
    }

    fun move(entry: Entry, target: Entry) {
        if (playing == entry.uri) stopPlayback()
        modify(
            errorMessage = "Verschieben fehlgeschlagen",
            successMessage = "„${entry.title}“ nach „${target.title}“ verschoben",
        ) { it.move(entry, target) }
    }

    fun setCover(entry: Entry, image: Uri) =
        modify("Coverbild konnte nicht gespeichert werden") { it.setCover(entry, image) }

    fun removeCover(entry: Entry) =
        modify("Coverbild konnte nicht entfernt werden") { it.removeCover(entry) }

    // ---- Suche ----

    /** null = Suche geschlossen. */
    var searchQuery by mutableStateOf<String?>(null)
    /** Alle Einträge des Speicherordners; null, solange er noch eingelesen wird. */
    private var searchIndex by mutableStateOf<List<SearchHit>?>(null)

    val searchResults: List<SearchHit>?
        get() {
            val index = searchIndex ?: return null
            val words = searchQuery.orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
            return index
                .filter { hit -> words.all { it in hit.entry.title.lowercase() } }
                .sortedWith(compareBy({ !it.entry.isFolder }, { it.entry.title.lowercase() }))
        }

    fun openSearch() {
        stopPlayback()
        searchQuery = ""
        rebuildSearchIndex()
    }

    fun closeSearch() {
        searchQuery = null
        searchIndex = null
    }

    /** Springt aus der Suche in einen gefundenen Ordner. */
    fun openSearchHit(hit: SearchHit) {
        closeSearch()
        stopPlayback()
        path = hit.ancestors + hit.entry
        refresh()
    }

    private fun rebuildSearchIndex() {
        val lib = library ?: return
        viewModelScope.launch {
            val index = withContext(Dispatchers.IO) { runCatching { lib.walk() }.getOrDefault(emptyList()) }
            if (searchQuery != null) searchIndex = index
        }
    }

    /** Unterordner von [folder], für den Verschieben-Dialog. */
    suspend fun listFolders(folder: Entry): List<Entry> = withContext(Dispatchers.IO) {
        runCatching { library?.list(folder).orEmpty().filter { it.isFolder } }.getOrDefault(emptyList())
    }

    /** Führt eine Dateioperation im Hintergrund aus und lädt danach den Ordner neu. */
    private fun modify(errorMessage: String, successMessage: String? = null, action: (SoundLibrary) -> Boolean) {
        val lib = library ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { action(lib) }.getOrDefault(false) }
            message = if (ok) successMessage else errorMessage
            refresh()
            if (searchQuery != null) rebuildSearchIndex()
        }
    }

    suspend fun copyForSharing(entry: Entry): File =
        withContext(Dispatchers.IO) { library!!.copyForSharing(entry) }

    fun togglePlay(entry: Entry) {
        if (playing == entry.uri) {
            stopPlayback()
            return
        }
        try {
            player.play(getApplication(), entry.uri) { playing = null }
            playing = entry.uri
        } catch (e: Exception) {
            playing = null
            message = "„${entry.title}“ kann nicht abgespielt werden"
        }
    }

    private fun stopPlayback() {
        player.stop()
        playing = null
    }

    override fun onCleared() {
        player.stop()
    }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
    }
}
