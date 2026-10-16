package de.thomas.soundboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache

/**
 * Lädt ein Bild verkleinert, sodass die längere Seite höchstens [maxSize] Pixel hat.
 * Volle Kamerafotos hätten sonst schnell 50 MB im Speicher.
 */
fun decodeScaled(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder berücksichtigt auch die Drehung aus den Kamera-Metadaten (EXIF)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = maxSize.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Bild nicht lesbar")
}

/** Zwischenspeicher für die Kachelbilder, damit beim Scrollen nicht ständig neu geladen wird. */
object CoverCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Der Schlüssel enthält die Änderungszeit: ein neues Bild unter gleichem Namen wird neu geladen. */
    fun key(entry: Entry) = "${entry.cover}#${entry.coverStamp}"

    fun get(entry: Entry): Bitmap? = cache.get(key(entry))

    fun load(context: Context, entry: Entry): Bitmap? {
        val uri = entry.cover ?: return null
        get(entry)?.let { return it }
        return runCatching { decodeScaled(context, uri, 512) }.getOrNull()
            ?.also { cache.put(key(entry), it) }
    }
}
