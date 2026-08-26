// Minimal androidx stub for an offline typecheck of the DropAim app sources.
// The container cannot reach dl.google.com (Google Maven), so the real AARs are
// unavailable. Signatures were transcribed from the actual upstream source at the
// version the app builds against (media3 1.4.1, github.com/androidx/media tag
// 1.4.1), so a type error against these stubs is a type error against the real
// library. Only the members the app actually uses are declared.

package androidx.media3.exoplayer

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.MediaSource

interface ExoPlayer : Player {
    fun setMediaSource(mediaSource: MediaSource)
    /** ExoPlayer.java: `public Builder(Context context)` */
    class Builder(context: Context) {
        fun build(): ExoPlayer = throw UnsupportedOperationException("stub")
    }
}
