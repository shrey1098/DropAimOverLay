

package androidx.media3.common

import android.view.TextureView

class MediaItem private constructor() {
    companion object {
        @JvmStatic fun fromUri(uri: String): MediaItem = MediaItem()
    }
}

class VideoSize(@JvmField val width: Int, @JvmField val height: Int)

open class PlaybackException(message: String?) : Exception(message) {
    val errorCodeName: String get() = ""
}

interface Player {
    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
    }
    fun addListener(listener: Listener)
    fun prepare()
    fun release()
    var playWhenReady: Boolean
    fun setVideoTextureView(textureView: TextureView?)
}
