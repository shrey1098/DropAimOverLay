package com.dropaim.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Port of the MAVLink handling in server.js: bind UDP :14551, parse telemetry,
 * relay to/from QGroundControl (:14550 on loopback), and send flight-mode
 * commands (LOCK/UNLOCK/RTL). v1 and v2 frames; v2 truncated payloads are
 * zero-padded back to full length as the spec requires.
 */
class MavlinkService {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var qgcAddr = InetAddress.getByName("127.0.0.1")
    // Last non-QGC source we heard telemetry from — where we send commands back.
    @Volatile private var datalinkAddr: InetAddress? = null
    @Volatile private var datalinkPort: Int = 0
    private var txSeq = 0

    private val copterModes = mapOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALTHOLD", 3 to "AUTO", 4 to "GUIDED",
        5 to "LOITER", 6 to "RTL", 7 to "CIRCLE", 9 to "LAND", 16 to "POSHOLD",
        17 to "BRAKE", 18 to "THROW", 20 to "GUIDED_NOGPS", 21 to "SMART_RTL"
    )

    fun start() {
        if (running) return
        running = true
        thread(name = "mavlink") {
            try {
                val s = DatagramSocket(Config.MAVLINK_PORT)
                socket = s
                Log.i(TAG, "listening UDP :${Config.MAVLINK_PORT}")
                val buf = ByteArray(2048)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val data = pkt.data.copyOfRange(0, pkt.length)
                    val fromQgc = pkt.port == Config.QGC_PORT && pkt.address.isLoopbackAddress
                    if (fromQgc) {
                        // Uplink: QGC -> vehicle. Pass straight back to the datalink.
                        val a = datalinkAddr; val p = datalinkPort
                        if (a != null) s.send(DatagramPacket(data, data.size, a, p))
                        continue
                    }
                    // Downlink: datalink -> us. Parse, then relay to QGC.
                    datalinkAddr = pkt.address; datalinkPort = pkt.port
                    if (!Telemetry.mavlinkOk) { Telemetry.mavlinkOk = true; Log.i(TAG, "receiving from ${pkt.address}:${pkt.port}") }
                    s.send(DatagramPacket(data, data.size, qgcAddr, Config.QGC_PORT))
                    parse(data)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "mavlink error: ${e.message}")
            }
        }
    }

    fun stop() { running = false; socket?.close() }

    /** LOCK->BRAKE(17), UNLOCK->LOITER(5), RTL(6). Returns false if no link yet. */
    fun sendMode(name: String): Boolean {
        val cm = when (name.uppercase()) {
            "BRAKE" -> 17; "LOITER" -> 5; "RTL" -> 6; else -> return false
        }
        val a = datalinkAddr ?: return false
        val pkt = buildDoSetMode(cm)
        // UDP is lossy; send twice so a dropped mode command doesn't silently no-op.
        socket?.send(DatagramPacket(pkt, pkt.size, a, datalinkPort))
        socket?.send(DatagramPacket(pkt, pkt.size, a, datalinkPort))
        return true
    }

    // ── parsing ───────────────────────────────────────────────────
    private fun parse(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size - 8) {
            val v2 = bytes[i].toInt() and 0xFF == 0xFD
            val v1 = bytes[i].toInt() and 0xFF == 0xFE
            if (!v1 && !v2) { i++; continue }
            val pl = bytes[i + 1].toInt() and 0xFF
            val hl = if (v2) 10 else 6
            val tl = hl + pl + 2
            if (i + tl > bytes.size) break
            val id = if (v2)
                (bytes[i + 7].toInt() and 0xFF) or ((bytes[i + 8].toInt() and 0xFF) shl 8) or ((bytes[i + 9].toInt() and 0xFF) shl 16)
            else bytes[i + 5].toInt() and 0xFF
            val sysid = if (v2) bytes[i + 5].toInt() and 0xFF else bytes[i + 3].toInt() and 0xFF

            // v2 truncates trailing zero payload bytes; pad back to 28 so fixed
            // offsets read their spec-defined zero.
            val p = ByteArray(28)
            val n = minOf(pl, 28)
            System.arraycopy(bytes, i + hl, p, 0, n)
            val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            try {
                when {
                    id == 0 && pl >= 6 -> {                       // HEARTBEAT
                        if (sysid == Config.TARGET_SYS) {
                            val cm = bb.getInt(0).toLong() and 0xFFFFFFFFL
                            Telemetry.mode = copterModes[cm.toInt()] ?: "MODE$cm"
                        }
                    }
                    id == 30 && pl >= 16 -> {                     // ATTITUDE
                        Telemetry.roll = bb.getFloat(4) * 180.0 / Math.PI
                        Telemetry.pitch = bb.getFloat(8) * 180.0 / Math.PI
                        var y = bb.getFloat(12) * 180.0 / Math.PI
                        Telemetry.yaw = if (y < 0) y + 360 else y
                    }
                    id == 33 && pl >= 18 -> {                     // GLOBAL_POSITION_INT
                        Telemetry.lat = bb.getInt(4) * 1e-7
                        Telemetry.lon = bb.getInt(8) * 1e-7
                        Telemetry.altMSL = bb.getInt(12) * 1e-3
                        Telemetry.altAGL = bb.getInt(16) * 1e-3
                        Telemetry.vx = bb.getShort(20) * 0.01
                        Telemetry.vy = bb.getShort(22) * 0.01
                        Telemetry.vz = bb.getShort(24) * 0.01
                        Telemetry.heading = (bb.getShort(26).toInt() and 0xFFFF) * 0.01
                        Telemetry.groundspeed = hypot(Telemetry.vx, Telemetry.vy)
                    }
                    id == 168 && pl >= 5 -> {                     // WIND
                        var d = bb.getFloat(0).toDouble() % 360.0
                        if (d < 0) d += 360.0
                        Telemetry.windDir = d
                        Telemetry.windSpeed = bb.getFloat(4).toDouble()
                    }
                }
            } catch (_: Exception) { /* short/garbled frame — skip */ }
            i += tl
        }
    }

    // ── COMMAND_LONG (msgid 76) DO_SET_MODE, CRC-16/MCRF4XX + crc_extra ──
    private fun buildDoSetMode(customMode: Int): ByteArray {
        val payload = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
        payload.putFloat(0, 1f)                    // param1 = base_mode = CUSTOM_MODE_ENABLED
        payload.putFloat(4, customMode.toFloat())  // param2 = custom_mode
        payload.putShort(28, 176)                  // command = MAV_CMD_DO_SET_MODE
        payload.put(30, Config.TARGET_SYS.toByte())
        payload.put(31, Config.TARGET_COMP.toByte())
        payload.put(32, 0)                         // confirmation
        val pl = payload.array()

        val hdr = ByteArray(10)
        hdr[0] = 0xFD.toByte(); hdr[1] = pl.size.toByte(); hdr[2] = 0; hdr[3] = 0
        txSeq = (txSeq + 1) and 0xFF
        hdr[4] = txSeq.toByte(); hdr[5] = Config.GCS_SYS.toByte(); hdr[6] = Config.GCS_COMP.toByte()
        hdr[7] = (76 and 0xFF).toByte(); hdr[8] = ((76 shr 8) and 0xFF).toByte(); hdr[9] = ((76 shr 16) and 0xFF).toByte()

        var c = 0xFFFF
        for (k in 1..9) c = crcAccum(hdr[k].toInt() and 0xFF, c)
        for (b in pl) c = crcAccum(b.toInt() and 0xFF, c)
        c = crcAccum(CRC_EXTRA_COMMAND_LONG, c)   // msgid 76 crc_extra

        val out = ByteArray(hdr.size + pl.size + 2)
        System.arraycopy(hdr, 0, out, 0, hdr.size)
        System.arraycopy(pl, 0, out, hdr.size, pl.size)
        out[out.size - 2] = (c and 0xFF).toByte()
        out[out.size - 1] = ((c shr 8) and 0xFF).toByte()
        return out
    }

    private fun crcAccum(byte: Int, crc: Int): Int {
        var tmp = byte xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    companion object {
        private const val TAG = "Mavlink"
        private const val CRC_EXTRA_COMMAND_LONG = 152
    }
}
