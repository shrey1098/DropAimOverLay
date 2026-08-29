package com.dropaim.app

object Config {
    const val HTTP_PORT     = 3000

    data class Variant(
        val model: String,
        val width: Int,
        val height: Int,
        val zoom: Double,
        val calibrated: Boolean,
    )

    data class Camera(
        val id: String,
        val label: String,
        val url: String,
        val zoom: Double,
        val calibrated: Boolean,

        val variants: List<Variant> = emptyList(),
    ) {

        fun variantFor(w: Int, h: Int): Variant? =
            variants.firstOrNull { it.width == w && it.height == h }
    }

    var cameras = listOf(
        Camera(
            id = "day", label = "DAY",
            url = "rtsp://192.168.144.108:554/stream=1",
            zoom = 22.0, calibrated = true,
        ),
        Camera(
            id = "thermal", label = "THERMAL",
            url = "rtsp://192.168.144.108:555/stream=2",
            zoom = 1.0, calibrated = false,
            variants = listOf(

                Variant("C12",  384, 288, 1.0, false),
                Variant("C13",  640, 512, 1.0, false),
            ),
        ),
    )

    const val RTSP_USER_AGENT = "DropAim"

    const val RTSP_ADD_ACCEPT = true

    const val RTSP_DEBUG_LOG = true

    const val RTSP_PATH_SWEEP = false

    const val MAVLINK_PORT  = 14551
    const val QGC_PORT      = 14550
    const val TARGET_SYS    = 1
    const val TARGET_COMP   = 1
    const val GCS_SYS       = 255
    const val GCS_COMP      = 190
    const val VIDEO_W       = 854
    const val VIDEO_H       = 480
    const val VIDEO_FPS     = 15
    const val VIDEO_Q       = 5

}
