package com.myweb.app

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.pow

// Auto-scroll halus: bergerak tiap frame layar (ikut 60/120/144 Hz), kecepatan dalam dp per detik.
// Berhenti otomatis di dasar halaman (menunggu 2 detik kalau gambar masih dimuat).
class AutoScroller(private val webView: WebView, prefs: SharedPreferences) {

    var running = false
        private set

    private var speedDp = 28f
    private var lastNanos = 0L
    private var remainder = 0f
    private var stuckSince = 0L

    init {
        setSpeedFromProgress(prefs.getInt("as_speed", 30))
    }

    // Slider 0..100 -> 10..300 dp/detik (skala eksponensial: bagian pelan lebih halus diatur)
    fun setSpeedFromProgress(p: Int) {
        speedDp = (10.0 * 30.0.pow(p / 100.0)).toFloat()
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos != 0L) {
                // dt dibatasi agar tidak melompat jauh setelah layar sempat tersendat
                val dt = ((frameTimeNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                remainder += speedDp * webView.resources.displayMetrics.density * dt
                val move = remainder.toInt()
                if (move > 0) {
                    remainder -= move
                    webView.scrollBy(0, move)
                }
            }
            lastNanos = frameTimeNanos

            if (webView.canScrollVertically(1)) {
                stuckSince = 0L
            } else {
                if (stuckSince == 0L) stuckSince = frameTimeNanos
                else if (frameTimeNanos - stuckSince > 2_000_000_000L) {
                    stop()
                    return
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        remainder = 0f
        stuckSince = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}

fun showScrollSheet(a: Activity, scroller: AutoScroller, prefs: SharedPreferences) {
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    val d = Dialog(a)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setCancelable(true)
    d.setCanceledOnTouchOutside(true)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val sheetBg = GradientDrawable().apply {
        setColor(Color.parseColor("#F01F2024"))
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
        text = "⏬ Scroll"
        textSize = 18f
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(match, wrap))

    // --- Ketuk tepi (bisa dimatikan) ---
    val swRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(12), 0, px(2))
    }
    swRow.addView(TextView(a).apply {
        text = "Ketuk tepi = geser 1 layar"
        textSize = 16f
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(0, wrap, 1f))
    swRow.addView(Switch(a).apply {
        isChecked = prefs.getBoolean("edge_on", true)
        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("edge_on", c).apply() }
    })
    root.addView(swRow)
    root.addView(TextView(a).apply {
        text = "Tepi bawah = turun, tepi atas = naik, tengah = tombol menu"
        textSize = 12f
        setTextColor(Color.parseColor("#8A8E99"))
    }, LinearLayout.LayoutParams(match, wrap))

    // --- Auto-scroll ---
    root.addView(TextView(a).apply {
        text = "Auto-scroll"
        textSize = 16f
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(18) })

    val speedTv = TextView(a).apply {
        text = "Kecepatan:  " + prefs.getInt("as_speed", 30)
        textSize = 14f
        setTextColor(Color.parseColor("#D0D3DC"))
    }
    root.addView(speedTv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })

    val bar = SeekBar(a).apply {
        max = 100
        progress = prefs.getInt("as_speed", 30)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                speedTv.text = "Kecepatan:  " + p
                if (!fromUser) return
                prefs.edit().putInt("as_speed", p).apply()
                scroller.setSpeedFromProgress(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    root.addView(bar, LinearLayout.LayoutParams(match, wrap))

    root.addView(TextView(a).apply {
        text = "0 = pelan sekali, 100 = cepat. Sentuh layar untuk berhenti."
        textSize = 12f
        setTextColor(Color.parseColor("#8A8E99"))
    }, LinearLayout.LayoutParams(match, wrap))

    val btnBg = GradientDrawable().apply {
        setColor(Color.parseColor("#2A2C33"))
        cornerRadius = px(18).toFloat()
    }
    val btn = TextView(a)
    btn.text = if (scroller.running) "⏸ Berhenti" else "▶ Mulai"
    btn.textSize = 16f
    btn.setTextColor(Color.WHITE)
    btn.gravity = Gravity.CENTER
    btn.background = btnBg
    btn.setPadding(0, px(12), 0, px(12))
    btn.isClickable = true
    btn.setOnClickListener {
        if (scroller.running) {
            scroller.stop()
            btn.text = "▶ Mulai"
        } else {
            scroller.start()
            d.dismiss()
        }
    }
    root.addView(btn, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(16) })

    // Seluruh layar jadi area ketuk: ketuk di luar panel = tutup (tanpa redup)
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
