package com.dropaim.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Embedded HTTP + WebSocket server that replicates the routes public/index.html
 * expects from the old Node server, so the web app runs unchanged in the WebView:
 *   GET  /                 -> assets/www/index.html (+ static assets)
 *   GET  /stream           -> MJPEG (multipart)     from FrameBus
 *   WS   /telemetry        -> telemetry JSON @5Hz
 *   GET  /api/status
 *   POST /api/mode         -> LOCK/UNLOCK/RTL
 */
class WebServer(
    private val ctx: Context,
    private val mav: MavlinkService,
    private val video: VideoPipe,
    port: Int = Config.HTTP_PORT
) : NanoWSD("127.0.0.1", port) {

    // ── WebSocket: telemetry ─────────────────────────────────────
    override fun openWebSocket(handshake: IHTTPSession): WebSocket = TelemetrySocket(handshake)

    private inner class TelemetrySocket(hs: IHTTPSession) : WebSocket(hs) {
        @Volatile private var open = false
        override fun onOpen() {
            open = true
            thread(name = "telem-ws") {
                try {
                    send(Telemetry.toJson())
                    while (open) { send(Telemetry.toJson()); Thread.sleep(200) }
                } catch (_: Exception) {}
            }
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) { open = false }
        override fun onMessage(message: WebSocketFrame?) {}
        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: java.io.IOException?) { open = false }
    }

    // ── HTTP ─────────────────────────────────────────────────────
    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/stream" -> streamResponse()
                uri == "/api/status" -> json(
                    JSONObject()
                        .put("video", FrameBus.connected)
                        .put("mavlink", Telemetry.mavlinkOk)
                        .put("videoUrl", FrameBus.activeUrl)
                        .put("videoErr", FrameBus.lastError)
                        .toString())
                uri == "/api/mode" && session.method == Method.POST -> apiMode(session)
                uri == "/api/cameras" -> apiCameras()
                uri == "/api/camera" && session.method == Method.POST -> apiSelectCamera(session)
                else -> staticAsset(if (uri == "/") "/index.html" else uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error $uri: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    // MJPEG: emit each NEW frame once (no stale re-sends).
    private fun streamResponse(): Response {
        val stream = object : InputStream() {
            private var cur: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
            private var lastSent: ByteArray? = null
            private fun refill() {
                var f = FrameBus.latest
                while (f == null || f === lastSent) { Thread.sleep(15); f = FrameBus.latest }
                lastSent = f
                val head = "--mjpegframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray()
                cur = ByteArrayInputStream(head + f + "\r\n".toByteArray())
            }
            override fun read(): Int { if (cur.available() == 0) refill(); return cur.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (cur.available() == 0) refill(); return cur.read(b, off, len) }
        }
        val r = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=mjpegframe", stream)
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    private fun apiMode(session: IHTTPSession): Response {
        val body = postBody(session)
        val name = try { JSONObject(body).optString("mode", "") } catch (_: Exception) { "" }.uppercase()
        if (name !in setOf("BRAKE", "LOITER", "RTL"))
            return jsonStatus(Response.Status.BAD_REQUEST, """{"ok":false,"err":"mode must be BRAKE, LOITER or RTL"}""")
        val ok = mav.sendMode(name)
        return if (ok) json("""{"ok":true,"mode":"$name"}""")
        else jsonStatus(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"err":"no telemetry link yet"}""")
    }

    /**
     * The sensors on this aircraft. 'present' is what actually answered a
     * DESCRIBE, so the UI can offer a switch on a dual-sensor drone and stay out
     * of the way on a day-only one, from the same build.
     */
    private fun apiCameras(): Response {
        val activeId = FrameBus.activeCamera.ifEmpty { Config.cameras.firstOrNull()?.id ?: "" }
        val arr = org.json.JSONArray()
        Config.cameras.forEachIndexed { i, c ->
            // A variant is only known for the camera that is actually playing,
            // and only once its resolution has arrived. Everything else reports
            // the camera's own default.
            val v = if (c.id == activeId) c.variantFor(FrameBus.videoW, FrameBus.videoH) else null
            arr.put(JSONObject()
                .put("index", i)
                .put("id", c.id)
                .put("label", c.label)
                .put("zoom", v?.zoom ?: c.zoom)
                .put("calibrated", v?.calibrated ?: c.calibrated)
                .put("model", v?.model ?: "")
                .put("present", FrameBus.availableCameras.isEmpty() || c.id in FrameBus.availableCameras))
        }
        return json(JSONObject()
            .put("cameras", arr)
            .put("active", activeId)
            .put("detected", FrameBus.availableCameras.isNotEmpty())
            .put("width", FrameBus.videoW)
            .put("height", FrameBus.videoH)
            .toString())
    }

    private fun apiSelectCamera(session: IHTTPSession): Response {
        val body = postBody(session)
        val idx = try { JSONObject(body).optInt("index", -1) } catch (_: Exception) { -1 }
        val cam = Config.cameras.getOrNull(idx)
            ?: return jsonStatus(Response.Status.BAD_REQUEST, """{"ok":false,"err":"no such camera"}""")
        return if (video.selectCamera(idx))
            json("""{"ok":true,"id":"${cam.id}","label":"${cam.label}","zoom":${cam.zoom},"calibrated":${cam.calibrated}}""")
        else jsonStatus(Response.Status.INTERNAL_ERROR, """{"ok":false,"err":"switch failed"}""")
    }

    private fun staticAsset(path: String): Response {
        val clean = path.trimStart('/')
        return try {
            val bytes = ctx.assets.open("www/$clean").readBytes()
            newFixedLengthResponse(Response.Status.OK, mimeOf(clean), ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (_: Exception) {
            // Say WHY, not just "not found" — a missing web asset means the APK
            // was built without the syncWebAssets copy having run.
            val have = try { ctx.assets.list("www")?.joinToString(", ") ?: "" } catch (e: Exception) { "?" }
            val msg = if (have.isBlank())
                "assets/www is EMPTY — the web app was not packaged into this APK.\n" +
                "Run './gradlew syncWebAssets' and rebuild."
            else "not found: $clean (assets/www contains: $have)"
            Log.e(TAG, msg)
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", msg)
        }
    }

    private fun postBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try { session.parseBody(files); files["postData"] ?: "" } catch (_: Exception) { "" }
    }

    private fun mimeOf(p: String) = when {
        p.endsWith(".html") -> "text/html"
        p.endsWith(".js") -> "application/javascript"
        p.endsWith(".css") -> "text/css"
        p.endsWith(".svg") -> "image/svg+xml"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".png") -> "image/png"
        p.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun json(s: String) = newFixedLengthResponse(Response.Status.OK, "application/json", s)
    private fun jsonStatus(st: Response.Status, s: String) = newFixedLengthResponse(st, "application/json", s)

    companion object { private const val TAG = "WebServer" }
}
