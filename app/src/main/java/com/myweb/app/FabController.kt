package com.myweb.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import kotlin.math.abs

// Kontrol ketukan di halaman:
//  - ketuk tepi KANAN layar = turun satu layar (animasi halus), bisa dimatikan lewat menu
//  - ketuk tepi KIRI layar  = naik satu layar (animasi halus), bisa dimatikan lewat menu
//    (hanya di bagian tengah-vertikal, supaya bar menu di atas/bawah situs tetap bisa diketuk)
//  - ketuk tempat lain      = tombol menu muncul/hilang
//  - scroll (arah apa pun)  = tombol menu menutup
//  - tarik dari tepi atas layar (saat jam/notifikasi muncul) = tombol menu ikut muncul sebentar
// Setiap kali tombol menu muncul/hilang karena ketukan ada jeda (COOLDOWN_MS).
class FabController(
    private val webView: WebView,
    private val fab: View,
    private val isBusy: () -> Boolean,
    private val edgeOn: () -> Boolean,
    private val onTouchDown: () -> Unit,
    private val onScroll: () -> Unit = {},
    private val edgeSpeed: () -> Float = { 2.0f },     // px CSS per milidetik (diatur di Pengaturan > Gulir)
    private val edgeFraction: () -> Float = { 0.9f }   // jarak satu ketukan = bagian tinggi layar (diatur di Pengaturan > Gulir)
) {
    // ---- Pengaturan (ubah angkanya sesuai selera) ----
    private val COOLDOWN_MS = 700L      // jeda tombol menu, milidetik
    private val STAGGER_MS = 70L        // jeda antar tombol saat muncul/hilang bergantian
    private val SIDE_ZONE = 0.09f       // lebar area ketuk kiri & kanan (9% lebar layar). 0f = matikan
    private val SIDE_TOP = 0.15f        // area samping mulai dari 15% tinggi layar (sisakan bar atas situs)
    private val SIDE_BOTTOM = 0.15f     // area samping berakhir 15% sebelum dasar (sisakan bar bawah situs)
    private val RIGHT_DIR = 1           // ketuk tepi kanan: 1 = turun, -1 = naik
    private val LEFT_DIR = 1           // ketuk tepi kiri: -1 = naik, 1 = turun (isi 1 agar dua sisi turun)
    private val TOP_EDGE_DP = 40f       // tarikan dianggap dari tepi atas bila mulai di 40dp teratas
    private val PULL_DP = 30f           // minimal geser ke bawah agar dianggap menarik bar
    private val REVEAL_MS = 4000L       // lama tombol menu bertahan setelah bar ditarik
    // --------------------------------------------------

    private var shown = true
    private var lockUntil = 0L
    private var lastTouch = 0L
    private var revealUntil = 0L
    private var shownBeforeReveal: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restore = Runnable {
        val prev = shownBeforeReveal
        shownBeforeReveal = null
        if (prev == false) applyShown(false)
    }

    init {
        attach()
        playEntrance()
    }

    // Tidak dipakai lagi (Android tidak melaporkan bar sementara ke aplikasi).
    // Dibiarkan kosong supaya baris lama di MainActivity tidak membuat build gagal.
    fun onSystemBars(visible: Boolean) {}

    // Dipanggil saat Activity dihancurkan supaya runnable tertunda tidak menahan view/Activity
    fun release() { handler.removeCallbacksAndMessages(null) }

    // Ketukan/scroll biasa: tunduk pada jeda
    private fun setShown(show: Boolean) {
        if (show == shown) return
        if (SystemClock.uptimeMillis() < lockUntil) return
        applyShown(show)
    }

    // Mengubah keadaan tombol dengan animasi (tanpa memeriksa jeda), lalu memulai jeda baru
    private fun applyShown(show: Boolean) {
        if (show == shown) return
        lockUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        shown = show

        val group = fab as? ViewGroup
        if (group == null || group.childCount == 0) {
            // satu tombol saja: menyelinap ke tepi kanan layar
            val dist = (if (fab.width > 0) fab.width else (52 * webView.resources.displayMetrics.density).toInt()).toFloat()
            if (show) {
                fab.visibility = View.VISIBLE
                fab.animate().alpha(1f).translationX(0f).setDuration(180).start()
            } else {
                fab.animate().alpha(0f).translationX(dist).setDuration(180)
                    .withEndAction { if (!shown) fab.visibility = View.INVISIBLE }
                    .start()
            }
            return
        }
        // beberapa tombol: muncul bergantian dari ATAS ke BAWAH, menghilang bergantian dari BAWAH ke ATAS
        val n = group.childCount
        if (show) fab.visibility = View.VISIBLE
        for (i in 0 until n) {
            val c = group.getChildAt(i)
            c.animate().cancel()
            if (show) {
                c.animate().alpha(1f).translationX(0f)
                    .setStartDelay(i * STAGGER_MS).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            } else {
                // tombol paling atas selesai paling akhir: setelah itu seluruh wadah disembunyikan agar tidak menangkap ketukan
                val a = c.animate().alpha(0f).translationX(slideDist(c))
                    .setStartDelay((n - 1 - i) * STAGGER_MS).setDuration(180).setInterpolator(DecelerateInterpolator())
                if (i == 0) a.withEndAction { if (!shown) fab.visibility = View.INVISIBLE }
                a.start()
            }
        }
    }

    private fun slideDist(c: View): Float =
        (if (c.width > 0) c.width else (76 * webView.resources.displayMetrics.density).toInt()).toFloat()

    // Saat aplikasi dibuka: tombol keluar satu per satu dari atas ke bawah
    private fun playEntrance() {
        val group = fab as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            c.alpha = 0f
            c.translationX = slideDist(c)
        }
        fab.post {
            if (!shown) return@post   // sudah disembunyikan sebelum sempat tampil: biarkan keadaan tersembunyi
            for (i in 0 until group.childCount) {
                group.getChildAt(i).animate().alpha(1f).translationX(0f)
                    .setStartDelay(300L + i * STAGGER_MS).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    // Bar sistem ditarik dari atas: tombol menu muncul REVEAL_MS lalu kembali ke keadaan semula
    private fun reveal() {
        if (shownBeforeReveal == null) shownBeforeReveal = shown
        revealUntil = SystemClock.uptimeMillis() + REVEAL_MS
        applyShown(true)
        handler.removeCallbacks(restore)
        handler.postDelayed(restore, REVEAL_MS)
    }

    // dir = 1 turun, -1 naik. Kecepatan konstan (lihat EDGE_SCROLL_JS), bukan animasi 'smooth' bawaan browser
    private fun scrollPage(dir: Int) {
        val js = EDGE_SCROLL_JS
            .replace("__DIR__", dir.toString())
            .replace("__FRAC__", edgeFraction().toString())
            .replace("__SPEED__", edgeSpeed().toString())
        webView.evaluateJavascript(js, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val slop = ViewConfiguration.get(webView.context).scaledTouchSlop
        val density = webView.resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var downT = 0L
        var multi = false
        var topDown = false

        // Scroll ke arah mana pun = tutup tombol (hanya jika sedang disentuh/baru disentuh,
        // dan tidak sedang masa tombol ditampilkan karena bar ditarik)
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldY ->
            onScroll()
            val now = SystemClock.uptimeMillis()
            val recent = now - lastTouch < 1500
            if (shown && recent && now > revealUntil && abs(scrollY - oldY) > 12) setShown(false)
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
                    topDown = e.y < TOP_EDGE_DP * density
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    topDown = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (topDown && e.y - downY > PULL_DP * density) {
                        topDown = false
                        reveal()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Sistem mengambil alih sentuhan dari tepi atas = bar sedang ditarik
                    if (topDown) {
                        topDown = false
                        reveal()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    topDown = false
                    val quick = e.eventTime - downT < 350
                    val still = abs(e.x - downX) < slop && abs(e.y - downY) < slop
                    if (!multi && quick && still && !isBusy()) {
                        val w = v.width
                        val h = v.height
                        val inBand = e.y > h * SIDE_TOP && e.y < h * (1f - SIDE_BOTTOM)
                        val edge = edgeOn() && SIDE_ZONE > 0f && inBand
                        if (edge && e.x > w * (1f - SIDE_ZONE)) {
                            scrollPage(RIGHT_DIR)
                            consumed = true
                        } else if (edge && e.x < w * SIDE_ZONE) {
                            scrollPage(LEFT_DIR)
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
