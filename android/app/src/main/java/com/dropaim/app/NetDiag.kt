package com.dropaim.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Network reachability for the camera.
 *
 * "RTSP failed" covers two completely different faults that need completely
 * different fixes: the camera is unreachable (wrong IP, GCS not on the camera's
 * network, traffic leaving via mobile data instead of the datalink), or the
 * camera is reachable but the RTSP negotiation failed (wrong path, wrong
 * transport, auth, unsupported codec). A plain TCP connect before the player
 * separates the two in about a second.
 */
object NetDiag {

    private const val TAG = "NetDiag"

    /** Can we open a TCP socket to the camera at all? */
    fun reachable(host: String, port: Int, timeoutMs: Int = 2000): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (e: Exception) {
            Log.w(TAG, "unreachable $host:$port — ${e.javaClass.simpleName}: ${e.message}")
            false
        }

    /**
     * Dump what this device thinks its networks are. The usual cause of a
     * perfectly good camera being unreachable is the GCS holding a mobile-data
     * default route, so sockets to 192.168.144.x leave via cellular and die.
     */
    fun logNetworks(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n)
                val lp = cm.getLinkProperties(n)
                val kind = when {
                    caps == null -> "?"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
                    else -> "OTHER"
                }
                val addrs = lp?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "?" } ?: ""
                val routes = lp?.routes?.joinToString(", ") { it.toString() } ?: ""
                Log.i(TAG, "network $kind${if (n == active) " (DEFAULT)" else ""} " +
                           "iface=${lp?.interfaceName} addrs=[$addrs]")
                Log.i(TAG, "   routes: $routes")
            }
        } catch (e: Exception) { Log.w(TAG, "network enumeration failed: ${e.message}") }

        // Raw interface list as a cross-check — this shows link-local and USB
        // tether addresses that ConnectivityManager sometimes omits.
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val a = nif.inetAddresses.toList().joinToString(", ") { it.hostAddress ?: "?" }
                if (a.isNotBlank()) Log.i(TAG, "iface ${nif.name}: $a")
            }
        } catch (e: Exception) { Log.w(TAG, "interface enumeration failed: ${e.message}") }
    }

    /** host and port out of an rtsp:// URL, for the preflight probe. */
    fun hostPort(url: String): Pair<String, Int>? = try {
        val u = java.net.URI(url)
        val h = u.host ?: return null
        Pair(h, if (u.port > 0) u.port else 554)
    } catch (e: Exception) { null }
}
