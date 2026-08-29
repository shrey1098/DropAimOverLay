package com.dropaim.app

import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

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

    private class AcceptInjecting(private val out: OutputStream) : OutputStream() {

        override fun write(b: Int) { out.write(b) }
        override fun flush() { out.flush() }
        override fun close() { out.close() }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val msg = String(b, off, len, Charsets.ISO_8859_1)
            if (msg.startsWith("DESCRIBE ") && !msg.contains("Accept:", ignoreCase = true)) {

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
