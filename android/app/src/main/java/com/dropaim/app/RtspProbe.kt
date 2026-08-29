package com.dropaim.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object RtspProbe {

    private const val TAG = "RtspProbe"

    private val PATHS = listOf(
        "/stream=0", "/stream=1", "/stream=2",
        "/main", "/sub",
        "/video0", "/video1", "/video2",
        "/live", "/live/0", "/live/main",
        "/ch0", "/ch0_0", "/0", "/1",
        "/h264", "/profile1",
        "/cam/realmonitor?channel=1&subtype=0",
        "/Streaming/Channels/101"
    )

    private class Response(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        val code: Int get() = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
    }

    fun sweep(host: String, ports: List<Int> = listOf(554, 555)) {
        Log.i(TAG, "===== RTSP path sweep on $host =====")
        val working = mutableListOf<String>()
        for (port in ports) {
            for (path in PATHS) {
                val url = "rtsp://$host:$port$path"
                val r = try { describe(host, port, url) } catch (e: Exception) {
                    Log.w(TAG, "$url -> ${e.javaClass.simpleName}: ${e.message}"); null
                } ?: continue

                val auth = r.headers["www-authenticate"]
                val note = when {
                    r.code == 200 -> "OK — SDP ${r.body.length} bytes"
                    r.code == 401 -> "AUTH REQUIRED: $auth"
                    else -> ""
                }
                Log.i(TAG, "$url -> ${r.statusLine} $note")
                if (r.code == 200) {
                    working += url

                    r.body.lines().filter { it.isNotBlank() }
                        .forEach { Log.i(TAG, "    $it") }
                }
            }
        }
        if (working.isEmpty())
            Log.e(TAG, "===== sweep finished: NO path returned 200 =====")
        else
            Log.i(TAG, "===== sweep finished: WORKING -> ${working.joinToString(", ")} =====")
    }

    fun check(urls: List<String>) {
        for (url in urls) {
            val hp = NetDiag.hostPort(url) ?: continue
            val r = try { describe(hp.first, hp.second, url) } catch (e: Exception) {
                Log.w(TAG, "$url -> ${e.javaClass.simpleName}: ${e.message}"); null
            } ?: continue
            Log.i(TAG, "$url -> ${r.statusLine}")
            r.body.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, "    $it") }
        }
    }

    fun describes(url: String): Boolean {
        val hp = NetDiag.hostPort(url) ?: return false
        return try { describe(hp.first, hp.second, url)?.code == 200 } catch (e: Exception) {
            Log.w(TAG, "$url -> ${e.javaClass.simpleName}: ${e.message}"); false
        }
    }

    private fun describe(host: String, port: Int, url: String, timeoutMs: Int = 2500): Response? {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()

            val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))

            send(out, "OPTIONS $url RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: DropAim\r\n\r\n")
            val opts = read(br)
            if (opts != null && opts.headers.containsKey("public"))
                Log.i(TAG, "$url OPTIONS -> ${opts.statusLine} | Public: ${opts.headers["public"]}")

            send(out, "DESCRIBE $url RTSP/1.0\r\nCSeq: 2\r\n" +
                      "User-Agent: DropAim\r\nAccept: application/sdp\r\n\r\n")
            return read(br)
        }
    }

    private fun send(out: OutputStream, msg: String) {
        out.write(msg.toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    private fun read(br: BufferedReader): Response? {
        val status = br.readLine() ?: return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = br.readLine() ?: break
            if (line.isBlank()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = CharArray(len)
            var got = 0
            while (got < len) {
                val n = br.read(buf, got, len - got)
                if (n < 0) break
                got += n
            }
            String(buf, 0, got)
        } else ""
        return Response(status.trim(), headers, body)
    }
}
