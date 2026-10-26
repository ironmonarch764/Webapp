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
import android.widget.Switch
import android.widget.TextView

class MenuAction(val icon: String, val label: String, val onClick: () -> Unit)

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun showMenuSheet(
    a: Activity,
    quick: List<MenuAction>,
    items: List<MenuAction>,
    crossOn: Boolean,
    onCross: (Boolean) -> Unit
) {
    val d = Dialog(a)
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
        background = rounded(Color.parseColor("#1F2024"), px(26).toFloat(), true)
        setPadding(px(18), px(10), px(18), px(24))
        isClickable = true
    }

    val handle = View(a).apply { background = rounded(0x44FFFFFF, px(3).toFloat()) }
    root.addView(handle, LinearLayout.LayoutParams(px(40), px(5)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = px(14)
    })

    val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    quick.forEachIndexed { i, q ->
        val tile = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#2A2C33"), px(18).toFloat())
            setPadding(0, px(14), 0, px(12))
            isClickable = true
            setOnClickListener { d.dismiss(); q.onClick() }
        }
        tile.addView(TextView(a).apply { text = q.icon; textSize = 24f; gravity = Gravity.CENTER })
        tile.addView(TextView(a).apply {
            text = q.label
            textSize = 12f
            setTextColor(Color.parseColor("#D0D3DC"))
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, 0)
        })
        row.addView(tile, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = px(10) })
    }
    root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(12) })

    items.forEach { item ->
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(14), px(10), px(14))
            isClickable = true
            setOnClickListener { d.dismiss(); item.onClick() }
        }
        r.addView(TextView(a).apply { text = item.icon; textSize = 20f }, LinearLayout.LayoutParams(px(36), WRAP))
        r.addView(TextView(a).apply {
            text = item.label
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        root.addView(r)
    }

    val swRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(10), px(8), px(10), px(8))
    }
    swRow.addView(TextView(a).apply { text = "🛡️"; textSize = 20f }, LinearLayout.LayoutParams(px(36), WRAP))
    swRow.addView(TextView(a).apply {
        text = "Blokir pindah domain"
        textSize = 16f
        setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(0, WRAP, 1f))
    swRow.addView(Switch(a).apply {
        isChecked = crossOn
        setOnCheckedChangeListener { _, c -> onCross(c) }
    })
    root.addView(swRow)

    // Seluruh layar jadi area ketuk: ketuk di luar menu = tutup
    val overlay = FrameLayout(a)
    overlay.setOnClickListener { d.dismiss() }
    overlay.addView(root, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

    d.setContentView(overlay)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MATCH, MATCH)
        setDimAmount(0.55f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}
