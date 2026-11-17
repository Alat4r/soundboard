package de.thomas.soundboard

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Wie sich die App nach dem Video öffnet. Alle Effekte gehen vom gesetzten Startpunkt aus. */
enum class RevealStyle(val label: String) {
    CIRCLE("Kreis"),
    SQUARE("Quadrat"),
    DIAMOND("Raute"),
    STAR("Stern"),
    HEART("Herz"),
    CURTAIN_HORIZONTAL("Vorhang ↔"),
    CURTAIN_VERTICAL("Vorhang ↕"),
    ZOOM("Zoom");

    val isCurtain: Boolean get() = this == CURTAIN_HORIZONTAL || this == CURTAIN_VERTICAL
}

/**
 * Bildausschnitt des Videos. [zoom] = 1 füllt den Bildschirm genau randlos aus,
 * größer schneidet mehr ab, kleiner (bis [minZoom]) lässt schwarze Ränder zu.
 * [panX]/[panY] wählen die Lage: 0 = linker/oberer Rand, 0.5 = mittig, 1 = rechter/unterer Rand.
 * Ist das Video kleiner als der Bildschirm, verschieben sie es innerhalb der Ränder.
 */
data class Framing(val zoom: Float = 1f, val panX: Float = 0.5f, val panY: Float = 0.5f) {

    companion object {
        const val MAX_ZOOM = 5f

        /** Kleinster Zoom: das ganze Video ist sichtbar (mit Rändern in einer Richtung). */
        fun minZoom(viewWidth: Float, viewHeight: Float, mediaWidth: Float, mediaHeight: Float): Float {
            val fit = minOf(viewWidth / mediaWidth, viewHeight / mediaHeight)
            val fill = maxOf(viewWidth / mediaWidth, viewHeight / mediaHeight)
            return fit / fill
        }
    }

    fun place(viewWidth: Float, viewHeight: Float, mediaWidth: Float, mediaHeight: Float): Placement {
        val scale = maxOf(viewWidth / mediaWidth, viewHeight / mediaHeight) * zoom
        val excessX = mediaWidth * scale - viewWidth
        val excessY = mediaHeight * scale - viewHeight
        return Placement(scale, -excessX * panX, -excessY * panY, excessX, excessY)
    }
}

/** Ergebnis von [Framing.place]: Skalierung, Position und wie viel über den Rand hinausragt. */
data class Placement(val scale: Float, val left: Float, val top: Float, val excessX: Float, val excessY: Float)

data class StartSequence(
    val id: String,
    val name: String,
    /** Dateiname der Videokopie im App-Speicher (files/intros/). */
    val file: String,
    val framing: Framing = Framing(),
    /** Startpunkt der Animation als Anteil von Bildschirmbreite/-höhe. */
    val pointX: Float = 0.5f,
    val pointY: Float = 1f / 3f,
    val style: RevealStyle = RevealStyle.CIRCLE,
    /** Ab dieser Stelle (ms) läuft das Video. */
    val startMs: Int = 0,
    /** Hier (ms) friert das Video ein und die Animation beginnt; null = am Videoende. */
    val revealAtMs: Int? = null,
    /** Optionaler Sound: Dateiname der Kopie im App-Speicher und Anzeigename. */
    val soundFile: String? = null,
    val soundName: String? = null,
    /** An dieser Stelle im Video (ms) setzt der Sound ein. */
    val soundAtMs: Int = 0,
) {
    val isGif: Boolean get() = file.substringAfterLast('.').lowercase() == "gif"

    /** Einsatz des Sounds, begrenzt auf den abgespielten Abschnitt des Videos. */
    fun effectiveSoundAt(durationMs: Int?): Int {
        val end = revealAtMs ?: durationMs ?: Int.MAX_VALUE
        return soundAtMs.coerceIn(startMs, maxOf(startMs, end))
    }
}

data class IntroState(val sequences: List<StartSequence>, val activeId: String?)

/** Speichert die Startsequenzen als JSON und die Videos als Kopien im privaten App-Speicher. */
class StartSequenceStore(private val context: Context) {

    private val dir = File(context.filesDir, "intros").apply { mkdirs() }
    private val json = File(context.filesDir, "intros.json")

    fun fileFor(sequence: StartSequence) = File(dir, sequence.file)

    fun fileFor(name: String) = File(dir, name)

    /** Löscht Kopien, die keine Sequenz mehr benutzt (z. B. ein ersetzter Sound). */
    fun cleanup(state: IntroState) {
        val used = state.sequences.flatMap { listOfNotNull(it.file, it.soundFile) }.toSet()
        dir.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    fun load(): IntroState =
        runCatching { if (json.exists()) parse(JSONObject(json.readText())) else null }.getOrNull() ?: seed()

    fun save(state: IntroState) {
        json.writeText(toJson(state).toString(2))
    }

    fun importFile(input: InputStream, extension: String): String {
        val name = "${UUID.randomUUID()}.$extension"
        File(dir, name).outputStream().use { input.copyTo(it) }
        return name
    }

    fun deleteVideo(sequence: StartSequence) {
        fileFor(sequence).delete()
    }

    /** Beim allerersten Start: das mitgelieferte Video als erste, aktive Sequenz anlegen. */
    private fun seed(): IntroState {
        val name = "startgif_1.mp4"
        context.resources.openRawResource(R.raw.startgif_1).use { input ->
            File(dir, name).outputStream().use { input.copyTo(it) }
        }
        val sequence = StartSequence(UUID.randomUUID().toString(), "startgif_1", name)
        return IntroState(listOf(sequence), sequence.id).also { save(it) }
    }

    private fun parse(obj: JSONObject): IntroState {
        val array = obj.getJSONArray("sequences")
        val sequences = (0 until array.length()).map { i ->
            val s = array.getJSONObject(i)
            StartSequence(
                id = s.getString("id"),
                name = s.getString("name"),
                file = s.getString("file"),
                framing = Framing(
                    zoom = s.optDouble("zoom", 1.0).toFloat(),
                    panX = s.optDouble("panX", 0.5).toFloat(),
                    panY = s.optDouble("panY", 0.5).toFloat(),
                ),
                pointX = s.optDouble("pointX", 0.5).toFloat(),
                pointY = s.optDouble("pointY", 1.0 / 3).toFloat(),
                style = runCatching { RevealStyle.valueOf(s.getString("style")) }.getOrDefault(RevealStyle.CIRCLE),
                startMs = s.optInt("startMs", 0),
                revealAtMs = if (s.has("revealAtMs") && !s.isNull("revealAtMs")) s.getInt("revealAtMs") else null,
                soundFile = if (s.has("soundFile") && !s.isNull("soundFile")) s.getString("soundFile") else null,
                soundName = if (s.has("soundName") && !s.isNull("soundName")) s.getString("soundName") else null,
                soundAtMs = s.optInt("soundAtMs", 0),
            )
        }
        val active = if (obj.isNull("active")) null else obj.getString("active")
        return IntroState(sequences, active)
    }

    private fun toJson(state: IntroState) = JSONObject().apply {
        put("active", state.activeId ?: JSONObject.NULL)
        put("sequences", JSONArray().apply {
            state.sequences.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("file", s.file)
                    put("zoom", s.framing.zoom.toDouble())
                    put("panX", s.framing.panX.toDouble())
                    put("panY", s.framing.panY.toDouble())
                    put("pointX", s.pointX.toDouble())
                    put("pointY", s.pointY.toDouble())
                    put("style", s.style.name)
                    put("startMs", s.startMs)
                    put("revealAtMs", s.revealAtMs ?: JSONObject.NULL)
                    put("soundFile", s.soundFile ?: JSONObject.NULL)
                    put("soundName", s.soundName ?: JSONObject.NULL)
                    put("soundAtMs", s.soundAtMs)
                })
            }
        })
    }
}

/** Zustand der Startsequenzen (Liste, welche aktiv ist). */
class IntroViewModel(app: Application) : AndroidViewModel(app) {

    private val store = StartSequenceStore(app)

    var state by mutableStateOf(store.load())
        private set

    val active: StartSequence? get() = state.sequences.find { it.id == state.activeId }

    fun find(id: String): StartSequence? = state.sequences.find { it.id == id }

    fun uriFor(sequence: StartSequence): Uri = Uri.fromFile(store.fileFor(sequence))

    fun soundUriFor(sequence: StartSequence): Uri? = sequence.soundFile?.let { Uri.fromFile(store.fileFor(it)) }

    /** Kopiert eine Datei (z. B. einen Sound aus dem Sounds-Reiter) in den App-Speicher und gibt ihren Namen zurück. */
    suspend fun importFile(entry: Entry): String = withContext(Dispatchers.IO) {
        val input = getApplication<Application>().contentResolver.openInputStream(entry.uri)
            ?: error("Datei nicht lesbar")
        input.use { store.importFile(it, entry.extension.ifEmpty { "bin" }) }
    }

    fun setActive(id: String?) = update(state.copy(activeId = id))

    fun save(sequence: StartSequence) =
        update(state.copy(sequences = state.sequences.map { if (it.id == sequence.id) sequence else it }))

    fun delete(sequence: StartSequence) {
        store.deleteVideo(sequence)
        update(
            IntroState(
                sequences = state.sequences.filter { it.id != sequence.id },
                activeId = state.activeId.takeIf { it != sequence.id },
            ),
        )
    }

    /** Legt eine neue Sequenz mit einer Kopie von [entry] aus dem GIF-Reiter an. */
    suspend fun add(entry: Entry): StartSequence {
        val sequence = StartSequence(UUID.randomUUID().toString(), entry.title, importFile(entry))
        update(state.copy(sequences = state.sequences + sequence))
        return sequence
    }

    private fun update(newState: IntroState) {
        state = newState
        store.save(newState)
        store.cleanup(newState)
    }
}
