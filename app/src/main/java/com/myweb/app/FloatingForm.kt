package com.myweb.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Satu kolom isian. isUrl = papan ketik alamat web.
class FormField(val hint: String, val text: String = "", val isUrl: Boolean = false)

// Kotak isian melayang (pengganti AlertDialog putih): judul, kolom isian, tombol Batal / OK.
// onOk dipanggil saat OK / tombol Selesai di keyboard. Di dalamnya panggil fail("pesan") untuk menampilkan galat
// dan membiarkan panel tetap terbuka, atau close() lalu lanjutkan pekerjaan bila isian sah.
// cancelable = false: tanpa tombol ✕/Batal, Kembali tidak menutup (dipakai saat alamat awal wajib diisi).
// onCancel dipanggil hanya dari tombol ✕ / Batal.
fun showFormPanel(
    a: Activity,
    title: String,
    fields: List<FormField>,
    okLabel: String,
    cancelable: Boolean,
    onCancel: (() -> Unit)? = null,
    onOk: (List<String>, (String) -> Unit, () -> Unit) -> Unit
) {
    val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    val d = FloatingPanel(a, key = "form")
    d.cancelable = cancelable

    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(2), px(16), px(16))
    }
    root.addView(d.grip(), LinearLayout.LayoutParams(match, px(24)))

    val head = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    head.addView(TextView(a).apply {
        text = title
        textSize = 18f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
    }, LinearLayout.LayoutParams(0, wrap, 1f))
    if (cancelable) {
        val x = d.closeButton()
        x.setOnClickListener { d.dismiss(); onCancel?.invoke() }
        head.addView(x)
    }
    root.addView(head, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(6) })

    val err = TextView(a).apply {
        textSize = 12.5f
        setTextColor(Color.parseColor("#FF8A80"))
        visibility = View.GONE
    }

    fun submit(inputs: List<EditText>) {
        err.visibility = View.GONE
        onOk(
            inputs.map { it.text.toString() },
            { msg -> err.text = msg; err.visibility = View.VISIBLE },
            { d.dismiss() }
        )
    }

    val inputs = ArrayList<EditText>()
    fields.forEachIndexed { i, f ->
        val last = i == fields.size - 1
        val et = EditText(a).apply {
            hint = f.hint
            inputType = if (f.isUrl) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A8E99"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2C33"))
                cornerRadius = px(14).toFloat()
            }
            setPadding(px(14), px(12), px(14), px(12))
            setText(f.text)
            setSelection(text.length)
            imeOptions = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            if (last) {
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        submit(inputs)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        inputs.add(et)
        root.addView(et, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
    }
    root.addView(err, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(6) })

    val btnRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    if (cancelable) {
        btnRow.addView(TextView(a).apply {
            text = "Batal"
            textSize = 15f
            setTextColor(Color.parseColor("#D0D3DC"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2A2C33")); cornerRadius = px(16).toFloat() }
            setPadding(0, px(12), 0, px(12))
            isClickable = true
            setOnClickListener { d.dismiss(); onCancel?.invoke() }
        }, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = px(8) })
    }
    btnRow.addView(TextView(a).apply {
        text = okLabel
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.parseColor("#2563EB")); cornerRadius = px(16).toFloat() }
        setPadding(0, px(12), 0, px(12))
        isClickable = true
        setOnClickListener { submit(inputs) }
    }, LinearLayout.LayoutParams(0, wrap, 1f))
    root.addView(btnRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })

    d.show(root)
    // fokus ke kolom pertama dan munculkan keyboard
    inputs.firstOrNull()?.let { first ->
        first.post {
            first.requestFocus()
            try {
                (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) { }
        }
    }
}
