package com.dropaim.app

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.FileInputStream
import kotlin.concurrent.thread

/** Latest decoded JPEG frame + link state, shared with the /stream handler. */
object FrameBus {
    @Volatile var latest: ByteArray? = null
    @Volatile var connected = false
}

/**
 * RTSP -> MJPEG using the exact ffmpeg pipeline the Node server used, via
 * FFmpegKit. ffmpeg writes JPEGs into a pipe; we split them on SOI/EOI markers
 * (same logic as server.js) and publish the newest frame to FrameBus.
 */
class VideoPipe(private val ctx: Context) {
    @Volatile private var running = false
    private var pipePath: String? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "video") { runLoop() }
    }

    fun stop() { running = false; FrameBus.connected = false }

    private fun runLoop() {
        while (running) {
            try {
                val pipe = FFmpegKitConfig.registerNewFFmpegPipe(ctx)
                pipePath = pipe
                val cmd = buildString {
                    append("-hide_banner -loglevel error ")
                    append("-fflags nobuffer -flags low_delay -probesize 32 -analyzeduration 0 ")
                    append("-rtsp_transport tcp -i ${Config.rtspUrl} -an ")
                    append("-f image2pipe -vcodec mjpeg -q:v ${Config.VIDEO_Q} ")
                    append("-vf fps=${Config.VIDEO_FPS},scale=${Config.VIDEO_W}:${Config.VIDEO_H} ")
                    append(pipe)
                }
                Log.i(TAG, "ffmpeg: $cmd")
                val session = FFmpegKit.executeAsync(cmd) { s ->
                    Log.i(TAG, "ffmpeg exited: ${s.returnCode}")
                    FrameBus.connected = false
                }
                // Reader: blocks until ffmpeg opens the pipe, then streams JPEGs.
                readPipe(pipe)
                session.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "video error: ${e.message}")
            }
            FrameBus.connected = false
            if (running) Thread.sleep(3000)   // retry, like the Node server
        }
    }

    private fun readPipe(pipe: String) {
        val input = FileInputStream(pipe)
        val chunk = ByteArray(64 * 1024)
        var buf = ByteArray(0)
        try {
            while (running) {
                val r = input.read(chunk)
                if (r < 0) break
                FrameBus.connected = true
                buf = buf + chunk.copyOfRange(0, r)
                // Drain every complete JPEG, keep only the newest (avoids backlog).
                var latest: ByteArray? = null
                var from = 0
                while (true) {
                    val s = indexOf(buf, 0xFF, 0xD8, from)
                    if (s < 0) break
                    val e = indexOf(buf, 0xFF, 0xD9, s + 2)
                    if (e < 0) break
                    latest = buf.copyOfRange(s, e + 2)
                    from = e + 2
                }
                if (latest != null) {
                    buf = buf.copyOfRange(from, buf.size)
                    FrameBus.latest = latest
                }
                if (buf.size > 5_000_000) buf = ByteArray(0)
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }

    private fun indexOf(a: ByteArray, b0: Int, b1: Int, from: Int): Int {
        var i = maxOf(0, from)
        while (i < a.size - 1) {
            if ((a[i].toInt() and 0xFF) == b0 && (a[i + 1].toInt() and 0xFF) == b1) return i
            i++
        }
        return -1
    }

    companion object { private const val TAG = "VideoPipe" }
}
