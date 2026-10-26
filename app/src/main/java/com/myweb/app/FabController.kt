package com.myweb.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.ImageButton
import kotlin.math.abs

// Tombol menu: muncul/hilang dengan ketuk halaman, scroll (arah apa pun) = tutup.
// Setiap kali muncul atau hilang ada jeda (COOLDOWN_MS) sebelum bisa berubah lagi.
class FabController(
    private val webView: WebView,
    private val fab: ImageButton,
    private val isBusy: () -> Boolean
) {
    // Ubah angka ini untuk memperpanjang/memperpendek jeda (milidetik)
    private val COOLDOWN_MS = 700L

    private var shown = true
    private var lockUntil = 0L
    private var lastTouch = 0L

    init {
        attach()
    }

    private fun setShown(show: Boolean) {
        if (show == shown) return
        val now = SystemClock.uptimeMillis()
        if (now < lockUntil) return
        lockUntil = now + COOLDOWN_MS
        shown = show

        val dist = -80 * webView.resources.displayMetrics.density
        if (show) {
            fab.visibility = View.VISIBLE
            fab.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            fab.animate().alpha(0f).translationY(dist).setDuration(180)
                .withEndAction { if (!shown) fab.visibility = View.INVISIBLE }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val slop = ViewConfiguration.get(webView.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downT = 0L
        var multi = false

        // Scroll ke arah mana pun = tutup tombol (hanya jika sedang disentuh/baru disentuh,
        // supaya pergeseran otomatis saat halaman dimuat tidak ikut menutup)
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldY ->
            val recent = SystemClock.uptimeMillis() - lastTouch < 1500
            if (shown && recent && abs(scrollY - oldY) > 12) setShown(false)
        }

        webView.setOnTouchListener { _, e ->
            lastTouch = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    downT = e.eventTime
                    multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                }
                MotionEvent.ACTION_UP -> {
                    val quick = e.eventTime - downT < 350
                    val still = abs(e.x - downX) < slop && abs(e.y - downY) < slop
                    if (!multi && quick && still && !isBusy()) setShown(!shown)
                }
            }
            false
        }
    }
}
