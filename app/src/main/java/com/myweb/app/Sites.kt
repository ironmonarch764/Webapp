package com.myweb.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Satu situs tersimpan. pinned = disematkan (tampil paling atas). Urutan daftar = urutan yang diatur pengguna.
class Site(val name: String, val url: String, val pinned: Boolean = false)

// Isi dan aksi sheet Situs. load/save membaca dan menulis daftar penuh (sheet menggambar ulang setelah tiap perubahan).
class SitesUi(
    val load: () -> List<Site>,
    val save: (List<Site>) -> Unit,
    val current: String,
    val grid: () -> Boolean,
    val setGrid: (Boolean) -> Unit,
    val onOpen: (String) -> Unit,
    val onNew: () -> Unit,
    val onSaveCurrent: () -> Unit,
    val onAddManual: () -> Unit,
    val onEdit: (Site, () -> Unit) -> Unit
)

// Geser situs satu langkah di dalam kelompoknya (sematan / bukan sematan). true bila ada yang bergeser.
fun moveSite(list: MutableList<Site>, url: String, dir: Int): Boolean {
    val i = list.indexOfFirst { it.url == url }
    if (i < 0) return false
    val j = PagePolicy.siteSwapTarget(BooleanArray(list.size) { list[it].pinned }, i, dir)
    if (j < 0) return false
    val t = list[i]
    list[i] = list[j]
    list[j] = t
    return true
}

// Pindahkan situs fromUrl ke posisi situs toUrl (urut tekan-tahan-geser). Hanya di dalam kelompoknya
// (sematan / bukan sematan). true bila urutan berubah.
fun reorderSite(list: MutableList<Site>, fromUrl: String, toUrl: String): Boolean {
    val i = list.indexOfFirst { it.url == fromUrl }
    val j = list.indexOfFirst { it.url == toUrl }
    if (i < 0 || j < 0 || i == j || list[i].pinned != list[j].pinned) return false
    return PagePolicy.moveToIndex(list, i, j)
}

private var sitesDialog: FloatingPanel? = null

fun dismissSitesSheet() {
    try { sitesDialog?.dismiss() } catch (e: Exception) { }
    sitesDialog = null
}

fun showSitesSheet(a: Activity, ui: SitesUi) {
    dismissSitesSheet()
    // Panel melayang (bukan dialog): halaman di belakang tetap kelihatan dan bisa disentuh
    val d = FloatingPanel(a, key = "sites")
    sitesDialog = d
    d.onDismiss = { if (sitesDialog === d) sitesDialog = null }
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    fun bg(color: Int, r: Float, topOnly: Boolean) = GradientDrawable().apply {
        setColor(color)
        if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
    }

    var editMode = false
    var gridMode = ui.grid()

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(2), px(16), px(16))
    }
    root.addView(d.grip(), LinearLayout.LayoutParams(match, px(24)))

    // Baris judul: Situs | tampilan list | tampilan kotak | Edit
    val header = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    root.addView(header, LinearLayout.LayoutParams(match, wrap))

    val newBtn = TextView(a).apply {
        text = "＋  Buka alamat baru"
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = bg(Color.parseColor("#2563EB"), px(16).toFloat(), false)
        setPadding(0, px(13), 0, px(13))
        isClickable = true
        setOnClickListener { d.dismiss(); ui.onNew() }
    }
    root.addView(newBtn, LinearLayout.LayoutParams(match, wrap).apply {
        topMargin = px(12)
        bottomMargin = px(4)
    })

    // Simpan alamat tanpa membukanya: halaman yang sedang dibuka, atau alamat yang ditulis sendiri
    fun secondaryBtn(label: String, action: () -> Unit) = TextView(a).apply {
        text = label
        textSize = 14f
        setTextColor(Color.parseColor("#BFD3FF"))
        gravity = Gravity.CENTER
        background = bg(Color.parseColor("#26324A"), px(16).toFloat(), false)
        setPadding(px(6), px(12), px(6), px(12))
        isClickable = true
        setOnClickListener { d.dismiss(); action() }
    }
    val saveRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    saveRow.addView(secondaryBtn("🔖 Simpan halaman ini", ui.onSaveCurrent), LinearLayout.LayoutParams(0, wrap, 1f))
    saveRow.addView(secondaryBtn("✎ Tulis alamat", ui.onAddManual), LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(8) })
    root.addView(saveRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(4); bottomMargin = px(4) })

    val hint = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(4), px(8), px(4), 0)
    }
    hint.addView(Icons.make(a, "@urut_hapus", 14f), LinearLayout.LayoutParams(px(22), px(22)).apply { rightMargin = px(8) })
    val hintTv = TextView(a).apply {
        textSize = 11.5f
        setTextColor(Color.parseColor("#7F93C9"))
    }
    hint.addView(hintTv)
    root.addView(hint, LinearLayout.LayoutParams(match, wrap))

    val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    // Daftar menggulir di dalam panel; tinggi dibatasi supaya halaman di belakang tetap terlihat
    val maxList = (a.resources.displayMetrics.heightPixels * 0.62f - px(210)).toInt().coerceAtLeast(px(120))
    val sv = MaxHeightScrollView(a, maxList).apply { addView(box) }
    root.addView(sv, LinearLayout.LayoutParams(match, wrap))

    lateinit var render: () -> Unit

    // Urut dengan tekan-tahan lalu geser. Indeks = urutan tampil (sematan dulu, lalu yang lain).
    val drag = DragReorder(a, sv, box) { from, to ->
        val all = ui.load()
        val ordered = all.filter { it.pinned } + all.filter { !it.pinned }
        if (from in ordered.indices && to in ordered.indices) {
            val l = all.toMutableList()
            if (reorderSite(l, ordered[from].url, ordered[to].url)) ui.save(l)
        }
        render()
    }

    fun iconBox(key: String, active: Boolean, desc: String, click: () -> Unit): View = FrameLayout(a).apply {
        contentDescription = desc
        background = bg(if (active) Color.parseColor("#2C3A5A") else Color.TRANSPARENT, px(12).toFloat(), false)
        isClickable = true
        setOnClickListener { click() }
        addView(Icons.make(a, key, 18f), FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
    }

    fun buildHeader() {
        header.removeAllViews()
        header.addView(TextView(a).apply {
            text = "Situs"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(iconBox("@view_list", !gridMode, "Tampilan daftar") {
            if (gridMode) { gridMode = false; ui.setGrid(false); render() }
        }, LinearLayout.LayoutParams(px(40), px(40)))
        header.addView(iconBox("@view_grid", gridMode, "Tampilan kotak") {
            if (!gridMode) { gridMode = true; ui.setGrid(true); render() }
        }, LinearLayout.LayoutParams(px(40), px(40)).apply { leftMargin = px(4) })
        if (editMode) {
            header.addView(TextView(a).apply {
                text = "Selesai"
                textSize = 13.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = bg(Color.parseColor("#2563EB"), px(12).toFloat(), false)
                setPadding(px(12), 0, px(12), 0)
                isClickable = true
                setOnClickListener { editMode = false; render() }
            }, LinearLayout.LayoutParams(wrap, px(40)).apply { leftMargin = px(8) })
        } else {
            header.addView(iconBox("@edit", false, "Edit") { editMode = true; render() },
                LinearLayout.LayoutParams(px(40), px(40)).apply { leftMargin = px(8) })
        }
        header.addView(d.closeButton())
    }

    fun ctl(label: String, enabled: Boolean, size: Float, action: () -> Unit) = TextView(a).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(if (enabled) Color.parseColor("#D0D3DC") else Color.parseColor("#4A4D57"))
        setPadding(px(9), px(7), px(9), px(7))
        if (enabled) {
            isClickable = true
            setOnClickListener { action() }
        }
    }

    // ---- perubahan daftar (membaca ulang daftar penuh supaya tidak memakai salinan lama) ----
    fun togglePin(s: Site) {
        val l = ui.load().toMutableList()
        val i = l.indexOfFirst { it.url == s.url }
        if (i >= 0) { l[i] = Site(l[i].name, l[i].url, !l[i].pinned); ui.save(l) }
        render()
    }
    fun remove(s: Site) {
        ui.save(ui.load().filter { it.url != s.url })
        render()
    }

    fun iconCtl(key: String, alpha: Float, sizeDp: Int, action: () -> Unit): View {
        val v = Icons.make(a, key, 14f)
        v.alpha = alpha
        v.setPadding(px(7), px(7), px(7), px(7))
        v.isClickable = true
        v.setOnClickListener { action() }
        return FrameLayout(a).apply { addView(v, FrameLayout.LayoutParams(px(sizeDp), px(sizeDp))) }
    }

    // Kontrol edit satu situs: sematkan, ubah, hapus. (Urutan diubah dengan tekan-tahan lalu geser.)
    fun editControls(s: Site, size: Float): List<View> = listOf(
        iconCtl("@sematkan", if (s.pinned) 1f else 0.4f, if (size > 16f) 40 else 36) { togglePin(s) },
        ctl("✎", true, size) { ui.onEdit(s) { render() } },
        ctl("✕", true, size) { remove(s) }
    )

    fun listRow(s: Site): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#2A2C33"), px(16).toFloat(), false)
            setPadding(px(14), px(12), px(8), px(if (editMode) 4 else 12))
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                if (editMode) ui.onEdit(s) { render() } else { d.dismiss(); ui.onOpen(s.url) }
            }
        }
        top.addView(Favicons.badge(a, Favicons.hostOf(s.url), s.name, px(40)),
            LinearLayout.LayoutParams(px(40), px(40)).apply { rightMargin = px(12) })
        val info = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(a).apply {
            text = (if (s.pinned) "📌 " else "") + s.name
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (s.url == ui.current) Color.parseColor("#6EA8FF") else Color.WHITE)
        })
        info.addView(TextView(a).apply {
            text = s.url
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#9AA0AC"))
        })
        top.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(top, LinearLayout.LayoutParams(match, wrap))
        if (editMode) {
            val bar = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            editControls(s, 17f).forEach { bar.addView(it) }
            row.addView(bar, LinearLayout.LayoutParams(match, wrap))
        }
        drag.add(row, if (s.pinned) 1 else 0, top, row)
        return row
    }

    fun tile(s: Site): View {
        val tileBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg(Color.parseColor("#2A2C33"), px(16).toFloat(), false)
            setPadding(px(6), px(12), px(6), px(if (editMode) 4 else 12))
        }
        val main = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener {
                if (editMode) ui.onEdit(s) { render() } else { d.dismiss(); ui.onOpen(s.url) }
            }
        }
        main.addView(Favicons.badge(a, Favicons.hostOf(s.url), s.name, px(46)), LinearLayout.LayoutParams(px(46), px(46)))
        main.addView(TextView(a).apply {
            text = (if (s.pinned) "📌 " else "") + s.name
            textSize = 12.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(if (s.url == ui.current) Color.parseColor("#6EA8FF") else Color.WHITE)
            setPadding(0, px(8), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        tileBox.addView(main, LinearLayout.LayoutParams(match, wrap))
        if (editMode) {
            // satu baris: sematkan, ubah, hapus
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            editControls(s, 15f).forEach { bar.addView(it) }
            tileBox.addView(bar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(4) })
        }
        drag.add(tileBox, if (s.pinned) 1 else 0, main, tileBox)
        return tileBox
    }

    render = {
        buildHeader()
        hintTv.text = if (editMode) "Mode edit: ketuk situs untuk mengubah · sematkan · hapus ✕ · tekan tahan lalu geser untuk urutan"
            else "Tekan tahan sebuah situs lalu geser untuk mengubah urutan"
        box.removeAllViews()
        drag.clear()
        val all = ui.load()
        val pinnedList = all.filter { it.pinned }
        val otherList = all.filter { !it.pinned }
        val ordered = pinnedList + otherList
        if (ordered.isEmpty()) {
            box.addView(TextView(a).apply {
                text = "Belum ada situs tersimpan"
                textSize = 14f
                setTextColor(Color.parseColor("#9AA0AC"))
                gravity = Gravity.CENTER
                setPadding(0, px(24), 0, px(12))
            }, LinearLayout.LayoutParams(match, wrap))
        } else if (!gridMode) {
            ordered.forEach { s ->
                box.addView(listRow(s), LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
            }
        } else {
            val widthDp = d.widthPx / dp
            val cols = ((widthDp - 32f) / 104f).toInt().coerceIn(2, 4)
            var rowLayout: LinearLayout? = null
            ordered.forEachIndexed { i, s ->
                if (i % cols == 0) {
                    val rl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(rl, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
                    rowLayout = rl
                }
                rowLayout?.addView(tile(s),
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { if (i % cols != 0) leftMargin = px(8) })
            }
            // isi sisa kolom di baris terakhir supaya lebar kotak sama
            val rem = ordered.size % cols
            if (rem != 0) {
                for (k in 0 until cols - rem) {
                    rowLayout?.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = px(8) })
                }
            }
        }
    }
    render()
    // logo situs yang belum ada diunduh di latar belakang, lalu daftar digambar ulang
    Favicons.fetchMissing(a, ui.load().map { Favicons.hostOf(it.url) }) {
        a.runOnUiThread { if (sitesDialog === d && d.isShowing && !drag.isDragging) render() }
    }

    d.show(root)
}
