package com.dropaim.app

import org.json.JSONObject

/**
 * Thread-safe latest-vehicle-state holder. Fields and JSON keys match exactly
 * what public/index.html expects on the /telemetry WebSocket.
 */
object Telemetry {
    @Volatile var roll = 0.0
    @Volatile var pitch = 0.0
    @Volatile var yaw = 0.0
    @Volatile var heading = 0.0
    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    @Volatile var altAGL: Double? = null
    @Volatile var altMSL: Double? = null
    @Volatile var groundspeed = 0.0
    @Volatile var vx = 0.0
    @Volatile var vy = 0.0
    @Volatile var vz = 0.0
    @Volatile var windSpeed: Double? = null
    @Volatile var windDir: Double? = null
    @Volatile var mode: String? = null

    @Volatile var mavlinkOk = false

    // Video liveness has exactly one owner: the frame publisher. This used to be
    // a second @Volatile field that nothing ever wrote, so the UI was told the
    // feed was dead even while frames were flowing.
    val videoOk: Boolean get() = FrameBus.connected

    fun toJson(): String {
        val o = JSONObject()
        o.put("roll", roll); o.put("pitch", pitch); o.put("yaw", yaw)
        o.put("heading", heading)
        o.put("lat", lat ?: JSONObject.NULL); o.put("lon", lon ?: JSONObject.NULL)
        o.put("altAGL", altAGL ?: JSONObject.NULL); o.put("altMSL", altMSL ?: JSONObject.NULL)
        o.put("groundspeed", groundspeed)
        o.put("vx", vx); o.put("vy", vy); o.put("vz", vz)
        o.put("windSpeed", windSpeed ?: JSONObject.NULL)
        o.put("windDir", windDir ?: JSONObject.NULL)
        o.put("mode", mode ?: JSONObject.NULL)
        o.put("videoOk", videoOk); o.put("mavlinkOk", mavlinkOk)
        return o.toString()
    }
}
