package com.myweb.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

// Tiga tombol setengah lonjong TERPISAH yang menempel di tepi kanan layar, bertumpuk dari atas ke bawah:
// Menu, Penyesuaian warna, Gulir. Ikut hilang/muncul bersama FabController (container-nya yang dianimasikan).
class QuickTools(
    container: LinearLayout,
    onMenu: () -> Unit,
    onColor: () -> Unit,
    onScroll: () -> Unit
) {
    init {
        val dp = container.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun pill(icon: String, desc: String, action: () -> Unit) {
            val v = TextView(container.context).apply {
                text = icon
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = desc
                setPadding(0, 0, px(8), 0)
                // sisi kiri membulat penuh, sisi kanan rata (menempel di tepi layar); sedikit mengkilap
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#FF3A3B40"), Color.parseColor("#FF16171A"))
                ).apply {
                    val r = px(27).toFloat()
                    cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                }
                isClickable = true
                setOnClickListener { action() }
            }
            container.addView(v, LinearLayout.LayoutParams(px(76), px(54)).apply {
                if (container.childCount > 0) topMargin = px(10)
            })
        }
        pill("☰", "Menu", onMenu)
        pill("🎨", "Penyesuaian warna", onColor)
        pill("⏬", "Gulir", onScroll)
    }
}
