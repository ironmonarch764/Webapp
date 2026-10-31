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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

// Baris pengaturan. Judul/subjudul sudah teks jadi; seluruh daftar dibangun ulang tiap ada perubahan (build dipanggil lagi).
sealed class SettingRow(val icon: String, val title: String, val subtitle: String) {
    class Toggle(icon: String, title: String, subtitle: String, val checked: Boolean, val onChange: (Boolean) -> Unit) :
        SettingRow(icon, title, subtitle)

    class Action(icon: String, title: String, subtitle: String, val onClick: () -> Unit) :
        SettingRow(icon, title, subtitle)

    // Slider bilangan bulat min..max; format() menghasilkan teks nilai; onChange dipanggil saat jari dilepas
    class Level(
        icon: String, title: String, subtitle: String,
        val min: Int, val max: Int, val value: Int,
        val format: (Int) -> String, val onChange: (Int) -> Unit
    ) : SettingRow(icon, title, subtitle)
}

class SettingSection(val title: String, val rows: List<SettingRow>)

private var settingsDialog: Dialog? = null
private var settingsRender: (() -> Unit)? = null

fun dismissSettingsSheet() {
    try { settingsDialog?.dismiss() } catch (e: Exception) { }
    settingsDialog = null
    settingsRender = null
}

// Dipanggil bila nilai yang tampil (mis. label DNS) berubah dari luar
fun refreshSettingsSheet() {
    settingsRender?.invoke()
}

fun showSettingsSheet(a: Activity, title: String, build: () -> List<SettingSection>) {
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    try { settingsDialog?.dismiss() } catch (e: Exception) { }
    val d = Dialog(a)
    settingsDialog = d
    d.setOnDismissListener {
        if (settingsDialog === d) { settingsDialog = null; settingsRender = null }
    }
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setCancelable(true)

    val bgColor = Color.parseColor("#121316")
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bgColor)
        // beri ruang untuk status bar / bilah navigasi bila jendela digambar di bawahnya
        setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
    }

    val bar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), px(8), px(16), px(8))
    }
    bar.addView(TextView(a).apply {
        text = "←"
        textSize = 24f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(px(14), px(6), px(14), px(6))
        isClickable = true
        setOnClickListener { d.dismiss() }
    })
    bar.addView(TextView(a).apply {
        text = title
        textSize = 20f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
    })
    root.addView(bar, LinearLayout.LayoutParams(match, wrap))

    val content = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(4), px(16), px(32))
    }
    val sv = ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        addView(content)
    }
    root.addView(sv, LinearLayout.LayoutParams(match, 0, 1f))

    fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    fun render() {
        val keepY = sv.scrollY
        content.removeAllViews()
        for (section in build()) {
            content.addView(TextView(a).apply {
                text = section.title.uppercase()
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(Color.parseColor("#7F93C9"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(px(8), px(18), px(8), px(8))
            }, LinearLayout.LayoutParams(match, wrap))

            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.parseColor("#1D1F24"), px(20).toFloat())
            }
            section.rows.forEachIndexed { idx, row ->
                if (idx > 0) {
                    card.addView(View(a).apply { setBackgroundColor(Color.parseColor("#2A2C33")) },
                        LinearLayout.LayoutParams(match, 1).apply { leftMargin = px(58) })
                }
                card.addView(buildRow(a, row, { v -> px(v) }) { content.post { settingsRender?.invoke() } })
            }
            content.addView(card, LinearLayout.LayoutParams(match, wrap))
        }
        sv.post { sv.scrollTo(0, keepY) }
    }
    settingsRender = { render() }
    render()

    d.setContentView(root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(bgColor))
        setLayout(match, match)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
    root.requestApplyInsets()
}

private fun buildRow(a: Activity, row: SettingRow, px: (Int) -> Int, rerender: () -> Unit): View {
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    val box = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(12), px(14), px(12))
    }
    val top = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(TextView(a).apply { text = row.icon; textSize = 20f; gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(px(32), wrap).apply { rightMargin = px(12) })

    val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(a).apply {
        text = row.title
        textSize = 15.5f
        setTextColor(Color.WHITE)
    })
    if (row.subtitle.isNotEmpty()) {
        texts.addView(TextView(a).apply {
            text = row.subtitle
            textSize = 12.5f
            setTextColor(Color.parseColor("#8A8E99"))
            setPadding(0, px(2), 0, 0)
        })
    }
    top.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))

    when (row) {
        is SettingRow.Toggle -> {
            val sw = Switch(a).apply {
                isChecked = row.checked
                setOnCheckedChangeListener { _, c -> row.onChange(c); rerender() }
            }
            top.addView(sw)
            box.isClickable = true
            box.setOnClickListener { sw.toggle() }
        }
        is SettingRow.Action -> {
            top.addView(TextView(a).apply {
                text = "›"
                textSize = 24f
                setTextColor(Color.parseColor("#6B6F7A"))
            })
            box.isClickable = true
            box.setOnClickListener { row.onClick() }
        }
        is SettingRow.Level -> {
            val valueTv = TextView(a).apply {
                text = row.format(row.value)
                textSize = 13.5f
                setTextColor(Color.parseColor("#BFD3FF"))
            }
            top.addView(valueTv)
        }
    }
    box.addView(top, LinearLayout.LayoutParams(match, wrap))

    if (row is SettingRow.Level) {
        val sb = SeekBar(a).apply {
            max = row.max - row.min
            progress = row.value - row.min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    // nilai ditampilkan langsung saat digeser
                    ((top.getChildAt(top.childCount - 1)) as? TextView)?.text = row.format(row.min + p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    row.onChange(row.min + (s?.progress ?: 0))
                }
            })
        }
        box.addView(sb, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(6) })
    }
    return box
}
