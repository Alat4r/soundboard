package de.thomas.soundboard

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class Phase { VIDEO, REVEAL, DONE }

/** Merkt sich die View, ohne bei jeder Änderung neu zu zeichnen. */
private class ViewRef {
    var view: IntroMediaView? = null
}

/**
 * Startanimation: Erst läuft das Video der [sequence] einmal durch, dann öffnet sich
 * [content] mit dem gewählten Effekt, ausgehend vom Startpunkt. Antippen überspringt das Video.
 * Ohne Sequenz wird [content] direkt gezeigt.
 */
@Composable
fun StartAnimation(
    sequence: StartSequence?,
    videoUri: Uri?,
    onFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // rememberSaveable: nach einer Bildschirmdrehung nicht wieder von vorn anfangen
    var savedPhase by rememberSaveable { mutableStateOf(if (sequence != null) Phase.VIDEO else Phase.DONE) }
    val phase = if (sequence == null || videoUri == null) Phase.DONE else savedPhase
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val progress = remember { Animatable(0f) }
    val viewRef = remember { ViewRef() }
    val currentOnFinished by rememberUpdatedState(onFinished)

    // Letztes Videobild festhalten: es bleibt stehen, während sich die App öffnet
    val startReveal = {
        if (savedPhase == Phase.VIDEO) {
            snapshot = viewRef.view?.snapshot()?.asImageBitmap()
            savedPhase = Phase.REVEAL
        }
    }

    LaunchedEffect(phase) {
        when (phase) {
            // Sicherheitsnetz, falls das Video aus irgendeinem Grund nie fertig meldet
            Phase.VIDEO -> {
                delay(15_000)
                startReveal()
            }
            Phase.REVEAL -> {
                progress.snapTo(0f)
                val duration = if (sequence?.style?.isCurtain == true) 900 else 750
                progress.animateTo(1f, tween(durationMillis = duration, easing = FastOutSlowInEasing))
                savedPhase = Phase.DONE
            }
            Phase.DONE -> currentOnFinished()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val style = sequence?.style ?: RevealStyle.CIRCLE
        val px = sequence?.pointX ?: 0.5f
        val py = sequence?.pointY ?: 0.5f

        // Bei Formen und Zoom liegt das letzte Videobild hinter der App
        if (phase == Phase.REVEAL && !style.isCurtain) SnapshotLayer(snapshot)

        Box(
            Modifier
                .fillMaxSize()
                .then(if (phase == Phase.REVEAL) revealModifier(style, progress.value, px, py) else Modifier),
        ) {
            content()
        }

        // Beim Vorhang liegt es davor und gleitet auseinander
        if (phase == Phase.REVEAL && style.isCurtain) CurtainLayer(snapshot, style, progress.value, px, py)

        if (phase == Phase.VIDEO && sequence != null && videoUri != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        startReveal()
                    },
            ) {
                AndroidView(
                    factory = {
                        IntroMediaView(it).apply {
                            looping = false
                            autoPlay = true
                            this.onFinished = { startReveal() }
                            viewRef.view = this
                        }
                    },
                    update = {
                        it.framing = sequence.framing
                        it.startMs = sequence.startMs
                        it.endMs = sequence.revealAtMs
                        it.setSource(videoUri, sequence.isGif)
                    },
                    onRelease = {
                        it.release()
                        viewRef.view = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun revealModifier(style: RevealStyle, progress: Float, px: Float, py: Float): Modifier = when {
    style.isCurtain -> Modifier
    style == RevealStyle.ZOOM -> Modifier.graphicsLayer {
        scaleX = progress
        scaleY = progress
        alpha = progress
        transformOrigin = TransformOrigin(px, py)
    }
    else -> Modifier.clip(RevealShape(style, progress, px, py))
}

@Composable
private fun SnapshotLayer(snapshot: ImageBitmap?) {
    if (snapshot == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        Image(snapshot, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** Das letzte Videobild teilt sich am Startpunkt und gleitet zu beiden Seiten weg. */
@Composable
private fun CurtainLayer(snapshot: ImageBitmap?, style: RevealStyle, progress: Float, px: Float, py: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val horizontal = style == RevealStyle.CURTAIN_HORIZONTAL
        val w = size.width
        val h = size.height
        val split = if (horizontal) w * px else h * py
        val total = if (horizontal) w else h
        // erste Hälfte wandert nach links/oben, zweite nach rechts/unten
        val firstShift = -split * progress
        val secondShift = (total - split) * progress

        if (snapshot == null) {
            if (horizontal) {
                drawRect(Color.Black, Offset(firstShift, 0f), Size(split, h))
                drawRect(Color.Black, Offset(split + secondShift, 0f), Size(w - split, h))
            } else {
                drawRect(Color.Black, Offset(0f, firstShift), Size(w, split))
                drawRect(Color.Black, Offset(0f, split + secondShift), Size(w, h - split))
            }
            return@Canvas
        }

        // Bild-Pixel ↔ Bildschirm-Pixel (meist gleich groß)
        val sx = snapshot.width / w
        val sy = snapshot.height / h
        fun part(srcX: Float, srcY: Float, partW: Float, partH: Float, dstX: Float, dstY: Float) {
            if (partW <= 0f || partH <= 0f) return
            drawImage(
                snapshot,
                srcOffset = IntOffset((srcX * sx).roundToInt(), (srcY * sy).roundToInt()),
                srcSize = IntSize((partW * sx).roundToInt(), (partH * sy).roundToInt()),
                dstOffset = IntOffset(dstX.roundToInt(), dstY.roundToInt()),
                dstSize = IntSize(partW.roundToInt(), partH.roundToInt()),
            )
        }
        if (horizontal) {
            part(0f, 0f, split, h, firstShift, 0f)
            part(split, 0f, w - split, h, split + secondShift, 0f)
        } else {
            part(0f, 0f, w, split, 0f, firstShift)
            part(0f, split, w, h - split, 0f, split + secondShift)
        }
    }
}

/** Wachsende Form um den Startpunkt; [progress] 0 = unsichtbar, 1 = deckt den ganzen Bildschirm ab. */
private class RevealShape(
    private val style: RevealStyle,
    private val progress: Float,
    private val px: Float,
    private val py: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = Offset(size.width * px, size.height * py)
        // Abstand zur am weitesten entfernten Ecke
        val farthest = hypot(maxOf(c.x, size.width - c.x), maxOf(c.y, size.height - c.y))
        // Wie groß die Form werden muss, damit sie auch die Ecken abdeckt
        val cover = when (style) {
            RevealStyle.DIAMOND -> 1.42f
            RevealStyle.STAR -> 2.8f
            RevealStyle.HEART -> 2.3f
            else -> 1f
        }
        val r = farthest * cover * progress
        fun pt(x: Float, y: Float) = Offset(c.x + x * r, c.y + y * r)

        val path = Path()
        when (style) {
            RevealStyle.SQUARE -> path.addRect(Rect(c, r))
            RevealStyle.DIAMOND -> path.polygon(listOf(pt(0f, -1f), pt(1f, 0f), pt(0f, 1f), pt(-1f, 0f)))
            RevealStyle.STAR -> path.polygon(
                (0 until 10).map { i ->
                    val angle = -PI / 2 + i * PI / 5
                    val radius = if (i % 2 == 0) 1f else 0.45f
                    pt((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
                },
            )
            RevealStyle.HEART -> {
                fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                    val a = pt(x1, y1)
                    val b = pt(x2, y2)
                    val e = pt(x3, y3)
                    path.cubicTo(a.x, a.y, b.x, b.y, e.x, e.y)
                }
                val tip = pt(0f, 0.9f)
                path.moveTo(tip.x, tip.y)
                cubic(-0.2f, 0.7f, -1f, 0.2f, -1f, -0.3f)
                cubic(-1f, -0.75f, -0.6f, -1f, -0.4f, -1f)
                cubic(-0.15f, -1f, 0f, -0.8f, 0f, -0.55f)
                cubic(0f, -0.8f, 0.15f, -1f, 0.4f, -1f)
                cubic(0.6f, -1f, 1f, -0.75f, 1f, -0.3f)
                cubic(1f, 0.2f, 0.2f, 0.7f, 0f, 0.9f)
                path.close()
            }
            else -> path.addOval(Rect(c, r))
        }
        return Outline.Generic(path)
    }

    private fun Path.polygon(points: List<Offset>) {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
}
