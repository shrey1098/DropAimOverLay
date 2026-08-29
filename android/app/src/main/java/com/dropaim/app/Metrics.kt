package com.dropaim.app

import android.content.Context
import org.json.JSONObject
import java.io.File

object Metrics {

    private const val FILE = "metrics.jsonl"
    private const val CURSOR = "metrics.cursor"
    private const val MAX_BYTES = 4 * 1024 * 1024

    @Synchronized
    fun log(ctx: Context, event: String, fields: Map<String, Any?> = emptyMap()) {
        try {
            val o = JSONObject()
            o.put("ts", java.time.Instant.now().toString())
            o.put("event", event)
            o.put("device_id", Licence.deviceId(ctx))
            o.put("app_version", appVersion(ctx))
            for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
            val f = File(ctx.filesDir, FILE)
            if (f.length() > MAX_BYTES) trim(ctx, f)
            f.appendText(o.toString() + "\n")
        } catch (e: Exception) {  }
    }

    fun lines(ctx: Context): List<String> {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    fun cursor(ctx: Context): Int =
        try { File(ctx.filesDir, CURSOR).readText().trim().toInt() } catch (e: Exception) { 0 }

    fun setCursor(ctx: Context, n: Int) {
        try { File(ctx.filesDir, CURSOR).writeText(n.toString()) } catch (e: Exception) {}
    }

    fun pending(ctx: Context): List<String> = lines(ctx).drop(cursor(ctx))

    private fun trim(ctx: Context, f: File) {
        val all = f.readLines().filter { it.isNotBlank() }
        val keep = all.drop(all.size / 2)
        f.writeText(keep.joinToString("\n") + "\n")
        setCursor(ctx, maxOf(0, cursor(ctx) - (all.size - keep.size)))
    }

    fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    fun toCsv(ctx: Context): String {
        val rows = lines(ctx).mapNotNull { try { JSONObject(it) } catch (e: Exception) { null } }
        if (rows.isEmpty()) return "no records\n"
        val cols = LinkedHashSet<String>()
        rows.forEach { r -> r.keys().forEach { cols.add(it) } }
        fun esc(s: String) = if (s.contains(Regex("[\",\n]"))) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder(cols.joinToString(",")).append("\n")
        rows.forEach { r ->
            sb.append(cols.joinToString(",") { c -> esc(if (r.has(c)) r.get(c).toString() else "") }).append("\n")
        }
        return sb.toString()
    }
}
