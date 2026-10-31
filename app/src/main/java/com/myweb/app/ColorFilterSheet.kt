package com.myweb.app

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject

private val CF_KEYS = arrayOf("cf_b", "cf_c", "cf_s", "cf_w")
private const val CF_DEFAULT = 50

// Filter warna untuk seluruh tampilan WebView (termasuk gambar).
// Semua slider warna di tengah (50) = normal, tanpa filter.
fun applyColorFilter(webView: WebView, prefs: SharedPreferences) {
    val b = prefs.getInt("cf_b", CF_DEFAULT)
    val c = prefs.getInt("cf_c", CF_DEFAULT)
    val s = prefs.getInt("cf_s", CF_DEFAULT)
    val w = prefs.getInt("cf_w", CF_DEFAULT)

    if (b == CF_DEFAULT && c == CF_DEFAULT && s == CF_DEFAULT && w == CF_DEFAULT) {
        // Tanpa filter: JANGAN pakai layer offscreen. Layer hardware memaksa seluruh WebView digambar ke tekstur ekstra
        // lalu disalin lagi tiap frame (paling terasa saat scroll di refresh rate tinggi); NONE = jalur normal WebView.
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        return
    }

    val offset = (b - 50) * 1.6f          // kecerahan: -80 .. +80
    val contrast = 0.5f + c / 100f        // kontras: 0.5 .. 1.5
    val sat = s / 50f                     // saturasi: 0 .. 2
    val warm = (w - 50) / 50f             // dingin -1 .. hangat +1

    val cm = ColorMatrix()
    cm.setSaturation(sat)

    val ct = (1f - contrast) * 128f
    cm.postConcat(ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, ct,
        0f, contrast, 0f, 0f, ct,
        0f, 0f, contrast, 0f, ct,
        0f, 0f, 0f, 1f, 0f
    )))

    cm.postConcat(ColorMatrix(floatArrayOf(
        1f + 0.2f * warm, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f - 0.3f * warm, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )))

    cm.postConcat(ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, offset,
        0f, 1f, 0f, 0f, offset,
        0f, 0f, 1f, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    )))

    val paint = Paint()
    paint.colorFilter = ColorMatrixColorFilter(cm)
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
}

// Haluskan (blur lembut) dan ketajaman, hanya untuk tag <img> di halaman.
// 0 dan 0 = mati, tidak ada yang dipasang.
fun applyImageFx(webView: WebView, prefs: SharedPreferences) {
    val sm = prefs.getInt("fx_sm", 0)
    val sh = prefs.getInt("fx_sh", 0)

    val clear = "var o=document.getElementById('__imgfx');if(o)o.remove();" +
        "var p=document.getElementById('__imgfx_svg');if(p)p.remove();"

    if (sm == 0 && sh == 0) {
        webView.evaluateJavascript("(function(){" + clear + "})();", null)
        return
    }

    val blur = sm / 100f * 1.2f
    val amt = sh / 100f
    val center = 1f + 4f * amt
    val kernel = "0 -" + amt + " 0 -" + amt + " " + center + " -" + amt + " 0 -" + amt + " 0"

    var parts = ""
    var input = "SourceGraphic"
    if (sm > 0) {
        parts += "<feGaussianBlur in='SourceGraphic' stdDeviation='" + blur + "' result='b'/>"
        input = "b"
    }
    if (sh > 0) {
        parts += "<feConvolveMatrix in='" + input + "' order='3' kernelMatrix='" + kernel +
            "' preserveAlpha='true'/>"
    }
    val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='0' height='0'>" +
        "<filter id='__imgfx_f' color-interpolation-filters='sRGB'>" + parts + "</filter></svg>"

    val js = "(function(){" + clear +
        "var d=document.createElement('div');d.id='__imgfx_svg';" +
        "d.style.cssText='position:absolute;width:0;height:0;overflow:hidden';" +
        "d.innerHTML=" + JSONObject.quote(svg) + ";" +
        "(document.body||document.documentElement).appendChild(d);" +
        "var s=document.createElement('style');s.id='__imgfx';" +
        "s.textContent='img{filter:url(#__imgfx_f)}';" +
        "(document.head||document.documentElement).appendChild(s);})();"
    webView.evaluateJavascript(js, null)
}

fun showColorFilterSheet(a: Activity, webView: WebView, prefs: SharedPreferences) {
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    val d = Dialog(a)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setCancelable(true)
    d.setCanceledOnTouchOutside(true)
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    val sheetBg = GradientDrawable().apply {
        setColor(Color.parseColor("#EE1F2024"))
        val r = px(26).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = sheetBg
        setPadding(px(20), px(16), px(20), px(24))
        isClickable = true
    }

    root.addView(TextView(a).apply {
        text = "🎨 Penyesuaian warna"
        textSize = 18f
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(match, wrap))

    class Slider(val bar: SeekBar, val key: String, val def: Int)
    val sliders = ArrayList<Slider>()

    // centered = true  -> tengah (50) tampil 0, rentang -50..+50
    // centered = false -> mulai dari 0, rentang 0..+100
    // live = true      -> diterapkan saat digeser; false -> saat jari dilepas
    fun addSlider(label: String, key: String, def: Int, centered: Boolean, live: Boolean, onApply: () -> Unit) {
        fun fmt(p: Int): String {
            val v = if (centered) p - 50 else p
            return if (v > 0) "+" + v else v.toString()
        }
        val tv = TextView(a).apply {
            text = label + ":  " + fmt(prefs.getInt(key, def))
            textSize = 14f
            setTextColor(Color.parseColor("#D0D3DC"))
        }
        root.addView(tv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })

        val sb = SeekBar(a).apply {
            max = 100
            progress = prefs.getInt(key, def)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = label + ":  " + fmt(p)
                    if (!fromUser) return
                    prefs.edit().putInt(key, p).apply()
                    if (live) onApply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { if (!live) onApply() }
            })
        }
        root.addView(sb, LinearLayout.LayoutParams(match, wrap))
        sliders.add(Slider(sb, key, def))
    }

    val colorApply: () -> Unit = { applyColorFilter(webView, prefs) }

    // Preset siap pakai (urutan nilai: kecerahan, kontras, saturasi, dingin/hangat). Setelah dipilih, tiap slider
    // masih bisa disesuaikan sendiri.
    val presets = listOf(
        "Normal" to intArrayOf(50, 50, 50, 50),
        "Malam" to intArrayOf(40, 45, 40, 70),
        "Sepia" to intArrayOf(48, 45, 20, 80),
        "Tajam" to intArrayOf(50, 65, 65, 50),
        "Lembut" to intArrayOf(45, 40, 40, 55)
    )
    val chipRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    presets.forEach { (name, v) ->
        chipRow.addView(TextView(a).apply {
            text = name
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#2A2C33")); cornerRadius = px(16).toFloat() }
            setPadding(px(14), px(8), px(14), px(8))
            isClickable = true
            setOnClickListener {
                val e = prefs.edit()
                CF_KEYS.forEachIndexed { i, k -> e.putInt(k, v[i]) }
                e.apply()
                sliders.forEach { sl ->
                    val i = CF_KEYS.indexOf(sl.key)
                    if (i >= 0) sl.bar.progress = v[i]
                }
                colorApply()
            }
        }, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = px(8) })
    }
    root.addView(HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipRow)
    }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10); bottomMargin = px(2) })
    val fxApply: () -> Unit = { applyImageFx(webView, prefs) }

    addSlider("Kecerahan", "cf_b", CF_DEFAULT, true, true, colorApply)
    addSlider("Kontras", "cf_c", CF_DEFAULT, true, true, colorApply)
    addSlider("Saturasi", "cf_s", CF_DEFAULT, true, true, colorApply)
    addSlider("Dingin / Hangat", "cf_w", CF_DEFAULT, true, true, colorApply)

    root.addView(TextView(a).apply {
        text = "Khusus gambar (diterapkan setelah slider dilepas)"
        textSize = 12f
        setTextColor(Color.parseColor("#8A8E99"))
    }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(16) })

    addSlider("Haluskan gambar", "fx_sm", 0, false, false, fxApply)
    addSlider("Ketajaman gambar", "fx_sh", 0, false, false, fxApply)

    val resetBg = GradientDrawable().apply {
        setColor(Color.parseColor("#2A2C33"))
        cornerRadius = px(18).toFloat()
    }
    root.addView(TextView(a).apply {
        text = "Reset"
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = resetBg
        setPadding(0, px(12), 0, px(12))
        isClickable = true
        setOnClickListener {
            val e = prefs.edit()
            sliders.forEach { e.putInt(it.key, it.def) }
            e.apply()
            sliders.forEach { it.bar.progress = it.def }
            applyColorFilter(webView, prefs)
            applyImageFx(webView, prefs)
        }
    }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(16) })

    // Seluruh layar jadi area ketuk: ketuk di luar panel = tutup (tanpa redup, agar warna terlihat asli)
    val scroll = ScrollView(a)
    scroll.addView(root)
    val overlay = FrameLayout(a)
    overlay.setOnClickListener { d.dismiss() }
    overlay.addView(scroll, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))

    d.setContentView(overlay)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(match, match)
        setDimAmount(0f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}

