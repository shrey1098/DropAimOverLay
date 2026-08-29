package com.dropaim.app

import android.content.Context
import android.util.Log
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background metrics upload.
 *
 * Runs under WorkManager, so it keeps working when the app is closed and is
 * re-scheduled after a reboot. It only runs when the device actually has a
 * connection; with no network it simply does not fire, and the logbook keeps
 * growing offline until one appears.
 *
 * Records are only marked as sent after the server confirms, so a half-finished
 * upload is retried rather than lost.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        // WorkManager can start this in a fresh process where MainActivity has
        // never run, and Settings is only populated by load(). Without this the
        // operator's collector override is ignored and the batch goes to the
        // compiled-in URL instead. Cheap and idempotent.
        Settings.load(ctx)
        // No collector configured, or a non-https one: do nothing and keep the
        // records. They still accumulate locally for the USB export.
        if (!Settings.metricsEnabled()) return Result.success()

        val pending = Metrics.pending(ctx)
        if (pending.isEmpty()) return Result.success()

        val batch = pending.take(BATCH)
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
                // Shared secret with the collector, injected at build time from
                // a git-ignored file. The collector refuses an unauthenticated
                // POST, so without it nothing uploads — which is the safe way
                // round: records stay on the device rather than going somewhere
                // unauthenticated.
                if (BuildConfig.METRICS_TOKEN.isNotEmpty())
                    setRequestProperty("Authorization", "Bearer " + BuildConfig.METRICS_TOKEN)
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.disconnect()

            if (code in 200..299) {
                Metrics.setCursor(ctx, Metrics.cursor(ctx) + batch.size)
                Log.i(TAG, "uploaded ${batch.size} records")
                // more waiting? come back promptly rather than waiting a full period
                if (Metrics.pending(ctx).isNotEmpty()) Result.retry() else Result.success()
            } else {
                Log.w(TAG, "server said $code — will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed: ${e.message} — will retry")
            Result.retry()          // offline / DNS / timeout: try again later
        }
    }

    companion object {
        private const val TAG = "Upload"
        private const val BATCH = 200
        private const val WORK = "dropaim-metrics-upload"

        /** Idempotent: safe to call on every app start. */
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
