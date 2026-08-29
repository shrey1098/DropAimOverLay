

package androidx.media3.exoplayer.rtsp

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import javax.net.SocketFactory

class RtspMediaSource private constructor() : MediaSource {
    class Factory {
        fun setForceUseRtpTcp(forceUseRtpTcp: Boolean): Factory = this
        fun setUserAgent(userAgent: String): Factory = this
        fun setSocketFactory(socketFactory: SocketFactory): Factory = this
        fun setDebugLoggingEnabled(debugLoggingEnabled: Boolean): Factory = this
        fun setTimeoutMs(timeoutMs: Long): Factory = this
        fun createMediaSource(mediaItem: MediaItem): RtspMediaSource =
            throw UnsupportedOperationException("stub")
    }
}
