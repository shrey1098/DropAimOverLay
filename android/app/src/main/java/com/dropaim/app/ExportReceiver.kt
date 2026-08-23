package com.dropaim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * USB-only metrics export.
 *
 * There is deliberately NO export button in the app — an operator cannot pull
 * the logbook. It is retrieved by the owner with the GCS plugged into their PC:
 *
 *   adb shell am broadcast -a com.dropaim.app.EXPORT \
 *        -n com.dropaim.app/.ExportReceiver --es token <EXPORT_TOKEN>
 *   adb pull /sdcard/Android/data/com.dropaim.app/files/dropaim_metrics.csv
 *
 * The file is written to the app's external files dir, which adb can read
 * without root and without any storage permission.
 *
 * Two gates: physical USB + debugging access, and the token below. The token is
 * a convenience check, not a secret worth much on its own — anyone who can run
 * adb on the device already has considerable access.
 */
class ExportReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val token = intent.getStringExtra("token")
        if (token != Config.EXPORT_TOKEN) {
            Log.w(TAG, "export refused: bad token")
            return
        }
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val csv = File(dir, "dropaim_metrics.csv")
            csv.writeText(Metrics.toCsv(ctx))

            // the raw drop log too — it is what the drag calibration needs
            val drops = File(ctx.filesDir, "drops.jsonl")
            if (drops.exists()) File(dir, "dropaim_drops.jsonl").writeText(drops.readText())

            Log.i(TAG, "exported ${Metrics.lines(ctx).size} metric records to ${csv.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "export failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Export"
        const val ACTION = "com.dropaim.app.EXPORT"
    }
}
