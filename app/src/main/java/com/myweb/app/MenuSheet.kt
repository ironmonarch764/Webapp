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

// Menu cepat: deretan vertikal di pinggir kanan layar (urutan atas ke bawah = urutan daftar tiles).
// Ketuk di luar deretan = tutup. header tidak ditampilkan lagi (nama situs ada di kartu Perlindungan).
fun showMenuSheet(a: Activity, header: String, tiles: List<MenuAction>) {
    val d = newCentered(a)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
    }
    tiles.forEachIndexed { i, q ->
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
            setPadding(px(4), px(10), px(4), px(9))
            isClickable = true
            setOnClickListener { d.dismiss(); q.onClick() }
        }
        tile.addView(Icons.make(a, q.icon, 22f),
            if (Icons.has(q.icon)) LinearLayout.LayoutParams(px(34), px(34)) else LinearLayout.LayoutParams(WRAP, WRAP))
        tile.addView(TextView(a).apply {
            text = q.label
            textSize = 11.5f
            maxLines = 2
            setTextColor(if (q.active) Color.parseColor("#BFD3FF") else Color.parseColor("#D0D3DC"))
            gravity = Gravity.CENTER
            setPadding(0, px(4), 0, 0)
        })
        if (q.state.isNotEmpty()) {
            tile.addView(TextView(a).apply {
                text = q.state
                textSize = 10.5f
                setTextColor(if (q.active) Color.parseColor("#7FA6FF") else Color.parseColor("#7B7F8A"))
                gravity = Gravity.CENTER
            })
        }
        column.addView(tile, LinearLayout.LayoutParams(px(84), WRAP).apply { if (i > 0) topMargin = px(8) })
    }

    val overlay = FrameLayout(a)
    overlay.setOnClickListener { d.dismiss() }
    val sv = ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        addView(column)
    }
    val m = px(12)
    overlay.addView(sv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(m, m, m, m) })
    d.setContentView(overlay)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MATCH, MATCH)
        setDimAmount(0.5f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_Dialog }
    }
    d.show()
}

// Kartu kecil di tengah layar berisi beberapa baris (saklar / aksi). Dipakai untuk submenu "Gulir" dan "Elemen".
fun showPopupCard(a: Activity, title: String, rows: List<SettingRow>) = showPopupCard(a, title) { rows }

// rowsProvider dipanggil lagi setiap ada saklar yang berubah, sehingga baris bisa muncul/hilang mengikuti saklar
fun showPopupCard(a: Activity, title: String, rowsProvider: () -> List<SettingRow>) {
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
    fun fill() {
        while (root.childCount > 1) root.removeViewAt(1)
        rowsProvider().forEachIndexed { idx, row ->
            if (idx > 0) {
                root.addView(View(a).apply { setBackgroundColor(Color.parseColor("#2A2C33")) },
                    LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = px(58) })
            }
            // dipasang lewat post agar baris tidak dibongkar saat listener saklarnya masih berjalan
            root.addView(buildRow(a, row, { v -> px(v) }) { root.post { if (d.isShowing) fill() } })
        }
    }
    fill()
    presentCentered(a, d, root)
}

// Kartu "Perlindungan situs": hitungan iklan/pelacak yang diblokir di halaman ini, ajakan menandai elemen iklan, lalu
// kelompok saklar. build() dipanggil lagi setiap ada perubahan supaya nilai yang tampil ikut diperbarui.
fun showProtectionCard(
    a: Activity, host: String, ads: Int, trackers: Int,
    onMark: () -> Unit, build: () -> List<SettingSection>
) {
    val d = newCentered(a)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1A1B1F"), px(28).toFloat())
        setPadding(px(14), px(18), px(14), px(14))
        isClickable = true
    }
    root.addView(TextView(a).apply {
        text = "🌐  " + host
        textSize = 15f
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(px(8), 0, px(8), px(10))
    }, LinearLayout.LayoutParams(MATCH, WRAP))

    val counters = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    fun counter(n: Int, label: String, color: String) {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(TextView(a).apply {
            text = n.toString()
            textSize = 34f
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
        })
        col.addView(TextView(a).apply {
            text = label
            textSize = 12.5f
            setTextColor(Color.parseColor("#9CA0AB"))
            gravity = Gravity.CENTER
        })
        counters.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    counter(ads, "Iklan diblokir", "#FF6B5E")
    counter(trackers, "Pelacak diblokir", "#3DDC84")
    root.addView(counters, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(12) })

    val promo = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#2B2326"), px(18).toFloat())
        setPadding(px(16), px(12), px(16), px(12))
        isClickable = true
        setOnClickListener { d.dismiss(); onMark() }
    }
    promo.addView(TextView(a).apply {
        text = "Iklan masih muncul?"
        textSize = 15f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
    })
    promo.addView(TextView(a).apply {
        text = "Tandai elemen iklan di halaman ini  ›"
        textSize = 12.5f
        setTextColor(Color.parseColor("#F0A9A1"))
        setPadding(0, px(2), 0, 0)
    })
    root.addView(promo, LinearLayout.LayoutParams(MATCH, WRAP))

    val sections = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    root.addView(sections, LinearLayout.LayoutParams(MATCH, WRAP))
    fun render() {
        sections.removeAllViews()
        for (sec in build()) {
            sections.addView(TextView(a).apply {
                text = sec.title
                textSize = 14f
                setTextColor(Color.parseColor("#7F93C9"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(px(8), px(14), px(8), px(6))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.parseColor("#232529"), px(18).toFloat())
            }
            sec.rows.forEachIndexed { idx, row ->
                if (idx > 0) {
                    card.addView(View(a).apply { setBackgroundColor(Color.parseColor("#30323A")) },
                        LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = px(58) })
                }
                card.addView(buildRow(a, row, { v -> px(v) }) { sections.post { render() } })
            }
            sections.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }
    render()
    presentCentered(a, d, root)
}
