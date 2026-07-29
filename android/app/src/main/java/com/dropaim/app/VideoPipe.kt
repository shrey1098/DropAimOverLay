package com.dropaim.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.ByteArrayOutputStream

/** Latest decoded JPEG frame + link state, shared with the /stream handler. */
object FrameBus {
    @Volatile var latest: ByteArray? = null
    @Volatile var connected = false
}

/**
 * RTSP video via Media3/ExoPlayer.
 *
 * ExoPlayer decodes the camera stream straight to a TextureView (hardware path,
 * no ffmpeg). A grab loop then pulls frames off that TextureView, encodes them
 * as JPEG and publishes them to FrameBus, so the WebView can display the feed
 * AND read its pixels — which is what the Lucas-Kanade target tracker needs.
 *
 * The JPEG round-trip is the cost of keeping the in-WebView tracker. The planned
 * native-video milestone removes it (native SurfaceView + OpenCV tracker).
 */
@OptIn(UnstableApi::class)
class VideoPipe(private val ctx: Context) {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    /** Must be called on the main thread; [tv] has to be attached to the view tree. */
    fun start(tv: TextureView) {
        if (running) return
        running = true
        textureView = tv
        openPlayer()
        handler.postDelayed(grabber, GRAB_MS)
    }

    private fun openPlayer() {
        try {
            player?.release()
            val src = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)          // matches the old -rtsp_transport tcp
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Config.rtspUrl))

            val p = ExoPlayer.Builder(ctx).build().apply {
                setVideoTextureView(textureView)
                setMediaSource(src)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "player error: ${error.errorCodeName} ${error.message}")
                        FrameBus.connected = false
                        // Retry, like the old ffmpeg supervisor did.
                        if (running) handler.postDelayed({ if (running) openPlayer() }, 3000)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.i(TAG, "isPlaying=$isPlaying")
                    }
                })
                prepare()
                playWhenReady = true
            }
            player = p
            Log.i(TAG, "RTSP opening ${Config.rtspUrl}")
        } catch (e: Exception) {
            Log.e(TAG, "openPlayer failed: ${e.message}")
            if (running) handler.postDelayed({ if (running) openPlayer() }, 3000)
        }
    }

    /** Pulls the newest frame off the TextureView and republishes it as JPEG. */
    private val grabber = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val tv = textureView
                if (tv != null && tv.isAvailable) {
                    // Scale down to the working resolution while grabbing.
                    val bmp: Bitmap? = tv.getBitmap(Config.VIDEO_W, Config.VIDEO_H)
                    if (bmp != null) {
                        val bos = ByteArrayOutputStream(64 * 1024)
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                        FrameBus.latest = bos.toByteArray()
                        FrameBus.connected = true
                        bmp.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "grab failed: ${e.message}")
            }
            handler.postDelayed(this, GRAB_MS)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        FrameBus.connected = false
    }

    companion object {
        private const val TAG = "VideoPipe"
        private const val JPEG_QUALITY = 70
        private val GRAB_MS = (1000L / Config.VIDEO_FPS)
    }
}
