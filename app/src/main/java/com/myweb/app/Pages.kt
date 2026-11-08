package com.myweb.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.ScriptHandler
import org.json.JSONArray
import org.json.JSONObject

// Satu page = satu "tab". WebView hanya ada selama page aktif (wv != null); page hibernasi hanya menyimpan
// alamat, judul, posisi baca (dan riwayat di memori) supaya RAM bebas. Keadaan yang dulu menjadi field MainActivity
// (siteBase, firstLoad, restoreY, ...) kini milik tiap page karena tiap page punya WebView sendiri.
class Page(val id: Long) {
    // Alamat awal page (setara pref "url" untuk page ini); configBase ikut dihitung ulang
    var home: String = ""
        set(v) {
            field = v
            configBase = PublicSuffix.registrableDomain(Uri.parse(v).host)
        }
    @Volatile var configBase: String = ""
    var url: String = ""                 // alamat terakhir yang dibuka (untuk lanjut membaca)
    var title: String = ""
    var scroll: Int = 0                  // posisi baca terakhir (piksel CSS)
    var lastActive: Long = System.currentTimeMillis()
    var hibernatedAt: Long = 0L          // 0 = sedang aktif (punya WebView)
    var wv: WebView? = null
    var state: Bundle? = null            // riwayat navigasi saat hibernasi (hanya di memori)
    @Volatile var siteBase: String = ""
    var firstLoad = false
    var restoreY = 0
    var restoreUrl: String? = null
    var restoreAt = 0L
    var failedUrl: String? = null
    var docStart: ScriptHandler? = null

    val isLive: Boolean get() = wv != null

    fun hostText(): String {
        val h = Uri.parse(if (url.isNotBlank()) url else home).host
        return h?.removePrefix("www.") ?: ""
    }

    fun label(): String {
        val t = title.trim()
        if (t.isNotEmpty()) return t
        val h = hostText()
        return if (h.isEmpty()) "Page baru" else h
    }
}

// Daftar page disimpan di SharedPreferences "cfg" (urutan = urutan yang diatur pengguna). Hanya data ringan:
// WebView tidak bisa disimpan, jadi setelah aplikasi mati/crash semua page kembali sebagai hibernasi dan hanya
// page terakhir yang dibuka yang dihidupkan lagi.
object PageStore {
    private const val KEY = "pages_json"
    private const val KEY_ACTIVE = "pages_active"

    fun load(prefs: SharedPreferences): Pair<MutableList<Page>, Long> {
        val out = ArrayList<Page>()
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                if (out.size >= PagePolicy.MAX_TOTAL) break
                val o = arr.getJSONObject(i)
                val id = o.getLong("i")
                if (out.any { it.id == id }) continue
                val p = Page(id)
                p.home = o.optString("h", "")
                p.url = o.optString("u", "")
                p.title = o.optString("t", "")
                p.scroll = ReadingPos.clampY(o.optInt("s", 0))
                p.lastActive = o.optLong("a", now)
                val z = o.optLong("z", 0L)
                p.hibernatedAt = if (z > 0L) z else now
                out.add(p)
            }
        } catch (e: Exception) { }
        return Pair(out, prefs.getLong(KEY_ACTIVE, -1L))
    }

    fun save(prefs: SharedPreferences, pages: List<Page>, activeId: Long) {
        val arr = JSONArray()
        for (p in pages) {
            arr.put(
                JSONObject()
                    .put("i", p.id)
                    .put("h", p.home)
                    .put("u", p.url)
                    .put("t", p.title.take(80))
                    .put("s", p.scroll)
                    .put("a", p.lastActive)
                    .put("z", if (p.isLive) 0L else p.hibernatedAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).putLong(KEY_ACTIVE, activeId).apply()
    }
}

// Batas page menurut spesifikasi HP. Angka-angkanya ada di PagePolicy (diuji); ini hanya membaca RAM perangkat.
object PageLimits {
    fun totalRamMb(ctx: Context): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024L * 1024L)
    } catch (e: Exception) {
        3000L
    }

    fun recommended(ctx: Context): Int {
        val low = try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        } catch (e: Exception) {
            false
        }
        return PagePolicy.recommendedLive(totalRamMb(ctx), low)
    }

    fun limit(ctx: Context, prefs: SharedPreferences): Int =
        PagePolicy.liveLimit(prefs.getInt("pages_limit", 0), recommended(ctx))

    fun hibernateMs(ctx: Context, prefs: SharedPreferences): Long =
        PagePolicy.hibernateMinutes(prefs.getInt("pages_hib_idx", 0), recommended(ctx)) * 60_000L

    fun closeMs(prefs: SharedPreferences): Long =
        PagePolicy.closeHours(prefs.getInt("pages_close_idx", 5)) * 3_600_000L

    fun formatHibernate(ctx: Context, idx: Int): String =
        if (idx <= 0) "Otomatis (" + PagePolicy.autoHibernateMinutes(recommended(ctx)) + " mnt)"
        else PagePolicy.hibernateMinutes(idx, recommended(ctx)).toString() + " mnt"

    fun formatClose(idx: Int): String = PagePolicy.closeHours(idx).toString() + " jam"
}

// ---------- Tampilan daftar page ----------

class PagesUi(
    val pages: () -> List<Page>,
    val currentId: () -> Long,
    val liveCount: () -> Int,
    val limit: () -> Int,
    val recommended: Int,
    val onSwitch: (Page) -> Unit,
    val onClose: (Page) -> Unit,
    val onMove: (Page, Int) -> Unit,
    val onHibernate: (Page) -> Unit,
    val onNewPage: () -> Unit,
    val onFromSites: () -> Unit,
    val grid: () -> Boolean,
    val setGrid: (Boolean) -> Unit
)

private var pagesDialog: Dialog? = null
private var pagesRender: (() -> Unit)? = null

fun dismissPagesSheet() {
    try { pagesDialog?.dismiss() } catch (e: Exception) { }
    pagesDialog = null
    pagesRender = null
}

// Dipanggil bila daftar berubah dari luar (hibernasi/tutup otomatis)
fun refreshPagesSheet() {
    pagesRender?.invoke()
}

fun showPagesSheet(a: Activity, ui: PagesUi) {
    dismissPagesSheet()
    val d = Dialog(a)
    pagesDialog = d
    d.setOnDismissListener {
        if (pagesDialog === d) { pagesDialog = null; pagesRender = null }
    }
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    fun bg(color: Int, r: Float, topOnly: Boolean) = GradientDrawable().apply {
        setColor(color)
        if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
    }

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Color.parseColor("#1F2024"), px(26).toFloat(), true)
        setPadding(px(18), px(10), px(18), px(24))
    }
    root.addView(View(a).apply { background = bg(0x44FFFFFF, px(3).toFloat(), false) },
        LinearLayout.LayoutParams(px(40), px(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = px(14)
        })

    val head = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    head.addView(Icons.make(a, "@page", 20f), LinearLayout.LayoutParams(px(26), px(26)).apply { rightMargin = px(10) })
    head.addView(TextView(a).apply {
        text = "Page"
        textSize = 18f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
    }, LinearLayout.LayoutParams(0, wrap, 1f))
    var gridMode = ui.grid()
    val toggles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    head.addView(toggles)
    root.addView(head, LinearLayout.LayoutParams(match, wrap))

    val infoRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    infoRow.addView(Icons.make(a, "@batas_page", 14f), LinearLayout.LayoutParams(px(20), px(20)).apply { rightMargin = px(8) })
    val infoTv = TextView(a).apply {
        textSize = 12.5f
        setTextColor(Color.parseColor("#9AA0AC"))
    }
    infoRow.addView(infoTv)
    root.addView(infoRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(6) })
    val warnTv = TextView(a).apply {
        textSize = 12.5f
        setTextColor(Color.parseColor("#FFB454"))
        setPadding(0, px(4), 0, 0)
    }
    root.addView(warnTv, LinearLayout.LayoutParams(match, wrap))

    fun secondaryBtn(label: String, primary: Boolean, action: () -> Unit) = TextView(a).apply {
        text = label
        textSize = 14f
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#BFD3FF"))
        gravity = Gravity.CENTER
        background = bg(Color.parseColor(if (primary) "#2563EB" else "#26324A"), px(16).toFloat(), false)
        setPadding(px(6), px(12), px(6), px(12))
        isClickable = true
        setOnClickListener { action() }
    }
    val btnRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    val newBtn = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = bg(Color.parseColor("#2563EB"), px(16).toFloat(), false)
        setPadding(px(6), px(10), px(6), px(10))
        isClickable = true
        setOnClickListener { d.dismiss(); ui.onNewPage() }
    }
    newBtn.addView(Icons.make(a, "@tambah_page", 16f), LinearLayout.LayoutParams(px(22), px(22)).apply { rightMargin = px(8) })
    newBtn.addView(TextView(a).apply { text = "Page baru"; textSize = 14f; setTextColor(Color.WHITE) })
    btnRow.addView(newBtn, LinearLayout.LayoutParams(0, wrap, 1f))
    btnRow.addView(secondaryBtn("🔖 Situs tersimpan", false) { d.dismiss(); ui.onFromSites() },
        LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(8) })
    root.addView(btnRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12); bottomMargin = px(4) })

    val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(match, wrap))

    fun ctl(label: String, enabled: Boolean, action: () -> Unit) = TextView(a).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (enabled) Color.parseColor("#D0D3DC") else Color.parseColor("#4A4D57"))
        setPadding(px(9), px(8), px(9), px(8))
        if (enabled) {
            isClickable = true
            setOnClickListener { action() }
        }
    }

    fun iconCtl(key: String, action: () -> Unit): View {
        val v = Icons.make(a, key, 14f)
        v.setPadding(px(8), px(8), px(8), px(8))
        v.isClickable = true
        v.setOnClickListener { action() }
        return FrameLayout(a).apply { addView(v, FrameLayout.LayoutParams(px(38), px(38))) }
    }

    fun viewToggle(key: String, active: Boolean, desc: String, click: () -> Unit): View = FrameLayout(a).apply {
        contentDescription = desc
        background = bg(if (active) Color.parseColor("#2C3A5A") else Color.TRANSPARENT, px(12).toFloat(), false)
        isClickable = true
        setOnClickListener { click() }
        addView(Icons.make(a, key, 18f), FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
    }

    fun render() {
        toggles.removeAllViews()
        toggles.addView(viewToggle("@view_list", !gridMode, "Tampilan daftar") {
            if (gridMode) { gridMode = false; ui.setGrid(false); render() }
        }, LinearLayout.LayoutParams(px(40), px(40)))
        toggles.addView(viewToggle("@view_grid", gridMode, "Tampilan kotak") {
            if (!gridMode) { gridMode = true; ui.setGrid(true); render() }
        }, LinearLayout.LayoutParams(px(40), px(40)).apply { leftMargin = px(4) })
        val list = ui.pages()
        val cur = ui.currentId()
        infoTv.text = list.size.toString() + " page  ·  " + ui.liveCount() + " aktif (batas " + ui.limit() + ")"
        if (ui.limit() > ui.recommended) {
            warnTv.visibility = View.VISIBLE
            warnTv.text = "⚠ Batas melebihi saran untuk HP ini (" + ui.recommended + "). HP bisa panas, aplikasi berat, bahkan crash."
        } else {
            warnTv.visibility = View.GONE
        }
        box.removeAllViews()

        // Tampilan kotak: logo, judul, status, dan kontrol kecil di tiap kotak
        fun gridTiles() {
            val widthDp = a.resources.displayMetrics.widthPixels / dp
            val cols = ((widthDp - 36f) / 150f).toInt().coerceIn(2, 3)
            var rowLayout: LinearLayout? = null
            list.forEachIndexed { idx, p ->
                val isCur = p.id == cur
                if (idx % cols == 0) {
                    val rl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(rl, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
                    rowLayout = rl
                }
                val tile = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = GradientDrawable().apply {
                        cornerRadius = px(16).toFloat()
                        setColor(Color.parseColor(if (isCur) "#1E3556" else "#2A2C33"))
                        if (isCur) setStroke(px(1), Color.parseColor("#5B8CFF"))
                    }
                    setPadding(px(6), px(12), px(6), px(4))
                }
                val main = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setOnClickListener { d.dismiss(); ui.onSwitch(p) }
                }
                main.addView(Favicons.badge(a, Favicons.hostOf(if (p.url.isNotBlank()) p.url else p.home), p.label(), px(44)),
                    LinearLayout.LayoutParams(px(44), px(44)))
                main.addView(TextView(a).apply {
                    text = p.label()
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(if (isCur) Color.parseColor("#BFD3FF") else Color.WHITE)
                    setPadding(px(4), px(8), px(4), 0)
                }, LinearLayout.LayoutParams(match, wrap))
                main.addView(TextView(a).apply {
                    text = if (isCur) "Sedang dibuka" else if (p.isLive) "Aktif" else "💤 Hibernasi"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (p.isLive) "#7FD6A0" else "#9AA0AC"))
                }, LinearLayout.LayoutParams(match, wrap))
                tile.addView(main, LinearLayout.LayoutParams(match, wrap))
                val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                if (p.isLive && !isCur) bar.addView(iconCtl("@hibernasi") { ui.onHibernate(p); render() })
                bar.addView(ctl("◀", idx > 0) { ui.onMove(p, -1); render() })
                bar.addView(ctl("▶", idx < list.size - 1) { ui.onMove(p, 1); render() })
                bar.addView(ctl("✕", true) { ui.onClose(p); render() })
                tile.addView(bar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(4) })
                rowLayout?.addView(tile, LinearLayout.LayoutParams(0, wrap, 1f).apply { if (idx % cols != 0) leftMargin = px(8) })
            }
            val rem = list.size % cols
            if (rem != 0) {
                for (k in 0 until cols - rem) {
                    rowLayout?.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = px(8) })
                }
            }
        }
        if (gridMode) {
            gridTiles()
            return
        }

        list.forEachIndexed { idx, p ->
            val isCur = p.id == cur
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = px(16).toFloat()
                    setColor(Color.parseColor(if (isCur) "#1E3556" else "#2A2C33"))
                    if (isCur) setStroke(px(1), Color.parseColor("#5B8CFF"))
                }
                setPadding(px(14), px(10), px(4), px(10))
            }
            row.addView(Favicons.badge(a, Favicons.hostOf(if (p.url.isNotBlank()) p.url else p.home), p.label(), px(36)),
                LinearLayout.LayoutParams(px(36), px(36)).apply { rightMargin = px(12) })
            val info = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setOnClickListener { d.dismiss(); ui.onSwitch(p) }
            }
            info.addView(TextView(a).apply {
                text = p.label()
                textSize = 15.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCur) Color.parseColor("#BFD3FF") else Color.WHITE)
            })
            val status = if (isCur) "Sedang dibuka" else if (p.isLive) "Aktif" else "💤 Hibernasi"
            val host = p.hostText()
            info.addView(TextView(a).apply {
                text = if (host.isEmpty()) status else host + "  ·  " + status
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(if (p.isLive) "#7FD6A0" else "#9AA0AC"))
            })
            row.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
            if (p.isLive && !isCur) row.addView(iconCtl("@hibernasi") { ui.onHibernate(p); render() })
            row.addView(ctl("▲", idx > 0) { ui.onMove(p, -1); render() })
            row.addView(ctl("▼", idx < list.size - 1) { ui.onMove(p, 1); render() })
            row.addView(ctl("✕", true) { ui.onClose(p); render() })
            box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
        }
    }
    pagesRender = { render() }
    render()
    Favicons.fetchMissing(a, ui.pages().map { Favicons.hostOf(if (it.url.isNotBlank()) it.url else it.home) }) {
        a.runOnUiThread { if (pagesDialog === d && d.isShowing) render() }
    }

    d.setContentView(root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(match, wrap)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.55f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}

// Pilihan "buka di mana" (menggantikan dialog putih biasa): dua kartu besar, Page baru disorot
fun showPageTargetSheet(a: Activity, url: String, info: String, onCurrent: () -> Unit, onNew: () -> Unit) {
    val d = Dialog(a)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    fun bg(color: Int, r: Float, topOnly: Boolean) = GradientDrawable().apply {
        setColor(color)
        if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
    }
    val host = Favicons.hostOf(url)
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Color.parseColor("#1F2024"), px(26).toFloat(), true)
        setPadding(px(18), px(10), px(18), px(24))
    }
    root.addView(View(a).apply { background = bg(0x44FFFFFF, px(3).toFloat(), false) },
        LinearLayout.LayoutParams(px(40), px(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(16) })

    val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(Favicons.badge(a, host, host, px(44)), LinearLayout.LayoutParams(px(44), px(44)).apply { rightMargin = px(12) })
    val ht = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    ht.addView(TextView(a).apply {
        text = "Buka di mana?"
        textSize = 18f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
    })
    ht.addView(TextView(a).apply {
        text = if (host.isEmpty()) url else host
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.parseColor("#9AA0AC"))
    })
    head.addView(ht, LinearLayout.LayoutParams(0, wrap, 1f))
    root.addView(head, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(14) })

    fun card(icon: String, title: String, sub: String, primary: Boolean, action: () -> Unit) {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(Color.parseColor(if (primary) "#1E3556" else "#2A2C33"))
                if (primary) setStroke(px(1), Color.parseColor("#5B8CFF"))
            }
            setPadding(px(16), px(14), px(16), px(14))
            isClickable = true
            setOnClickListener { d.dismiss(); action() }
        }
        c.addView(Icons.make(a, icon, 22f), LinearLayout.LayoutParams(px(40), px(40)).apply { rightMargin = px(14) })
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(a).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (primary) Color.parseColor("#BFD3FF") else Color.WHITE)
        })
        t.addView(TextView(a).apply {
            text = sub
            textSize = 12.5f
            setTextColor(Color.parseColor("#9AA0AC"))
        })
        c.addView(t, LinearLayout.LayoutParams(0, wrap, 1f))
        c.addView(TextView(a).apply { text = "›"; textSize = 24f; setTextColor(Color.parseColor("#6B6F7A")) })
        root.addView(c, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
    }
    card("@tambah_page", "Page baru", "Buka di tab baru, halaman yang sekarang tetap ada", true, onNew)
    card("@page", "Page sekarang", "Ganti halaman yang sedang dibuka", false, onCurrent)
    if (info.isNotEmpty()) {
        root.addView(TextView(a).apply {
            text = info
            textSize = 12f
            setTextColor(Color.parseColor("#7F93C9"))
            setPadding(px(4), px(12), px(4), 0)
        }, LinearLayout.LayoutParams(match, wrap))
    }

    d.setContentView(root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(match, wrap)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.55f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}
