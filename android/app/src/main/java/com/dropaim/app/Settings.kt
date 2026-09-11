package com.dropaim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

object Settings {

    private const val TAG    = "Settings"
    private const val PREFS  = "dropaim_settings"
    private const val K_MAV  = "mavlink_port"
    private const val K_QGC  = "qgc_port"
    private const val K_URLS = "camera_urls"
    private const val K_ZOOM = "camera_zooms"
    private const val K_SRC  = "telemetry_source"
    private const val K_BT   = "bluetooth_address"
    private const val K_MURL = "metrics_url"
    private const val K_BIAS = "aim_bias"

    private const val BIAS_LIMIT_M = 50.0

    const val SRC_UDP = "udp"
    const val SRC_BT  = "bluetooth"

    @Volatile private var mavPortV = Config.MAVLINK_PORT
    @Volatile private var qgcPortV = Config.QGC_PORT
    @Volatile private var srcV     = SRC_UDP
    @Volatile private var btAddrV  = ""

    @Volatile private var metricsUrlV = ""

    @Volatile private var urls: Map<String, String> = emptyMap()

    @Volatile private var zooms: Map<String, Double> = emptyMap()

    @Volatile private var biasDownV  = 0.0
    @Volatile private var biasCrossV = 0.0
    @Volatile private var biasAtV    = 0L

    val mavlinkPort: Int get() = mavPortV
    val qgcPort: Int get() = qgcPortV
    val telemetrySource: String get() = srcV

    val bluetoothAddress: String get() = btAddrV

    val metricsUrl: String
        get() = metricsUrlV.ifEmpty { BuildConfig.METRICS_URL }

    fun metricsEnabled(): Boolean = metricsUrl.startsWith("https://")

    val cameras: List<Config.Camera>
        get() = Config.cameras.map { c ->
            var out = c
            urls[c.id]?.let { out = out.copy(url = it) }
            zooms[c.id]?.let { out = out.copy(zoom = it, calibrated = true) }
            out
        }

    fun zoomOverride(id: String): Double? = zooms[id]

    fun biasJson(): JSONObject = JSONObject()
        .put("saved", biasAtV > 0L)
        .put("down", biasDownV)
        .put("cross", biasCrossV)
        .put("savedAt", biasAtV)

    fun saveBias(ctx: Context, down: Double, cross: Double): String? {
        if (!down.isFinite() || !cross.isFinite()) return "bias must be a number"
        if (Math.abs(down) > BIAS_LIMIT_M || Math.abs(cross) > BIAS_LIMIT_M)
            return "bias looks wrong ($down / $cross m) — expected within ±${BIAS_LIMIT_M.toInt()} m"
        val at = System.currentTimeMillis()
        biasDownV = down; biasCrossV = cross; biasAtV = at
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_BIAS, JSONObject().put("down", down).put("cross", cross).put("t", at).toString())
            .apply()
        Log.i(TAG, "saved bias down=$down cross=$cross")
        return null
    }

    fun clearBias(ctx: Context) {
        biasDownV = 0.0; biasCrossV = 0.0; biasAtV = 0L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_BIAS).apply()
        Log.i(TAG, "bias cleared")
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mavPortV = p.getInt(K_MAV, Config.MAVLINK_PORT)
        qgcPortV = p.getInt(K_QGC, Config.QGC_PORT)
        srcV     = p.getString(K_SRC, SRC_UDP) ?: SRC_UDP
        btAddrV  = p.getString(K_BT, "") ?: ""
        metricsUrlV = p.getString(K_MURL, "") ?: ""
        urls = try {
            val o = JSONObject(p.getString(K_URLS, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        zooms = try {
            val o = JSONObject(p.getString(K_ZOOM, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getDouble(it) }
        } catch (e: Exception) { emptyMap() }

        try {
            val o = JSONObject(p.getString(K_BIAS, "{}") ?: "{}")
            val d = o.optDouble("down", 0.0)
            val c = o.optDouble("cross", 0.0)
            val t = o.optLong("t", 0L)
            if (t > 0L && d.isFinite() && c.isFinite() &&
                Math.abs(d) <= BIAS_LIMIT_M && Math.abs(c) <= BIAS_LIMIT_M) {
                biasDownV = d; biasCrossV = c; biasAtV = t
            } else { biasDownV = 0.0; biasCrossV = 0.0; biasAtV = 0L }
        } catch (e: Exception) { biasDownV = 0.0; biasCrossV = 0.0; biasAtV = 0L }
        Log.i(TAG, "source=$srcV mavlink=$mavPortV qgc=$qgcPortV bt=$btAddrV overrides=${urls.keys} " +
                   "bias=" + (if (biasAtV > 0L) "$biasDownV/$biasCrossV" else "none"))
    }

    fun save(ctx: Context, mav: Int?, qgc: Int?, newUrls: Map<String, String>?,
             source: String? = null, btAddress: String? = null,
             newZooms: Map<String, Double>? = null,
             metricsUrl: String? = null): String? {
        val m = mav ?: mavPortV
        val q = qgc ?: qgcPortV
        val s = (source ?: srcV).lowercase()
        val bt = (btAddress ?: btAddrV).trim().uppercase()
        if (s != SRC_UDP && s != SRC_BT) return "telemetry source must be '$SRC_UDP' or '$SRC_BT'"

        if (bt.isNotEmpty() && !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(bt))
            return "Bluetooth address must look like AA:BB:CC:11:22:33"
        val mu = (metricsUrl ?: metricsUrlV).trim()

        if (mu.isNotEmpty() && !mu.startsWith("https://"))
            return "metrics URL must start with https:// (got \"${mu.take(12)}…\")"
        if (m !in 1024..65535) return "MAVLink port must be between 1024 and 65535"
        if (q !in 1024..65535) return "QGC port must be between 1024 and 65535"

        if (m == q) return "MAVLink and QGC ports must differ (both are $m)"

        val known = Config.cameras.map { it.id }.toSet()
        val clean = HashMap<String, String>()
        newUrls?.forEach { (id, url) ->
            if (id !in known) return "unknown camera '$id'"
            val u = url.trim()
            if (u.isEmpty()) return@forEach
            if (!u.startsWith("rtsp://")) return "camera '$id' URL must start with rtsp://"
            if (NetDiag.hostPort(u) == null) return "camera '$id' URL is not a valid address"
            clean[id] = u
        }

        val zoomOut = HashMap(zooms)
        newZooms?.forEach { (id, z) ->
            if (id !in known) return "unknown camera '$id'"

            if (!z.isFinite() || z <= 0.0) return "camera '$id' zoom must be greater than 0"
            if (z > 100.0) return "camera '$id' zoom looks wrong (${z}) — expected 0.5 to 100"
            zoomOut[id] = z
        }

        mavPortV = m; qgcPortV = q; urls = clean; srcV = s; btAddrV = bt; zooms = zoomOut
        metricsUrlV = mu
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_MAV, m).putInt(K_QGC, q)
            .putString(K_SRC, s).putString(K_BT, bt).putString(K_MURL, mu)
            .putString(K_URLS, JSONObject(clean as Map<*, *>).toString())
            .putString(K_ZOOM, JSONObject(zoomOut as Map<*, *>).toString())
            .apply()
        Log.i(TAG, "saved source=$s mavlink=$m qgc=$q bt=$bt overrides=${clean.keys}")
        return null
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        mavPortV = Config.MAVLINK_PORT; qgcPortV = Config.QGC_PORT; urls = emptyMap()
        srcV = SRC_UDP; btAddrV = ""; zooms = emptyMap(); metricsUrlV = ""
        biasDownV = 0.0; biasCrossV = 0.0; biasAtV = 0L
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
            .put("metricsUrl", metricsUrlV)
            .put("defaultMetricsUrl", BuildConfig.METRICS_URL)
            .put("metricsEnabled", metricsEnabled())
            .put("cameras", cams)
    }
}
