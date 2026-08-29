package com.dropaim.app

import android.content.Context
import android.util.Log
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val r = uploadOnce(applicationContext)
        return when {
            !r.attempted -> Result.success()
            r.ok && r.more -> Result.retry()
            r.ok -> Result.success()
            else -> Result.retry()
        }
    }

    data class Outcome(val attempted: Boolean, val ok: Boolean,
                       val sent: Int, val more: Boolean, val detail: String)

    companion object {
        private const val TAG = "Upload"
        private const val BATCH = 200
        private const val WORK = "dropaim-metrics-upload"

        fun uploadOnce(ctx: Context): Outcome {

            Settings.load(ctx)
            if (!Settings.metricsEnabled())
                return Outcome(false, false, 0, false,
                    "No collector set, or it is not https. Uploading is off; records are kept on the device.")

            val pending = Metrics.pending(ctx)
            if (pending.isEmpty())
                return Outcome(false, true, 0, false, "Nothing waiting to upload.")

            val batch = pending.take(BATCH)
            val host = try { URL(Settings.metricsUrl).host } catch (e: Exception) { "?" }
            return try {
                val arr = JSONArray()
                batch.forEach { l -> try { arr.put(JSONObject(l)) } catch (e: Exception) {} }
                val body = JSONObject()
                    .put("device_id", Licence.deviceId(ctx))
                    .put("app_version", Metrics.appVersion(ctx))
                    .put("records", arr)
                    .toString()

                val c = (URL(Settings.metricsUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-DropAim-Device", Licence.deviceId(ctx))

                    if (BuildConfig.METRICS_TOKEN.isNotEmpty())
                        setRequestProperty("Authorization", "Bearer " + BuildConfig.METRICS_TOKEN)
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                c.disconnect()

                if (code in 200..299) {
                    Metrics.setCursor(ctx, Metrics.cursor(ctx) + batch.size)
                    val more = Metrics.pending(ctx).isNotEmpty()
                    Log.i(TAG, "uploaded ${batch.size} records to $host")
                    Outcome(true, true, batch.size, more,
                        "Sent ${batch.size} record(s) to $host — accepted (HTTP $code)." +
                        if (more) " More still waiting." else "")
                } else {
                    Log.w(TAG, "server said $code — will retry")
                    Outcome(true, false, 0, true, when (code) {
                        401 -> "$host refused the token (HTTP 401). The app's metricsToken and the " +
                               "collector's METRICS_TOKEN do not match. Records are kept."
                        404 -> "$host has no collector at that path (HTTP 404). The URL should end " +
                               "in /v1/metrics. Records are kept."
                        413 -> "$host rejected the batch as too large (HTTP 413). Records are kept."
                        429 -> "$host is rate limiting (HTTP 429). Records are kept; it will retry."
                        else -> "$host answered HTTP $code. Records are kept and will be retried."
                    })
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload failed: ${e.message} — will retry")
                Outcome(true, false, 0, true, "Could not reach $host: ${e.message}. " +
                    "Check the ground station has internet and the certificate is valid. Records are kept.")
            }
        }

        fun testUpload(ctx: Context): Outcome {
            Metrics.log(ctx, "setup_test")
            return uploadOnce(ctx)
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
