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
    // Confirmed by RtspProbe against the C13: each port serves exactly one
    // stream, and DESCRIBE returns 200 + SDP on these two and 454 on everything
    // else. Both are H.265 (RTP payload 96).
    //   554 -> stream=1   (555/stream=1 answers 454)
    //   555 -> stream=2   (554/stream=2 answers 454)
    var   rtspUrls          = listOf(
        "rtsp://192.168.144.108:554/stream=1",
        "rtsp://192.168.144.108:555/stream=2"
    )

    // The camera answered our own raw DESCRIBE with 200 but gave ExoPlayer 406
    // for the same URL, so the difference is in the request headers, not the
    // path. Send the User-Agent that is known to work.
    const val RTSP_USER_AGENT = "DropAim"

    // Makes media3 log its entire RTSP conversation (tag: RtspClient), which is
    // the only way to see the headers it actually sends. Turn off for release.
    const val RTSP_DEBUG_LOG = true
    // Full path discovery: ~40 s of raw RTSP against every likely path. Already
    // done for the C13 — the results are pinned in rtspUrls below — so leave it
    // off and just DESCRIBE the known URLs. Turn on for a new camera.
    const val RTSP_PATH_SWEEP = false

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
