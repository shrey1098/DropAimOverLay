package com.dropaim.app

import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Adds the Accept header that media3 leaves off its DESCRIBE.
 *
 * Captured from the wire, same URL, seconds apart:
 *
 *   media3 ->  DESCRIBE rtsp://.../stream=1 RTSP/1.0
 *              User-Agent: DropAim
 *              CSeq: 1
 *              ................................. RTSP/1.0 406 Not Acceptable
 *
 *   ours   ->  DESCRIBE rtsp://.../stream=1 RTSP/1.0
 *              CSeq: 2
 *              User-Agent: DropAim
 *              Accept: application/sdp
 *              ................................. RTSP/1.0 200 OK + SDP
 *
 * The Accept header is the only difference. RFC 2326 permits a client to omit
 * it, but this camera runs the vendor "rtsp_demo" sample server, which requires
 * it and answers 406 — literally "Not Acceptable", the status that exists for
 * exactly this header — when it is absent.
 *
 * media3 exposes no way to add RTSP request headers, but RtspMediaSource.Factory
 * does accept a SocketFactory, so the header goes in on its way out of the
 * socket. Narrow and reversible: only a DESCRIBE that has no Accept is touched.
 */
class RtspAcceptFixSocketFactory : SocketFactory() {

    override fun createSocket(): Socket = PatchingSocket()

    override fun createSocket(host: String, port: Int): Socket =
        PatchingSocket().apply { connect(InetSocketAddress(host, port), CONNECT_MS) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        PatchingSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port), CONNECT_MS)
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        PatchingSocket().apply { connect(InetSocketAddress(host, port), CONNECT_MS) }

    override fun createSocket(
        address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int
    ): Socket = PatchingSocket().apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port), CONNECT_MS)
    }

    private class PatchingSocket : Socket() {
        private var wrapped: OutputStream? = null
        override fun getOutputStream(): OutputStream {
            wrapped?.let { return it }
            return AcceptInjecting(super.getOutputStream()).also { wrapped = it }
        }
    }

    /**
     * media3 writes each RTSP message with a single write(), so a request
     * arrives here whole and can be rewritten as one string. Everything that is
     * not a DESCRIBE lacking an Accept passes through byte-for-byte.
     */
    private class AcceptInjecting(private val out: OutputStream) : OutputStream() {

        override fun write(b: Int) { out.write(b) }
        override fun flush() { out.flush() }
        override fun close() { out.close() }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val msg = String(b, off, len, Charsets.ISO_8859_1)
            if (msg.startsWith("DESCRIBE ") && !msg.contains("Accept:", ignoreCase = true)) {
                // Insert before the blank line that ends the header block.
                val patched = msg.replaceFirst("\r\n\r\n", "\r\n$ACCEPT\r\n\r\n")
                if (patched != msg) {
                    Log.i(TAG, "added '$ACCEPT' to DESCRIBE")
                    val bytes = patched.toByteArray(Charsets.ISO_8859_1)
                    out.write(bytes, 0, bytes.size)
                    return
                }
                Log.w(TAG, "DESCRIBE had no header terminator to insert before — sent unchanged")
            }
            out.write(b, off, len)
        }
    }

    companion object {
        private const val TAG = "RtspAcceptFix"
        private const val ACCEPT = "Accept: application/sdp"
        private const val CONNECT_MS = 5000
    }
}
