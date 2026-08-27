package com.dropaim.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Find where this ground station's MAVLink actually is.
 *
 * PASSIVE by default: [run] only opens listening sockets and reads /proc. It
 * transmits nothing. [probe] does transmit, and is only reached from a button
 * that says so.
 *
 * Background: the SIYI handheld decodes MAVLink in its own app but exposes no
 * datalink or port setting, so there is nothing to read the answer off. The
 * first pass of this scan established that no MAVLink is broadcast on any of
 * eleven usual ports, and that no app-owned socket (uid >= 10000) holds a UDP
 * port at all — only the Android radio, mDNS and DNS system uids.
 *
 * That ruled out the easy explanations and pointed at the thing the first
 * version could not see: a socket is only half described by its LOCAL address.
 * A UDP socket connected to a peer, or a TCP connection, names the far end in
 * /proc too — and a far end of 192.168.144.11 or .12 is the datalink. So this
 * version reads tcp and tcp6 as well as udp and udp6, keeps the remote address,
 * and groups the system noise so the two or three interesting rows are not
 * buried under thirty consecutive radio ports.
 */
object MavScan {

    private const val TAG = "MavScan"

    /** SIYI's documented MAVLink UDP broadcast, the QGC and Skydroid
     *  conventions, and the common alternates on ArduPilot ground stations. */
    val PORTS = listOf(19856, 19857, 14550, 14551, 14552, 14553, 14555, 14556, 14445, 15550, 18570)

    /** Kept under NanoHTTPD's socket timeout so the HTTP response is not cut off. */
    const val LISTEN_MS = 6000L

    /** Where a datalink lives on a SIYI/Skydroid style network. */
    private val PROBE_HOSTS = listOf("192.168.144.11", "192.168.144.12", "192.168.144.255")
    private val PROBE_PORTS = listOf(19856, 14550, 14551, 14555)
    private const val PROBE_WAIT_MS = 3000L

    // ── PASSIVE ──────────────────────────────────────────────────────
    fun run(ctx: Context): JSONObject {
        // Android drops broadcast and multicast on Wi-Fi unless something holds
        // this lock.
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
                .put("sockets", procNet())
                .put("listened", listen())
                .put("appMavlinkPort", Settings.mavlinkPort)
                .put("myUid", android.os.Process.myUid())
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    private fun interfaces(): JSONArray {
        val out = JSONArray()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                val addrs = ni.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
                    .mapNotNull { it.hostAddress }
                if (addrs.isEmpty()) continue
                out.put(JSONObject().put("name", ni.name).put("up", ni.isUp)
                    .put("addresses", JSONArray(addrs)))
            }
        } catch (e: Exception) { Log.w(TAG, "interfaces: ${e.message}") }
        return out
    }

    /**
     * Every socket on the device, from /proc/net/{udp,udp6,tcp,tcp6}.
     *
     * Columns: sl local_address rem_address st tx:rx tr:when retrnsmt uid ...
     * Addresses are HEX ip:port; the IPv4 word and each IPv6 32-bit word are
     * little-endian. `st` is the TCP state (01 established, 0A listen); for UDP
     * it carries no useful meaning and is ignored.
     */
    private fun procNet(): JSONArray {
        val out = JSONArray()
        for ((path, proto) in listOf(
            "/proc/net/udp" to "UDP", "/proc/net/udp6" to "UDP6",
            "/proc/net/tcp" to "TCP", "/proc/net/tcp6" to "TCP6")) {
            try {
                val f = File(path)
                if (!f.canRead()) { out.put(JSONObject().put("error", "$path not readable")); continue }
                f.readLines().drop(1).forEach { line ->
                    val c = line.trim().split(Regex("\\s+"))
                    if (c.size < 8) return@forEach
                    val lp = c[1].split(':'); val rp = c[2].split(':')
                    if (lp.size != 2 || rp.size != 2) return@forEach
                    val localPort = lp[1].toIntOrNull(16) ?: return@forEach
                    val remotePort = rp[1].toIntOrNull(16) ?: 0
                    out.put(JSONObject()
                        .put("proto", proto)
                        .put("localAddr", ip(lp[0])).put("localPort", localPort)
                        // A zero remote is an unconnected listener. A non-zero one
                        // names the far end, which is the whole point of this pass.
                        .put("remoteAddr", if (remotePort == 0) "" else ip(rp[0]))
                        .put("remotePort", remotePort)
                        .put("state", c[3])
                        .put("uid", c[7].toIntOrNull() ?: -1))
                }
            } catch (e: Exception) { out.put(JSONObject().put("error", "$path: ${e.message}")) }
        }
        return out
    }

    /** Hex address -> dotted quad, or a readable IPv6. IPv4-mapped v6 is
     *  reported as the v4 address it really is, since that is what the operator
     *  needs to compare against the datalink's subnet. */
    private fun ip(hex: String): String = try {
        when (hex.length) {
            8 -> v4(hex)
            32 -> {
                // ::ffff:a.b.c.d — the first three words are 0,0,0000FFFF.
                if (hex.startsWith("0000000000000000FFFF0000", true)) v4(hex.substring(24))
                else if (hex.all { it == '0' }) "::"
                else (0 until 4).joinToString(":") { w ->
                    val word = hex.substring(w * 8, w * 8 + 8)
                    val be = (3 downTo 0).joinToString("") { word.substring(it * 2, it * 2 + 2) }
                    be.substring(0, 4).trimStart('0').ifEmpty { "0" } + ":" +
                    be.substring(4, 8).trimStart('0').ifEmpty { "0" }
                }
            }
            else -> hex
        }
    } catch (e: Exception) { hex }

    private fun v4(hex: String) =
        (3 downTo 0).joinToString(".") { hex.substring(it * 2, it * 2 + 2).toInt(16).toString() }

    private fun listen(): JSONArray {
        val results = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val threads = PORTS.map { port ->
            Thread {
                var sock: DatagramSocket? = null
                val row = JSONObject().put("port", port)
                try {
                    sock = DatagramSocket(null).apply {
                        reuseAddress = true; broadcast = true; soTimeout = 400
                        bind(InetSocketAddress(port))
                    }
                    var packets = 0; var bytes = 0
                    val sources = LinkedHashSet<String>()
                    val msgIds = LinkedHashSet<Int>(); val sysIds = LinkedHashSet<Int>()
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + LISTEN_MS
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            packets++; bytes += pkt.length
                            sources.add("${pkt.address.hostAddress}:${pkt.port}")
                            decode(pkt.data, pkt.length, sysIds, msgIds)
                        } catch (_: java.net.SocketTimeoutException) {}
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
        return JSONArray(results.sortedByDescending { it.optInt("packets", 0) })
    }

    // ── ACTIVE ───────────────────────────────────────────────────────
    /**
     * Say hello to the datalink and see if it answers.
     *
     * TRANSMITS. Only called from a button that says so.
     *
     * Many MAVLink UDP endpoints are servers: they send nothing until a client
     * speaks first, then stream telemetry back to whatever address and port that
     * client used. If SIYI's ground unit works that way, the reason we hear
     * silence is simply that we have never introduced ourselves — and the fix is
     * for the app to speak first, not to find a magic port.
     *
     * What is sent is one standard GCS HEARTBEAT per endpoint, byte for byte
     * what QGroundControl emits once a second. It commands nothing, arms
     * nothing, and changes no mode.
     */
    fun probe(): JSONObject {
        val hb = heartbeat()
        val results = JSONArray()
        for (host in PROBE_HOSTS) for (port in PROBE_PORTS) {
            val row = JSONObject().put("host", host).put("port", port)
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true; broadcast = true; soTimeout = 300
                    bind(InetSocketAddress(0))          // ephemeral: we are the client
                }
                val addr = InetAddress.getByName(host)
                row.put("localPort", sock.localPort)
                // Twice, ~1s apart: some endpoints only register a client after a
                // second heartbeat, and UDP is lossy.
                sock.send(DatagramPacket(hb, hb.size, addr, port))
                var packets = 0
                val sources = LinkedHashSet<String>()
                val sysIds = LinkedHashSet<Int>(); val msgIds = LinkedHashSet<Int>()
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + PROBE_WAIT_MS
                var resent = false
                while (System.currentTimeMillis() < deadline) {
                    if (!resent && System.currentTimeMillis() > deadline - PROBE_WAIT_MS + 1000) {
                        try { sock.send(DatagramPacket(hb, hb.size, addr, port)) } catch (_: Exception) {}
                        resent = true
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        packets++
                        sources.add("${pkt.address.hostAddress}:${pkt.port}")
                        decode(pkt.data, pkt.length, sysIds, msgIds)
                    } catch (_: java.net.SocketTimeoutException) {}
                }
                row.put("sent", true).put("replies", packets)
                   .put("sources", JSONArray(sources.toList()))
                   .put("mavlink", msgIds.isNotEmpty())
                   .put("sysIds", JSONArray(sysIds.toList()))
                   .put("msgIds", JSONArray(msgIds.toList()))
            } catch (e: Exception) {
                row.put("sent", false).put("error", e.message ?: e.javaClass.simpleName)
            } finally { try { sock?.close() } catch (_: Exception) {} }
            results.put(row)
        }
        return JSONObject().put("probed", results).put("waitMs", PROBE_WAIT_MS)
    }

    /** A standard GCS HEARTBEAT (msgid 0), MAVLink v2, CRC-16/MCRF4XX. */
    private fun heartbeat(): ByteArray {
        val pl = ByteArray(9)                       // custom_mode(4)=0
        pl[4] = 6                                   // type = MAV_TYPE_GCS
        pl[5] = 8                                   // autopilot = MAV_AUTOPILOT_INVALID
        pl[6] = 0                                   // base_mode
        pl[7] = 4                                   // system_status = MAV_STATE_ACTIVE
        pl[8] = 3                                   // mavlink_version
        val hdr = ByteArray(10)
        hdr[0] = 0xFD.toByte(); hdr[1] = pl.size.toByte()
        hdr[5] = Config.GCS_SYS.toByte(); hdr[6] = Config.GCS_COMP.toByte()
        hdr[7] = 0; hdr[8] = 0; hdr[9] = 0          // msgid 0
        var c = 0xFFFF
        for (k in 1..9) c = crc(hdr[k].toInt() and 0xFF, c)
        for (b in pl) c = crc(b.toInt() and 0xFF, c)
        c = crc(HEARTBEAT_CRC_EXTRA, c)
        return hdr + pl + byteArrayOf((c and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte())
    }

    private fun crc(byte: Int, crc: Int): Int {
        var t = byte xor (crc and 0xFF)
        t = (t xor (t shl 4)) and 0xFF
        return ((crc shr 8) xor (t shl 8) xor (t shl 3) xor (t shr 4)) and 0xFFFF
    }

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

    private const val HEARTBEAT_CRC_EXTRA = 50
}
