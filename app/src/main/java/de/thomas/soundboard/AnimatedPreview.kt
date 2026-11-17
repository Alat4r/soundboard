package de.thomas.soundboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stumme Endlosschleife eines GIFs oder Videos.
 * [crop] = true füllt die Fläche (Ränder werden abgeschnitten), false zeigt alles (mit Rand).
 */
@Composable
fun AnimatedPreview(entry: Entry, crop: Boolean, modifier: Modifier = Modifier) {
    if (entry.isGif) GifPreview(entry.uri, crop, modifier) else VideoPreview(entry.uri, crop, modifier)
}

@Composable
private fun GifPreview(uri: Uri, crop: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    // Kacheln brauchen nur eine kleine Version, die Großansicht eine größere
    val maxSize = if (crop) 400 else 1080
    val drawable by produceState<Drawable?>(null, uri, maxSize) {
        value = withContext(Dispatchers.IO) { runCatching { loadAnimated(context, uri, maxSize) }.getOrNull() }
    }
    val d = drawable ?: return
    AndroidView(
        factory = { LoopingGifView(it) },
        update = {
            it.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            it.setAnimated(d)
        },
        modifier = modifier,
    )
}

@Composable
private fun VideoPreview(uri: Uri, crop: Boolean, modifier: Modifier) {
    AndroidView(
        factory = { LoopingVideoView(it) },
        update = {
            it.crop = crop
            it.setVideo(uri)
        },
        onRelease = { it.releasePlayer() },
        modifier = modifier,
    )
}

/** Lädt ein GIF als animiertes Drawable (ab Android 9), sonst nur das erste Bild. */
private fun loadAnimated(context: Context, uri: Uri, maxSize: Int): Drawable {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = maxSize.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        if (drawable is AnimatedImageDrawable) drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        return drawable
    }
    return BitmapDrawable(context.resources, decodeScaled(context, uri, maxSize))
}

/** ImageView, das sein GIF nur abspielt, solange die App sichtbar ist. */
@SuppressLint("ViewConstructor")
private class LoopingGifView(context: Context) : ImageView(context), DefaultLifecycleObserver {

    private var lifecycle: Lifecycle? = null
    private val animation: Animatable? get() = drawable as? Animatable

    fun setAnimated(d: Drawable) {
        if (drawable === d) return
        animation?.stop()
        setImageDrawable(d)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) animation?.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // addObserver ruft onStart sofort auf, wenn die App gerade sichtbar ist
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(this) }
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(this)
        lifecycle = null
        animation?.stop()
        super.onDetachedFromWindow()
    }

    override fun onStart(owner: LifecycleOwner) {
        animation?.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        animation?.stop()
    }
}

/**
 * Spielt ein Video stumm ab, normalerweise in Endlosschleife.
 * Der MediaPlayer lebt nur, solange die Ansicht sichtbar ist, und pausiert im Hintergrund.
 */
@SuppressLint("ViewConstructor")
internal class LoopingVideoView(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener, DefaultLifecycleObserver {

    var crop = true
        set(value) {
            field = value
            updateTransform()
        }

    /** false = nur einmal abspielen und danach [onFinished] aufrufen. */
    var looping = true

    /** Wird aufgerufen, wenn ein nicht loopendes Video zu Ende ist (oder nicht abspielbar war). */
    var onFinished: (() -> Unit)? = null

    private var uri: Uri? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var lifecycle: Lifecycle? = null
    private val visibleToUser get() = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setVideo(newUri: Uri) {
        if (newUri == uri) return
        uri = newUri
        releasePlayer()
        if (isAvailable) open()
    }

    private fun open() {
        val videoUri = uri ?: return
        val texture = surfaceTexture ?: return
        val s = Surface(texture)
        surface = s
        prepared = false
        val mp = MediaPlayer()
        player = mp
        mp.setSurface(s)
        mp.setVolume(0f, 0f)
        mp.isLooping = looping
        mp.setOnCompletionListener { onFinished?.invoke() }
        mp.setOnVideoSizeChangedListener { _, w, h ->
            videoWidth = w
            videoHeight = h
            updateTransform()
        }
        mp.setOnPreparedListener {
            prepared = true
            if (visibleToUser) it.start()
        }
        // kaputte Datei: Kachel bleibt einfach leer, ein einmaliges Video gilt als beendet
        mp.setOnErrorListener { _, _, _ ->
            onFinished?.invoke()
            true
        }
        runCatching {
            mp.setDataSource(context, videoUri)
            mp.prepareAsync()
        }.onFailure { onFinished?.invoke() }
    }

    fun releasePlayer() {
        player?.release()
        player = null
        prepared = false
        surface?.release()
        surface = null
    }

    /** Skaliert das Bild passend, statt es auf die Kachelgröße zu verzerren. */
    private fun updateTransform() {
        if (videoWidth == 0 || videoHeight == 0 || width == 0 || height == 0) return
        val viewRatio = width.toFloat() / height
        val videoRatio = videoWidth.toFloat() / videoHeight
        var sx = 1f
        var sy = 1f
        if (crop) {
            if (videoRatio > viewRatio) sx = videoRatio / viewRatio else sy = viewRatio / videoRatio
        } else {
            if (videoRatio > viewRatio) sy = viewRatio / videoRatio else sx = videoRatio / viewRatio
        }
        setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(this) }
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(this)
        lifecycle = null
        super.onDetachedFromWindow()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (prepared) player?.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (prepared) player?.pause()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = open()

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateTransform()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
