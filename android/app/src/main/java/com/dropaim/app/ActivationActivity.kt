package com.dropaim.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * The lock screen. Until a valid code is entered the app does nothing else —
 * no feed, no telemetry, no targeting. Built in code rather than XML to keep it
 * self-contained.
 */
class ActivationActivity : AppCompatActivity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = Licence.deviceId(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080c10"))
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun label(t: String, size: Float, col: String, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(col))
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
        }

        root.addView(label("AI-BASED MUNITION DROPPING", 16f, "#7fa0b8", true))
        root.addView(label("ACCURACY ENHANCER", 16f, "#7fa0b8", true))
        root.addView(label("ACTIVATION REQUIRED", 22f, "#ffd700", true))
        root.addView(label("This device is not activated. Send the Device ID below to the system owner and enter the activation code you receive.",
            13f, "#7fa0b8"))

        root.addView(label("DEVICE ID", 12f, "#3d607a"))
        val idView = label(id, 26f, "#00e5ff", true)
        idView.setTextIsSelectable(true)
        root.addView(idView)

        root.addView(Button(this).apply {
            text = "COPY DEVICE ID"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("DropAim Device ID", id))
                Toast.makeText(this@ActivationActivity, "Device ID copied", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(label("ACTIVATION CODE", 12f, "#3d607a"))
        val input = EditText(this).apply {
            hint = "paste the activation code here"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#3d607a"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 5
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(input)

        val msg = label("", 14f, "#ff3b55", true)
        root.addView(msg)

        root.addView(Button(this).apply {
            text = "ACTIVATE"
            setOnClickListener {
                val code = input.text.toString().trim()
                when {
                    !Licence.isConfigured() ->
                        msg.text = "Build error: no public key compiled in."
                    code.isEmpty() ->
                        msg.text = "Enter the activation code."
                    Licence.activate(this@ActivationActivity, code) -> {
                        Toast.makeText(this@ActivationActivity, "Activated", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@ActivationActivity, MainActivity::class.java))
                        finish()
                    }
                    else ->
                        msg.text = "Invalid code for this device."
                }
            }
        })

        val sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)
    }

    // Locked means locked: back does not get past this screen.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishAffinity() }
}
