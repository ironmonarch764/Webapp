package com.myweb.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ScrollView yang tingginya dibatasi: daftar panjang menggulir di dalam panel, panel tidak memenuhi layar.
class MaxHeightScrollView(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = View.MeasureSpec.getSize(heightSpec)
        val cap = if (size in 1 until maxPx) size else maxPx
        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}

// Panel melayang seperti panel Auto-scroll: kartu kecil di atas halaman, TANPA redup dan bukan dialog,
// jadi halaman di belakangnya tetap kelihatan dan bisa disentuh.
//  - Hanya satu panel melayang pada satu waktu (membuka yang baru menutup yang lama).
//  - Tutup lewat tombol ✕, tombol Kembali, atau setelah memilih sesuatu (panggil dismiss()).
//  - Bisa digeser: seret garis pegangan di bagian atas kartu. Posisi terakhir diingat per jenis panel (key) dan
//    dipakai lagi saat panel dibuka berikutnya, juga setelah aplikasi dibuka ulang.
//  - Ketukan di luar kartu menutup panel (lihat onTouch, dipanggil dari Activity.dispatchTouchEvent).
class FloatingPanel(
    private val a: Activity,
    bgColor: Int = Color.parseColor("#F01F2024"),
    private val key: String = "default"
) {

    companion object {
        private var current: FloatingPanel? = null

        // Tutup panel yang sedang tampil (dipakai tombol Kembali dan onDestroy). true bila ada yang ditutup.
        // Panel yang tidak boleh dibatalkan (cancelable = false) hanya ditutup bila force = true.
        fun dismissCurrent(force: Boolean = false): Boolean {
            val c = current ?: return false
            if (!c.isShowing) return false
            if (!c.cancelable && !force) return true   // tombol Kembali ditelan, panel tetap
            c.dismiss()
            return true
        }

        // --- ketukan di luar kartu ---
        private var gestureOutside = false
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var moved = false

        /**
         * Dipanggil dari Activity.dispatchTouchEvent untuk SETIAP sentuhan, sebelum diteruskan.
         *  - Ketukan singkat di luar kartu: panel ditutup pada ACTION_UP dan fungsi ini mengembalikan true; Activity harus
         *    mengganti UP itu dengan ACTION_CANCEL supaya halaman/tombol di bawahnya tidak ikut terketuk.
         *  - Geseran di luar kartu (menggulir/menyeret halaman) atau sentuhan kedua: panel ditutup begitu jari bergeser
         *    melewati batas touch slop, dan gerakan itu diteruskan apa adanya ke halaman (halaman ikut tergulir).
         *  - Sentuhan di dalam kartu dan tahan lama di luar kartu tidak menutup panel.
         */
        fun onTouch(ev: MotionEvent): Boolean {
            val c = current
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureOutside = false
                    if (c != null && c.isShowing && c.cancelable && !c.insideCard(ev.rawX, ev.rawY)) {
                        gestureOutside = true
                        downX = ev.rawX
                        downY = ev.rawY
                        downTime = ev.eventTime
                        moved = false
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (gestureOutside && c != null) {
                        moved = true
                        gestureOutside = false
                        c.dismiss()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gestureOutside && !moved && c != null) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (dx * dx + dy * dy > c.slop * c.slop) {
                            // digeser: tutup panel, gerakan tetap diteruskan ke halaman
                            moved = true
                            gestureOutside = false
                            c.dismiss()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> gestureOutside = false
                MotionEvent.ACTION_UP -> {
                    val was = gestureOutside
                    gestureOutside = false
                    if (was && !moved && c != null && c.isShowing &&
                        ev.eventTime - downTime < ViewConfiguration.getLongPressTimeout()) {
                        c.dismiss()
                        return true
                    }
                }
            }
            return false
        }
    }

    private val dp = a.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val slop = ViewConfiguration.get(a).scaledTouchSlop
    private val posPrefs = a.getSharedPreferences("floating_panel", Context.MODE_PRIVATE)

    private fun insideCard(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + card.width && rawY >= loc[1] && rawY <= loc[1] + card.height
    }

    // Lebar kartu: hampir selebar layar di HP, maksimal 440dp di layar lebar
    val widthPx: Int = minOf(a.resources.displayMetrics.widthPixels - px(24), px(440))

    var isShowing = false
        private set

    // false = tidak bisa ditutup dengan Kembali dan tidak digantikan panel lain (mis. alamat awal yang wajib diisi)
    var cancelable = true

    // Dipanggil saat panel ditutup (dengan cara apa pun)
    var onDismiss: (() -> Unit)? = null

    private val card = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = px(24).toFloat()
        }
        elevation = px(12).toFloat()
        isClickable = true   // sentuhan di dalam kartu tidak tembus ke halaman
    }

    // Garis pegangan: seret untuk memindahkan panel
    fun grip(): View {
        val g = FrameLayout(a)
        g.addView(
            View(a).apply {
                background = GradientDrawable().apply {
                    setColor(0x55FFFFFF)
                    cornerRadius = px(3).toFloat()
                }
            },
            FrameLayout.LayoutParams(px(40), px(5), Gravity.CENTER)
        )
        var startX = 0f
        var startY = 0f
        var baseX = 0f
        var baseY = 0f
        g.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    baseX = card.translationX
                    baseY = card.translationY
                }
                MotionEvent.ACTION_MOVE -> {
                    card.translationX = baseX + (e.rawX - startX)
                    card.translationY = baseY + (e.rawY - startY)
                    clampToScreen()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // ingat posisi terakhir untuk pembukaan berikutnya
                    posPrefs.edit().putFloat(key + "_x", card.translationX).putFloat(key + "_y", card.translationY).apply()
                }
            }
            true
        }
        return g
    }

    // Tombol ✕ 40x40dp untuk baris judul
    fun closeButton(): View = TextView(a).apply {
        text = "✕"
        textSize = 18f
        setTextColor(Color.parseColor("#D0D3DC"))
        gravity = Gravity.CENTER
        contentDescription = "Tutup"
        isClickable = true
        setOnClickListener { dismiss() }
        layoutParams = LinearLayout.LayoutParams(px(40), px(40)).apply { leftMargin = px(4) }
    }

    // Jaga kartu tetap di dalam layar saat digeser
    private fun clampToScreen() {
        val p = card.parent as? View ?: return
        val minX = -card.left.toFloat()
        val maxX = (p.width - card.right).toFloat()
        val minY = -card.top.toFloat()
        val maxY = (p.height - card.bottom).toFloat()
        if (minX > maxX || minY > maxY) return
        card.translationX = card.translationX.coerceIn(minX, maxX)
        card.translationY = card.translationY.coerceIn(minY, maxY)
    }

    fun show(content: View) {
        val cur = current
        if (cur != null && cur !== this && cur.isShowing && !cur.cancelable) return
        current?.let { if (it !== this) it.dismiss() }
        current = this
        isShowing = true
        card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val host = a.findViewById<ViewGroup>(android.R.id.content)
        host.addView(
            card,
            FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = px(28)
            }
        )
        // pakai posisi terakhir yang digeser (disesuaikan bila layar/ukuran panel berbeda)
        val sx = posPrefs.getFloat(key + "_x", 0f)
        val sy = posPrefs.getFloat(key + "_y", 0f)
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampToScreen() }
        card.alpha = 0f
        card.translationX = sx
        card.translationY = sy + px(36)
        card.animate().alpha(1f).translationY(sy).setDuration(180L).start()
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        if (current === this) current = null
        card.animate().cancel()
        try {
            // tutup keyboard bila panel berisi kolom isian
            card.windowToken?.let {
                (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it, 0)
            }
        } catch (e: Exception) { }
        try {
            (card.parent as? ViewGroup)?.removeView(card)
        } catch (e: Exception) { }
        onDismiss?.invoke()
    }
}
