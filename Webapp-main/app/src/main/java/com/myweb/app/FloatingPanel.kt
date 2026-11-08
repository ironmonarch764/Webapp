package com.myweb.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
//  - Bisa digeser: seret garis pegangan di bagian atas kartu.
class FloatingPanel(private val a: Activity, bgColor: Int = Color.parseColor("#F01F2024")) {

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
    }

    private val dp = a.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

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
        card.alpha = 0f
        card.translationY = px(36).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(180L).start()
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
