// Minimal androidx stub for an offline typecheck of the DropAim app sources.
// The container cannot reach dl.google.com (Google Maven), so the real AARs are
// unavailable. Signatures were transcribed from the actual upstream source at the
// version the app builds against (media3 1.4.1, github.com/androidx/media tag
// 1.4.1), so a type error against these stubs is a type error against the real
// library. Only the members the app actually uses are declared.

package androidx.media3.exoplayer.rtsp

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import javax.net.SocketFactory

/** RtspMediaSource.java @ 1.4.1 — Factory setter signatures verbatim:
 *    setForceUseRtpTcp(boolean)  setUserAgent(String)  setSocketFactory(SocketFactory)
 *    setDebugLoggingEnabled(boolean)  setTimeoutMs(@IntRange(from = 1) long)
 *    RtspMediaSource createMediaSource(MediaItem)                                */
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
