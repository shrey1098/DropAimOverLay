package com.dropaim.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

object Integrity {

    private const val TAG = "Integrity"

    fun ok(ctx: Context): Boolean {
        val expected = BuildConfig.RELEASE_SIG.trim().uppercase().replace(":", "")
        if (expected.isEmpty()) return true

        val actual = signature(ctx)
        if (actual == null) {

            Log.w(TAG, "signature unreadable — allowing")
            return true
        }
        if (actual == expected) return true
        Log.e(TAG, "signature mismatch: this APK was not signed with the release key")
        return false
    }

    private fun signature(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        sigs?.firstOrNull()?.let { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "signature read failed: ${e.message}")
        null
    }

    fun logFingerprint(ctx: Context) {
        signature(ctx)?.let { Log.i(TAG, "this APK's signing SHA-256: $it") }
    }
}
