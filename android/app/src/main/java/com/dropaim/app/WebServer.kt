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
import java.io.File
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
 *   POST /api/log          -> append drop record
 *   GET  /api/log/export   -> CSV
 *   GET  /api/log/count
 */
class WebServer(
    private val ctx: Context,
    private val mav: MavlinkService,
    port: Int = Config.HTTP_PORT
) : NanoWSD(port) {

    private val logFile = File(ctx.filesDir, "drops.jsonl")

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
                uri == "/api/status" -> json("""{"video":${FrameBus.connected},"mavlink":${Telemetry.mavlinkOk}}""")
                uri == "/api/mode" && session.method == Method.POST -> apiMode(session)
                uri == "/api/log" && session.method == Method.POST -> apiLog(session)
                uri == "/api/log/export" -> apiLogExport()
                uri == "/api/log/count" -> json("""{"count":${readLog().size}}""")
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

    private fun apiLog(session: IHTTPSession): Response {
        val body = postBody(session)
        val rec = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        rec.put("server_ts", java.time.Instant.now().toString())
        logFile.appendText(rec.toString() + "\n")
        // Mirror the essentials into the usage logbook that gets uploaded.
        Metrics.log(ctx, "drop", mapOf(
            "alt_agl_m" to rec.opt("alt_agl_m"),
            "wind_ms" to rec.opt("wind_ms"),
            "miss_m" to rec.opt("miss_m"),
            "raw_miss_downwind_m" to rec.opt("raw_miss_downwind_m"),
            "offset_applied" to rec.opt("offset_applied")
        ))
        return json("""{"ok":true,"count":${readLog().size}}""")
    }

    private fun apiLogExport(): Response {
        val rows = readLog()
        if (rows.isEmpty()) return jsonStatus(Response.Status.NOT_FOUND, "no drops logged yet")
        val cols = LinkedHashSet<String>()
        rows.forEach { o -> o.keys().forEach { cols.add(it) } }
        fun esc(v: String) = if (v.contains(Regex("[\",\n]"))) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val sb = StringBuilder(cols.joinToString(",")).append("\n")
        rows.forEach { o ->
            sb.append(cols.joinToString(",") { c -> esc(if (o.has(c)) o.get(c).toString() else "") }).append("\n")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "text/csv", sb.toString())
        resp.addHeader("Content-Disposition", "attachment; filename=\"dropaim_log.csv\"")
        return resp
    }

    private fun readLog(): List<JSONObject> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().filter { it.isNotBlank() }.mapNotNull {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
    }

    private fun staticAsset(path: String): Response {
        val clean = path.trimStart('/')
        return try {
            val bytes = ctx.assets.open("www/$clean").readBytes()
            newFixedLengthResponse(Response.Status.OK, mimeOf(clean), ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: $clean")
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
