package com.dropaim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * The handful of values that differ between ground stations and airframes,
 * editable by the operator and persisted on the device.
 *
 * These were compile-time constants, which meant a new GCS or a different
 * camera needed a rebuild, a reinstall and a fresh activation code. They are
 * the settings that actually vary in the field:
 *
 *   MAVLink port  Skydroid delivers telemetry to 14551; SIYI hands it straight
 *                 to QGC on 14550. Being able to set both ports lets the app be
 *                 placed correctly in either chain without a build.
 *   Camera URLs   Same gimbal family, but IP and stream path vary by fit.
 *
 * Config holds the defaults. Nothing here changes physics, payload constants or
 * the licence — only where the app looks for telemetry and video.
 */
object Settings {

    private const val TAG    = "Settings"
    private const val PREFS  = "dropaim_settings"
    private const val K_MAV  = "mavlink_port"
    private const val K_QGC  = "qgc_port"
    private const val K_URLS = "camera_urls"
    private const val K_ZOOM = "camera_zooms"
    private const val K_SRC  = "telemetry_source"
    private const val K_BT   = "bluetooth_address"

    /** Where telemetry comes from. Not every ground station puts MAVLink on IP:
     *  the SIYI MK32 hands it to Android over a Bluetooth serial link. */
    const val SRC_UDP = "udp"
    const val SRC_BT  = "bluetooth"

    @Volatile private var mavPortV = Config.MAVLINK_PORT
    @Volatile private var qgcPortV = Config.QGC_PORT
    @Volatile private var srcV     = SRC_UDP
    @Volatile private var btAddrV  = ""
    /** camera id -> URL, only for cameras the operator has overridden. */
    @Volatile private var urls: Map<String, String> = emptyMap()
    /** camera id -> aim zoom, measured by the operator against a known ground
     *  distance. Calibrating a sensor IS moving that slider, so the figure has
     *  to survive a restart or the calibration was never really done. */
    @Volatile private var zooms: Map<String, Double> = emptyMap()

    val mavlinkPort: Int get() = mavPortV
    val qgcPort: Int get() = qgcPortV
    val telemetrySource: String get() = srcV
    /** Empty means "pick the paired device that offers a serial port". */
    val bluetoothAddress: String get() = btAddrV

    /** Config.cameras with any operator-set URLs and zooms applied. */
    val cameras: List<Config.Camera>
        get() = Config.cameras.map { c ->
            var out = c
            urls[c.id]?.let { out = out.copy(url = it) }
            zooms[c.id]?.let { out = out.copy(zoom = it, calibrated = true) }
            out
        }

    /** The operator's measured zoom for a sensor, or null if never set. It wins
     *  over the compiled-in default AND over a variant's default: a figure
     *  measured on this airframe beats one guessed from the resolution. */
    fun zoomOverride(id: String): Double? = zooms[id]

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mavPortV = p.getInt(K_MAV, Config.MAVLINK_PORT)
        qgcPortV = p.getInt(K_QGC, Config.QGC_PORT)
        srcV     = p.getString(K_SRC, SRC_UDP) ?: SRC_UDP
        btAddrV  = p.getString(K_BT, "") ?: ""
        urls = try {
            val o = JSONObject(p.getString(K_URLS, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        zooms = try {
            val o = JSONObject(p.getString(K_ZOOM, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getDouble(it) }
        } catch (e: Exception) { emptyMap() }
        Log.i(TAG, "source=$srcV mavlink=$mavPortV qgc=$qgcPortV bt=$btAddrV overrides=${urls.keys}")
    }

    /**
     * Validate and persist. Returns null on success or a message naming what was
     * wrong — the operator is on an aircraft and needs to be told, not guess.
     * Any argument left null keeps its current value.
     */
    fun save(ctx: Context, mav: Int?, qgc: Int?, newUrls: Map<String, String>?,
             source: String? = null, btAddress: String? = null,
             newZooms: Map<String, Double>? = null): String? {
        val m = mav ?: mavPortV
        val q = qgc ?: qgcPortV
        val s = (source ?: srcV).lowercase()
        val bt = (btAddress ?: btAddrV).trim().uppercase()
        if (s != SRC_UDP && s != SRC_BT) return "telemetry source must be '$SRC_UDP' or '$SRC_BT'"
        // A blank address is legitimate: it means "use whichever paired device
        // offers a serial port". Anything else has to look like a MAC.
        if (bt.isNotEmpty() && !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(bt))
            return "Bluetooth address must look like AA:BB:CC:11:22:33"
        if (m !in 1024..65535) return "MAVLink port must be between 1024 and 65535"
        if (q !in 1024..65535) return "QGC port must be between 1024 and 65535"
        // Both are UDP ports on this device; the same number for each would mean
        // the app relaying telemetry straight back into its own socket.
        if (m == q) return "MAVLink and QGC ports must differ (both are $m)"

        val known = Config.cameras.map { it.id }.toSet()
        val clean = HashMap<String, String>()
        newUrls?.forEach { (id, url) ->
            if (id !in known) return "unknown camera '$id'"
            val u = url.trim()
            if (u.isEmpty()) return@forEach            // cleared = back to the built-in URL
            if (!u.startsWith("rtsp://")) return "camera '$id' URL must start with rtsp://"
            if (NetDiag.hostPort(u) == null) return "camera '$id' URL is not a valid address"
            clean[id] = u
        }

        // Merged, not replaced: the zoom slider saves one sensor at a time, and
        // saving the day camera must not wipe a measured thermal figure.
        val zoomOut = HashMap(zooms)
        newZooms?.forEach { (id, z) ->
            if (id !in known) return "unknown camera '$id'"
            // Zero would make pixels-per-metre zero and the aim point undefined.
            if (!z.isFinite() || z <= 0.0) return "camera '$id' zoom must be greater than 0"
            if (z > 100.0) return "camera '$id' zoom looks wrong (${z}) — expected 0.5 to 100"
            zoomOut[id] = z
        }

        mavPortV = m; qgcPortV = q; urls = clean; srcV = s; btAddrV = bt; zooms = zoomOut
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_MAV, m).putInt(K_QGC, q)
            .putString(K_SRC, s).putString(K_BT, bt)
            .putString(K_URLS, JSONObject(clean as Map<*, *>).toString())
            .putString(K_ZOOM, JSONObject(zoomOut as Map<*, *>).toString())
            .apply()
        Log.i(TAG, "saved source=$s mavlink=$m qgc=$q bt=$bt overrides=${clean.keys}")
        return null
    }

    /** Back to the compiled-in defaults. */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        mavPortV = Config.MAVLINK_PORT; qgcPortV = Config.QGC_PORT; urls = emptyMap()
        srcV = SRC_UDP; btAddrV = ""; zooms = emptyMap()
        Log.i(TAG, "reset to defaults")
    }

    fun toJson(): JSONObject {
        val cams = org.json.JSONArray()
        Config.cameras.forEach { c ->
            cams.put(JSONObject()
                .put("id", c.id).put("label", c.label)
                .put("url", urls[c.id] ?: c.url)
                .put("defaultUrl", c.url)
                .put("overridden", c.id in urls)
                .put("zoom", zooms[c.id] ?: c.zoom)
                .put("defaultZoom", c.zoom)
                .put("zoomSet", c.id in zooms))
        }
        return JSONObject()
            .put("mavlinkPort", mavPortV).put("defaultMavlinkPort", Config.MAVLINK_PORT)
            .put("qgcPort", qgcPortV).put("defaultQgcPort", Config.QGC_PORT)
            .put("telemetrySource", srcV)
            .put("bluetoothAddress", btAddrV)
            .put("bluetoothDevices", BluetoothLink.devicesJson())
            .put("cameras", cams)
    }
}
