package com.dropaim.app

/** Mirrors the CONFIG block in the original server.js. */
object Config {
    const val HTTP_PORT     = 3000                        // WebView loads http://127.0.0.1:3000/

    /**
     * The camera feeds this airframe might carry. One build serves every drone:
     * at startup each URL is DESCRIBEd, and only the ones that answer are
     * offered to the operator. A dual-sensor aircraft shows both and can switch;
     * a day-only aircraft simply shows one and no switch appears.
     *
     * Confirmed against the C13 by raw RTSP — each port serves exactly one
     * stream, and DESCRIBE returned 200 + SDP on these two and 454 on all 38
     * other paths tried. Resolutions decoded from the SDP's sprop-sps.
     *
     * zoom is the aim-solution scale factor, NOT a camera control: the solver
     * uses pxPerM = width/(2*alt)*zoom. Day and thermal have different optics,
     * so each carries its own value and switching cameras switches it too.
     * calibrated=false means that value is a placeholder — the UI warns, and the
     * figure must be measured against a known ground distance before that camera
     * is trusted for aiming.
     *
     * Add credentials inline if a camera demands them:
     *   "rtsp://admin:pass@192.168.144.108:554/stream=1"
     */
    data class Camera(
        val id: String,
        val label: String,
        val url: String,
        val zoom: Int,
        val calibrated: Boolean,
    )

    var cameras = listOf(
        //     id         label      url                                     zoom  calibrated
        Camera("day",     "DAY",     "rtsp://192.168.144.108:554/stream=1",   22,  true),
        Camera("thermal", "THERMAL", "rtsp://192.168.144.108:555/stream=2",   22,  false),
    )

    // The camera answered our own raw DESCRIBE with 200 but gave ExoPlayer 406
    // for the same URL, so the difference is in the request headers, not the
    // path. Send the User-Agent that is known to work.
    const val RTSP_USER_AGENT = "DropAim"

    // media3 omits 'Accept: application/sdp' from DESCRIBE. This camera's
    // rtsp_demo firmware requires it and returns 406 without it — proven by
    // capturing both requests to the same URL seconds apart. See RtspAcceptFix.
    const val RTSP_ADD_ACCEPT = true

    // Makes media3 log its entire RTSP conversation (tag: RtspClient), which is
    // the only way to see the headers it actually sends. Turn off for release.
    const val RTSP_DEBUG_LOG = true
    // Full path discovery: ~40 s of raw RTSP against every likely path. Already
    // done for the C13 — the results are pinned in cameras below — so leave it
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
