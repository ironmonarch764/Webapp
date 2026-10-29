package com.myweb.app

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.pow

// Apakah ada gambar di ujung dokumen yang belum selesai dimuat (tanda konten di bawah masih datang)
private const val IMAGES_PENDING_JS =
    "(function(){var im=document.images,n=im.length,c=0;for(var i=Math.max(0,n-10);i<n;i++){if(!im[i].complete)c++;}return c>0;})()"

// Auto-scroll halus: bergerak tiap frame layar, kecepatan dalam dp per detik berdasarkan WAKTU (AutoScrollMath),
// jadi jarak per detik sama di 60/90/120/144 Hz.
// Berhenti otomatis di dasar halaman: menunggu 2 detik (tinggi dokumen bisa bertambah), dan sampai 8 detik bila
// halaman melaporkan gambar di ujung masih dimuat. Berhenti juga bila WebView sudah lepas dari layar (Activity ditutup).
class AutoScroller(private val webView: WebView, prefs: SharedPreferences) {

    var running = false
        private set

    // Dipanggil setiap kali auto-scroll mulai (true) atau berhenti (false)
    var onStateChange: ((Boolean) -> Unit)? = null

    private var speedDp = 28f
    private val math = AutoScrollMath()
    private var imagesPending = false

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
            // WebView sudah dilepas/dihancurkan: berhenti, jangan terus memanggil callback pada view yang mati
            if (!webView.isAttachedToWindow) {
                stop()
                return
            }
            val move = math.step(frameTimeNanos, speedDp * webView.resources.displayMetrics.density)
            if (move > 0) webView.scrollBy(0, move)

            if (math.shouldStop(frameTimeNanos, webView.canScrollVertically(1), imagesPending)) {
                stop()
                return
            }
            if (math.wantImageCheck(frameTimeNanos)) checkImages()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun checkImages() {
        webView.evaluateJavascript(IMAGES_PENDING_JS) { r ->
            if (running) imagesPending = r?.trim() == "true"
        }
    }

    fun start() {
        if (running) return
        running = true
        math.reset()
        imagesPending = false
        Choreographer.getInstance().postFrameCallback(callback)
        onStateChange?.invoke(true)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
        onStateChange?.invoke(false)
    }
}

// Panel kecil melayang untuk auto-scroll.
//  - Menu "Auto-scroll: ON" memunculkan panel (mode siap).
//  - Tekan ▶ : auto-scroll jalan, panel menghilang.
//  - Sentuh layar / sampai dasar halaman : auto-scroll berhenti, panel muncul lagi.
//  - Tekan ✕ (atau menu "Auto-scroll: OFF") : keluar dari mode, panel hilang.
class AutoScrollPanel(
    private val a: Activity,
    private val scroller: AutoScroller,
    private val prefs: SharedPreferences
) {
    var modeOn = false
        private set

    private var box: LinearLayout? = null

    init {
        scroller.onStateChange = { refresh() }
    }

    fun toggle() {
        if (modeOn) exit() else enter()
    }

    private fun enter() {
        modeOn = true
        refresh()
    }

    private fun exit() {
        modeOn = false
        scroller.stop()
        refresh()
    }

    private fun refresh() {
        if (!modeOn) {
            box?.visibility = View.GONE
            return
        }
        val b = box ?: build()
        b.visibility = if (scroller.running) View.GONE else View.VISIBLE
    }

    private fun build(): LinearLayout {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#EE1F2024"))
            cornerRadius = px(28).toFloat()
        }
        val b = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(px(6), px(4), px(6), px(4))
            isClickable = true
        }

        b.addView(TextView(a).apply {
            text = "▶"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(px(14), px(8), px(14), px(8))
            isClickable = true
            setOnClickListener { scroller.start() }
        })

        val valueTv = TextView(a).apply {
            text = prefs.getInt("as_speed", 30).toString()
            textSize = 13f
            setTextColor(Color.parseColor("#D0D3DC"))
            gravity = Gravity.CENTER
            minWidth = px(30)
        }
        val bar = SeekBar(a).apply {
            max = 100
            progress = prefs.getInt("as_speed", 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    valueTv.text = p.toString()
                    if (!fromUser) return
                    prefs.edit().putInt("as_speed", p).apply()
                    scroller.setSpeedFromProgress(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        b.addView(bar, LinearLayout.LayoutParams(px(140), wrap))
        b.addView(valueTv)

        b.addView(TextView(a).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#D0D3DC"))
            gravity = Gravity.CENTER
            setPadding(px(12), px(8), px(12), px(8))
            isClickable = true
            setOnClickListener { exit() }
        })

        a.findViewById<ViewGroup>(android.R.id.content).addView(
            b,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = px(28)
            }
        )
        box = b
        return b
    }
}
