package com.myweb.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// Logo (favicon) situs untuk daftar Situs dan daftar Page. Disimpan sebagai PNG kecil di filesDir/favicons.
// Sumber: (1) ikon yang diterima WebView saat halaman dibuka (tanpa permintaan tambahan), (2) /favicon.ico dari situs itu
// sendiri di latar belakang. Tidak memakai layanan pihak ketiga, jadi daftar situs Anda tidak dikirim ke siapa pun.
// Tanpa logo: huruf awal pada lingkaran berwarna.
object Favicons {
    private val cache = LruCache<String, Bitmap>(64)
    private val failedAt = HashMap<String, Long>()
    private var fetching = false
    private const val RETRY_MS = 24L * 3600 * 1000
    private val COLORS = arrayOf("#3B82F6", "#22C55E", "#F59E0B", "#EC4899", "#8B5CF6", "#14B8A6", "#EF4444", "#0EA5E9")

    fun hostOf(url: String?): String = (Uri.parse(url ?: "").host ?: "").removePrefix("www.").lowercase()

    private fun fileFor(ctx: Context, host: String): File {
        val dir = File(ctx.filesDir, "favicons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, host.replace(Regex("[^a-z0-9.-]"), "_") + ".png")
    }

    fun get(ctx: Context, host: String): Bitmap? {
        if (host.isEmpty()) return null
        cache.get(host)?.let { return it }
        val f = fileFor(ctx, host)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)?.also { cache.put(host, it) }
        } catch (e: Exception) {
            null
        }
    }

    // Simpan ikon (diperkecil maks 128 px). Ikon yang sudah ada hanya diganti bila yang baru lebih besar.
    fun save(ctx: Context, host: String, bmp: Bitmap) {
        if (host.isEmpty() || bmp.width < 8 || bmp.height < 8) return
        try {
            val old = cache.get(host) ?: get(ctx, host)
            if (old != null && old.width >= bmp.width) return
            val s = minOf(1f, 128f / maxOf(bmp.width, bmp.height))
            val out = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
            fileFor(ctx, host).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cache.put(host, out)
        } catch (e: Exception) { }
    }

    private fun download(host: String): Bitmap? {
        for (h in listOf(host, "www.$host")) {
            var c: HttpURLConnection? = null
            try {
                c = URL("https://$h/favicon.ico").openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = true
                if (c.responseCode != 200) continue
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > 300_000) continue
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (b != null) return b
            } catch (e: Exception) {
            } finally {
                c?.disconnect()
            }
        }
        return null
    }

    // Unduh logo yang belum ada, satu per satu di latar belakang; onUpdate dipanggil (di thread latar) tiap ada yang baru
    fun fetchMissing(ctx: Context, hosts: List<String>, onUpdate: () -> Unit) {
        val app = ctx.applicationContext
        val todo = synchronized(this) {
            if (fetching) return
            val now = System.currentTimeMillis()
            val l = hosts.distinct().filter { it.contains('.') && get(app, it) == null && now - (failedAt[it] ?: 0L) > RETRY_MS }
            if (l.isEmpty()) return
            fetching = true
            l
        }
        thread {
            try {
                for (h in todo.take(12)) {
                    val b = download(h)
                    if (b != null) {
                        save(app, h, b)
                        onUpdate()
                    } else {
                        synchronized(this) { failedAt[h] = System.currentTimeMillis() }
                    }
                }
            } finally {
                synchronized(this) { fetching = false }
            }
        }
    }

    // Lingkaran logo situs; tanpa logo = huruf awal nama
    fun badge(ctx: Context, host: String, name: String, sizePx: Int): View {
        val bmp = get(ctx, host)
        if (bmp != null) {
            return ImageView(ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = sizePx / 5
                setPadding(p, p, p, p)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F1F3F4")) }
            }
        }
        val color = Color.parseColor(COLORS[(host.hashCode() and 0x7fffffff) % COLORS.size])
        return TextView(ctx).apply {
            text = name.trim().take(1).uppercase().ifEmpty { "?" }
            textSize = sizePx / ctx.resources.displayMetrics.density * 0.43f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
    }
}
