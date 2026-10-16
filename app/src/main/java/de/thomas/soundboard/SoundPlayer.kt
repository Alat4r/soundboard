package de.thomas.soundboard

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

/** Spielt immer nur einen Sound gleichzeitig ab. */
class SoundPlayer {

    private var player: MediaPlayer? = null

    fun play(context: Context, uri: Uri, onFinished: () -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(context, uri)
            mp.setOnCompletionListener {
                stop()
                onFinished()
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        player?.release()
        player = null
    }
}
