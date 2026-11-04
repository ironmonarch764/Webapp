package com.myweb.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Tiga tombol bulat terpisah (Menu, Penyesuaian warna, Gulir) yang keluar dari pegangan setengah lonjong di tepi kanan
// saat pegangan diketuk. Diketuk lagi (atau ketuk/geser halaman) = tertutup. Tombol dibuat lewat kode, bukan XML.
class QuickTools(
    private val handle: View,
    private val parent: FrameLayout,
    private val onMenu: () -> Unit,
    private val onColor: () -> Unit,
    private val onScroll: () -> Unit
) {
    var expanded = false
        private set

    private val dp = handle.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val box = LinearLayout(handle.context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    init {
        addCircle("☰", "Menu", onMenu)
        addCircle("🎨", "Penyesuaian warna", onColor)
        addCircle("⏬", "Gulir", onScroll)
        parent.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP))
        handle.setOnClickListener { if (expanded) collapse() else expand() }
    }

    private fun addCircle(icon: String, desc: String, action: () -> Unit) {
        val c = TextView(handle.context).apply {
            text = icon
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = desc
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF1F2024"))
            }
            isClickable = true
            setOnClickListener { collapse(); action() }
        }
        box.addView(c, LinearLayout.LayoutParams(px(56), px(56)).apply {
            if (box.childCount > 0) topMargin = px(12)
        })
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        // tiga bulatan sejajar tengah pegangan, di sebelah kirinya, dan tetap di dalam layar
        val lp = box.layoutParams as FrameLayout.LayoutParams
        lp.marginEnd = (if (handle.width > 0) handle.width else px(52)) + px(10)
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val mh = box.measuredHeight
        val maxTop = (parent.height - mh - px(8)).coerceAtLeast(px(8))
        lp.topMargin = (handle.top + handle.height / 2 - mh / 2).coerceIn(px(8), maxTop)
        box.layoutParams = lp
        box.animate().cancel()
        box.alpha = 0f
        box.translationX = px(40).toFloat()
        box.visibility = View.VISIBLE
        box.animate().alpha(1f).translationX(0f).setDuration(160).start()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        box.animate().cancel()
        box.animate().alpha(0f).translationX(px(40).toFloat()).setDuration(120)
            .withEndAction { if (!expanded) box.visibility = View.GONE }
            .start()
    }
}
