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

class MavlinkService {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var qgcAddr = InetAddress.getByName("127.0.0.1")

    @Volatile private var generation = 0

    @Volatile private var datalinkAddr: InetAddress? = null
    @Volatile private var datalinkPort: Int = 0
    private var txSeq = 0

    private val copterModes = mapOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALTHOLD", 3 to "AUTO", 4 to "GUIDED",
        5 to "LOITER", 6 to "RTL", 7 to "CIRCLE", 9 to "LAND", 16 to "POSHOLD",
        17 to "BRAKE", 18 to "THROW", 20 to "GUIDED_NOGPS", 21 to "SMART_RTL"
    )

    private var bt: BluetoothLink? = null

    private val rx = ByteArray(4096)
    private var rxLen = 0

    fun start() {
        if (Settings.telemetrySource == Settings.SRC_BT) startBluetooth() else startUdp()
    }

    private fun startBluetooth() {
        if (running) return
        running = true
        val qgcPort = Settings.qgcPort
        val gen = ++generation
        rxLen = 0
        Telemetry.mavlinkOk = false

        val relay = try {
            DatagramSocket(null).apply { reuseAddress = true; soTimeout = 200
                bind(java.net.InetSocketAddress(Settings.mavlinkPort)) }
        } catch (e: Exception) { Log.w(TAG, "no QGC relay socket: ${e.message}"); null }
        socket = relay

        val link = BluetoothLink(
            onBytes = { buf, n ->
                if (gen == generation) {
                    if (!Telemetry.mavlinkOk) { Telemetry.mavlinkOk = true; Log.i(TAG, "receiving over Bluetooth") }

                    try { relay?.send(DatagramPacket(buf, n, qgcAddr, qgcPort)) } catch (_: Exception) {}
                    feed(buf, n)
                }
            },
            onState = { ok, why ->
                if (gen == generation) {
                    Telemetry.mavlinkOk = ok
                    if (!ok) { btError = why; rxLen = 0 } else btError = ""
                }
            })
        bt = link
        link.start(Settings.bluetoothAddress)

        if (relay != null) thread(name = "mavlink-qgc-uplink") {
            val buf = ByteArray(2048)
            while (running && gen == generation) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    relay.receive(pkt)
                    if (pkt.port == qgcPort && pkt.address.isLoopbackAddress)
                        link.write(pkt.data.copyOfRange(0, pkt.length))
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) { if (running && gen == generation) Log.w(TAG, "uplink: ${e.message}"); }
            }
        }
    }

    private fun feed(chunk: ByteArray, n: Int) {
        if (rxLen + n > rx.size) rxLen = 0
        System.arraycopy(chunk, 0, rx, rxLen, n)
        rxLen += n
        val used = parseFrames(rx, rxLen)
        if (used > 0) {
            System.arraycopy(rx, used, rx, 0, rxLen - used)
            rxLen -= used
        }
    }

    private fun startUdp() {
        if (running) return
        running = true

        val listenPort = Settings.mavlinkPort
        val qgcPort = Settings.qgcPort
        val gen = ++generation
        thread(name = "mavlink") {
            try {

                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(listenPort))
                }
                socket = s
                Log.i(TAG, "listening UDP :$listenPort, relaying to QGC :$qgcPort")
                val buf = ByteArray(2048)
                while (running && gen == generation) {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val data = pkt.data.copyOfRange(0, pkt.length)
                    val fromQgc = pkt.port == qgcPort && pkt.address.isLoopbackAddress
                    if (fromQgc) {

                        val a = datalinkAddr; val p = datalinkPort
                        if (a != null) s.send(DatagramPacket(data, data.size, a, p))
                        continue
                    }

                    datalinkAddr = pkt.address; datalinkPort = pkt.port
                    if (!Telemetry.mavlinkOk) { Telemetry.mavlinkOk = true; Log.i(TAG, "receiving from ${pkt.address}:${pkt.port}") }
                    s.send(DatagramPacket(data, data.size, qgcAddr, qgcPort))
                    parseFrames(data, data.size)
                }
            } catch (e: java.net.BindException) {
                Log.e(TAG, "UDP :$listenPort is already held by another app " +
                           "and would not share it — no telemetry this session (${e.message})")
            } catch (e: Exception) {
                if (running && gen == generation) Log.e(TAG, "mavlink error: ${e.message}")
            } finally {
                if (gen == generation) socket = null
            }
        }
    }

    fun stop() {
        running = false; generation++
        try { bt?.stop() } catch (_: Exception) {}
        bt = null
        socket?.close()
    }

    @Volatile var btError = ""; private set

    val linkDescription: String
        get() = if (Settings.telemetrySource == Settings.SRC_BT)
            "Bluetooth" + (bt?.deviceName?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") +
            (if (btError.isNotEmpty()) " — $btError" else "")
        else "UDP :${Settings.mavlinkPort}"

    fun restart() {
        Log.i(TAG, "restarting on :${Settings.mavlinkPort} -> QGC :${Settings.qgcPort}")
        stop()

        Thread.sleep(200)
        Telemetry.mavlinkOk = false
        datalinkAddr = null; datalinkPort = 0
        start()
    }

    fun sendMode(name: String): Boolean {
        val cm = when (name.uppercase()) {
            "BRAKE" -> 17; "LOITER" -> 5; "RTL" -> 6; else -> return false
        }
        val pkt = buildDoSetMode(cm)

        val link = bt
        if (link != null) return link.write(pkt)

        val a = datalinkAddr ?: return false

        socket?.send(DatagramPacket(pkt, pkt.size, a, datalinkPort))
        socket?.send(DatagramPacket(pkt, pkt.size, a, datalinkPort))
        return true
    }

    private fun parseFrames(bytes: ByteArray, len: Int): Int {
        var i = 0
        while (i < len - 8) {
            val v2 = bytes[i].toInt() and 0xFF == 0xFD
            val v1 = bytes[i].toInt() and 0xFF == 0xFE
            if (!v1 && !v2) { i++; continue }
            val pl = bytes[i + 1].toInt() and 0xFF
            val hl = if (v2) 10 else 6
            val tl = hl + pl + 2
            if (i + tl > len) break
            val id = if (v2)
                (bytes[i + 7].toInt() and 0xFF) or ((bytes[i + 8].toInt() and 0xFF) shl 8) or ((bytes[i + 9].toInt() and 0xFF) shl 16)
            else bytes[i + 5].toInt() and 0xFF
            val sysid = if (v2) bytes[i + 5].toInt() and 0xFF else bytes[i + 3].toInt() and 0xFF

            val p = ByteArray(28)
            val n = minOf(pl, 28)
            System.arraycopy(bytes, i + hl, p, 0, n)
            val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            try {
                when {
                    id == 0 && pl >= 6 -> {
                        if (sysid == Config.TARGET_SYS) {
                            val cm = bb.getInt(0).toLong() and 0xFFFFFFFFL
                            Telemetry.mode = copterModes[cm.toInt()] ?: "MODE$cm"
                        }
                    }
                    id == 30 && pl >= 16 -> {
                        Telemetry.roll = bb.getFloat(4) * 180.0 / Math.PI
                        Telemetry.pitch = bb.getFloat(8) * 180.0 / Math.PI
                        var y = bb.getFloat(12) * 180.0 / Math.PI
                        Telemetry.yaw = if (y < 0) y + 360 else y
                    }
                    id == 33 && pl >= 18 -> {
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
                    id == 168 && pl >= 5 -> {
                        var d = bb.getFloat(0).toDouble() % 360.0
                        if (d < 0) d += 360.0
                        Telemetry.windDir = d
                        Telemetry.windSpeed = bb.getFloat(4).toDouble()
                    }
                }
            } catch (_: Exception) {  }
            i += tl
        }

        return i
    }

    private fun buildDoSetMode(customMode: Int): ByteArray {
        val payload = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
        payload.putFloat(0, 1f)
        payload.putFloat(4, customMode.toFloat())
        payload.putShort(28, 176)
        payload.put(30, Config.TARGET_SYS.toByte())
        payload.put(31, Config.TARGET_COMP.toByte())
        payload.put(32, 0)
        val pl = payload.array()

        val hdr = ByteArray(10)
        hdr[0] = 0xFD.toByte(); hdr[1] = pl.size.toByte(); hdr[2] = 0; hdr[3] = 0
        txSeq = (txSeq + 1) and 0xFF
        hdr[4] = txSeq.toByte(); hdr[5] = Config.GCS_SYS.toByte(); hdr[6] = Config.GCS_COMP.toByte()
        hdr[7] = (76 and 0xFF).toByte(); hdr[8] = ((76 shr 8) and 0xFF).toByte(); hdr[9] = ((76 shr 16) and 0xFF).toByte()

        var c = 0xFFFF
        for (k in 1..9) c = crcAccum(hdr[k].toInt() and 0xFF, c)
        for (b in pl) c = crcAccum(b.toInt() and 0xFF, c)
        c = crcAccum(CRC_EXTRA_COMMAND_LONG, c)

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
