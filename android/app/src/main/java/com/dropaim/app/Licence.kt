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

object Licence {

    private const val PUBLIC_KEY_B64 ="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENT/jxQfXHDg4txb7DYPfkoVaavq9t1u7xWNWvz9vSdA9F2eZ8T9N8nyvuh3bYtHNzaTH8LUCRw1mGTJXyKu/eQ=="

    private const val LIC_FILE = "licence.dat"
    private const val PREFIX = "DROPAIM-V1:"

    fun isConfigured(): Boolean =
        PUBLIC_KEY_B64.length > 40 && !PUBLIC_KEY_B64.startsWith("REPLACE")

    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val raw = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "no-android-id") +
                  "|" + Build.MODEL + "|" + Build.DEVICE
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = h.joinToString("") { "%02X".format(it) }
        return "DA-" + hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12)
    }

    fun isActivated(ctx: Context): Boolean {
        val f = File(ctx.filesDir, LIC_FILE)
        if (!f.exists()) return false
        return try { check(ctx, f.readText().trim()) } catch (e: Exception) { false }
    }

    fun activate(ctx: Context, code: String): Boolean {
        if (!check(ctx, code)) return false
        File(ctx.filesDir, LIC_FILE).writeText(code.trim())
        Metrics.log(ctx, "activated", mapOf("device_id" to deviceId(ctx)))
        return true
    }

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
            false
        }
    }
}
