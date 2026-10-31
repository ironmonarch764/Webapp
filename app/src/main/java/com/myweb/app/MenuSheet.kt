package com.myweb.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// active = fitur sedang menyala (kotak disorot)
class MenuAction(val icon: String, val label: String, val active: Boolean = false, val onClick: () -> Unit)

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private var currentSheet: Dialog? = null

// Dipanggil saat Activity dihancurkan agar referensi statis ke dialog (dan Activity) dilepas
fun dismissMenuSheet() {
    try { currentSheet?.dismiss() } catch (e: Exception) { }
    currentSheet = null
}

// Menu cepat: kisi 4 kolom berisi aksi yang sering dipakai. Fitur lain ada di Pengaturan.
fun showMenuSheet(a: Activity, header: String, tiles: List<MenuAction>) {
    val d = Dialog(a)
    try { currentSheet?.dismiss() } catch (e: Exception) { }
    currentSheet = d
    d.setOnDismissListener { if (currentSheet === d) currentSheet = null }
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setCancelable(true)
    d.setCanceledOnTouchOutside(true)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun rounded(color: Int, r: Float, topOnly: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
    }

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1A1B1F"), px(28).toFloat(), true)
        setPadding(px(16), px(10), px(16), px(20))
        isClickable = true
    }

    root.addView(View(a).apply { background = rounded(0x44FFFFFF, px(3).toFloat()) },
        LinearLayout.LayoutParams(px(40), px(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = px(12)
        })

    // Kepala: situs yang sedang dibuka
    root.addView(TextView(a).apply {
        text = header
        textSize = 16f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(px(6), 0, px(6), 0)
    }, LinearLayout.LayoutParams(MATCH, WRAP))
    root.addView(TextView(a).apply {
        text = "Menu cepat"
        textSize = 12f
        setTextColor(Color.parseColor("#8A8E99"))
        setPadding(px(6), px(2), px(6), px(12))
    }, LinearLayout.LayoutParams(MATCH, WRAP))

    val cols = 4
    tiles.chunked(cols).forEachIndexed { ri, rowTiles ->
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until cols) {
            val q = rowTiles.getOrNull(i)
            if (q == null) {
                row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { if (i > 0) leftMargin = px(10) })
                continue
            }
            val bg = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                if (q.active) {
                    setColor(Color.parseColor("#1E3556"))
                    setStroke(px(1), Color.parseColor("#5B8CFF"))
                } else {
                    setColor(Color.parseColor("#26282E"))
                }
            }
            val tile = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = bg
                setPadding(px(2), px(14), px(2), px(12))
                isClickable = true
                setOnClickListener { d.dismiss(); q.onClick() }
            }
            tile.addView(TextView(a).apply { text = q.icon; textSize = 22f; gravity = Gravity.CENTER })
            tile.addView(TextView(a).apply {
                text = q.label
                textSize = 11.5f
                maxLines = 2
                setTextColor(if (q.active) Color.parseColor("#BFD3FF") else Color.parseColor("#D0D3DC"))
                gravity = Gravity.CENTER
                setPadding(0, px(6), 0, 0)
            })
            row.addView(tile, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) leftMargin = px(10) })
        }
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (ri > 0) topMargin = px(10) })
    }

    // Seluruh layar jadi area ketuk: ketuk di luar menu = tutup
    val overlay = FrameLayout(a)
    overlay.setOnClickListener { d.dismiss() }
    val sv = android.widget.ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        addView(root)
    }
    overlay.addView(sv, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

    d.setContentView(overlay)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MATCH, MATCH)
        setDimAmount(0.5f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}
