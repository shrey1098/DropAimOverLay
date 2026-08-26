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

    @Volatile private var mavPortV = Config.MAVLINK_PORT
    @Volatile private var qgcPortV = Config.QGC_PORT
    /** camera id -> URL, only for cameras the operator has overridden. */
    @Volatile private var urls: Map<String, String> = emptyMap()

    val mavlinkPort: Int get() = mavPortV
    val qgcPort: Int get() = qgcPortV

    /** Config.cameras with any operator-set URLs applied. */
    val cameras: List<Config.Camera>
        get() = Config.cameras.map { c -> urls[c.id]?.let { c.copy(url = it) } ?: c }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mavPortV = p.getInt(K_MAV, Config.MAVLINK_PORT)
        qgcPortV = p.getInt(K_QGC, Config.QGC_PORT)
        urls = try {
            val o = JSONObject(p.getString(K_URLS, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        Log.i(TAG, "mavlink=$mavPortV qgc=$qgcPortV overrides=${urls.keys}")
    }

    /**
     * Validate and persist. Returns null on success or a message naming what was
     * wrong — the operator is on an aircraft and needs to be told, not guess.
     * Any argument left null keeps its current value.
     */
    fun save(ctx: Context, mav: Int?, qgc: Int?, newUrls: Map<String, String>?): String? {
        val m = mav ?: mavPortV
        val q = qgc ?: qgcPortV
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

        mavPortV = m; qgcPortV = q; urls = clean
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_MAV, m).putInt(K_QGC, q)
            .putString(K_URLS, JSONObject(clean as Map<*, *>).toString())
            .apply()
        Log.i(TAG, "saved mavlink=$m qgc=$q overrides=${clean.keys}")
        return null
    }

    /** Back to the compiled-in defaults. */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        mavPortV = Config.MAVLINK_PORT; qgcPortV = Config.QGC_PORT; urls = emptyMap()
        Log.i(TAG, "reset to defaults")
    }

    fun toJson(): JSONObject {
        val cams = org.json.JSONArray()
        Config.cameras.forEach { c ->
            cams.put(JSONObject()
                .put("id", c.id).put("label", c.label)
                .put("url", urls[c.id] ?: c.url)
                .put("defaultUrl", c.url)
                .put("overridden", c.id in urls))
        }
        return JSONObject()
            .put("mavlinkPort", mavPortV).put("defaultMavlinkPort", Config.MAVLINK_PORT)
            .put("qgcPort", qgcPortV).put("defaultQgcPort", Config.QGC_PORT)
            .put("cameras", cams)
    }
}
