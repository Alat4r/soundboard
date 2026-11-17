package de.thomas.soundboard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {

    private val sounds: SoundsViewModel by viewModels()
    private val gifs: GifsViewModel by viewModels()
    private fun boardFor(kind: MediaKind): BoardViewModel = if (kind == MediaKind.SOUND) sounds else gifs

    private val intro: IntroViewModel by viewModels()

    /** Der gerade sichtbare Reiter. */
    private var tab by mutableStateOf(MediaKind.SOUND)

    /** Welcher Bildschirm gerade zu sehen ist: die Reiter, die Startsequenzen oder der Editor. */
    private var screen by mutableStateOf(SCREEN_BOARDS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.getString(KEY_TAB)?.let { tab = MediaKind.valueOf(it) }
        savedInstanceState?.getString(KEY_SCREEN)?.let { screen = it }
        // Nur beim ersten Start importieren, nicht nochmal nach einer Bildschirmdrehung
        if (savedInstanceState == null) handleIncomingShare(intent)
        // Startanimation nur beim normalen Start über das App-Icon, nicht beim Teilen in die App
        val introSequence = if (savedInstanceState == null && intent.action == Intent.ACTION_MAIN) intro.active else null
        setContent {
            SoundboardTheme {
                StartAnimation(introSequence, introSequence?.let(intro::uriFor)) {
                    Screens()
                }
            }
        }
    }

    @Composable
    private fun Screens() {
        val current = screen
        when {
            current == SCREEN_SEQUENCES -> SequenceListScreen(
                intro = intro,
                gifs = gifs,
                onEdit = { screen = EDITOR_PREFIX + it.id },
                onBack = { screen = SCREEN_BOARDS },
            )
            current.startsWith(EDITOR_PREFIX) -> {
                val sequence = intro.find(current.removePrefix(EDITOR_PREFIX))
                if (sequence == null) {
                    LaunchedEffect(Unit) { screen = SCREEN_SEQUENCES }
                } else {
                    SequenceEditor(
                        original = sequence,
                        videoUri = intro.uriFor(sequence),
                        onSave = intro::save,
                        onClose = { screen = SCREEN_SEQUENCES },
                        previewContent = { App() },
                    )
                }
            }
            else -> App()
        }
    }

    @Composable
    private fun App() {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    MediaKind.entries.forEach { kind ->
                        NavigationBarItem(
                            selected = tab == kind,
                            onClick = { switchTo(kind) },
                            icon = {
                                Icon(
                                    painterResource(if (kind == MediaKind.SOUND) R.drawable.ic_music else R.drawable.ic_gif),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(kind.plural) },
                        )
                    }
                }
            },
        ) { padding ->
            // key: jeder Reiter hat seine eigenen Dialoge und Scrollposition
            key(tab) {
                BoardScreen(
                    boardFor(tab),
                    onOpenSequences = {
                        sounds.stopPlayback()
                        screen = SCREEN_SEQUENCES
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    private fun switchTo(kind: MediaKind) {
        if (kind != MediaKind.SOUND) sounds.stopPlayback()
        tab = kind
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
        outState.putString(KEY_SCREEN, screen)
    }

    override fun onResume() {
        super.onResume()
        // Ordnerinhalt neu einlesen, z. B. nachdem am PC Dateien hinzugekommen sind
        sounds.refresh()
        gifs.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    /** Wird aufgerufen, wenn jemand Dateien aus einer anderen App an uns "teilt". */
    private fun handleIncomingShare(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // Audio landet bei den Sounds, GIFs und Videos bei den GIFs
        uris.groupBy { MediaKind.forMime(contentResolver.getType(it) ?: intent.type) }
            .forEach { (kind, kindUris) ->
                if (kind == null) return@forEach
                boardFor(kind).importAll(kindUris)
                screen = SCREEN_BOARDS
                switchTo(kind)
            }
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val KEY_SCREEN = "screen"
        const val SCREEN_BOARDS = "boards"
        const val SCREEN_SEQUENCES = "sequences"
        const val EDITOR_PREFIX = "editor:"
    }
}

@Composable
fun SoundboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        // Ab Android 12: Farben passend zum Hintergrundbild ("Material You")
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
