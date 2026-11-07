package com.myweb.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Tombol setengah lonjong TERPISAH yang menempel di tepi kanan layar, bertumpuk dari atas ke bawah:
// Menu, Penyesuaian warna, Gulir, dan (bila fitur Page menyala) Page.
// Ikut hilang/muncul bersama FabController (tombolnya dianimasikan bergantian).
class QuickTools(
    container: LinearLayout,
    onMenu: () -> Unit,
    onColor: () -> Unit,
    onScroll: () -> Unit,
    onPage: () -> Unit
) {
    private val pagePill: View
    private val pageBadge: TextView

    init {
        val dp = container.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun pill(icon: String, desc: String, action: () -> Unit): FrameLayout {
            val v = FrameLayout(container.context).apply {
                contentDescription = desc
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
            val ic = Icons.make(container.context, icon, 24f)
            v.addView(ic, FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER).apply { marginEnd = px(8) })
            container.addView(v, LinearLayout.LayoutParams(px(76), px(54)).apply {
                if (container.childCount > 0) topMargin = px(10)
            })
            return v
        }
        pill("@menu", "Menu", onMenu)
        pill("@filter_warna", "Penyesuaian warna", onColor)
        pill("@autoscroll", "Gulir", onScroll)
        val pg = pill("@page", "Page", onPage)
        // lencana jumlah page (kiri bawah ikon), seperti penghitung tab di peramban
        val badge = TextView(container.context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = px(16)
            setPadding(px(4), 0, px(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = px(8).toFloat()
                setColor(Color.parseColor("#FF2563EB"))
            }
        }
        pg.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(16), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(14)
            bottomMargin = px(6)
        })
        pagePill = pg
        pageBadge = badge
        pagePill.visibility = View.GONE
    }

    // Tombol Page hanya ada bila fitur Page menyala (Pengaturan > Page)
    fun setPageVisible(show: Boolean) {
        pagePill.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun setPageCount(n: Int) {
        pageBadge.text = n.toString()
    }
}
