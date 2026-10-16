package de.thomas.soundboard

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/** Ein Eintrag im Speicherordner: entweder ein Unterordner oder eine Audiodatei. */
data class Entry(
    val uri: Uri,
    val name: String,
    val isFolder: Boolean,
    /** Der Ordner, in dem der Eintrag liegt (null nur beim Speicherordner selbst). */
    val parent: Uri?,
    /** Coverbild: bei Sounds das gleichnamige Bild daneben, bei Ordnern folder.jpg darin. */
    val cover: Uri? = null,
    /** Änderungszeit des Coverbilds, damit ein ersetztes Bild neu geladen wird. */
    val coverStamp: Long = 0,
) {
    /** Angezeigter Name: bei Sounds ohne Dateiendung. */
    val title: String get() = if (isFolder) name else name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Ein Suchtreffer samt der Ordner, die über ihm liegen (ohne den Speicherordner selbst). */
data class SearchHit(val entry: Entry, val ancestors: List<Entry>)

/**
 * Zugriff auf den vom Nutzer gewählten Speicherordner (Storage Access Framework).
 * Unterordner erscheinen in der App als Ordner, Audiodateien als Sounds.
 * Die Dateien sind ganz normal im Handyspeicher sichtbar, z. B. am PC im Explorer.
 */
class SoundLibrary(private val context: Context, val treeUri: Uri) {

    private val resolver = context.contentResolver

    val root: Entry = DocumentsContract
        .buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        .let { Entry(it, queryName(it) ?: "Soundboard", isFolder = true, parent = null) }

    private class Child(val uri: Uri, val docId: String, val name: String, val mime: String?, val modified: Long) {
        val isFolder get() = mime == Document.MIME_TYPE_DIR
        val isImage get() = mime?.startsWith("image/") == true
        val baseName get() = name.substringBeforeLast('.').lowercase()
    }

    private fun children(folderUri: Uri): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(folderUri),
        )
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<Child>()
        resolver.query(childrenUri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val docId = c.getString(0)
                result += Child(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                    docId, name, c.getString(2), c.getLong(3),
                )
            }
        }
        return result
    }

    fun list(folder: Entry): List<Entry> {
        val kids = children(folder.uri)
        val images = kids.filter { it.isImage }.associateBy { it.baseName }
        return kids.mapNotNull { kid ->
            when {
                kid.isFolder -> {
                    val cover = runCatching { children(kid.uri) }.getOrDefault(emptyList())
                        .firstOrNull { it.name.lowercase() in FOLDER_COVER_NAMES }
                    Entry(kid.uri, kid.name, true, folder.uri, cover?.uri, cover?.modified ?: 0)
                }
                isAudio(kid.name, kid.mime) -> {
                    val cover = images[kid.baseName]
                    Entry(kid.uri, kid.name, false, folder.uri, cover?.uri, cover?.modified ?: 0)
                }
                else -> null
            }
        }.sortedWith(compareBy({ !it.isFolder }, { it.title.lowercase() }))
    }

    /** Alle Ordner und Sounds unterhalb des Speicherordners, für die Suche. */
    fun walk(folder: Entry = root, ancestors: List<Entry> = emptyList()): List<SearchHit> =
        list(folder).flatMap { entry ->
            val hit = SearchHit(entry, ancestors)
            if (entry.isFolder) listOf(hit) + walk(entry, ancestors + entry) else listOf(hit)
        }

    fun createFolder(parent: Entry, name: String): Boolean {
        val clean = sanitize(name)
        if (clean.isBlank()) return false
        return DocumentsContract.createDocument(resolver, parent.uri, Document.MIME_TYPE_DIR, clean) != null
    }

    /** Kopiert eine Datei aus einer anderen App (Datei-Dialog, WhatsApp, ...) in [parent]. */
    fun import(parent: Entry, source: Uri): Boolean {
        val name = queryName(source) ?: "Sound"
        val input = resolver.openInputStream(source) ?: return false
        return input.use { write(parent.uri, name, resolver.getType(source), it) }
    }

    fun importFile(parent: Entry, file: File): Boolean =
        file.inputStream().use { write(parent.uri, file.name, null, it) }

    fun rename(entry: Entry, newName: String): Boolean {
        val base = sanitize(newName)
        if (base.isBlank()) return false
        val name = if (!entry.isFolder && entry.extension.isNotEmpty()) "$base.${entry.extension}" else base
        if (name == entry.name) return true
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, entry.uri, name) != null }
            .getOrDefault(false)
        // Coverbild eines Sounds heißt immer wie der Sound
        if (renamed && !entry.isFolder) entry.cover?.let { cover ->
            val ext = queryName(cover)?.substringAfterLast('.', "jpg") ?: "jpg"
            runCatching { DocumentsContract.renameDocument(resolver, cover, "$base.$ext") }
        }
        return renamed
    }

    /** Verschiebt eine Datei oder einen Ordner nach [to], echt im Dateisystem. */
    fun move(entry: Entry, to: Entry): Boolean {
        val from = entry.parent ?: return false
        val moved = runCatching { DocumentsContract.moveDocument(resolver, entry.uri, from, to.uri) }.getOrNull()
        if (moved == null) {
            if (entry.isFolder) return false
            // Klappt z. B. nicht, wenn im Ziel schon eine gleichnamige Datei liegt:
            // dann kopieren (bekommt automatisch einen eindeutigen Namen) und Original löschen
            val copied = resolver.openInputStream(entry.uri)?.use { write(to.uri, entry.name, null, it) } ?: false
            if (!copied || !deleteDocument(entry.uri)) return false
        }
        // Coverbild eines Sounds mitnehmen (Ordner-Cover liegt im Ordner und wandert sowieso mit)
        if (!entry.isFolder) entry.cover?.let { cover ->
            runCatching { DocumentsContract.moveDocument(resolver, cover, from, to.uri) }
        }
        return true
    }

    /** Löscht eine Datei oder einen Ordner samt Inhalt. */
    fun delete(entry: Entry): Boolean {
        if (!entry.isFolder) entry.cover?.let { deleteDocument(it) }
        return deleteDocument(entry.uri)
    }

    /** Speichert [image] verkleinert als Coverbild von [entry]. */
    fun setCover(entry: Entry, image: Uri): Boolean {
        val bitmap = decodeScaled(context, image, 800)
        removeCover(entry)
        val (folder, name) =
            if (entry.isFolder) entry.uri to FOLDER_COVER
            else (entry.parent ?: return false) to "${entry.title}.jpg"
        val target = DocumentsContract.createDocument(resolver, folder, "image/jpeg", name) ?: return false
        val output = resolver.openOutputStream(target) ?: return false
        output.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return true
    }

    fun removeCover(entry: Entry): Boolean = entry.cover?.let { deleteDocument(it) } ?: true

    /**
     * Legt eine Kopie im App-Cache an, die per FileProvider geteilt werden kann.
     * So bekommt WhatsApp/Signal nur genau diese eine Datei zu sehen.
     */
    fun copyForSharing(entry: Entry): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // alte Kopien aufräumen, aber nicht sofort: die Ziel-App liest evtl. noch
        val anHourAgo = System.currentTimeMillis() - 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < anHourAgo }?.forEach { it.delete() }

        val file = File(dir, entry.name)
        val input = resolver.openInputStream(entry.uri) ?: error("Datei nicht lesbar")
        input.use { src -> file.outputStream().use { src.copyTo(it) } }
        return file
    }

    private fun deleteDocument(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    private fun write(folder: Uri, name: String, sourceMime: String?, input: InputStream): Boolean {
        val clean = sanitize(name).ifBlank { "Sound" }
        val ext = clean.substringAfterLast('.', "").lowercase()
        // Der MIME-Typ muss zur Endung passen, sonst hängt Android eine zweite Endung an.
        // Unbekannte Endungen (octet-stream) lässt Android unverändert.
        val mime = if (ext.isEmpty()) sourceMime ?: "application/octet-stream"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val target = DocumentsContract.createDocument(resolver, folder, mime, clean) ?: return false
        val output = resolver.openOutputStream(target) ?: return false
        output.use { input.copyTo(it) }
        return true
    }

    private fun queryName(uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(80)

    private fun isAudio(name: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true ||
            mime == "application/ogg" ||
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "opus", "ogg", "oga", "m4a", "aac", "wav", "amr", "flac")
        const val FOLDER_COVER = "folder.jpg"
        val FOLDER_COVER_NAMES = setOf("folder.jpg", "folder.jpeg", "folder.png", "folder.webp")
    }
}

fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
    "mp3" -> "audio/mpeg"
    // WhatsApp-Sprachnachrichten sind Opus-in-Ogg
    "opus", "ogg", "oga" -> "audio/ogg"
    "m4a", "mp4" -> "audio/mp4"
    "aac" -> "audio/aac"
    "wav" -> "audio/wav"
    "amr" -> "audio/amr"
    "flac" -> "audio/flac"
    else -> "audio/*"
}

/** Öffnet das Android-Teilen-Menü für einen Sound. */
fun shareSound(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeFor(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        // ClipData sorgt dafür, dass die Leseberechtigung auch durch den Chooser hindurch gilt
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
