package com.dropaim.app

/** Mirrors the CONFIG block in the original server.js. */
object Config {
    const val HTTP_PORT     = 3000                        // WebView loads http://127.0.0.1:3000/
    var   rtspUrl           = "rtsp://192.168.144.108:554/main"
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
}
