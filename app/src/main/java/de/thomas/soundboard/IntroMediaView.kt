package de.thomas.soundboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.drawToBitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * Spielt das Video (MP4/WebM) oder GIF einer Startsequenz stumm ab, mit frei
 * wählbarem Bildausschnitt ([framing]). Wird für die Startanimation und den Editor benutzt.
 */
@SuppressLint("ViewConstructor")
internal class IntroMediaView(context: Context) :
    FrameLayout(context), TextureView.SurfaceTextureListener, DefaultLifecycleObserver {

    private val texture = TextureView(context)
    private val image = ImageView(context)

    var framing = Framing()
        set(value) {
            field = value
            applyFraming()
        }

    /** true = Endlosschleife (Editor), false = einmal abspielen und dann [onFinished]. */
    var looping = false

    /** Gleich nach dem Laden loslegen (Startanimation) oder auf [play] warten (Editor). */
    var autoPlay = true

    var onFinished: (() -> Unit)? = null
    var onMediaSize: ((Int, Int) -> Unit)? = null

    /** Gibt die Länge in Millisekunden weiter (bei GIFs 0, die lassen sich nicht spulen). */
    var onPrepared: ((Int) -> Unit)? = null

    private var uri: Uri? = null
    private var isGif = false
    private var mediaWidth = 0
    private var mediaHeight = 0
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantsPlaying = false
    private var videoTransform = Matrix()
    private var lifecycle: Lifecycle? = null
    private val visibleToUser get() = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    val position: Int get() = if (prepared && !isGif) player?.currentPosition ?: 0 else 0

    init {
        setBackgroundColor(Color.BLACK)
        texture.surfaceTextureListener = this
        texture.isOpaque = false
        image.scaleType = ImageView.ScaleType.MATRIX
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setSource(newUri: Uri, gif: Boolean) {
        if (newUri == uri) return
        release()
        uri = newUri
        isGif = gif
        wantsPlaying = autoPlay
        texture.visibility = if (gif) GONE else VISIBLE
        image.visibility = if (gif) VISIBLE else GONE
        if (gif) loadGif(newUri) else if (texture.isAvailable) openVideo()
    }

    fun play() {
        wantsPlaying = true
        if (!visibleToUser) return
        if (isGif) (image.drawable as? Animatable)?.start() else if (prepared) player?.start()
    }

    fun pause() {
        wantsPlaying = false
        if (isGif) (image.drawable as? Animatable)?.stop() else if (prepared) player?.pause()
    }

    fun seekTo(ms: Int) {
        if (prepared && !isGif) player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    /** Das aktuell sichtbare Bild, z. B. das letzte Videobild für den Vorhang-Effekt. */
    fun snapshot(): Bitmap? = runCatching {
        if (isGif) return@runCatching drawToBitmap()
        // getBitmap() liefert das Video ohne Ausschnitt (nur auf View-Größe gestreckt),
        // daher die Verschiebung/den Zoom hier selbst anwenden
        val raw = texture.bitmap ?: return@runCatching null
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(raw, videoTransform, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }.getOrNull()

    fun release() {
        releaseVideo()
        (image.drawable as? Animatable)?.stop()
        image.setImageDrawable(null)
        uri = null
        mediaWidth = 0
        mediaHeight = 0
        prepared = false
    }

    private fun releaseVideo() {
        player?.release()
        player = null
        surface?.release()
        surface = null
        if (!isGif) prepared = false
    }

    private fun openVideo() {
        val source = uri ?: return
        val tex = texture.surfaceTexture ?: return
        val s = Surface(tex)
        surface = s
        prepared = false
        val mp = MediaPlayer()
        player = mp
        mp.setSurface(s)
        mp.setVolume(0f, 0f)
        mp.isLooping = looping
        mp.setOnVideoSizeChangedListener { _, w, h ->
            if (w > 0 && h > 0) {
                mediaWidth = w
                mediaHeight = h
                applyFraming()
                onMediaSize?.invoke(w, h)
            }
        }
        mp.setOnPreparedListener {
            prepared = true
            onPrepared?.invoke(it.duration)
            if (wantsPlaying && visibleToUser) it.start()
        }
        mp.setOnCompletionListener {
            if (!looping) {
                wantsPlaying = false
                onFinished?.invoke()
            }
        }
        mp.setOnErrorListener { _, _, _ ->
            onFinished?.invoke()
            true
        }
        runCatching {
            mp.setDataSource(context, source)
            mp.prepareAsync()
        }.onFailure { onFinished?.invoke() }
    }

    private fun loadGif(source: Uri) {
        Thread {
            val drawable = runCatching { loadAnimated(context, source, 1600) }.getOrNull()
            post { showGif(source, drawable) }
        }.start()
    }

    private fun showGif(source: Uri, drawable: Drawable?) {
        if (uri != source) return // inzwischen eine andere Datei gewählt
        if (drawable == null) {
            onFinished?.invoke()
            return
        }
        mediaWidth = drawable.intrinsicWidth
        mediaHeight = drawable.intrinsicHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = if (looping) AnimatedImageDrawable.REPEAT_INFINITE else 0
            drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(d: Drawable?) {
                    if (!looping) onFinished?.invoke()
                }
            })
        } else if (!looping) {
            // Vor Android 9 sind GIFs nur ein Standbild: kurz zeigen, dann weiter
            postDelayed({ onFinished?.invoke() }, 1500)
        }
        image.setImageDrawable(drawable)
        applyFraming()
        prepared = true
        onMediaSize?.invoke(mediaWidth, mediaHeight)
        onPrepared?.invoke(0)
        if (wantsPlaying && visibleToUser) (drawable as? Animatable)?.start()
    }

    private fun applyFraming() {
        if (width == 0 || height == 0 || mediaWidth == 0 || mediaHeight == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val p = framing.place(w, h, mediaWidth.toFloat(), mediaHeight.toFloat())
        if (isGif) {
            image.imageMatrix = Matrix().apply {
                setScale(p.scale, p.scale)
                postTranslate(p.left, p.top)
            }
        } else {
            // TextureView streckt das Video erst auf die View-Größe, daher hier zurückrechnen
            videoTransform = Matrix().apply {
                setScale(p.scale * mediaWidth / w, p.scale * mediaHeight / h)
                postTranslate(p.left, p.top)
            }
            texture.setTransform(videoTransform)
            // bei pausiertem Video kommt kein neues Bild, also selbst neu zeichnen lassen
            texture.invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFraming()
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
        if (!wantsPlaying) return
        if (isGif) (image.drawable as? Animatable)?.start() else if (prepared) player?.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (isGif) (image.drawable as? Animatable)?.stop() else if (prepared) player?.pause()
    }

    override fun onSurfaceTextureAvailable(tex: SurfaceTexture, width: Int, height: Int) {
        if (!isGif) openVideo()
    }

    override fun onSurfaceTextureSizeChanged(tex: SurfaceTexture, width: Int, height: Int) = applyFraming()

    override fun onSurfaceTextureDestroyed(tex: SurfaceTexture): Boolean {
        releaseVideo()
        return true
    }

    override fun onSurfaceTextureUpdated(tex: SurfaceTexture) = Unit
}
