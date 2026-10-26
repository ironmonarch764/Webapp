package com.myweb.app

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

private val CF_KEYS = arrayOf("cf_b", "cf_c", "cf_s", "cf_w")
private const val CF_DEFAULT = 50

// Menerapkan filter warna ke seluruh tampilan WebView (termasuk gambar).
// Semua slider di tengah (50) = normal, tanpa filter.
fun applyColorFilter(webView: WebView, prefs: SharedPreferences) {
    val b = prefs.getInt("cf_b", CF_DEFAULT)
    val c = prefs.getInt("cf_c", CF_DEFAULT)
    val s = prefs.getInt("cf_s", CF_DEFAULT)
    val w = prefs.getInt("cf_w", CF_DEFAULT)

    if (b == CF_DEFAULT && c == CF_DEFAULT && s == CF_DEFAULT && w == CF_DEFAULT) {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        return
    }

    val offset = (b - 50) * 1.6f          // kecerahan: -80 .. +80
    val contrast = 0.5f + c / 100f        // kontras: 0.5 .. 1.5
    val sat = s / 50f                     // saturasi: 0 .. 2
    val warm = (w - 50) / 50f             // dingin -1 .. hangat +1

    val cm = ColorMatrix()
    cm.setSaturation(sat)

    val ct = (1f - contrast) * 128f
    cm.postConcat(ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, ct,
        0f, contrast, 0f, 0f, ct,
        0f, 0f, contrast, 0f, ct,
        0f, 0f, 0f, 1f, 0f
    )))

    cm.postConcat(ColorMatrix(floatArrayOf(
        1f + 0.2f * warm, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f - 0.3f * warm, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )))

    cm.postConcat(ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, offset,
        0f, 1f, 0f, 0f, offset,
        0f, 0f, 1f, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    )))

    val paint = Paint()
    paint.colorFilter = ColorMatrixColorFilter(cm)
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
}

fun showColorFilterSheet(a: Activity, webView: WebView, prefs: SharedPreferences) {
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    val d = Dialog(a)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setCancelable(true)
    d.setCanceledOnTouchOutside(true)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val sheetBg = GradientDrawable().apply {
        setColor(Color.parseColor("#1F2024"))
        val r = px(26).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = sheetBg
        setPadding(px(20), px(16), px(20), px(24))
        isClickable = true
    }

    root.addView(TextView(a).apply {
        text = "🎨 Filter warna"
        textSize = 18f
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(match, wrap))

    val labels = arrayOf("Kecerahan", "Kontras", "Saturasi", "Dingin  ←  →  Hangat")
    val bars = ArrayList<SeekBar>()
    for (i in 0..3) {
        root.addView(TextView(a).apply {
            text = labels[i]
            textSize = 14f
            setTextColor(Color.parseColor("#D0D3DC"))
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })

        val sb = SeekBar(a).apply {
            max = 100
            progress = prefs.getInt(CF_KEYS[i], CF_DEFAULT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.edit().putInt(CF_KEYS[i], p).apply()
                    applyColorFilter(webView, prefs)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bars.add(sb)
        root.addView(sb, LinearLayout.LayoutParams(match, wrap))
    }

    val resetBg = GradientDrawable().apply {
        setColor(Color.parseColor("#2A2C33"))
        cornerRadius = px(18).toFloat()
    }
    root.addView(TextView(a).apply {
        text = "Reset"
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = resetBg
        setPadding(0, px(12), 0, px(12))
        isClickable = true
        setOnClickListener {
            val e = prefs.edit()
            CF_KEYS.forEach { e.putInt(it, CF_DEFAULT) }
            e.apply()
            bars.forEach { it.progress = CF_DEFAULT }
            applyColorFilter(webView, prefs)
        }
    }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(18) })

    // Seluruh layar jadi area ketuk: ketuk di luar panel = tutup (tanpa redup, agar warna terlihat asli)
    val overlay = FrameLayout(a)
    overlay.setOnClickListener { d.dismiss() }
    overlay.addView(root, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))

    d.setContentView(overlay)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(match, match)
        setDimAmount(0f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}
