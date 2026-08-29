package com.dropaim.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object NetDiag {

    private const val TAG = "NetDiag"

    fun reachable(host: String, port: Int, timeoutMs: Int = 2000): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (e: Exception) {
            Log.w(TAG, "unreachable $host:$port — ${e.javaClass.simpleName}: ${e.message}")
            false
        }

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
                    else -> "OTHER"
                }
                val addrs = lp?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "?" } ?: ""
                val routes = lp?.routes?.joinToString(", ") { it.toString() } ?: ""
                Log.i(TAG, "network $kind${if (n == active) " (DEFAULT)" else ""} " +
                           "iface=${lp?.interfaceName} addrs=[$addrs]")
                Log.i(TAG, "   routes: $routes")
            }
        } catch (e: Exception) { Log.w(TAG, "network enumeration failed: ${e.message}") }

        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val a = nif.inetAddresses.toList().joinToString(", ") { it.hostAddress ?: "?" }
                if (a.isNotBlank()) Log.i(TAG, "iface ${nif.name}: $a")
            }
        } catch (e: Exception) { Log.w(TAG, "interface enumeration failed: ${e.message}") }
    }

    fun scanPorts(host: String) {
        val ports = intArrayOf(80, 554, 555, 8000, 8080, 8554, 8899, 88)
        val open = ports.filter { reachable(host, it, 1200) }
        if (open.isEmpty())
            Log.e(TAG, "port scan $host: NOTHING open of ${ports.joinToString(",")} " +
                       "— the camera is not reachable from this device")
        else
            Log.i(TAG, "port scan $host: OPEN ${open.joinToString(", ")}")
    }

    fun hostPort(url: String): Pair<String, Int>? {
        return try {
            val u = java.net.URI(url)
            val h = u.host
            if (h.isNullOrBlank()) null else Pair(h, if (u.port > 0) u.port else 554)
        } catch (e: Exception) { null }
    }
}
