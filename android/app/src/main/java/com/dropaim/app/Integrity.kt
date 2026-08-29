package com.dropaim.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Refuse to run from a re-signed APK.
 *
 * The licence gate is a boolean in the app's own code. Anyone who can decompile
 * the APK can patch it out, rebuild and sign with their own key — so the licence
 * alone cannot tell a legitimate install from a cracked one. What a cracked
 * install CANNOT do is keep the original signing certificate, because that
 * requires the private key.
 *
 * This is deterrence, not security. A patcher who reaches this check can remove
 * it too. It raises the cost from "flip one boolean" to "find and remove every
 * check", which is the honest limit of anything that runs on hardware the
 * attacker owns.
 *
 * FAILS OPEN WHEN UNCONFIGURED, deliberately. BuildConfig.RELEASE_SIG is empty
 * for debug builds and for any release built before the fingerprint was
 * recorded, and an integrity check that bricked a build nobody could yet
 * configure would be a self-inflicted outage on a live aircraft. It only
 * enforces once a fingerprint has actually been set.
 */
object Integrity {

    private const val TAG = "Integrity"

    /** True when the app may run. Reason is logged, never shown to a user. */
    fun ok(ctx: Context): Boolean {
        val expected = BuildConfig.RELEASE_SIG.trim().uppercase().replace(":", "")
        if (expected.isEmpty()) return true          // unconfigured: fail open

        val actual = signature(ctx)
        if (actual == null) {
            // Could not read our own signature. Do not strand the operator on
            // what is most likely a platform quirk.
            Log.w(TAG, "signature unreadable — allowing")
            return true
        }
        if (actual == expected) return true
        Log.e(TAG, "signature mismatch: this APK was not signed with the release key")
        return false
    }

    /** SHA-256 of the signing certificate, uppercase hex, no separators. */
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

    /**
     * The fingerprint of the APK currently running, for putting into
     * keystore.properties after the first signed release build. Logged at
     * install time so it can be read with logcat rather than computed by hand
     * from keytool output.
     */
    fun logFingerprint(ctx: Context) {
        signature(ctx)?.let { Log.i(TAG, "this APK's signing SHA-256: $it") }
    }
}
