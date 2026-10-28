package com.myweb.app

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

// ---------------------------------------------------------------------------------------------
// Log aplikasi: file teks lokal (applog.txt) berisi kejadian penting beserta waktunya.
// TIDAK mencatat alamat situs, host, cookie, atau isi halaman: hanya kejadian teknis dan angka.
// ---------------------------------------------------------------------------------------------

private val logLock = Any()
private const val LOG_FILE = "applog.txt"
private const val LOG_MAX = 64 * 1024

fun logEvent(ctx: Context, msg: String) {
    try {
        synchronized(logLock) {
            val f = File(ctx.applicationContext.filesDir, LOG_FILE)
            if (f.length() > LOG_MAX) {
                val tail = f.readText().takeLast(LOG_MAX / 2)
                val cut = tail.indexOf('\n')
                f.writeText(if (cut >= 0) tail.substring(cut + 1) else tail)
            }
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText(ts + " " + msg.replace('\n', ' ') + "\n")
        }
    } catch (e: Exception) { }
}

fun readLog(ctx: Context): String {
    return try {
        synchronized(logLock) {
            val f = File(ctx.applicationContext.filesDir, LOG_FILE)
            if (f.exists()) f.readText() else ""
        }
    } catch (e: Exception) { "" }
}

fun clearLog(ctx: Context) {
    try {
        synchronized(logLock) { File(ctx.applicationContext.filesDir, LOG_FILE).delete() }
    } catch (e: Exception) { }
}

fun ramSnapshot(ctx: Context): String {
    return try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        "ram-kosong=" + mi.availMem / 1048576 + "MB heap-app=" + (rt.totalMemory() - rt.freeMemory()) / 1048576 + "MB"
    } catch (e: Exception) { "ram=?" }
}

// ---------------------------------------------------------------------------------------------
// Perekam crash: menyimpan nama exception + 6 baris stack (TANPA pesan/URL), menulis ke log,
// dan menandai spanduk merah yang ditampilkan pada pembukaan berikutnya.
// ---------------------------------------------------------------------------------------------

private var crashRecorderInstalled = false

fun installCrashRecorder(app: Context) {
    if (crashRecorderInstalled) return
    crashRecorderInstalled = true
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try {
            val sb = StringBuilder(e.javaClass.name)
            val st = e.stackTrace
            for (i in 0 until minOf(6, st.size)) sb.append("\n  ").append(st[i].toString())
            app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("diag_crash", sb.toString())
                .putLong("diag_crash_at", System.currentTimeMillis())
                .putString("banner_pending", "Aplikasi sebelumnya berhenti karena error (" + e.javaClass.simpleName + "). Ketuk untuk melihat log.")
                .commit()
            logEvent(app, "CRASH " + sb.toString().replace("\n  ", " | ") + " thread=" + t.name)
        } catch (x: Throwable) { }
        prev?.uncaughtException(t, e)
    }
}

// ---------------------------------------------------------------------------------------------
// Penyebab proses sebelumnya berhenti menurut sistem Android (API 30+)
// ---------------------------------------------------------------------------------------------

@android.annotation.TargetApi(Build.VERSION_CODES.R)
private fun exitName(r: Int): String = when (r) {
    ApplicationExitInfo.REASON_LOW_MEMORY -> "MEMORI SISTEM HABIS"
    ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native)"
    ApplicationExitInfo.REASON_ANR -> "ANR (tidak merespons)"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "pemakaian sumber daya berlebih"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "gagal inisialisasi"
    ApplicationExitInfo.REASON_EXIT_SELF -> "keluar sendiri (finish)"
    ApplicationExitInfo.REASON_SIGNALED -> "dimatikan sistem (signal)"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "dihentikan pengguna"
    ApplicationExitInfo.REASON_USER_STOPPED -> "dihentikan pengguna (pengaturan)"
    else -> "kode " + r
}

@android.annotation.TargetApi(Build.VERSION_CODES.R)
private fun isAbnormal(r: Int): Boolean = when (r) {
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
    else -> false
}

/**
 * Dipanggil saat aplikasi dibuka. Menulis ke log setiap proses yang berhenti sejak pemeriksaan terakhir dan
 * mengembalikan teks bila yang terbaru berhenti TIDAK wajar saat masih di layar (untuk spanduk merah).
 * Proses yang dibuang sistem saat sudah di latar belakang itu normal bagi Android: hanya dicatat, tanpa spanduk.
 */
fun checkPreviousExit(a: Activity, prefs: SharedPreferences): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    try {
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val seen = prefs.getLong("exit_seen_ts", 0L)
        var newest = seen
        var banner: String? = null
        for (x in am.getHistoricalProcessExitReasons(a.packageName, 0, 5)) {
            if (x.timestamp <= seen) continue
            if (x.timestamp > newest) newest = x.timestamp
            // importance <= 200: proses masih di layar/terlihat saat berhenti; 400 = sudah di cache/latar belakang
            val foreground = x.importance <= 200
            val ageMin = (System.currentTimeMillis() - x.timestamp) / 60000
            val line = exitName(x.reason) + ", " + (if (foreground) "saat di layar" else "di latar belakang") +
                ", memori " + x.rss / 1024 + " MB, " + ageMin + " menit lalu"
            logEvent(a, "proses sebelumnya berhenti: " + line)
            // spanduk hanya untuk kejadian baru (< 24 jam), bukan riwayat lama
            if (banner == null && isAbnormal(x.reason) && foreground && ageMin < 1440) banner = line
        }
        prefs.edit().putLong("exit_seen_ts", newest).apply()
        return banner
    } catch (e: Exception) {
        return null
    }
}

// ---------------------------------------------------------------------------------------------
// Tampilan
// ---------------------------------------------------------------------------------------------

/** Spanduk merah di atas layar; ketuk untuk menutup dan menjalankan onTap; hilang sendiri setelah 20 detik. */
fun showRedBanner(a: Activity, text: String, onTap: () -> Unit) {
    a.runOnUiThread {
        val host = a.findViewById<ViewGroup>(android.R.id.content)
        val old = host.findViewWithTag<TextView>("red_banner")
        if (old != null) host.removeView(old)
        val d = a.resources.displayMetrics.density
        val tv = TextView(a)
        tv.tag = "red_banner"
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(0xFFC62828.toInt())
        tv.textSize = 14f
        val p = (12 * d).toInt()
        tv.setPadding(p, p, p, p)
        tv.elevation = 12 * d
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        lp.setMargins((12 * d).toInt(), (32 * d).toInt(), (72 * d).toInt(), 0)
        tv.setOnClickListener {
            host.removeView(tv)
            onTap()
        }
        host.addView(tv, lp)
        tv.postDelayed({ if (tv.parent != null) host.removeView(tv) }, 20000)
    }
}

private fun ago(text: String?, at: Long): String {
    if (text == null) return "belum pernah"
    return text + " (" + (System.currentTimeMillis() - at) / 60000 + " menit lalu)"
}

fun diagText(a: Activity, prefs: SharedPreferences, filterSummary: String): String {
    val sb = StringBuilder()
    val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    sb.append("RAM kosong: ").append(mi.availMem / 1048576).append(" MB dari ").append(mi.totalMem / 1048576).append(" MB")
    if (mi.lowMemory) sb.append(" (RENDAH)")
    sb.append("\nBatas memori aplikasi: ").append(am.memoryClass).append(" MB")
    sb.append("\nAndroid: ").append(Build.VERSION.SDK_INT).append(", perangkat: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
    sb.append("\nFilter kosmetik: ").append(if (prefs.getBoolean("cosmetic", false)) "ON" else "OFF")
    sb.append("\nDaftar filter: ").append(filterSummary)
    sb.append("\n\nRenderer WebView mati terakhir: ")
        .append(ago(prefs.getString("diag_render", null), prefs.getLong("diag_render_at", 0L)))
    sb.append("\n\nCrash terakhir: ")
        .append(ago(prefs.getString("diag_crash", null), prefs.getLong("diag_crash_at", 0L)))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val list = am.getHistoricalProcessExitReasons(a.packageName, 0, 4)
            sb.append("\n\nPenyebab proses berhenti (menurut sistem):")
            if (list.isEmpty()) sb.append("\n- belum ada")
            for (x in list) {
                sb.append("\n- ").append(exitName(x.reason))
                    .append(", ").append((System.currentTimeMillis() - x.timestamp) / 60000).append(" menit lalu")
                    .append(if (x.importance <= 200) ", saat di layar" else ", di latar belakang")
                    .append(", memori ").append(x.rss / 1024).append(" MB")
            }
        } catch (e: Exception) { }
    }
    return sb.toString()
}

// Android 10+: simpan ke folder Unduhan lewat MediaStore (tanpa izin penyimpanan)
@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private fun saveToDownloads(a: Activity, name: String, text: String): Boolean {
    return try {
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (uri == null) {
            false
        } else {
            a.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }
    } catch (e: Exception) { false }
}

/**
 * Satu klik: info diagnosa + seluruh log digabung menjadi satu file .txt, salinannya disimpan di Unduhan
 * (Android 10+) dan menu kirim dibuka (bisa langsung dikirim ke aplikasi lain atau disimpan).
 */
fun exportReport(a: Activity, prefs: SharedPreferences, filterSummary: String) {
    try {
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "webapp-diagnosa-" + stamp + ".txt"
        val text = "=== INFO DIAGNOSA ===\n" + diagText(a, prefs, filterSummary) + "\n\n=== LOG ===\n" + readLog(a)
        var saved = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saved = saveToDownloads(a, name, text)
        val dir = File(a.cacheDir, "export")
        dir.mkdirs()
        val f = File(dir, name)
        f.writeText(text)
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.putExtra(Intent.EXTRA_SUBJECT, "Diagnosa WebApp")
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, "Kirim diagnosa (.txt)"))
        if (saved) Toast.makeText(a, "Disimpan juga di Unduhan: " + name, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(a, "Gagal mengekspor: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }
}

fun showLogDialog(a: Activity, filterSummary: String) {
    val prefs = a.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val text = readLog(a)
    val shown = if (text.isEmpty()) "(log masih kosong)" else text.takeLast(12000)
    AlertDialog.Builder(a)
        .setTitle("Log aplikasi")
        .setMessage(shown)
        .setPositiveButton("Ekspor .txt") { _, _ -> exportReport(a, prefs, filterSummary) }
        .setNegativeButton("Hapus") { _, _ ->
            clearLog(a)
            Toast.makeText(a, "Log dihapus", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("Tutup", null)
        .show()
}

fun showDiagDialog(a: Activity, prefs: SharedPreferences, filterSummary: String) {
    AlertDialog.Builder(a)
        .setTitle("Info diagnosa")
        .setMessage(diagText(a, prefs, filterSummary))
        .setPositiveButton("Ekspor .txt") { _, _ -> exportReport(a, prefs, filterSummary) }
        .setNegativeButton("Lihat log") { _, _ -> showLogDialog(a, filterSummary) }
        .setNeutralButton("Tutup", null)
        .show()
}
