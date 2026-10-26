package com.myweb.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.ImageButton
import kotlin.math.abs

// Kontrol ketukan di halaman:
//  - ketuk tepi BAWAH layar = turun satu layar (animasi halus), bisa dimatikan lewat menu
//  - ketuk tepi ATAS layar  = naik satu layar (animasi halus), bisa dimatikan lewat menu
//  - ketuk tengah layar     = tombol menu muncul/hilang (juga di tepi bila fitur tepi dimatikan)
//  - scroll (arah apa pun)  = tombol menu menutup
// Setiap kali tombol menu muncul/hilang ada jeda (COOLDOWN_MS) sebelum bisa berubah lagi.
class FabController(
    private val webView: WebView,
    private val fab: ImageButton,
    private val isBusy: () -> Boolean,
    private val edgeOn: () -> Boolean,
    private val onTouchDown: () -> Unit
) {
    // ---- Pengaturan (ubah angkanya sesuai selera) ----
    private val COOLDOWN_MS = 700L      // jeda tombol menu, milidetik
    private val BOTTOM_ZONE = 0.25f     // tinggi area ketuk bawah (25% layar). 0f = matikan
    private val TOP_ZONE = 0.12f        // tinggi area ketuk atas (12% layar). 0f = matikan
    private val PAGE_FRACTION = 0.9f    // satu ketukan = 90% tinggi layar (sisakan 10% agar nyambung)
    // --------------------------------------------------

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

    // dir = 1 turun, -1 naik. Pakai animasi scroll halus bawaan browser.
    private fun scrollPage(dir: Int) {
        val js = "(function(){var d=window.innerHeight*" + PAGE_FRACTION + "*" + dir +
            ";window.scrollBy({top:d,left:0,behavior:'smooth'});})();"
        webView.evaluateJavascript(js, null)
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

        webView.setOnTouchListener { v, e ->
            lastTouch = SystemClock.uptimeMillis()
            var consumed = false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onTouchDown()
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
                    if (!multi && quick && still && !isBusy()) {
                        val h = v.height
                        val edge = edgeOn()
                        if (edge && BOTTOM_ZONE > 0f && e.y > h * (1f - BOTTOM_ZONE)) {
                            scrollPage(1)
                            consumed = true
                        } else if (edge && TOP_ZONE > 0f && e.y < h * TOP_ZONE) {
                            scrollPage(-1)
                            consumed = true
                        } else {
                            setShown(!shown)
                        }
                        if (consumed) {
                            // Batalkan ketukan agar link/tombol di bawah jari tidak ikut terbuka
                            val c = MotionEvent.obtain(e)
                            c.action = MotionEvent.ACTION_CANCEL
                            v.onTouchEvent(c)
                            c.recycle()
                        }
                    }
                }
            }
            consumed
        }
    }
}
