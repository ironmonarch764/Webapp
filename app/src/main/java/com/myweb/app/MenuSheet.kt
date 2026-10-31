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
import android.widget.ScrollView
import android.widget.TextView

// active = fitur sedang menyala (kotak disorot); state = teks kecil di bawah nama (mis. ON/OFF)
class MenuAction(
    val icon: String,
    val label: String,
    val active: Boolean = false,
    val state: String = "",
    val onClick: () -> Unit
)

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private var currentSheet: Dialog? = null

// Dipanggil saat Activity dihancurkan agar referensi statis ke dialog (dan Activity) dilepas
fun dismissMenuSheet() {
    try { currentSheet?.dismiss() } catch (e: Exception) { }
    currentSheet = null
}

private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = r
}

// Kartu di tengah layar (bukan lembar bawah): ketuk di luar kartu = tutup.
private fun presentCentered(a: Activity, d: Dialog, card: View) {
    val dp = a.resources.displayMetrics.density
    val m = (20 * dp).toInt()
    val overlay = FrameLayout(a)
    overlay.setOnClickListener { d.dismiss() }
    val sv = ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        addView(card)
    }
    overlay.addView(sv, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(m, m, m, m) })
    d.setContentView(overlay)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MATCH, MATCH)
        setDimAmount(0.6f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_Dialog }
    }
    d.show()
}

private fun newCentered(a: Activity): Dialog {
    val d = Dialog(a)
    try { currentSheet?.dismiss() } catch (e: Exception) { }
    currentSheet = d
    d.setOnDismissListener { if (currentSheet === d) currentSheet = null }
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setCancelable(true)
    d.setCanceledOnTouchOutside(true)
    return d
}

// Menu cepat: kisi 4 kolom berisi aksi yang sering dipakai, tampil sebagai kartu di tengah. Fitur lain ada di Pengaturan.
fun showMenuSheet(a: Activity, header: String, tiles: List<MenuAction>) {
    val d = newCentered(a)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1A1B1F"), px(28).toFloat())
        setPadding(px(16), px(18), px(16), px(18))
        isClickable = true
    }

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
        setPadding(px(6), px(2), px(6), px(14))
    }, LinearLayout.LayoutParams(MATCH, WRAP))

    val cols = 4
    tiles.chunked(cols).forEachIndexed { ri, rowTiles ->
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until cols) {
            val q = rowTiles.getOrNull(i)
            if (q == null) {
                row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { if (i > 0) leftMargin = px(8) })
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
                setPadding(px(2), px(12), px(2), px(10))
                isClickable = true
                setOnClickListener { d.dismiss(); q.onClick() }
            }
            tile.addView(TextView(a).apply { text = q.icon; textSize = 22f; gravity = Gravity.CENTER })
            tile.addView(TextView(a).apply {
                text = q.label
                textSize = 11.5f
                minLines = 2
                maxLines = 2
                setTextColor(if (q.active) Color.parseColor("#BFD3FF") else Color.parseColor("#D0D3DC"))
                gravity = Gravity.CENTER
                setPadding(0, px(6), 0, 0)
            })
            tile.addView(TextView(a).apply {
                text = if (q.state.isEmpty()) " " else q.state
                textSize = 10.5f
                setTextColor(if (q.active) Color.parseColor("#7FA6FF") else Color.parseColor("#7B7F8A"))
                gravity = Gravity.CENTER
            })
            row.addView(tile, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) leftMargin = px(8) })
        }
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (ri > 0) topMargin = px(8) })
    }
    presentCentered(a, d, root)
}

// Kartu kecil di tengah layar berisi beberapa baris (saklar / aksi). Dipakai untuk submenu "Gulir" dan "Elemen".
fun showPopupCard(a: Activity, title: String, rows: List<SettingRow>) {
    val d = newCentered(a)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1A1B1F"), px(28).toFloat())
        setPadding(px(10), px(18), px(10), px(12))
        isClickable = true
    }
    root.addView(TextView(a).apply {
        text = title
        textSize = 17f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(px(10), 0, px(10), px(8))
    }, LinearLayout.LayoutParams(MATCH, WRAP))
    rows.forEachIndexed { idx, row ->
        if (idx > 0) {
            root.addView(View(a).apply { setBackgroundColor(Color.parseColor("#2A2C33")) },
                LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = px(58) })
        }
        root.addView(buildRow(a, row, { v -> px(v) }) { })
    }
    presentCentered(a, d, root)
}
