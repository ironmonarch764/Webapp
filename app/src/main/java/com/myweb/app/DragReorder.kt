package com.myweb.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ScrollView

// Urut dengan tekan-tahan lalu geser (pengganti tombol ▲▼◀▶), untuk daftar maupun kotak.
//  - Tekan tahan sebuah item: item terangkat dan mengikuti jari (ada getaran singkat).
//  - Geser ke atas item lain: item itu meredup = tujuan. Lepas jari: item pindah ke posisi tujuan.
//  - Lepas di luar item / di luar daftar: dibatalkan, urutan tidak berubah.
//  - Dekat tepi atas/bawah daftar, daftar menggulir sendiri.
//  - Item hanya bisa dipindah ke item lain dalam kelompok yang sama (group), mis. situs tersemat vs tidak.
// Ketukan biasa dan menggulir daftar tetap berjalan seperti biasa (tekan tahan baru dihitung bila jari diam).
class DragReorder(
    private val a: Activity,
    private val scroll: ScrollView,
    private val container: ViewGroup,
    // (dari, ke) = indeks urutan tampil. Dipanggil sekali saat jari dilepas di atas item tujuan yang sah.
    private val onDrop: (Int, Int) -> Unit
) {
    private class Item(val view: View, val group: Int)

    private val items = ArrayList<Item>()
    private val handler = Handler(Looper.getMainLooper())
    private val density = a.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(a).scaledTouchSlop

    private var pendingFrom = -1
    private var pendingRun: Runnable? = null
    private var dragFrom = -1
    private var target = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrollAtStart = 0
    private var edgeRun: Runnable? = null
    private val raised = ArrayList<View>()

    /** true selama ada item yang sedang diseret (jangan gambar ulang daftar saat itu). */
    val isDragging: Boolean
        get() = dragFrom >= 0

    /** Panggil di awal setiap render ulang, sebelum add(). */
    fun clear() {
        cancelPending()
        stopEdge()
        if (dragFrom >= 0) restoreViews()
        dragFrom = -1
        target = -1
        items.clear()
    }

    /**
     * Daftarkan satu item (urutan pemanggilan = urutan tampil). item = tampilan yang diangkat dan dipindah;
     * handles = bagian item yang boleh ditekan-tahan untuk menyeretnya (jangan sertakan tombol kecil di dalamnya).
     */
    fun add(item: View, group: Int, vararg handles: View) {
        val idx = items.size
        items.add(Item(item, group))
        for (h in handles) {
            h.isClickable = true   // agar menerima rangkaian sentuhan sampai selesai
            h.setOnTouchListener { v, e -> onTouch(v, idx, e) }
        }
    }

    private fun onTouch(handle: View, idx: Int, e: MotionEvent): Boolean {
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_DOWN) {
            cancelPending()
            if (dragFrom >= 0) return false
            pendingFrom = idx
            downX = e.rawX
            downY = e.rawY
            val r = Runnable { startDrag(handle, idx) }
            pendingRun = r
            handler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
            return false
        }
        if (act == MotionEvent.ACTION_MOVE) {
            if (dragFrom == idx) {
                move(e.rawX, e.rawY)
                return true
            }
            if (pendingFrom == idx) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (dx * dx + dy * dy > slop * slop) cancelPending()
            }
            return false
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (dragFrom == idx) {
                endDrag(act == MotionEvent.ACTION_UP)
                return true
            }
            cancelPending()
            return false
        }
        return dragFrom == idx
    }

    private fun cancelPending() {
        val r = pendingRun
        if (r != null) handler.removeCallbacks(r)
        pendingRun = null
        pendingFrom = -1
    }

    private fun startDrag(handle: View, idx: Int) {
        pendingRun = null
        pendingFrom = -1
        if (idx < 0 || idx >= items.size) return
        val item = items[idx].view
        dragFrom = idx
        target = -1
        lastX = downX
        lastY = downY
        scrollAtStart = scroll.scrollY
        handle.parent?.requestDisallowInterceptTouchEvent(true)   // daftar tidak boleh merebut sentuhan lagi
        handle.isPressed = false
        item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        raise(item)
        item.scaleX = 1.04f
        item.scaleY = 1.04f
        item.alpha = 0.95f
        startEdgeScroll()
    }

    // Angkat item di atas yang lain dan jangan biarkan terpotong oleh baris induknya (tampilan kotak).
    private fun raise(item: View) {
        val z = 12f * density
        item.translationZ = z
        raised.add(item)
        val p = item.parent
        if (p is ViewGroup && p !== container) {
            p.translationZ = z
            raised.add(p)
        }
        var q: ViewParent? = item.parent
        while (q is ViewGroup && q !== scroll) {
            q.clipChildren = false
            q = q.parent
        }
    }

    private fun move(x: Float, y: Float) {
        if (dragFrom < 0 || dragFrom >= items.size) return
        lastX = x
        lastY = y
        val item = items[dragFrom].view
        item.translationX = x - downX
        // gulir daftar ikut menggeser posisi item, jadi ditambahkan agar item tetap di bawah jari
        item.translationY = (y - downY) + (scroll.scrollY - scrollAtStart)
        updateTarget(x, y)
    }

    private fun updateTarget(x: Float, y: Float) {
        val loc = IntArray(2)
        var hover = -1
        for (i in items.indices) {
            if (i == dragFrom) continue
            val v = items[i].view
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) {
                hover = i
                break
            }
        }
        var best = hover
        if (best >= 0 && items[best].group != items[dragFrom].group) best = -1
        if (hover < 0) {
            // di sela-sela item: pertahankan tujuan terakhir selama jari masih di dalam daftar
            scroll.getLocationOnScreen(loc)
            val inside = x >= loc[0] && x <= loc[0] + scroll.width && y >= loc[1] && y <= loc[1] + scroll.height
            if (inside) return
        }
        if (best != target) {
            if (target >= 0 && target < items.size) items[target].view.alpha = 1f
            target = best
            if (target >= 0) items[target].view.alpha = 0.5f
        }
    }

    // Gulir otomatis saat jari mendekati tepi atas/bawah daftar
    private fun startEdgeScroll() {
        val r = object : Runnable {
            override fun run() {
                if (dragFrom < 0) return
                val loc = IntArray(2)
                scroll.getLocationOnScreen(loc)
                val zone = 56f * density
                val top = loc[1]
                val bottom = loc[1] + scroll.height
                val step = (8f * density).toInt()
                val dy = if (lastY < top + zone) -step else if (lastY > bottom - zone) step else 0
                if (dy != 0) {
                    val before = scroll.scrollY
                    scroll.scrollBy(0, dy)
                    if (scroll.scrollY != before) move(lastX, lastY)
                }
                handler.postDelayed(this, 16L)
            }
        }
        edgeRun = r
        handler.postDelayed(r, 16L)
    }

    private fun stopEdge() {
        val r = edgeRun
        if (r != null) handler.removeCallbacks(r)
        edgeRun = null
    }

    private fun endDrag(commit: Boolean) {
        val from = dragFrom
        val to = target
        stopEdge()
        restoreViews()
        dragFrom = -1
        target = -1
        // ditunda satu putaran: daftar digambar ulang setelah penanganan sentuhan ini selesai
        if (commit && from >= 0 && to >= 0 && to != from) handler.post { onDrop(from, to) }
    }

    private fun restoreViews() {
        for (it2 in items) it2.view.alpha = 1f
        if (dragFrom >= 0 && dragFrom < items.size) {
            val v = items[dragFrom].view
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
        }
        for (r in raised) r.translationZ = 0f
        raised.clear()
    }
}
