package com.myweb.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

fun showSitesSheet(
    a: Activity,
    sites: List<Pair<String, String>>,
    current: String,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit
) {
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
    root.addView(TextView(a).apply {
        text = "Situs tersimpan"
        textSize = 18f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    })

    val newBtn = TextView(a).apply {
        text = "＋  Situs baru"
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = bg(Color.parseColor("#2563EB"), px(16).toFloat(), false)
        setPadding(0, px(13), 0, px(13))
        isClickable = true
        setOnClickListener { d.dismiss(); onNew() }
    }
    root.addView(newBtn, LinearLayout.LayoutParams(match, wrap).apply {
        topMargin = px(12)
        bottomMargin = px(4)
    })

    val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    sites.forEach { s ->
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(Color.parseColor("#2A2C33"), px(16).toFloat(), false)
            setPadding(px(14), px(12), px(6), px(12))
        }
        val info = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { d.dismiss(); onOpen(s.second) }
        }
        info.addView(TextView(a).apply {
            text = s.first
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (s.second == current) Color.parseColor("#6EA8FF") else Color.WHITE)
        })
        info.addView(TextView(a).apply {
            text = s.second
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#9AA0AC"))
        })
        row.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(TextView(a).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#9AA0AC"))
            setPadding(px(14), px(8), px(14), px(8))
            isClickable = true
            setOnClickListener { row.visibility = View.GONE; onDelete(s.second) }
        })
        box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
    }
    root.addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(match, wrap))

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
