package com.dropaim.app

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.os.Build
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Offline activation.
 *
 * The app holds only the PUBLIC key, so it can CHECK an activation code but can
 * never CREATE one. Codes are produced solely by tools/dropaim-licence.js, which
 * lives on the owner's PC with the private key. No network is involved at any
 * point — the operator reads a Device ID off the screen, sends it by any means,
 * and types back the code.
 *
 * A code is a signature over "DROPAIM-V1:<DEVICE-ID>", so it is bound to that one
 * device: pasting it into a second GCS produces a different Device ID and fails.
 * Verification runs on EVERY launch, so copying the stored licence file to
 * another device does not work either.
 */
object Licence {

    // Replace with the output of `node dropaim-licence.js init`.
    // The build refuses to arm until this is a real key (see isConfigured()).
    private const val PUBLIC_KEY_B64 = "REPLACE_WITH_YOUR_PUBLIC_KEY"

    private const val LIC_FILE = "licence.dat"
    private const val PREFIX = "DROPAIM-V1:"

    fun isConfigured(): Boolean =
        PUBLIC_KEY_B64.length > 40 && !PUBLIC_KEY_B64.startsWith("REPLACE")

    /** Stable per-device fingerprint, shown to the operator. e.g. DA-7F3C-9B21-E45A */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val raw = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "no-android-id") +
                  "|" + Build.MODEL + "|" + Build.DEVICE
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = h.joinToString("") { "%02X".format(it) }
        return "DA-" + hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12)
    }

    /** True only if a stored code still validates against THIS device. */
    fun isActivated(ctx: Context): Boolean {
        val f = File(ctx.filesDir, LIC_FILE)
        if (!f.exists()) return false
        return try { check(ctx, f.readText().trim()) } catch (e: Exception) { false }
    }

    /** Validate a code and, if good, persist it. Returns true on success. */
    fun activate(ctx: Context, code: String): Boolean {
        if (!check(ctx, code)) return false
        File(ctx.filesDir, LIC_FILE).writeText(code.trim())
        Metrics.log(ctx, "activated", mapOf("device_id" to deviceId(ctx)))
        return true
    }

    /** The actual signature check — verified interoperable with the Node issuer. */
    private fun check(ctx: Context, code: String): Boolean {
        if (!isConfigured()) return false
        return try {
            val spki = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
            val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pk)
            sig.update((PREFIX + deviceId(ctx)).toByteArray(Charsets.UTF_8))
            sig.verify(Base64.decode(code.trim(), Base64.DEFAULT))
        } catch (e: Exception) {
            false      // malformed code, bad base64, wrong curve — all just "invalid"
        }
    }
}
