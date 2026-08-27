package com.dropaim.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Find where this ground station's MAVLink actually is.
 *
 * READ-ONLY. Nothing here changes how telemetry is received, parsed or relayed;
 * it opens its own sockets, looks, and closes them.
 *
 * The SIYI handheld decodes MAVLink in its own app — voltage, satellites and
 * attitude are on its LINK STATUS page — but its app exposes no UDP or datalink
 * setting, so there is nothing to switch on and no port named anywhere. Guessing
 * one port per rebuild is not a method. This answers three questions in one
 * pass:
 *
 *   1. What addresses does this device hold? (is it even on the datalink's net)
 *   2. What UDP ports are already open on it, and by which app?  /proc/net/udp
 *      lists every bound socket, so the port SIYI's own app is listening on is
 *      visible without guessing.
 *   3. Does anything actually arrive on the usual MAVLink ports?
 *
 * IMPORTANT CAVEAT, and the reason (2) matters as much as (3): on Linux a
 * *unicast* datagram is delivered to ONE socket even when both set
 * SO_REUSEADDR. So if the datalink sends MAVLink straight to SIYI's app, this
 * listener will see nothing on that port however right the port is — silence in
 * (3) is not proof of absence. Broadcast and multicast go to every bound
 * socket, so those do show up. (2) is what distinguishes the two cases.
 */
object MavScan {

    private const val TAG = "MavScan"

    /**
     * Ports worth listening on. 19856 is SIYI's documented MAVLink UDP
     * broadcast; 14550/14551 are the QGC and Skydroid conventions; the rest are
     * the common alternates seen on ArduPilot ground stations.
     */
    val PORTS = listOf(19856, 19857, 14550, 14551, 14552, 14553, 14555, 14556, 14445, 15550, 18570)

    /** Kept under NanoHTTPD's socket timeout so the HTTP response is not cut off. */
    const val LISTEN_MS = 6000L

    fun run(ctx: Context): JSONObject {
        // Android drops broadcast and multicast on Wi-Fi unless something holds
        // this lock. If SIYI broadcasts to 192.168.144.255 and the datalink
        // presents as Wi-Fi, this is the difference between hearing it and not.
        var lock: WifiManager.MulticastLock? = null
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wm?.createMulticastLock("dropaim-mavscan")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "no multicast lock: ${e.message}") }

        return try {
            JSONObject()
                .put("listenMs", LISTEN_MS)
                .put("multicastLock", lock?.isHeld == true)
                .put("interfaces", interfaces())
                .put("openUdpPorts", procNetUdp())
                .put("listened", listen())
                .put("appMavlinkPort", Settings.mavlinkPort)
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    /** Which networks this device is on — the first thing to check is whether
     *  it holds an address on the datalink's subnet at all. */
    private fun interfaces(): JSONArray {
        val out = JSONArray()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                val addrs = ni.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
                    .mapNotNull { it.hostAddress }
                if (addrs.isEmpty()) continue
                out.put(JSONObject()
                    .put("name", ni.name)
                    .put("up", ni.isUp)
                    .put("addresses", JSONArray(addrs)))
            }
        } catch (e: Exception) { Log.w(TAG, "interfaces: ${e.message}") }
        return out
    }

    /**
     * Every UDP socket bound on this device, from /proc/net/udp.
     *
     * This is the part that does not depend on guessing: whatever port SIYI's
     * app is listening on is in this list. Columns are
     *   sl  local_address rem_address st tx_queue:rx_queue ... uid ... inode
     * with local_address as HEX ip:port, the IP in host byte order reversed.
     */
    private fun procNetUdp(): JSONArray {
        val out = JSONArray()
        for (path in listOf("/proc/net/udp", "/proc/net/udp6")) {
            try {
                val f = File(path)
                if (!f.canRead()) {
                    out.put(JSONObject().put("error", "$path not readable"))
                    continue
                }
                f.readLines().drop(1).forEach { line ->
                    val c = line.trim().split(Regex("\\s+"))
                    if (c.size < 8) return@forEach
                    val local = c[1].split(':')
                    if (local.size != 2) return@forEach
                    val port = local[1].toIntOrNull(16) ?: return@forEach
                    val queues = c[4].split(':')
                    out.put(JSONObject()
                        .put("port", port)
                        .put("addr", hexIp(local[0]))
                        .put("uid", c[7].toIntOrNull() ?: -1)
                        .put("rxQueue", queues.getOrNull(1)?.toIntOrNull(16) ?: 0)
                        .put("drops", c.getOrNull(12)?.toIntOrNull() ?: 0)
                        .put("v6", path.endsWith("6")))
                }
            } catch (e: Exception) {
                out.put(JSONObject().put("error", "$path: ${e.message}"))
            }
        }
        return out
    }

    /** 0100007F -> 127.0.0.1 (little-endian). IPv6 is left as the raw hex. */
    private fun hexIp(hex: String): String {
        if (hex.length != 8) return hex
        return try {
            (3 downTo 0).joinToString(".") { hex.substring(it * 2, it * 2 + 2).toInt(16).toString() }
        } catch (e: Exception) { hex }
    }

    /** Listen on every candidate at once and report whatever turns up. */
    private fun listen(): JSONArray {
        val results = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val threads = PORTS.map { port ->
            Thread {
                var sock: DatagramSocket? = null
                val row = JSONObject().put("port", port)
                try {
                    // SO_REUSEADDR so a port another app already holds can still
                    // be bound; broadcast enabled so 192.168.144.255 traffic is
                    // not dropped by the socket itself.
                    sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 400
                        bind(InetSocketAddress(port))
                    }
                    var packets = 0; var bytes = 0
                    val sources = LinkedHashSet<String>()
                    val msgIds = LinkedHashSet<Int>()
                    val sysIds = LinkedHashSet<Int>()
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + LISTEN_MS
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            packets++; bytes += pkt.length
                            sources.add("${pkt.address.hostAddress}:${pkt.port}")
                            decode(pkt.data, pkt.length, sysIds, msgIds)
                        } catch (_: java.net.SocketTimeoutException) { /* keep waiting */ }
                    }
                    row.put("bound", true).put("packets", packets).put("bytes", bytes)
                       .put("sources", JSONArray(sources.toList()))
                       .put("mavlink", msgIds.isNotEmpty())
                       .put("sysIds", JSONArray(sysIds.toList()))
                       .put("msgIds", JSONArray(msgIds.toList()))
                } catch (e: Exception) {
                    row.put("bound", false).put("error", e.message ?: e.javaClass.simpleName)
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                    results.add(row)
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { try { it.join(LISTEN_MS + 2000) } catch (_: Exception) {} }
        // Ports that heard something first — that is the whole answer.
        val sorted = results.sortedByDescending { it.optInt("packets", 0) }
        return JSONArray(sorted)
    }

    /** Pick out MAVLink v1/v2 frames and note their system and message ids, so
     *  a port carrying MAVLink is distinguishable from one carrying video or
     *  some other chatter that merely happens to be there. */
    private fun decode(b: ByteArray, len: Int, sysIds: MutableSet<Int>, msgIds: MutableSet<Int>) {
        var i = 0
        while (i < len - 8) {
            val v2 = (b[i].toInt() and 0xFF) == 0xFD
            val v1 = (b[i].toInt() and 0xFF) == 0xFE
            if (!v1 && !v2) { i++; continue }
            val pl = b[i + 1].toInt() and 0xFF
            val hl = if (v2) 10 else 6
            val total = hl + pl + 2
            if (i + total > len) break
            sysIds.add(if (v2) b[i + 5].toInt() and 0xFF else b[i + 3].toInt() and 0xFF)
            msgIds.add(if (v2)
                (b[i + 7].toInt() and 0xFF) or ((b[i + 8].toInt() and 0xFF) shl 8) or ((b[i + 9].toInt() and 0xFF) shl 16)
            else b[i + 5].toInt() and 0xFF)
            i += total
        }
    }
}
