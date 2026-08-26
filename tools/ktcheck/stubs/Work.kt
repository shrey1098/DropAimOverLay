// Minimal androidx stub for an offline typecheck of the DropAim app sources.
// The container cannot reach dl.google.com (Google Maven), so the real AARs are
// unavailable. Signatures were transcribed from the actual upstream source at the
// version the app builds against (media3 1.4.1, github.com/androidx/media tag
// 1.4.1), so a type error against these stubs is a type error against the real
// library. Only the members the app actually uses are declared.

package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit

abstract class ListenableWorker(appContext: Context, params: WorkerParameters) {
    val applicationContext: Context = appContext
    abstract class Result {
        companion object {
            @JvmStatic fun success(): Result = object : Result() {}
            @JvmStatic fun retry(): Result = object : Result() {}
            @JvmStatic fun failure(): Result = object : Result() {}
        }
    }
}

class WorkerParameters

abstract class Worker(appContext: Context, params: WorkerParameters) :
    ListenableWorker(appContext, params) {
    abstract fun doWork(): Result
}

enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED, NOT_ROAMING, METERED }
enum class BackoffPolicy { EXPONENTIAL, LINEAR }
enum class ExistingPeriodicWorkPolicy { KEEP, REPLACE, UPDATE }

class Constraints {
    class Builder {
        fun setRequiredNetworkType(networkType: NetworkType): Builder = this
        fun build(): Constraints = Constraints()
    }
}

class PeriodicWorkRequest {
    class Builder {
        fun setConstraints(constraints: Constraints): Builder = this
        fun setBackoffCriteria(backoffPolicy: BackoffPolicy, backoffDelay: Long, timeUnit: TimeUnit): Builder = this
        fun build(): PeriodicWorkRequest = PeriodicWorkRequest()
    }
}

/** work-runtime-ktx: reified builder taking (Long, TimeUnit). */
@Suppress("FunctionName", "UNUSED_PARAMETER")
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: TimeUnit
): PeriodicWorkRequest.Builder = PeriodicWorkRequest.Builder()

abstract class WorkManager {
    abstract fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        periodicWork: PeriodicWorkRequest
    )
    companion object {
        @JvmStatic fun getInstance(context: Context): WorkManager =
            throw UnsupportedOperationException("stub")
    }
}
