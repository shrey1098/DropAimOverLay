

package androidx.media3.exoplayer

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.MediaSource

interface ExoPlayer : Player {
    fun setMediaSource(mediaSource: MediaSource)

    class Builder(context: Context) {
        fun build(): ExoPlayer = throw UnsupportedOperationException("stub")
    }
}
