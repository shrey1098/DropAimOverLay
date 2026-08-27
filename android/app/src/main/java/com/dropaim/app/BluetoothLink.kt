package com.dropaim.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Telemetry over a Bluetooth serial (SPP / RFCOMM) link.
 *
 * Not every ground station puts MAVLink on IP. The SIYI MK32 hands it to
 * Android over Bluetooth serial at 57600 — which is why a scan of eleven UDP
 * ports, every socket on the device and the whole datalink subnet found nothing:
 * there was nothing on the network to find.
 *
 * This is a byte STREAM, not datagrams. A MAVLink frame can be split across two
 * reads and two frames can arrive in one, so the caller must buffer and only
 * consume whole frames — [MavlinkService] does that. Handing a half frame to a
 * parser that assumes datagram boundaries silently drops telemetry.
 *
 * The link is exclusive: RFCOMM is point to point, so whichever app holds it,
 * holds it. Nothing else on the ground station can read telemetry while this is
 * connected, which is why the app relays what it receives back out over UDP.
 */
class BluetoothLink(
    /** Called on the reader thread with a buffer and how much of it is valid. */
    private val onBytes: (ByteArray, Int) -> Unit,
    /** connected state + a human-readable reason when it is false. */
    private val onState: (Boolean, String) -> Unit
) {

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var connected = false; private set
    @Volatile var deviceName = ""; private set

    /** [address] empty = pick a paired device that offers a serial port. */
    fun start(address: String) {
        if (running) return
        running = true
        Thread({
            var attempt = 0
            while (running) {
                val dev = pick(address)
                if (dev == null) {
                    fail("No paired Bluetooth device offers a serial port. " +
                         "Pair the datalink in Android Settings first.")
                    sleep(RETRY_MS)
                    continue
                }
                try {
                    connect(dev)
                    attempt = 0            // connected: next drop retries promptly
                    read()                 // blocks until the link drops
                } catch (e: SecurityException) {
                    fail("Bluetooth permission not granted: ${e.message}")
                } catch (e: Exception) {
                    fail("${dev.address}: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    close()
                }
                if (running) sleep(minOf(RETRY_MS shl minOf(attempt++, 4), MAX_RETRY_MS))
            }
        }, "mav-bt").start()
    }

    fun stop() { running = false; close() }

    /** Mode commands go back out the same link. Returns false if it is down. */
    fun write(b: ByteArray): Boolean {
        val o = out ?: return false
        return try { o.write(b); o.flush(); true }
        catch (e: Exception) { Log.w(TAG, "write failed: ${e.message}"); false }
    }

    // ── internals ────────────────────────────────────────────────────
    private fun pick(address: String): BluetoothDevice? {
        val paired = bonded()
        if (address.isNotEmpty()) return paired.firstOrNull { it.address.equals(address, true) }
        // No explicit choice: prefer a device advertising the serial profile.
        return paired.firstOrNull { offersSpp(it) } ?: paired.firstOrNull()
    }

    private fun connect(dev: BluetoothDevice) {
        deviceName = try { dev.name ?: dev.address } catch (e: SecurityException) { dev.address }
        Log.i(TAG, "connecting to $deviceName (${dev.address})")
        // Discovery is expensive and will stall or fail the connect if it is
        // running — the platform docs are explicit about cancelling it first.
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}

        // Three ways in, because serial bridges vary. Secure first (what a
        // properly-paired device wants), then insecure (bridges that do not
        // implement pairing-backed encryption), then channel 1 directly (ones
        // whose SDP record is missing or wrong — common on cheap modules).
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure SPP"   to { dev.createRfcommSocketToServiceRecord(SPP) },
            "insecure SPP" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            "channel 1"    to {
                val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(dev, 1) as BluetoothSocket
            })

        var last: Exception? = null
        for ((how, make) in attempts) {
            try {
                val s = make()
                s.connect()
                socket = s; out = s.outputStream
                connected = true
                onState(true, "")
                Log.i(TAG, "connected to $deviceName via $how")
                return
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "$how failed: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }
        throw last ?: java.io.IOException("could not open a serial link")
    }

    private fun read() {
        val s = socket ?: return
        val ins: InputStream = s.inputStream
        val buf = ByteArray(1024)
        while (running) {
            val n = ins.read(buf)
            if (n < 0) throw java.io.IOException("link closed by the datalink")
            if (n > 0) onBytes(buf, n)
        }
    }

    private fun close() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
    }

    private fun fail(why: String) {
        connected = false
        Log.e(TAG, why)
        onState(false, why)
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    companion object {
        private const val TAG = "MavBT"
        private const val RETRY_MS = 2000L
        private const val MAX_RETRY_MS = 30000L
        /** Serial Port Profile — what every MAVLink-over-Bluetooth bridge uses. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun bonded(): List<BluetoothDevice> = try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) { Log.w(TAG, "bonded devices: ${e.message}"); emptyList() }

        fun offersSpp(d: BluetoothDevice): Boolean = try {
            d.uuids?.any { it.uuid.toString().startsWith("00001101", true) } == true
        } catch (e: Exception) { false }

        /** Paired devices, for the settings picker. Serial-capable ones first. */
        fun devicesJson(): org.json.JSONArray {
            val arr = org.json.JSONArray()
            bonded().sortedByDescending { offersSpp(it) }.forEach { d ->
                arr.put(org.json.JSONObject()
                    .put("address", d.address)
                    .put("name", try { d.name ?: d.address } catch (e: SecurityException) { d.address })
                    .put("spp", offersSpp(d)))
            }
            return arr
        }
    }
}
