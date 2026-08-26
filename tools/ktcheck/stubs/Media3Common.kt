// Minimal androidx stub for an offline typecheck of the DropAim app sources.
// The container cannot reach dl.google.com (Google Maven), so the real AARs are
// unavailable. Signatures were transcribed from the actual upstream source at the
// version the app builds against (media3 1.4.1, github.com/androidx/media tag
// 1.4.1), so a type error against these stubs is a type error against the real
// library. Only the members the app actually uses are declared.

package androidx.media3.common

import android.view.TextureView

/** MediaItem.java: `public static MediaItem fromUri(String uri)` */
class MediaItem private constructor() {
    companion object {
        @JvmStatic fun fromUri(uri: String): MediaItem = MediaItem()
    }
}

/** VideoSize.java: `public final int width; public final int height;` */
class VideoSize(@JvmField val width: Int, @JvmField val height: Int)

/** PlaybackException.java: `public final String getErrorCodeName()`, extends Exception */
open class PlaybackException(message: String?) : Exception(message) {
    val errorCodeName: String get() = ""
}

/** Player.java — the three Listener callbacks VideoPipe overrides, with the
 *  exact parameter types of the `default void` methods upstream. */
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
