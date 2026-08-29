package com.dropaim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

class ExportReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val expected = BuildConfig.EXPORT_TOKEN
        if (expected.isEmpty()) {
            Log.w(TAG, "export refused: no token configured in this build")
            return
        }
        val token = intent.getStringExtra("token")
        if (token != expected) {
            Log.w(TAG, "export refused: bad token")
            return
        }
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val csv = File(dir, "dropaim_metrics.csv")
            csv.writeText(Metrics.toCsv(ctx))

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
