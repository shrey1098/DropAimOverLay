package com.dropaim.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Single self-contained activity: starts the MAVLink + video + embedded server,
 * then points a full-screen WebView at http://127.0.0.1:3000/ where the tested
 * DropAim web app runs unchanged. No Node, no manual start.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val mav = MavlinkService()
    private lateinit var video: VideoPipe
    private var server: WebServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        video = VideoPipe(applicationContext)

        // Bring up the native services first, then the embedded server.
        mav.start()
        video.start()
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
        setContentView(webView)
        // Small delay lets the server bind before the first request.
        webView.postDelayed({ webView.loadUrl("http://127.0.0.1:${Config.HTTP_PORT}/") }, 400)
    }

    override fun onDestroy() {
        try { server?.stop() } catch (_: Exception) {}
        video.stop()
        mav.stop()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SOCKET_TIMEOUT = 10000
    }
}
