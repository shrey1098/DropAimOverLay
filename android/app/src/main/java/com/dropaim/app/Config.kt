package com.dropaim.app

/** Mirrors the CONFIG block in the original server.js. */
object Config {
    const val HTTP_PORT     = 3000                        // WebView loads http://127.0.0.1:3000/

    // Candidate video sources, tried in order until one plays. A dual-sensor
    // gimbal publishes thermal and daylight on separate URLs (and sometimes
    // separate ports), and which one answers varies by payload fit — so the app
    // works through the list rather than being pinned to one guess.
    // Add credentials inline if the camera demands them:
    //   "rtsp://admin:pass@192.168.144.108:554/stream=1"
    var   rtspUrls          = listOf(
        "rtsp://192.168.144.108:554/stream=1",   // thermal
        "rtsp://192.168.144.108:555/stream=2",   // second sensor
        "rtsp://192.168.144.108:554/main"        // legacy single-sensor path
    )
    const val MAVLINK_PORT  = 14551                        // datalink -> app
    const val QGC_PORT      = 14550                        // app <-> QGroundControl (localhost)
    const val TARGET_SYS    = 1
    const val TARGET_COMP   = 1
    const val GCS_SYS       = 255
    const val GCS_COMP      = 190
    const val VIDEO_W       = 854
    const val VIDEO_H       = 480
    const val VIDEO_FPS     = 15
    const val VIDEO_Q       = 5                            // mjpeg quality (1 best .. 31 worst)

    // ── Usage metrics ────────────────────────────────────────────────
    // Where the background uploader posts to. Leave as-is to disable uploading
    // entirely (records still accumulate locally and can be pulled over USB).
    // Must be https:// for anything leaving a controlled network.
    const val METRICS_URL   = "REPLACE_WITH_YOUR_HTTPS_ENDPOINT"
    fun metricsUrlConfigured(): Boolean =
        METRICS_URL.startsWith("https://") || METRICS_URL.startsWith("http://")

    // Second gate on the USB export broadcast. Change this per build.
    const val EXPORT_TOKEN  = "CHANGE-ME-EXPORT-TOKEN"
}
