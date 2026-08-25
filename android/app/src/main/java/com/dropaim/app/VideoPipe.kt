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
    /** The URL and transport currently playing, or the one being tried. */
    @Volatile var activeUrl = ""
    /** Why the last attempt failed — surfaced in the NO VIDEO panel, because
     *  the operator in the field has no logcat. */
    @Volatile var lastError = ""
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
    @Volatile private var playing = false
    /** Did the CURRENT candidate ever deliver? A source that worked and then
     *  dropped should be retried as-is, not abandoned for the next guess. */
    @Volatile private var everPlayed = false
    private var attemptIdx = 0
    /** Completed full sweeps of every candidate, used to back off retries. */
    private var sweeps = 0

    /** One thing to try: a URL over a specific RTP transport. */
    private data class Attempt(val url: String, val tcp: Boolean) {
        override fun toString() = "$url [${if (tcp) "TCP" else "UDP"}]"
    }

    /**
     * Every URL over interleaved TCP first, then every URL over UDP. TCP is
     * tried first because it traverses the datalink more reliably, but some
     * cameras only ever implement UDP — pinning the transport was enough on its
     * own to make a perfectly good camera look dead.
     */
    private val attempts: List<Attempt> by lazy {
        Config.rtspUrls.map { Attempt(it, true) } + Config.rtspUrls.map { Attempt(it, false) }
    }

    /** Must be called on the main thread; [tv] has to be attached to the view tree. */
    fun start(tv: TextureView) {
        if (running) return
        running = true
        textureView = tv
        // Diagnostics must never compete with the player for the camera. This
        // firmware announces itself as "rtsp_demo" — the vendor sample RTSP
        // server — and those commonly serve one client at a time, so probing
        // while ExoPlayer is negotiating can be what breaks the negotiation.
        // Wait, and only probe if there is still no picture by then.
        handler.postDelayed({
            if (!running || FrameBus.connected) return@postDelayed
            Thread {
                NetDiag.logNetworks(ctx)
                NetDiag.hostPort(Config.rtspUrls.firstOrNull() ?: "")?.let {
                    NetDiag.scanPorts(it.first)
                    if (Config.RTSP_PATH_SWEEP) RtspProbe.sweep(it.first)
                    else RtspProbe.check(Config.rtspUrls)
                }
            }.start()
        }, DIAG_DELAY_MS)
        openPlayer()
        handler.postDelayed(grabber, GRAB_MS)
    }

    /**
     * Preflight the candidate off the main thread, then build the player on it.
     * The TCP probe blocks for up to two seconds, which is an ANR if run inline.
     */
    private fun openPlayer() {
        if (attempts.isEmpty()) { Log.e(TAG, "no RTSP URLs configured"); return }
        val a = attempts[attemptIdx % attempts.size]
        FrameBus.activeUrl = a.toString()

        Thread {
            // Can we even open a TCP socket to the camera? This separates
            // "unreachable host" (wrong IP, GCS not on the camera's network,
            // traffic leaving via mobile data) from "reachable but RTSP refused"
            // (wrong path, auth, codec) — two faults with completely different
            // fixes that otherwise look identical to the operator.
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
                // The camera answers our own raw DESCRIBE with 200 but gives
                // media3 406 for the identical URL, so it is discriminating on
                // the request headers. Match the User-Agent that works, and log
                // the conversation so the remaining difference is visible.
                .setUserAgent(Config.RTSP_USER_AGENT)
                .setDebugLoggingEnabled(Config.RTSP_DEBUG_LOG)
                .createMediaSource(MediaItem.fromUri(a.url))

            val p = ExoPlayer.Builder(ctx).build().apply {
                setVideoTextureView(textureView)
                setMediaSource(src)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Name the URL as well as the fault — with a list of
                        // candidates, "player error" alone says nothing about
                        // which one failed or why.
                        val why = "${error.errorCodeName}: ${error.message}"
                        Log.e(TAG, "FAILED $a — $why")
                        FrameBus.lastError = "$a — $why"
                        FrameBus.connected = false
                        playing = false
                        // Move on to the next candidate rather than hammering
                        // the same dead URL forever — unless this one HAD been
                        // delivering, in which case retry it rather than
                        // wandering off a source we know is good.
                        if (!everPlayed) attemptIdx++
                        if (running) handler.postDelayed({ if (running) openPlayer() }, nextDelay())
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                        if (isPlaying) {
                            everPlayed = true
                            sweeps = 0            // healthy again: retry fast if it drops
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

    /**
     * Back off once a whole sweep has failed. Retrying every 1.5 s forever tore
     * down and rebuilt an ExoPlayer twice a second for as long as the app was
     * open — pointless load on the GCS when the camera plainly is not going to
     * answer. Each completed sweep doubles the wait to a 30 s ceiling; the first
     * sweep still runs at full speed so a working camera is found quickly.
     */
    private fun nextDelay(): Long {
        if (attemptIdx > 0 && attemptIdx % attempts.size == 0) {
            sweeps++
            Log.w(TAG, "sweep $sweeps failed on all ${attempts.size} candidates — backing off")
        }
        return minOf(RETRY_MS shl minOf(sweeps, 5), MAX_RETRY_MS)
    }

    /** Pulls the newest frame off the TextureView and republishes it as JPEG. */
    private val grabber = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val tv = textureView
                // Gate on the player actually playing. An attached TextureView
                // hands back a (blank) bitmap even when nothing is decoding into
                // it, so grabbing successfully is NOT evidence of a live feed —
                // publishing on that basis reported a healthy camera when there
                // was no camera at all.
                if (playing && tv != null && tv.isAvailable) {
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
        playing = false
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        FrameBus.connected = false
    }

    companion object {
        private const val TAG = "VideoPipe"
        private const val JPEG_QUALITY = 70
        private const val TIMEOUT_MS = 6000   // per candidate, before moving on
        private const val RETRY_MS = 1500L      // pause between candidates
        private const val MAX_RETRY_MS = 30000L // ceiling once sweeps keep failing
        private const val DIAG_DELAY_MS = 25000L // probe only after the player has had its go
        private val GRAB_MS = (1000L / Config.VIDEO_FPS)
    }
}
