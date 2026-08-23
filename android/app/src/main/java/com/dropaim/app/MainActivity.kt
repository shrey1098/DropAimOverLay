package com.dropaim.app

import android.annotation.SuppressLint
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Single self-contained activity: starts the MAVLink + video + embedded server,
 * then points a full-screen WebView at http://127.0.0.1:3000/ where the tested
 * DropAim web app runs unchanged. No Node, no manual start.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var videoSurface: TextureView
    private val mav = MavlinkService()
    private lateinit var video: VideoPipe
    private var server: WebServer? = null
    private var sessionStart = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // GATE: nothing starts on an unactivated device — no feed, no telemetry,
        // no targeting. Re-checked every launch against this device's fingerprint,
        // so a copied licence file does not travel to another GCS.
        if (!Licence.isActivated(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        logInputDevices()
        Metrics.log(this, "app_start")
        sessionStart = System.currentTimeMillis()
        UploadWorker.schedule(applicationContext)

        video = VideoPipe(applicationContext)

        // Bring up the native services first, then the embedded server.
        mav.start()
        try {
            server = WebServer(applicationContext, mav).also { it.start(SOCKET_TIMEOUT, false) }
            Log.i(TAG, "embedded server on :${Config.HTTP_PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "server failed: ${e.message}")
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d("WebApp", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
        }
        // ExoPlayer decodes RTSP into this TextureView. It sits *behind* the
        // WebView (which paints the feed from /stream plus the overlay), and is
        // only there so VideoPipe has a surface to grab frames from.
        videoSurface = TextureView(this)

        val root = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(videoSurface, FrameLayout.LayoutParams(lp))
        root.addView(webView, FrameLayout.LayoutParams(lp))
        setContentView(root)

        // Start video once the surface is live, then load the UI.
        video.start(videoSurface)
        // Small delay lets the server bind before the first request.
        webView.postDelayed({ webView.loadUrl("http://127.0.0.1:${Config.HTTP_PORT}/") }, 400)
    }

    // ── GCS stick input ───────────────────────────────────────────────
    // Physical sticks reach Android as joystick MotionEvents. Intercepting at
    // dispatch level (rather than onGenericMotionEvent) means the WebView cannot
    // swallow them first. Every common axis is forwarded so the web layer can
    // map whichever ones this particular controller actually moves.
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            forwardSticks(ev)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        // Hardware D-pad / shoulder buttons on the controller.
        val name = when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> "a"
            KeyEvent.KEYCODE_BUTTON_B -> "b"
            else -> null
        }
        if (name != null && ev.action == KeyEvent.ACTION_DOWN && ::webView.isInitialized) {
            js("window.__pad && window.__pad('$name')")
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun axis(ev: MotionEvent, a: Int): Float {
        val v = ev.getAxisValue(a)
        val dev = ev.device
        val flat = dev?.getMotionRange(a, ev.source)?.flat ?: 0f
        val dead = maxOf(flat, DEADZONE)
        return if (kotlin.math.abs(v) < dead) 0f else v
    }

    private fun forwardSticks(ev: MotionEvent) {
        if (!::webView.isInitialized) return
        val j = StringBuilder("{")
        AXES.forEach { (name, code) ->
            j.append("\"").append(name).append("\":")
              .append(String.format("%.3f", axis(ev, code))).append(",")
        }
        j.setLength(j.length - 1); j.append("}")
        js("window.__stick && window.__stick($j)")
    }

    private fun js(code: String) {
        runOnUiThread { try { webView.evaluateJavascript(code, null) } catch (e: Exception) {} }
    }

    /** Log what Android can actually see — the fastest way to tell whether the
     *  GCS exposes its sticks to the OS at all. */
    private fun logInputDevices() {
        try {
            val ids = InputDevice.getDeviceIds()
            Log.i(TAG, "input devices: ${ids.size}")
            for (id in ids) {
                val d = InputDevice.getDevice(id) ?: continue
                val joy = d.sources and InputDevice.SOURCE_CLASS_JOYSTICK == InputDevice.SOURCE_CLASS_JOYSTICK
                val axes = d.motionRanges.joinToString(",") { MotionEvent.axisToString(it.axis) }
                Log.i(TAG, "  [$id] ${d.name} joystick=$joy sources=0x${Integer.toHexString(d.sources)} axes=[$axes]")
            }
        } catch (e: Exception) { Log.w(TAG, "input enumeration failed: ${e.message}") }
    }

    override fun onDestroy() {
        // Guarded: when the licence gate sends us straight to ActivationActivity
        // none of these were ever created.
        if (sessionStart > 0L) {
            Metrics.log(this, "session_end",
                mapOf("minutes" to (System.currentTimeMillis() - sessionStart) / 60000))
            try { server?.stop() } catch (_: Exception) {}
            try { video.stop() } catch (_: Exception) {}
            try { mav.stop() } catch (_: Exception) {}
            try { webView.destroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SOCKET_TIMEOUT = 10000
        private const val DEADZONE = 0.08f

        // Forwarded to JS by name. Which of these a given controller populates
        // varies, so the sim maps them at runtime rather than assuming a layout.
        private val AXES = listOf(
            "x"    to MotionEvent.AXIS_X,      "y"    to MotionEvent.AXIS_Y,
            "z"    to MotionEvent.AXIS_Z,      "rz"   to MotionEvent.AXIS_RZ,
            "rx"   to MotionEvent.AXIS_RX,     "ry"   to MotionEvent.AXIS_RY,
            "hatx" to MotionEvent.AXIS_HAT_X,  "haty" to MotionEvent.AXIS_HAT_Y,
            "ltrigger" to MotionEvent.AXIS_LTRIGGER, "rtrigger" to MotionEvent.AXIS_RTRIGGER
        )
    }
}
