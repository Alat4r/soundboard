package de.thomas.soundboard

/** Die beiden Bereiche der App. Jeder hat seinen eigenen Speicherordner. */
enum class MediaKind(
    /** Anzeigename im Singular, z. B. für "1 Sound hinzugefügt". */
    val singular: String,
    val plural: String,
    /** Unter diesem Schlüssel wird der gewählte Speicherordner gemerkt. */
    val prefsKey: String,
    /** MIME-Typen für die Dateiauswahl über "+". */
    val pickerMimeTypes: Array<String>,
    private val extensions: Set<String>,
) {
    SOUND(
        singular = "Sound",
        plural = "Sounds",
        prefsKey = "tree_uri",
        pickerMimeTypes = arrayOf("audio/*"),
        extensions = setOf("mp3", "opus", "ogg", "oga", "m4a", "aac", "wav", "amr", "flac"),
    ),
    GIF(
        singular = "GIF",
        plural = "GIFs",
        prefsKey = "gif_tree_uri",
        pickerMimeTypes = arrayOf("image/gif", "video/*"),
        extensions = setOf("gif", "mp4", "webm"),
    );

    fun matches(name: String, mime: String?): Boolean {
        if (name.substringAfterLast('.', "").lowercase() in extensions) return true
        return when (this) {
            SOUND -> mime?.startsWith("audio/") == true || mime == "application/ogg"
            GIF -> mime == "image/gif" || mime?.startsWith("video/") == true
        }
    }

    /** Nur Sounds bekommen ein Coverbild als Datei daneben, GIFs zeigen sich ja selbst. */
    val fileCovers: Boolean get() = this == SOUND

    companion object {
        /** Zu welchem Bereich gehört eine Datei, die aus einer anderen App geteilt wird? */
        fun forMime(mime: String?): MediaKind? = when {
            mime == null -> null
            mime.startsWith("audio/") || mime == "application/ogg" -> SOUND
            mime == "image/gif" || mime.startsWith("video/") -> GIF
            else -> null
        }
    }
}
