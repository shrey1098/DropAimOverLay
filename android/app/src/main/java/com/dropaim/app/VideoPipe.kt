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

object FrameBus {
    @Volatile var latest: ByteArray? = null
    @Volatile var connected = false

    @Volatile var activeUrl = ""

    @Volatile var activeCamera = ""

    @Volatile var availableCameras: Set<String> = emptySet()

    @Volatile var videoW = 0
    @Volatile var videoH = 0

    @Volatile var variant = ""

    @Volatile var lastError = ""
}

@OptIn(UnstableApi::class)
class VideoPipe(private val ctx: Context) {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var playing = false

    @Volatile private var everPlayed = false
    private var attemptIdx = 0

    private var sweeps = 0

    private data class Attempt(val url: String, val tcp: Boolean) {
        override fun toString() = "$url [${if (tcp) "TCP" else "UDP"}]"
    }

    @Volatile private var cameraIdx = 0

    private val attempts: List<Attempt>
        get() {
            val cam = Settings.cameras.getOrNull(cameraIdx) ?: return emptyList()
            return listOf(Attempt(cam.url, true), Attempt(cam.url, false))
        }

    fun selectCamera(index: Int): Boolean {
        val cam = Settings.cameras.getOrNull(index) ?: return false
        if (index == cameraIdx && playing) return true
        Log.i(TAG, "switching to ${cam.label} (${cam.url})")
        handler.post {
            cameraIdx = index
            attemptIdx = 0
            sweeps = 0
            FrameBus.connected = false
            FrameBus.latest = null
            FrameBus.activeCamera = cam.id

            FrameBus.videoW = 0; FrameBus.videoH = 0; FrameBus.variant = ""
            if (running) openPlayer()
        }
        return true
    }

    private fun detectCameras() {
        val cams = Settings.cameras
        val present = cams.filter { RtspProbe.describes(it.url) }.map { it.id }.toSet()
        FrameBus.availableCameras = present
        Log.i(TAG, "cameras present: ${if (present.isEmpty()) "(none answered)" else present.joinToString(", ")}")

        val cur = cams.getOrNull(cameraIdx)
        if (cur != null && present.isNotEmpty() && cur.id !in present) {
            val i = cams.indexOfFirst { it.id in present }
            if (i >= 0) { Log.i(TAG, "${cur.label} absent — selecting ${cams[i].label}"); selectCamera(i) }
        }
    }

    fun reload() {
        Log.i(TAG, "reloading video with current settings")
        handler.post {
            attemptIdx = 0
            sweeps = 0
            FrameBus.connected = false
            FrameBus.latest = null
            FrameBus.availableCameras = emptySet()
            FrameBus.videoW = 0; FrameBus.videoH = 0; FrameBus.variant = ""
            FrameBus.lastError = ""
            if (running) {
                openPlayer()

                handler.postDelayed({ if (running) Thread { detectCameras() }.start() }, DIAG_DELAY_MS)
            }
        }
    }

    fun start(tv: TextureView) {
        if (running) return
        running = true
        textureView = tv

        handler.postDelayed({
            if (!running) return@postDelayed
            Thread {

                detectCameras()
                if (FrameBus.connected) return@Thread
                NetDiag.logNetworks(ctx)
                NetDiag.hostPort(Settings.cameras.firstOrNull()?.url ?: "")?.let {
                    NetDiag.scanPorts(it.first)
                    if (Config.RTSP_PATH_SWEEP) RtspProbe.sweep(it.first)
                    else RtspProbe.check(Settings.cameras.map { c -> c.url })
                }
            }.start()
        }, DIAG_DELAY_MS)
        openPlayer()
        handler.postDelayed(grabber, GRAB_MS)
    }

    private fun openPlayer() {
        if (attempts.isEmpty()) { Log.e(TAG, "no RTSP URLs configured"); return }
        val a = attempts[attemptIdx % attempts.size]
        FrameBus.activeUrl = a.toString()

        Thread {

            val hp = NetDiag.hostPort(a.url)
            val reachable = hp == null || NetDiag.reachable(hp.first, hp.second)
            handler.post {
                if (!running) return@post
                if (!reachable) {
                    val msg = "Cannot reach ${hp!!.first}:${hp.second} — nothing is " +
                              "answering there. Check the GCS holds an address on the " +
                              "camera's subnet."
                    Log.e(TAG, "UNREACHABLE $a — $msg")
                    FrameBus.lastError = msg
                    FrameBus.connected = false
                    attemptIdx++
                    handler.postDelayed({ if (running) openPlayer() }, nextDelay())
                } else {
                    if (hp != null) Log.i(TAG, "reachable ${hp.first}:${hp.second} — negotiating RTSP")
                    buildPlayer(a)
                }
            }
        }.start()
    }

    private fun buildPlayer(a: Attempt) {
        try {
            player?.release()
            playing = false
            everPlayed = false
            val src = RtspMediaSource.Factory()
                .setForceUseRtpTcp(a.tcp)
                .setTimeoutMs(TIMEOUT_MS)

                .setUserAgent(Config.RTSP_USER_AGENT)
                .setDebugLoggingEnabled(Config.RTSP_DEBUG_LOG)

                .let { if (Config.RTSP_ADD_ACCEPT) it.setSocketFactory(RtspAcceptFixSocketFactory()) else it }
                .createMediaSource(MediaItem.fromUri(a.url))

            val p = ExoPlayer.Builder(ctx).build().apply {
                setVideoTextureView(textureView)
                setMediaSource(src)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {

                        val why = "${error.errorCodeName}: ${error.message}"
                        Log.e(TAG, "FAILED $a — $why")
                        FrameBus.lastError = "$a — $why"
                        FrameBus.connected = false
                        playing = false

                        if (!everPlayed) attemptIdx++
                        if (running) handler.postDelayed({ if (running) openPlayer() }, nextDelay())
                    }

                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                        if (size.width <= 0 || size.height <= 0) return
                        FrameBus.videoW = size.width
                        FrameBus.videoH = size.height
                        val cam = Settings.cameras.getOrNull(cameraIdx)
                        val v = cam?.variantFor(size.width, size.height)
                        FrameBus.variant = v?.model ?: ""
                        Log.i(TAG, "video ${size.width}x${size.height}" +
                                   (v?.let { " -> ${cam?.label} ${it.model}, aim zoom ${it.zoom}" +
                                             if (!it.calibrated) " (UNCALIBRATED)" else "" }
                                    ?: if (cam?.variants?.isNotEmpty() == true)
                                           " -> unrecognised ${cam.label} variant, using default zoom ${cam.zoom}"
                                       else ""))
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                        if (isPlaying) {
                            everPlayed = true
                            sweeps = 0
                            Log.i(TAG, "PLAYING $a")
                            FrameBus.lastError = ""
                        } else {
                            Log.i(TAG, "not playing $a")
                            FrameBus.connected = false
                        }
                    }
                })
                prepare()
                playWhenReady = true
            }
            player = p
            FrameBus.activeUrl = a.toString()
            Log.i(TAG, "RTSP trying $a (candidate ${attemptIdx % attempts.size + 1}/${attempts.size})")
        } catch (e: Exception) {
            Log.e(TAG, "openPlayer failed for $a: ${e.message}")
            FrameBus.lastError = "$a — ${e.message}"
            attemptIdx++
            if (running) handler.postDelayed({ if (running) openPlayer() }, nextDelay())
        }
    }

    private fun nextDelay(): Long {
        if (attemptIdx > 0 && attemptIdx % attempts.size == 0) {
            sweeps++
            Log.w(TAG, "sweep $sweeps failed on all ${attempts.size} candidates — backing off")
        }
        return minOf(RETRY_MS shl minOf(sweeps, 5), MAX_RETRY_MS)
    }

    private val grabber = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val tv = textureView

                if (playing && tv != null && tv.isAvailable) {

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
        playing = false
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        FrameBus.connected = false
    }

    companion object {
        private const val TAG = "VideoPipe"
        private const val JPEG_QUALITY = 70

        private const val TIMEOUT_MS = 6000L
        private const val RETRY_MS = 1500L
        private const val MAX_RETRY_MS = 30000L
        private const val DIAG_DELAY_MS = 25000L
        private val GRAB_MS = (1000L / Config.VIDEO_FPS)
    }
}
