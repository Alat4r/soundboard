package de.thomas.soundboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlin.math.hypot

private enum class Phase { VIDEO, REVEAL, DONE }

/**
 * Startanimation: Erst läuft das Intro-Video einmal durch, dann öffnet sich die App
 * in einem wachsenden Kreis, ausgehend von der Bildschirmmitte auf 1/3 der Höhe.
 * Antippen überspringt das Video.
 */
@Composable
fun StartAnimation(enabled: Boolean, content: @Composable () -> Unit) {
    // rememberSaveable: nach einer Bildschirmdrehung nicht wieder von vorn anfangen
    var phase by rememberSaveable { mutableStateOf(if (enabled) Phase.VIDEO else Phase.DONE) }
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(phase) {
        when (phase) {
            // Sicherheitsnetz, falls das Video aus irgendeinem Grund nie fertig meldet
            Phase.VIDEO -> {
                delay(10_000)
                phase = Phase.REVEAL
            }
            Phase.REVEAL -> {
                reveal.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
                phase = Phase.DONE
            }
            Phase.DONE -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (phase != Phase.DONE) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (phase == Phase.VIDEO) phase = Phase.REVEAL
                    },
                contentAlignment = Alignment.Center,
            ) {
                IntroVideo(
                    onFinished = { if (phase == Phase.VIDEO) phase = Phase.REVEAL },
                    // Seitenverhältnis des Videos (480 x 488)
                    modifier = Modifier.fillMaxWidth().aspectRatio(480f / 488f),
                )
            }
        }
        // Die App liegt darüber und wird nur innerhalb des Kreises gezeigt.
        // Sie lädt währenddessen schon ihre Ordner, sodass nach dem Öffnen alles da ist.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (phase == Phase.DONE) Modifier else Modifier.clip(CircleRevealShape(reveal.value))),
        ) {
            content()
        }
    }
}

@Composable
private fun IntroVideo(onFinished: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val currentOnFinished by rememberUpdatedState(onFinished)
    val uri = remember { "android.resource://${context.packageName}/${R.raw.startgif_1}".toUri() }
    AndroidView(
        factory = {
            LoopingVideoView(it).apply {
                looping = false
                crop = false
                this.onFinished = { currentOnFinished() }
            }
        },
        update = { it.setVideo(uri) },
        onRelease = { it.releasePlayer() },
        modifier = modifier,
    )
}

/** Kreis um (Mitte, 1/3 Höhe); [progress] 0 = unsichtbar, 1 = deckt den ganzen Bildschirm ab. */
private class CircleRevealShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val center = Offset(size.width / 2f, size.height / 3f)
        // bis zur am weitesten entfernten Ecke (unten links/rechts)
        val maxRadius = hypot(size.width / 2f, size.height - center.y)
        return Outline.Generic(Path().apply { addOval(Rect(center, maxRadius * progress)) })
    }
}
