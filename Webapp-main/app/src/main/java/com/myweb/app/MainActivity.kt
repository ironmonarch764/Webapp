package com.myweb.app

import android.webkit.WebSettings
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// Berapa kali proses render WebView mati berturut-turut (direset saat sebuah halaman selesai dimuat)
private var renderGoneCount = 0

// Hasil parse daftar filter dipakai bersama antar Activity: recreate() tidak perlu parse ulang
@Volatile private var sharedRules: FilterEngine.Rules = FilterEngine.Builder(DEFAULT_AD_HOSTS, false).build()
@Volatile private var sharedSig = ""

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var currentUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) {
            prefs.edit().putString("url", v).remove("last_page").remove("last_scroll").apply()
            addSiteIfNew(v)
            if (::curPage.isInitialized) {
                // alamat awal page yang sedang dibuka berganti: posisi baca lama bukan miliknya lagi
                curPage.home = v
                curPage.url = ""
                curPage.scroll = 0
                curPage.title = ""
                persistPages()
            }
        }

    private fun crossKey() = "blockCross:" + baseDomain(Uri.parse(currentUrl).host)

    // Pengaturan "cegah pindah domain" untuk page tertentu (kuncinya per domain alamat awal page itu)
    private fun blockCrossFor(pg: Page): Boolean =
        prefs.getBoolean("blockCross:" + pg.configBase, prefs.getBoolean("blockCross", true))
    private var blockCross: Boolean
    get() = prefs.getBoolean(crossKey(), prefs.getBoolean("blockCross", true))
    set(v) = prefs.edit().putBoolean(crossKey(), v).apply()

    // ----- Page: tiap page punya WebView dan keadaan sendiri. webView = WebView milik page yang sedang dibuka. -----
    private val pages = ArrayList<Page>()
    private lateinit var curPage: Page
    private lateinit var pageHost: FrameLayout
    private var quickTools: QuickTools? = null
    private var lastLimitWarnAt = 0L

    // Keadaan per page yang dulu berupa field Activity. Kode lama memakai nama yang sama = page yang sedang dibuka
    // (callback WebView memakai page miliknya sendiri, bukan yang sedang dibuka).
    private var firstLoad: Boolean
        get() = curPage.firstLoad
        set(v) { curPage.firstLoad = v }
    private var restoreY: Int
        get() = curPage.restoreY
        set(v) { curPage.restoreY = v }
    private var restoreUrl: String?
        get() = curPage.restoreUrl
        set(v) { curPage.restoreUrl = v }
    private var restoreAt: Long
        get() = curPage.restoreAt
        set(v) { curPage.restoreAt = v }
    private var failedUrl: String?      // halaman utama yang terakhir gagal dimuat (untuk "Coba lagi")
        get() = curPage.failedUrl
        set(v) { curPage.failedUrl = v }
    private var pickerActive = false
    @Volatile private var pickerToken: String? = null
    private var webViewDead = false
    private var lastSslToast = 0L
    private var lastUpgradeUrl = ""
    private var lastUpgradeAt = 0L
    private val warnedHttp = HashSet<String>()
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var dnsState = ""                 // kosong = DNS terpilih benar-benar dipasang; selain itu alasan tidak aktif

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    inner class Bridge(private val pg: Page) {
        @JavascriptInterface
        fun retry() { runOnUiThread { if (pg === curPage && onErrorPage()) retryFailed() } }

        @JavascriptInterface
        fun systemDns() { runOnUiThread { if (pg === curPage && onErrorPage()) setDns("off") } }

        @JavascriptInterface
        fun changeUrl() { runOnUiThread { if (pg === curPage && onErrorPage()) askUrl(true) } }

        @JavascriptInterface
        fun addRule(token: String, sel: String) {
            runOnUiThread {
                // Hanya skrip pemilih milik aplikasi yang tahu token ini; iframe/pihak ketiga tidak bisa memanggil
                if (!pickerActive || token != pickerToken) return@runOnUiThread
                pickerActive = false
                pickerToken = null
                if (sel.length > 300 || sel.contains("{") || sel.contains("}") || sel.contains("\n")) return@runOnUiThread
                val key = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)
                val cur = prefs.getString(key, "") ?: ""
                prefs.edit().putString(key, if (cur.isEmpty()) sel else cur + "\n" + sel).apply()
                Toast.makeText(this@MainActivity, "Elemen diblokir", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun pickerDone(token: String) {
            runOnUiThread { if (token == pickerToken) { pickerActive = false; pickerToken = null } }
        }
    }
    
   private var popupView: WebView? = null

   // Pasang WebView sebagai jendela popup di atas halaman (dipakai popup biasa dan popup login yang diizinkan)
   private fun attachPopup(popup: WebView) {
    closePopup()
    popup.settings.javaScriptEnabled = true
    popup.settings.userAgentString = webView.settings.userAgentString   // UA sama dengan halaman utama
    popup.settings.domStorageEnabled = true
    popup.settings.allowFileAccess = false
    popup.settings.allowContentAccess = false
    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, !block3pOn())
    popup.webViewClient = object : WebViewClient() {
        @android.annotation.TargetApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            closePopup()
            return true
        }
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean = isPopupAd(req.url.host)
    }
    popup.webChromeClient = object : WebChromeClient() {
        override fun onCloseWindow(window: WebView?) { closePopup() }
    }
    findViewById<android.view.ViewGroup>(android.R.id.content).addView(
        popup,
        FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    popupView = popup
}

   private fun closePopup() {
    val p = popupView ?: return
    popupView = null
    (p.parent as? android.view.ViewGroup)?.removeView(p)
    p.stopLoading()
    p.destroy()
}

    private val autoScroller by lazy { AutoScroller(webView, prefs) }
    private val autoPanel by lazy { AutoScrollPanel(this, autoScroller, prefs) }
    private var fabCtl: FabController? = null
    private fun onErrorPage(): Boolean = webView.url?.startsWith("file:") == true

    private fun newPickerToken(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // Proses render WebView mati (biasanya kehabisan memori). WebView ini tidak bisa dipakai lagi:
    // buat ulang Activity dan lanjutkan dari halaman terakhir yang tersimpan. Tanpa penanganan ini aplikasi ikut mati.
    private fun handleRenderGone(crashed: Boolean?, priority: Int) {
        if (webViewDead) return
        webViewDead = true
        renderGoneCount++
        val safe = renderGoneCount >= 3
        // spanduk merah ditampilkan oleh instance Activity berikutnya (yang ini sedang dibuat ulang)
        val banner = if (safe) "Halaman terus berhenti (memori habis). Dibuka halaman aman. Ketuk untuk melihat log."
            else "Halaman berhenti (memori habis), dimuat ulang. Ketuk untuk melihat log."
        prefs.edit()
            .putString("diag_render", "crash=" + crashed + " prioritas=" + priority + " beruntun=" + renderGoneCount +
                " kosmetik=" + prefs.getBoolean("cosmetic", false))
            .putLong("diag_render_at", System.currentTimeMillis())
            .putString("banner_pending", banner)
            .apply()
        logEvent(this, "RENDERER MATI crash=" + crashed + " prioritas=" + priority + " beruntun=" + renderGoneCount +
            " " + ramSnapshot(this))
        if (safe) {
            // jangan menutup aplikasi: mulai ulang di halaman aman supaya pengguna bisa mengubah pengaturan
            renderGoneCount = 0
            prefs.edit().putBoolean("safe_once", true).apply()
            logEvent(this, "masuk halaman aman setelah 3 kali renderer mati")
            recreate()
            return
        }
        if (renderGoneCount == 2) {
            prefs.edit().remove("last_page").remove("last_scroll").apply()
            curPage.url = ""
            curPage.scroll = 0
        }
        persistPages()   // semua page kembali sebagai hibernasi; hanya page terakhir yang dihidupkan lagi
        recreate()
    }

    private fun note(msg: String) {
        Log.w("AdBlock", msg)
        logEvent(this, msg)
    }

    private fun baseDomain(host: String?): String = PublicSuffix.registrableDomain(host)

    // Pihak pertama = situs yang sedang dibuka ATAU alamat yang disimpan pengguna. siteBase baru terisi setelah halaman
    // pertama selesai, jadi tanpa configBase situs utama sendiri bisa terblokir saat pemuatan pertama.
    private fun isFirstParty(host: String, pg: Page = curPage): Boolean {
        val b = baseDomain(host)
        return b.isNotEmpty() && (b == pg.siteBase || b == pg.configBase)
    }

    private fun adBlockOn() = prefs.getBoolean("adblock", true)
    private fun trackerBlockOn() = prefs.getBoolean("trackerblock", true)
    private fun popupBlockOn() = prefs.getBoolean("popupblock", true)
    private fun gpcOn() = prefs.getBoolean("gpc", true)
    private fun ampOn() = prefs.getBoolean("amp_unwrap", true)
    private fun urlCleanOn() = prefs.getBoolean("url_clean", false)
    private fun fpOn() = prefs.getBoolean("fingerprint", false)
    private fun block3pOn() = prefs.getBoolean("block3p", false)
    private fun httpsForceOn() = prefs.getBoolean("https_force", false)

    // Hitungan permintaan yang diblokir pada halaman yang sedang dibuka (diatur ulang tiap navigasi baru)
    private val adBlocked = java.util.concurrent.atomic.AtomicInteger()
    private val trackerBlocked = java.util.concurrent.atomic.AtomicInteger()

    // Penulisan ulang alamat navigasi sesuai pengaturan: AMP -> situs asli, buang parameter pelacak, paksa HTTPS.
    // Pengaman putaran: alamat yang sama tidak ditulis ulang dua kali dalam 5 detik (mis. server mengembalikan ke http).
    private var lastRwFrom = ""
    private var lastRwAt = 0L
    private fun rewriteUrl(url: String): String? {
        var cur = url
        var changed = false
        if (ampOn()) UrlRules.unwrapAmp(cur)?.let { cur = it; changed = true }
        if (urlCleanOn()) UrlRules.cleanTracking(cur)?.let { cur = it; changed = true }
        if (httpsForceOn()) UrlRules.upgradeHttps(cur)?.let { cur = it; changed = true }
        if (!changed) return null
        val now = System.currentTimeMillis()
        if (url == lastRwFrom && now - lastRwAt < 5000) return null
        lastRwFrom = url
        lastRwAt = now
        return cur
    }

    // Skrip yang harus berjalan sebelum skrip halaman: sinyal GPC dan (dasar) perlindungan sidik jari.
    private fun docStartJs(): String {
        val sb = StringBuilder()
        if (gpcOn()) sb.append("try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true})}catch(e){}")
        if (fpOn()) sb.append("(function(){var d=function(k,v){try{Object.defineProperty(Navigator.prototype,k,{get:function(){return v},configurable:true})}catch(e){}};d('hardwareConcurrency',4);d('deviceMemory',4);d('languages',Object.freeze(['en-US','en']));d('language','en-US');})();")
        return sb.toString()
    }

    private fun applyDocStart(pg: Page) {
        val wv = pg.wv ?: return
        try { pg.docStart?.remove() } catch (e: Exception) { }
        pg.docStart = null
        val js = docStartJs()
        if (js.isEmpty()) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                pg.docStart = WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
            } catch (e: Exception) {
                logEvent(this, "skrip awal dokumen gagal: " + e.javaClass.simpleName)
            }
        }
    }

    // Pengaturan GPC/sidik jari berubah: berlaku di semua page yang aktif (page hibernasi memasangnya saat dihidupkan)
    private fun applyDocStartAll() {
        pages.forEach { if (it.wv != null) applyDocStart(it) }
    }

    // Saklar utama pemblokir iklan: mati = tidak ada permintaan/jendela yang diblokir dan CSS penyembunyi iklan tidak dipasang
    // (elemen yang disembunyikan pengguna sendiri tetap berlaku)
    private fun setAdBlock(on: Boolean) {
        prefs.edit().putBoolean("adblock", on).apply()
        Toast.makeText(this, if (on) "Pemblokir iklan aktif" else "Pemblokir iklan dimatikan", Toast.LENGTH_SHORT).show()
        logEvent(this, "pemblokir iklan " + (if (on) "ON" else "OFF"))
        if (!onErrorPage()) webView.reload()
        refreshSettingsSheet()
    }

    private fun isAd(host: String?, pg: Page = curPage): Boolean {
        if (!adBlockOn()) return false
        if (host.isNullOrEmpty() || isFirstParty(host, pg)) return false
        return sharedRules.isBlocked(host)
    }

    // Untuk jendela popup dan pengalihan otomatis: aturan $popup ikut berlaku
    private fun isPopupAd(host: String?, pg: Page = curPage): Boolean {
        if (!adBlockOn()) return false
        if (host.isNullOrEmpty() || isFirstParty(host, pg)) return false
        return sharedRules.isBlocked(host) || sharedRules.isPopupBlocked(host)
    }

    private fun parseLists() {
        val cosmetic = prefs.getBoolean("cosmetic", false)
        val files = LISTS.indices.map { File(filesDir, "list$it.txt") }
        val sig = files.joinToString("|") { if (it.exists()) it.length().toString() + ":" + it.lastModified() else "-" } + "|c=" + cosmetic
        if (sig == sharedSig) return
        val b = FilterEngine.Builder(DEFAULT_AD_HOSTS, cosmetic)
        for (i in LISTS.indices) {
            val f = files[i]
            if (!f.exists()) continue
            val kind = if (i <= 1) FilterEngine.KIND_DOMAIN else FilterEngine.KIND_ABP
            try {
                FilterEngine.addFile(b, f, kind)
            } catch (e: Exception) {
                note("daftar #" + i + " tidak terbaca penuh: " + e.javaClass.simpleName)
            }
        }
        sharedRules = b.build()
        sharedSig = sig
        note("filter dimuat: " + sharedRules.summary())
    }

    // Unduh ke file sementara, validasi isinya, baru ganti file lama (rename). Gagal/tidak valid: file lama tetap dipakai.
    private fun downloadValidated(url: String, dest: File, valid: (File) -> Boolean): Boolean {
        val tmp = File(filesDir, dest.name + ".tmp")
        var c: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) {
                note("unduh gagal: HTTP " + conn.responseCode)
                return false
            }
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 30_000_000L) throw java.io.IOException("terlalu besar")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!valid(tmp)) {
                note("isi unduhan bukan daftar yang valid; salinan lama dipertahankan (" + dest.name + ")")
                return false
            }
            if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
            note("diperbarui: " + dest.name + " " + dest.length() / 1024 + " KB")
            return true
        } catch (e: Exception) {
            note("unduh gagal: " + e.javaClass.simpleName)
            return false
        } finally {
            tmp.delete()
            c?.disconnect()
        }
    }

    private fun refreshLists(force: Boolean) {
        thread {
            parseLists()
            var downloaded = false
            for (i in LISTS.indices) {
                val f = File(filesDir, "list$i.txt")
                val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 3L * 24 * 3600 * 1000
                if (!force && !stale) continue
                val kind = if (i <= 1) FilterEngine.KIND_DOMAIN else FilterEngine.KIND_ABP
                // unduhan yang bukan daftar filter (halaman login Wi-Fi, error HTML) tidak boleh menimpa daftar yang bagus
                val ok = downloadValidated(LISTS[i], f) { FilterEngine.looksLikeList(it, kind) }
                if (ok) downloaded = true
            }
            // Public Suffix List jarang berubah; cukup tiap 14 hari dan baru dipakai pada peluncuran berikutnya
            val psl = File(filesDir, "psl.dat")
            if (force || !psl.exists() || System.currentTimeMillis() - psl.lastModified() > 14L * 24 * 3600 * 1000) {
                downloadValidated(PSL_URL, psl) { PublicSuffix.isValidFile(it) }
            }
            if (downloaded) parseLists()
            if (force) runOnUiThread {
                Toast.makeText(this, "Daftar iklan: " + sharedRules.blockCount() + " domain", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun userCss(): String {
        val key = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)
        val s = prefs.getString(key, "") ?: ""
        val rules = s.split("\n").filter { it.isNotBlank() }.joinToString("\n") { it + "{display:none!important}" }
        return if (rules.isEmpty()) "" else rules + "\nhtml,body{overflow:auto!important;position:static!important}"
    }

    private fun injectCss(v: WebView, logStats: Boolean = false) {
        // filter kosmetik daftar (opsional) hanya untuk host halaman ini; aturan buatan pengguna dan BASE_CSS selalu berlaku
        val host = Uri.parse(v.url ?: "").host
        val cosmeticOn = prefs.getBoolean("cosmetic", false) && adBlockOn()
        val listCss = if (host != null && cosmeticOn) sharedRules.cssFor(host) else ""
        // jumlah aturan yang disuntikkan ke halaman ini (tanpa host) supaya beban kosmetik per halaman bisa dinilai dari log
        if (logStats && cosmeticOn) logEvent(this, "kosmetik halaman: " + listCss.count { it == '\n' } + " aturan, " + listCss.length / 1024 + " KB")
        val css = listCss + "\n" + userCss() + "\n" + (if (adBlockOn()) BASE_CSS else "")
        val js = "(function(){if(document.getElementById('__adb'))return;var s=document.createElement('style');s.id='__adb';s.textContent=" +
            JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})();"
        v.evaluateJavascript(js, null)
    }
    
    override fun onPause() {
    super.onPause()
    LoginSupport.save()
    autoScroller.stop()
    saveReadingPosition()
    curPage.lastActive = System.currentTimeMillis()
    persistPages()
    // Hentikan pemrosesan tambahan WebView (video/audio/timer halaman) saat aplikasi tidak di layar
    if (!webViewDead) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDead) webView.onResume()
        // periksa page yang lama tidak dibuka (hibernasi / tutup otomatis), lalu lanjut berkala
        idleHandler.removeCallbacks(idleTick)
        idleHandler.post(idleTick)
    }

    // Simpan halaman + posisi scroll terakhir supaya aplikasi dibuka lagi di tempat yang sama.
    // Dipanggil saat onPause dan, selama membaca, paling sering tiap 2 detik (scheduleSave) supaya posisi tetap ada
    // walau proses dimatikan sistem / renderer mati tanpa sempat onPause.
    private fun saveReadingPosition() {
        if (webViewDead) return
        // pemulihan menunggu halaman selesai (maks 20 detik): posisi 0 sementara jangan menimpa posisi tersimpan
        if (restoreY > 0 && System.currentTimeMillis() - restoreAt < 20000) return
        val pg = curPage
        val wv = webView
        val u = wv.url ?: return
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        // halaman berganti: alamat baru disimpan LANGSUNG dengan posisi 0 (posisi lama bukan milik halaman ini)
        if (prefs.getString("last_page", null) != u) {
            prefs.edit().putString("last_page", u).putInt("last_scroll", 0).apply()
            pg.url = u
            pg.scroll = 0
            persistPages()
        }
        wv.evaluateJavascript(SAVE_POS_JS) { r ->
            // jawaban datang belakangan: abaikan bila halaman/page sudah berganti / WebView sudah mati
            if (webViewDead || pg !== curPage || wv.url != u) return@evaluateJavascript
            val y = r?.trim('"')?.toIntOrNull() ?: return@evaluateJavascript
            // -1 = halaman belum selesai atau pemulihan posisi sedang berjalan: posisi sementara tidak boleh menimpa
            if (y < 0) return@evaluateJavascript
            prefs.edit().putString("last_page", u).putInt("last_scroll", ReadingPos.clampY(y)).apply()
            pg.url = u
            pg.scroll = ReadingPos.clampY(y)
            persistPages()
        }
    }

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var savePending = false
    private val saveRunnable = Runnable {
        savePending = false
        saveReadingPosition()
    }

    // Dipanggil tiap kali WebView bergeser: simpan paling sering sekali per 2 detik
    private fun scheduleSave() {
        if (savePending || webViewDead) return
        savePending = true
        saveHandler.postDelayed(saveRunnable, 2000)
    }

    private fun preloadOn() = prefs.getBoolean("preload", true)

    private fun preloadAhead() = prefs.getInt("preload_ahead", 0).coerceIn(0, 12)      // 0 = otomatis
    private fun preloadParallel() = prefs.getInt("preload_parallel", 2).coerceIn(1, 4)

    // Bila skrip sudah berjalan di halaman, injeksi ulang hanya memperbarui pengaturannya (window.__preCfg)
    private fun injectPreload(v: WebView) {
        if (!preloadOn()) return
        val js = PRELOAD_JS.replace("__AHEAD__", preloadAhead().toString()).replace("__MAXI__", preloadParallel().toString())
        v.evaluateJavascript("window.__preOff=0;" + js, null)
    }

    // Pemulihan dijalankan DI DALAM dokumen (RESTORE_JS): menunggu tinggi dokumen cukup, terbatas 12 detik, berhenti bila
    // pengguna menyentuh/scroll, dan otomatis mati bila halaman berganti (timer milik dokumen, bukan milik aplikasi).
    private fun restoreScroll(v: WebView, y: Int) {
        v.evaluateJavascript(RESTORE_JS.replace("__Y__", y.toString()).replace("__BUDGET__", "12000"), null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder(applicationContext)
        logEvent(this, "mulai (android " + Build.VERSION.SDK_INT + ", " + (if (savedInstanceState != null) "dibuat ulang" else "baru") +
            ", kosmetik " + (if (prefs.getBoolean("cosmetic", false)) "ON" else "OFF") + ") " + ramSnapshot(this))
        // Public Suffix List hasil unduhan (bila ada) dimuat sebelum ada keputusan "situs yang sama"
        PublicSuffix.loadFromFile(File(filesDir, "psl.dat"))
        setContentView(R.layout.activity_main)
        goFullscreen()

        window.decorView.post {
            val prevExit = checkPreviousExit(this, prefs)
            val pending = prefs.getString("banner_pending", null)
            if (pending != null) prefs.edit().remove("banner_pending").apply()
            val msg = if (prevExit != null) "Aplikasi sebelumnya berhenti: " + prevExit + ". Ketuk untuk melihat log." else pending
            if (msg != null) showRedBanner(this, msg) { showLogDialog(this, sharedRules.summary()) }
        }

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, ime.bottom)
            fabCtl?.onSystemBars(insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            insets
     }

        pageHost = findViewById(R.id.pageHost)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        // Daftar page dimuat dari penyimpanan (semua hibernasi); hanya page terakhir yang dibuka yang dibuatkan WebView
        restorePages()
        webView = createWebView(curPage)
        pageHost.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Tombol setengah lonjong di tepi kanan: Menu, Penyesuaian warna, Gulir, Page (bila fitur Page menyala)
        val sideTools = findViewById<LinearLayout>(R.id.menuBtn)
        quickTools = QuickTools(sideTools, { showMenu() }, { showColorFilterSheet(this, { webView }, prefs) }, { showScrollPopup() }, { showPages() })
        refreshPageButton()
        fabCtl = FabController(webView, sideTools, { pickerActive }, { prefs.getBoolean("edge_on", true) }, { autoScroller.stop() }, { scheduleSave() }, { edgeSpeed() }, { edgeFraction() })

        // Tarik layar ke bawah (saat di paling atas halaman) untuk menyegarkan, seperti di peramban
        val dpi = resources.displayMetrics.density
        swipe.setColorSchemeColors(0xFF5B8CFF.toInt())
        swipe.setProgressBackgroundColorSchemeColor(0xFF26282E.toInt())
        swipe.setProgressViewOffset(false, (16 * dpi).toInt(), (80 * dpi).toInt())
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || pickerActive || autoPanel.modeOn }
        swipe.setOnRefreshListener {
            if (onErrorPage()) retryFailed() else webView.reload()
            swipe.postDelayed({ swipe.isRefreshing = false }, 15000)   // pengaman: spinner tidak boleh berputar selamanya
        }
        swipe.isEnabled = prefs.getBoolean("pull_refresh", true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (FloatingPanel.dismissCurrent()) return   // panel melayang (Situs / Page / Warna) ditutup dulu
                if (popupView != null) closePopup()
                else if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        refreshLists(false)

        applyDns {
            if (prefs.getBoolean("safe_once", false)) {
                prefs.edit().remove("safe_once").apply()
                webView.loadUrl("file:///android_asset/error.html")
                return@applyDns
            }
            var restored = false
            if (savedInstanceState != null && savedInstanceState.getLong("pageId", -1L) == curPage.id) {
                firstLoad = true
                restored = webView.restoreState(savedInstanceState) != null
            }
            if (!restored) {
                if (curPage.home.isBlank() && curPage.url.isBlank()) askUrl(cancelable = false) else loadPageContent(curPage)
            }
        }
        idleHandler.postDelayed(idleTick, 30_000L)
    }

    // Pasang pengaturan, klien halaman, dan pendengar ke WebView sebuah page (dipakai tiap page dibuat/dihidupkan)
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, pg: Page) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        
        }
        applyColorFilter(wv, prefs) 
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !block3pOn())
        applyDocStart(pg)
        wv.addJavascriptInterface(Bridge(pg), "Android")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val scheme = u.scheme ?: return false
                if (scheme != "http" && scheme != "https") {
                    // file: hanya boleh untuk halaman error milik aplikasi; content: tidak pernah diteruskan
                    if (scheme == "file") return !u.toString().startsWith("file:///android_asset/")
                    if (scheme == "content") return true
                    if (req.hasGesture()) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                    }
                    return true
                }
                if (!req.isForMainFrame) return false
                if (isAd(u.host, pg) || (!req.hasGesture() && isPopupAd(u.host, pg))) return true
                val rewritten = rewriteUrl(u.toString())
                if (rewritten != null) {
                    v.loadUrl(rewritten)
                    return true
                }
                // Utamakan HTTPS: situs yang disimpan memakai https tidak boleh turun ke http (dengan pengaman agar tidak berputar)
                if (scheme == "http" && u.port == -1 && pg.home.startsWith("https://") &&
                    baseDomain(u.host) == pg.configBase) {
                    val now = System.currentTimeMillis()
                    if (!(u.toString() == lastUpgradeUrl && now - lastUpgradeAt < 5000)) {
                        lastUpgradeUrl = u.toString()
                        lastUpgradeAt = now
                        v.loadUrl(u.buildUpon().scheme("https").build().toString())
                        return true
                    }
                }
                if (!pg.firstLoad && pg.siteBase.isNotEmpty() && blockCrossFor(pg) && baseDomain(u.host) != pg.siteBase && !isLoginProvider(u.host)) {
                    Toast.makeText(this@MainActivity, "Pindah domain diblokir: " + u.host, Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && !request.isForMainFrame && trackerBlockOn()) {
                    val th = request.url.host
                    if (!th.isNullOrEmpty() && !isFirstParty(th, pg) && Trackers.isTracker(th)) {
                        trackerBlocked.incrementAndGet()
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                if (request != null && isAd(request.url.host, pg)) {
                    adBlocked.incrementAndGet()
                    val p = request.url.path ?: ""
                    if (p.endsWith(".js")) {
                        val js = "window.adsbygoogle=window.adsbygoogle||[];window.adsbygoogle.loaded=true;window.adsbygoogle.push=function(){};"
                        return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(js.toByteArray()))
                    }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && !url.startsWith("file:")) {
                    if (pg === curPage) {
                        adBlocked.set(0)
                        trackerBlocked.set(0)
                    }
                    // WebView lama tanpa dukungan skrip awal dokumen: jalankan secepat mungkin (lebih lambat dari skrip halaman)
                    if (view != null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        val js = docStartJs()
                        if (js.isNotEmpty()) view.evaluateJavascript(js, null)
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (view != null && url != null && !url.startsWith("file:")) injectCss(view)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                swipe.isRefreshing = false
                if (url == null || url.startsWith("file:")) return
                renderGoneCount = 0
                pg.failedUrl = null
                // alamat dan judul page (untuk daftar Page dan lanjut membaca setelah hibernasi)
                if (!ReadingPos.sameDocument(pg.url, url)) pg.scroll = 0
                pg.url = url
                val pt = v.title
                if (!pt.isNullOrBlank()) pg.title = pt.trim().take(80)
                persistPages()
                refreshPagesSheet()
                logEvent(this@MainActivity, "halaman selesai [page aktif " + pages.count { it.isLive } + "/" + pages.size + "] " + ramSnapshot(this@MainActivity))
                if (url.startsWith("http://")) {
                    val hh = Uri.parse(url).host ?: ""
                    if (hh.isNotEmpty() && warnedHttp.add(hh))
                        Toast.makeText(this@MainActivity, "Situs ini memakai HTTP (tidak terenkripsi)", Toast.LENGTH_LONG).show()
                }
                if (pg.firstLoad) {
                    val h = Uri.parse(url).host
                    if (!h.isNullOrEmpty()) {
                        pg.siteBase = baseDomain(h)
                        pg.firstLoad = false
                    }
                }
                injectCss(v, true)
                injectPreload(v)
                // efek gambar (haluskan/ketajaman) tersimpan di pengaturan tapi hilang tiap pindah halaman
                if (prefs.getInt("fx_sm", 0) > 0 || prefs.getInt("fx_sh", 0) > 0) applyImageFx(v, prefs)
                if (pg.restoreY > 0) {
                    if (System.currentTimeMillis() - pg.restoreAt > 20000) {
                        pg.restoreY = 0                                // kedaluwarsa: halaman yang diharapkan tidak kunjung selesai
                    } else if (ReadingPos.sameDocument(url, pg.restoreUrl)) {
                        val y = pg.restoreY                            // hanya untuk halaman yang posisinya disimpan
                        pg.restoreY = 0
                        restoreScroll(v, y)
                    }                                                  // alamat lain (mis. halaman perantara): tunggu yang cocok
                }
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                // jangan memuat ulang halaman error bila halaman error itu sendiri yang gagal (cegah perulangan)
                if (req.isForMainFrame && !req.url.toString().startsWith("file:")) {
                    logEvent(this@MainActivity, "gagal memuat halaman: kode " + err.errorCode + " " + err.description)
                    swipe.isRefreshing = false
                    val u = req.url.toString()
                    if (u.startsWith("http://") || u.startsWith("https://")) pg.failedUrl = u
                    // kode galat + penanda DNS kustom dikirim ke halaman error (hanya angka/flag, bukan alamat)
                    v.loadUrl("file:///android_asset/error.html?c=" + err.errorCode + (if (dnsUrl() != null) "&d=1" else ""))
                }
            }

            // Kesalahan sertifikat TLS: selalu dibatalkan (tidak pernah proceed), pengguna diberi tahu
            override fun onReceivedSslError(v: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                val now = System.currentTimeMillis()
                if (now - lastSslToast > 3000) {
                    lastSslToast = now
                    logEvent(this@MainActivity, "sertifikat TLS ditolak (kode " + (error?.primaryError ?: -1) + ")")
                    Toast.makeText(this@MainActivity, "Sertifikat situs tidak valid, koneksi dibatalkan", Toast.LENGTH_LONG).show()
                }
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                handleRenderGone(detail?.didCrash(), detail?.rendererPriorityAtExit() ?: -1)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            // logo situs untuk daftar Situs/Page (tanpa permintaan tambahan: ikon sudah diterima WebView)
            override fun onReceivedIcon(v: WebView?, icon: android.graphics.Bitmap?) {
                val u = v?.url
                if (icon != null && isHttp(u)) Favicons.save(applicationContext, Favicons.hostOf(u), icon)
            }

            override fun onReceivedTitle(v: WebView?, title: String?) {
                if (!title.isNullOrBlank()) pg.title = title.trim().take(80)
            }

            override fun onProgressChanged(v: WebView, p: Int) {
                if (pg !== curPage) return
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                progress.progress = p
            }

            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
            }

override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
    if (!isUserGesture || resultMsg == null) return false
    val transport = resultMsg.obj as WebView.WebViewTransport
    if (popupBlockOn() && blockCrossFor(pg)) {
        val temp = WebView(this@MainActivity)
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        var dead = false
        // View.post() pada view yang tidak pernah dipasang ke layar tidak pernah berjalan, jadi pakai Handler
        val kill = Runnable {
            if (!dead) {
                dead = true
                temp.stopLoading()
                temp.destroy()
            }
        }
        temp.webViewClient = object : WebViewClient() {
            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                ui.post(kill)
                return true
            }

            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (isLoginProvider(u.host) && !dead) {
                    // Masuk dengan Google/Apple/Microsoft: jadikan jendela ini popup sungguhan (hubungan window.opener utuh)
                    dead = true
                    ui.removeCallbacks(kill)
                    attachPopup(temp)
                    return false
                }
                if (baseDomain(u.host) == pg.siteBase && !isPopupAd(u.host, pg)) {
                    wv.loadUrl(u.toString())
                } else {
                    Toast.makeText(this@MainActivity, "Popup diblokir", Toast.LENGTH_SHORT).show()
                }
                v.stopLoading()
                ui.post(kill)
                return true
            }
        }
        ui.postDelayed(kill, 15000)
        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }
    val popup = WebView(this@MainActivity)
    attachPopup(popup)
    transport.webView = popup
    resultMsg.sendToTarget()
    return true
}
        
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                result?.cancel()
                return true
            }
        }

        wv.setDownloadListener { url, _, _, _, _ ->
            val ds = Uri.parse(url).scheme
            if (ds != "http" && ds != "https") {
                Toast.makeText(this, "Jenis unduhan ini tidak didukung", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak ada aplikasi untuk membuka unduhan ini", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Buat WebView untuk sebuah page (belum dimuat dan belum dipasang ke layar)
    private fun createWebView(pg: Page): WebView {
        val wv = WebView(this)
        pg.wv = wv
        pg.hibernatedAt = 0L
        LoginSupport.setup(wv)
        wv.setBackgroundColor(Color.BLACK)
        setupWebView(wv, pg)
        return wv
    }

    // ---------- Page (tab) ----------
    private fun pagesOn() = prefs.getBoolean("pages_on", true)

    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleTick = object : Runnable {
        override fun run() {
            idleCheck()
            idleHandler.postDelayed(this, 30_000L)
        }
    }

    private fun persistPages() {
        if (!::curPage.isInitialized) return
        PageStore.save(prefs, pages, curPage.id)
    }

    // Muat daftar page (semua hibernasi). Tanpa data tersimpan: page pertama dibuat dari alamat dan posisi baca lama.
    private fun restorePages() {
        val (saved, activeId) = PageStore.load(prefs)
        pages.clear()
        pages.addAll(saved)
        if (pages.isEmpty()) {
            val p = Page(1L)
            p.home = currentUrl
            val last = prefs.getString("last_page", null)
            if (!last.isNullOrBlank() && baseDomain(Uri.parse(last).host) == baseDomain(Uri.parse(currentUrl).host)) {
                p.url = last
                p.scroll = ReadingPos.clampY(prefs.getInt("last_scroll", 0))
            }
            pages.add(p)
            curPage = p
        } else {
            curPage = pages.firstOrNull { it.id == activeId } ?: pages[0]
        }
        curPage.hibernatedAt = 0L
        curPage.lastActive = System.currentTimeMillis()
        syncPrefsToPage(curPage)
    }

    // Pengaturan lama (url, last_page, last_scroll) selalu mengikuti page yang sedang dibuka
    private fun syncPrefsToPage(p: Page) {
        val e = prefs.edit().putString("url", p.home)
        if (p.url.isNotBlank()) e.putString("last_page", p.url).putInt("last_scroll", p.scroll)
        else e.remove("last_page").remove("last_scroll")
        e.apply()
    }

    private fun isHttp(u: String?) = u != null && (u.startsWith("http://") || u.startsWith("https://"))

    // Isi page: riwayat tersimpan (bila baru hibernasi) -> halaman terakhir + posisi baca -> alamat awal
    private fun loadPageContent(p: Page) {
        val wv = p.wv ?: return
        val st = p.state
        if (st != null) {
            p.state = null
            p.firstLoad = true
            if (wv.restoreState(st) != null) return
        }
        val sameSite = p.home.isBlank() || baseDomain(Uri.parse(p.url).host) == p.configBase
        if (isHttp(p.url) && sameSite) {
            p.restoreY = ReadingPos.clampY(p.scroll)
            p.restoreUrl = p.url
            p.restoreAt = System.currentTimeMillis()
            p.firstLoad = true
            wv.loadUrl(p.url)
        } else if (p.home.isNotBlank()) {
            p.restoreY = 0
            p.firstLoad = true
            wv.loadUrl(p.home)
        }
    }

    private fun destroyPageView(p: Page) {
        val wv = p.wv ?: return
        p.wv = null
        try { p.docStart?.remove() } catch (e: Exception) { }
        p.docStart = null
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        } catch (e: Exception) { }
    }

    // Catat alamat, judul, dan posisi baca sebuah page yang masih hidup
    private fun capturePage(p: Page) {
        val wv = p.wv ?: return
        try {
            val u = wv.url
            if (isHttp(u)) {
                if (!ReadingPos.sameDocument(p.url, u)) p.scroll = 0
                p.url = u!!
            }
            val t = wv.title
            if (!t.isNullOrBlank()) p.title = t.trim().take(80)
            if (p.restoreY > 0 && System.currentTimeMillis() - p.restoreAt < 20000) return
            wv.evaluateJavascript(SAVE_POS_JS) { r ->
                val y = r?.trim('"')?.toIntOrNull() ?: return@evaluateJavascript
                if (y < 0 || p.wv !== wv) return@evaluateJavascript
                p.scroll = ReadingPos.clampY(y)
                persistPages()
            }
        } catch (e: Exception) { }
    }

    // Pindah ke page lain (dihidupkan dulu bila hibernasi)
    private fun activatePage(p: Page) {
        if (webViewDead) return
        if (p === curPage && p.wv != null) return
        val prev = curPage
        closePopup()
        autoScroller.stop()
        pickerActive = false
        pickerToken = null
        capturePage(prev)
        val now = System.currentTimeMillis()
        prev.lastActive = now
        val wasLive = p.wv != null
        val wv = p.wv ?: createWebView(p)
        pageHost.removeAllViews()
        pageHost.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        curPage = p
        webView = wv
        try { prev.wv?.onPause() } catch (e: Exception) { }
        try { wv.onResume() } catch (e: Exception) { }
        p.lastActive = now
        p.hibernatedAt = 0L
        autoScroller.webView = wv
        fabCtl?.rebind(wv)
        applyColorFilter(wv, prefs)
        syncPrefsToPage(p)
        progress.visibility = View.GONE
        swipe.isRefreshing = false
        adBlocked.set(0)
        trackerBlocked.set(0)
        if (!wasLive) loadPageContent(p)
        else if (prefs.getInt("fx_sm", 0) > 0 || prefs.getInt("fx_sh", 0) > 0) applyImageFx(wv, prefs)
        enforceLimit()
        refreshPageButton()
        persistPages()
        refreshPagesSheet()
        wv.requestFocus()
    }

    // Hibernasi: WebView dibuang (RAM bebas); alamat, judul, posisi baca, dan riwayat tetap disimpan
    private fun hibernatePage(p: Page, reason: String) {
        if (p === curPage) return
        val wv = p.wv ?: return
        try {
            val u = wv.url
            if (isHttp(u)) {
                if (!ReadingPos.sameDocument(p.url, u)) p.scroll = 0
                p.url = u!!
            }
            val t = wv.title
            if (!t.isNullOrBlank()) p.title = t.trim().take(80)
            val b = Bundle()
            p.state = if (u != null && !u.startsWith("file:") && wv.saveState(b) != null && !b.isEmpty) b else null
        } catch (e: Exception) {
            p.state = null
        }
        destroyPageView(p)
        p.hibernatedAt = System.currentTimeMillis()
        logEvent(this, "page hibernasi (" + reason + "): aktif " + pages.count { it.isLive } + "/" + pages.size)
        persistPages()
        refreshPageButton()
        refreshPagesSheet()
    }

    private fun closePage(p: Page) {
        if (pages.size <= 1) {
            Toast.makeText(this, "Minimal harus ada satu page", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = pages.indexOf(p)
        if (idx < 0) return
        val wasCur = p === curPage
        pages.remove(p)
        destroyPageView(p)
        if (wasCur) activatePage(pages[PagePolicy.nextAfterClose(idx, pages.size)])
        persistPages()
        refreshPageButton()
        refreshPagesSheet()
    }

    private fun movePage(p: Page, dir: Int) {
        val i = pages.indexOf(p)
        val j = PagePolicy.moveTarget(i, dir, pages.size)
        if (j < 0) return
        java.util.Collections.swap(pages, i, j)
        persistPages()
    }

    private fun openInNewPage(url: String) {
        if (pages.size >= PagePolicy.MAX_TOTAL) {
            Toast.makeText(this, "Jumlah page sudah maksimal (" + PagePolicy.MAX_TOTAL + "). Tutup beberapa dulu.", Toast.LENGTH_LONG).show()
            return
        }
        val p = Page((pages.maxOfOrNull { it.id } ?: 0L) + 1L)
        p.home = url
        addSiteIfNew(url)
        val at = pages.indexOf(curPage).let { if (it < 0) pages.size else it + 1 }
        pages.add(at, p)
        activatePage(p)
    }

    private fun askNewPage() {
        showFormPanel(this, "Page baru", listOf(FormField("contoh: komiku.org", "", true)), "Buka", true) { v, fail, close ->
            val url = normalize(v[0])
            if (url == null) fail("Alamat tidak valid") else { close(); openInNewPage(url) }
        }
    }

    private fun showPages() {
        autoScroller.stop()
        showPagesSheet(
            this,
            PagesUi(
                pages = { pages.toList() },
                currentId = { curPage.id },
                liveCount = { pages.count { it.isLive } },
                limit = { PageLimits.limit(this, prefs) },
                recommended = PageLimits.recommended(this),
                onSwitch = { p -> activatePage(p) },
                onClose = { p -> closePage(p) },
                onMove = { p, dir -> movePage(p, dir) },
                onHibernate = { p -> hibernatePage(p, "manual") },
                onNewPage = { askNewPage() },
                onFromSites = { showSites() },
                grid = { prefs.getString("pages_view", "list") == "grid" },
                setGrid = { g -> prefs.edit().putString("pages_view", if (g) "grid" else "list").apply() }
            )
        )
    }

    private fun refreshPageButton() {
        quickTools?.setPageVisible(pagesOn())
        quickTools?.setPageCount(pages.size)
    }

    // Jumlah page aktif tidak boleh melebihi batas: yang paling lama tidak dilihat dihibernasi. Peringatan bila di atas saran HP.
    private fun enforceLimit() {
        if (!pagesOn()) return
        val lim = PageLimits.limit(this, prefs)
        val la = LongArray(pages.size) { pages[it].lastActive }
        val live = BooleanArray(pages.size) { pages[it].isLive }
        for (i in PagePolicy.overLimit(la, live, pages.indexOf(curPage), lim)) hibernatePage(pages[i], "melebihi batas " + lim)
        val rec = PageLimits.recommended(this)
        val n = pages.count { it.isLive }
        val now = System.currentTimeMillis()
        if (PagePolicy.exceedsSafe(n, rec) && now - lastLimitWarnAt > 20000) {
            lastLimitWarnAt = now
            Toast.makeText(this, "Peringatan: " + n + " page aktif melebihi batas aman HP ini (" + rec +
                "). HP bisa panas, aplikasi berat, bahkan crash.", Toast.LENGTH_LONG).show()
        }
    }

    // Berkala (30 dtk) dan saat aplikasi dibuka: hibernasi page yang lama tidak dibuka, tutup yang lama hibernasi
    private fun idleCheck() {
        if (!pagesOn() || webViewDead || !::curPage.isInitialized) return
        val now = System.currentTimeMillis()
        val cur = pages.indexOf(curPage)
        val la = LongArray(pages.size) { pages[it].lastActive }
        val live = BooleanArray(pages.size) { pages[it].isLive }
        for (i in PagePolicy.idleToHibernate(la, live, cur, now, PageLimits.hibernateMs(this, prefs))) hibernatePage(pages[i], "lama tidak dibuka")
        if (prefs.getBoolean("pages_autoclose", true)) {
            val hz = LongArray(pages.size) { pages[it].hibernatedAt }
            val lv = BooleanArray(pages.size) { pages[it].isLive }
            val closing = PagePolicy.expiredToClose(hz, lv, cur, now, PageLimits.closeMs(prefs)).map { pages[it] }
            if (closing.isNotEmpty()) {
                pages.removeAll(closing.toSet())
                logEvent(this, "page ditutup otomatis: " + closing.size)
                persistPages()
                refreshPageButton()
                refreshPagesSheet()
            }
        }
    }

    private fun setPagesOn(on: Boolean) {
        prefs.edit().putBoolean("pages_on", on).apply()
        if (!on) {
            // tanpa fitur Page hanya satu halaman yang hidup; page lain tetap tersimpan (hibernasi) bila fitur dinyalakan lagi
            pages.filter { it !== curPage && it.isLive }.forEach { hibernatePage(it, "fitur Page dimatikan") }
            dismissPagesSheet()
        }
        refreshPageButton()
    }

    private fun setPageLimit(v: Int) {
        val rec = PageLimits.recommended(this)
        val commit = {
            prefs.edit().putInt("pages_limit", v).apply()
            enforceLimit()
            refreshSettingsSheet()
        }
        if (v > rec) {
            AlertDialog.Builder(this)
                .setTitle("Batas melebihi saran")
                .setMessage("Saran untuk HP ini: " + rec + " page aktif. Membuka " + v + " page sekaligus bisa membuat HP panas, " +
                    "aplikasi berat, dan membuat aplikasi crash. Page yang lama tidak dibuka tetap otomatis dihibernasi.\n\nTetap pakai " + v + "?")
                .setPositiveButton("Tetap pakai") { _, _ -> commit() }
                .setNegativeButton("Batal") { _, _ -> refreshSettingsSheet() }
                .setOnCancelListener { refreshSettingsSheet() }
                .show()
        } else {
            commit()
        }
    }

    private fun pageSettingRows(): List<SettingRow> {
        val rows = ArrayList<SettingRow>()
        rows.add(SettingRow.Toggle("@page", "Page", "Buka beberapa halaman sekaligus seperti di peramban. Tombol Page muncul di bawah tombol Gulir", pagesOn()) { on ->
            setPagesOn(on)
        })
        if (!pagesOn()) return rows
        val rec = PageLimits.recommended(this)
        rows.add(SettingRow.Level("@batas_page", "Batas page aktif", "Page yang boleh terbuka sekaligus; sisanya dihibernasi. Otomatis menyesuaikan RAM HP",
            0, PagePolicy.HARD_MAX_LIVE, prefs.getInt("pages_limit", 0).coerceIn(0, PagePolicy.HARD_MAX_LIVE),
            { v -> if (v == 0) "Otomatis (" + rec + ")" else if (v > rec) v.toString() + " ⚠" else v.toString() + " page" }
        ) { v -> setPageLimit(v) })
        rows.add(SettingRow.Level("@hibernasi", "Hibernasi setelah", "Page yang lama tidak dibuka dibekukan untuk menghemat RAM; dimuat ulang di posisi terakhir saat dibuka",
            0, PagePolicy.HIB_MINUTES.size, prefs.getInt("pages_hib_idx", 0).coerceIn(0, PagePolicy.HIB_MINUTES.size),
            { v -> PageLimits.formatHibernate(this, v) }
        ) { v -> prefs.edit().putInt("pages_hib_idx", v).apply() })
        rows.add(SettingRow.Toggle("@tutup_otomatis", "Tutup otomatis page hibernasi", "Page yang lama hibernasi tanpa dibuka ditutup sendiri (minimal satu page tersisa)",
            prefs.getBoolean("pages_autoclose", true)) { on ->
            prefs.edit().putBoolean("pages_autoclose", on).apply()
        })
        if (prefs.getBoolean("pages_autoclose", true)) {
            rows.add(SettingRow.Level("@tutup_otomatis", "Tutup setelah hibernasi", "Lama page hibernasi sebelum ditutup otomatis",
                1, PagePolicy.CLOSE_HOURS.size, prefs.getInt("pages_close_idx", 5).coerceIn(1, PagePolicy.CLOSE_HOURS.size),
                { v -> PageLimits.formatClose(v) }
            ) { v -> prefs.edit().putInt("pages_close_idx", v).apply() })
        }
        return rows
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 10/15 = memori menipis saat aplikasi di layar; >= 60 = proses kandidat dibuang sistem
        if (level == 10 || level == 15 || level >= 60) {
            logEvent(this, "memori sistem menipis (onTrimMemory=" + level + ") " + ramSnapshot(this))
            // memori menipis: page di latar dihibernasi supaya tidak ikut membuat aplikasi dimatikan sistem
            if (pagesOn()) pages.filter { it !== curPage && it.isLive }.forEach { hibernatePage(it, "memori menipis") }
        }
    }

    override fun onDestroy() {
        logEvent(this, "ditutup (finishing=" + isFinishing + ")")
        autoScroller.stop()                              // pastikan tidak ada callback frame yang tersisa
        saveHandler.removeCallbacksAndMessages(null)
        closePopup()
        fabCtl?.release()
        dismissMenuSheet()
        dismissSettingsSheet()
        idleHandler.removeCallbacksAndMessages(null)
        dismissPagesSheet()
        dismissSitesSheet()
        FloatingPanel.dismissCurrent(true)               // panel melayang lain (Penyesuaian warna, kotak alamat)
        for (p in pages) destroyPageView(p)
        DnsProxy.stop(this)   // hanya bila Activity ini pemilik proxy (Activity lama tidak boleh mematikan milik yang baru)
        super.onDestroy()
    }

    // ---------- DNS kustom (DNS-over-HTTPS lewat proxy lokal) ----------
    private fun dnsMode(): String = prefs.getString("dns", "off") ?: "off"

    // Alamat DoH untuk mode terpilih. null = DNS sistem, ATAU alamat kustom tersimpan tidak valid (dnsInvalid() membedakan).
    private fun dnsUrl(): String? {
        val m = dnsMode()
        return when (m) {
            "cloudflare", "google", "quad9" -> DnsProxy.PRESETS[m]
            "custom" -> prefs.getString("dns_custom", "")?.trim()?.takeIf { DnsProxy.validate(it) == null }
            else -> null
        }
    }

    private fun dnsInvalid(): Boolean = dnsMode() == "custom" && dnsUrl() == null

    private fun dnsLabel(): String {
        val name = when (dnsMode()) {
            "cloudflare" -> "Cloudflare"
            "google" -> "Google"
            "quad9" -> "Quad9"
            "custom" -> "Kustom"
            else -> "Sistem"
        }
        val fb = DnsProxy.lastFallbackAt()
        val st = when {
            dnsMode() == "off" -> ""
            dnsState.isNotEmpty() -> " (" + dnsState + ")"
            fb > 0 && System.currentTimeMillis() - fb < 120000 -> " (cadangan sistem aktif)"
            else -> ""
        }
        return name + st
    }

    // Menghentikan proxy DNS dan melepas proxy dari WebView (DNS sistem).
    private fun clearProxy(done: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            DnsProxy.stopAll()
            done()
            return
        }
        try {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) { DnsProxy.stopAll(); done() }
        } catch (e: Exception) {
            DnsProxy.stopAll()
            done()
        }
    }

    private fun applyDns(onReady: () -> Unit) {
        val url = dnsUrl()
        // callback bisa datang setelah Activity ditutup; jangan sentuh WebView/dialog saat itu
        val done: () -> Unit = { if (!isDestroyed) onReady() }
        dnsState = ""
        if (url == null) {
            // DNS sistem dipilih, atau alamat kustom tersimpan tidak valid (jangan diam-diam: beri tahu)
            if (dnsInvalid()) {
                dnsState = "alamat tidak valid, memakai sistem"
                Toast.makeText(this, "Alamat DoH kustom tidak valid, memakai DNS sistem", Toast.LENGTH_LONG).show()
            }
            clearProxy(done)
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            dnsState = "tidak aktif: WebView belum mendukung"
            Toast.makeText(this, "WebView di HP ini belum mendukung DNS kustom, memakai DNS sistem", Toast.LENGTH_LONG).show()
            done()
            return
        }
        try {
            val appCtx = applicationContext
            val ui = android.os.Handler(android.os.Looper.getMainLooper())
            // pemberitahuan cadangan DNS sistem datang dari thread proxy: catat (tanpa host) dan tampilkan di UI thread
            val port = DnsProxy.start(url, this) { msg ->
                logEvent(appCtx, msg)
                ui.post { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
            }
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").addDirect().build()
            ProxyController.getInstance().setProxyOverride(cfg, ContextCompat.getMainExecutor(this)) { done() }
        } catch (e: Exception) {
            // proxy gagal dimulai (port/alamat) atau aturan ditolak: lepas override lama dan beri tahu
            dnsState = "gagal dimulai, memakai sistem"
            logEvent(this, "proxy DNS gagal dimulai: " + e.javaClass.simpleName)
            Toast.makeText(this, "Proxy DNS gagal dimulai, memakai DNS sistem", Toast.LENGTH_LONG).show()
            clearProxy(done)
        }
    }

    // Uji server DoH terpilih di thread latar; kegagalan dilaporkan (aplikasi tetap jalan lewat cadangan sistem).
    private fun testDns() {
        val url = dnsUrl() ?: return
        if (dnsState.isNotEmpty()) return
        thread {
            val err = DnsProxy.probe(url)
            if (err != null) runOnUiThread {
                if (!isDestroyed && dnsUrl() == url) {
                    Toast.makeText(this, "Server DoH tidak berfungsi: " + err + ". DNS sistem dipakai sebagai cadangan.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setDns(mode: String) {
        prefs.edit().putString("dns", mode).apply()
        applyDns {
            Toast.makeText(this, "DNS: " + dnsLabel(), Toast.LENGTH_SHORT).show()
            refreshSettingsSheet()
            if (onErrorPage()) retryFailed() else webView.reload()
            testDns()
        }
    }

    private fun showDnsDialog() {
        val names = arrayOf("Sistem (bawaan HP)", "Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Quad9 (9.9.9.9)", "Kustom (alamat DoH)")
        val keys = arrayOf("off", "cloudflare", "google", "quad9", "custom")
        val cur = keys.indexOf(prefs.getString("dns", "off")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("DNS")
            .setSingleChoiceItems(names, cur) { d, i ->
                d.dismiss()
                if (keys[i] == "custom") askCustomDns() else setDns(keys[i])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askCustomDns() {
        val input = EditText(this).apply {
            hint = "https://.../dns-query"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("dns_custom", ""))
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val m = (20 * resources.displayMetrics.density).toInt()
            setPadding(m, m / 2, m, 0); addView(input)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Alamat DoH (standar atau JSON)")
            .setView(box)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = input.text.toString().trim()
                val err = DnsProxy.validate(u)
                if (err != null) input.error = err
                else {
                    prefs.edit().putString("dns_custom", u).apply()
                    setDns("custom")
                    dlg.dismiss()
                }
            }
        }
        dlg.show()
    }

    @Suppress("DEPRECATION")
    private fun setHighRefresh() {
        try {
            val d = windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (e: Exception) { }
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        setHighRefresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun load() {
        restoreY = 0   // pemuatan eksplisit (Awal / situs lain): jangan terapkan posisi lama
        if (currentUrl.isNotBlank()) {
            firstLoad = true
            webView.loadUrl(currentUrl)
        }
    }

    // Coba lagi halaman yang gagal dimuat (bukan selalu halaman awal); tanpa catatan gagal: halaman awal
    private fun retryFailed() {
        val f = failedUrl
        if (f != null && (f.startsWith("https://") || f.startsWith("http://"))) webView.loadUrl(f) else load()
    }

    private fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() }) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        else s = s.substring(0, s.indexOf("://")).lowercase() + s.substring(s.indexOf("://"))   // skema huruf kecil
        val host = Uri.parse(s).host
        return if (host.isNullOrBlank() || !host.contains(".")) null else s
    }

    private fun askUrl(cancelable: Boolean, choose: Boolean = false) {
        showFormPanel(this, "Alamat web", listOf(FormField("contoh: komiku.org", currentUrl, true)), "Simpan", cancelable) { v, fail, close ->
            val url = normalize(v[0])
            if (url == null) {
                fail("Alamat tidak valid")
            } else {
                close()
                // fitur Page menyala: tanya buka di page sekarang atau page baru
                if (choose && pagesOn()) askPageTarget(url) else openInCurrent(url)
            }
        }
    }

    private fun colorFilterActive(): Boolean =
        listOf("cf_b", "cf_c", "cf_s", "cf_w").any { prefs.getInt(it, 50) != 50 } ||
            prefs.getInt("fx_sm", 0) != 0 || prefs.getInt("fx_sh", 0) != 0

    // Menu cepat (deretan di kanan): Perlindungan, Segarkan, Beranda, Situs, Pengaturan. Gulir dan Penyesuaian warna ada di 3 tombol bulat (QuickTools).
    private fun showMenu() {
        autoScroller.stop()   // halaman tidak boleh terus bergerak di belakang menu
        val host = Uri.parse(webView.url ?: currentUrl).host?.removePrefix("www.")
        val adOn = adBlockOn()
        showMenuSheet(
            this,
            if (host.isNullOrEmpty()) "Menu" else host,
            listOf(
                MenuAction("@perlindungan", "Perlindungan", adOn || trackerBlockOn(),
                    if (adOn || trackerBlockOn()) (adBlocked.get() + trackerBlocked.get()).toString() + " diblokir" else "OFF") { showProtection() },
                MenuAction("@refresh", "Segarkan") { if (onErrorPage()) retryFailed() else webView.reload() },
                MenuAction("@home", "Beranda") { load() },
                MenuAction("@situs", "Situs") { showSites() },
                MenuAction("@pengaturan", "Pengaturan") { showSettings() }
            )
        )
    }

    private fun startPicker() {
        if (onErrorPage()) return
        val tk = pickerToken ?: newPickerToken().also { pickerToken = it }
        pickerActive = true
        webView.evaluateJavascript(PICKER_JS.replace("__PK_TOKEN__", tk), null)
    }

    // Kartu "Perlindungan situs" (dibuka dari menu cepat)
    private fun showProtection() {
        val host = Uri.parse(webView.url ?: currentUrl).host?.removePrefix("www.")
        showProtectionCard(this, if (host.isNullOrEmpty()) "Situs" else host, adBlocked.get(), trackerBlocked.get(), { startPicker() }) {
            val cur = webView.url ?: ""
            val hidden = hiddenElements()
            val adRows = ArrayList<SettingRow>()
            adRows.add(SettingRow.Toggle("@blok_iklan", "Pemblokir iklan", "Blokir iklan dari daftar filter", adBlockOn()) { on -> setAdBlock(on) })
            adRows.add(SettingRow.Toggle("@blok_popup", "Pemblokir popup", "Cegah jendela popup dan pindah domain yang tidak diminta", popupBlockOn()) { on ->
                prefs.edit().putBoolean("popupblock", on).apply()
            })
            adRows.add(SettingRow.Action("@sembunyi", "Tandai elemen iklan", "Sembunyikan bagian halaman yang masih mengganggu") {
                dismissMenuSheet()
                startPicker()
            })
            // hanya muncul bila memang ada elemen yang disembunyikan di situs ini
            if (hidden.isNotEmpty()) {
                adRows.add(SettingRow.Action("@sembunyi", "Elemen tersembunyi", hidden.size.toString() + " elemen di situs ini, ketuk untuk memulihkan") {
                    dismissMenuSheet()
                    showHiddenElements()
                })
            }
            listOf(
                SettingSection("Pemblokir iklan", adRows),
                SettingSection("Perlindungan privasi", listOf(
                    SettingRow.Toggle("@pelacak", "Pelacak",
                        if (trackerBlocked.get() > 0) trackerBlocked.get().toString() + " diblokir di halaman ini" else "Blokir pelacak analitik dan pengukuran",
                        trackerBlockOn()) { on ->
                        prefs.edit().putBoolean("trackerblock", on).apply()
                        if (!onErrorPage()) webView.reload()
                    },
                    SettingRow.Value("@gpc", "GPC", "", if (gpcOn()) "Aktif" else "Mati", if (gpcOn()) 1 else 0) {
                        dismissMenuSheet()
                        showSettings()
                    },
                    SettingRow.Value("@https", "Koneksi", "",
                        if (cur.startsWith("https://")) "HTTPS" else if (cur.startsWith("http://")) "HTTP (tidak aman)" else "-",
                        if (cur.startsWith("https://")) 1 else if (cur.startsWith("http://")) -1 else 0, null)
                ))
            )
        }
    }

    // Gulir otomatis dan gulir ketuk-tepi: satu menu, tetapi saklar on/off-nya terpisah
    private fun showScrollPopup() {
        showPopupCard(this, "Gulir") {
            listOf<SettingRow>(
                SettingRow.Toggle("@autoscroll", "Gulir otomatis", "Halaman bergulir sendiri; panel kecepatan muncul di layar", autoPanel.modeOn) { on ->
                    if (on != autoPanel.modeOn) autoPanel.toggle()
                    if (on) {
                        dismissMenuSheet()
                        FloatingPanel.dismissCurrent()   // panel Gulir otomatis memakai tempat yang sama di bawah layar
                    }
                }
            ) + edgeScrollRows()
        }
    }

    // ---------- Gulir ketuk-tepi: saklar, kecepatan, dan jarak mendarat ----------
    // Kecepatan level 1..10 = px CSS per milidetik (level 5 = 2,0 = bawaan). Mendarat 3..10 = 30%..100% tinggi layar per ketukan (9 = 90%).
    private val edgeSpeeds = floatArrayOf(0.6f, 0.9f, 1.2f, 1.6f, 2.0f, 2.5f, 3.0f, 3.8f, 4.8f, 6.0f)
    private fun edgeSpeedLevel() = prefs.getInt("edge_speed", 5).coerceIn(1, 10)
    private fun edgeSpeed() = edgeSpeeds[edgeSpeedLevel() - 1]
    private fun edgeLandLevel() = prefs.getInt("edge_land", 9).coerceIn(3, 10)
    private fun edgeFraction() = edgeLandLevel() / 10f

    // Saklar dimatikan = kedua slider (kecepatan dan mendarat) ikut disembunyikan
    private fun edgeScrollRows(): List<SettingRow> =
        edgeScrollAllRows().let { if (prefs.getBoolean("edge_on", true)) it else it.take(1) }

    private fun edgeScrollAllRows(): List<SettingRow> = listOf(
        SettingRow.Toggle("@gulir_ketuk", "Gulir ketuk-tepi", "Ketuk tepi layar untuk menggulir halaman", prefs.getBoolean("edge_on", true)) { on ->
            prefs.edit().putBoolean("edge_on", on).apply()
        },
        SettingRow.Level("@kecepatan", "Kecepatan gulir ketuk-tepi", "Seberapa cepat halaman bergeser saat tepi diketuk (tetap, tidak melambat)",
            1, 10, edgeSpeedLevel(),
            { v -> v.toString() + " / 10" }
        ) { v -> prefs.edit().putInt("edge_speed", v).apply() },
        SettingRow.Level("@mendarat", "Mendarat (jarak per ketukan)", "Sejauh apa halaman turun/naik tiap ketukan; kurang dari 100% menyisakan bagian yang tumpang tindih",
            3, 10, edgeLandLevel(),
            { v -> (v * 10).toString() + "% layar" }
        ) { v -> prefs.edit().putInt("edge_land", v).apply() }
    )

    // ---------- Elemen yang disembunyikan pengguna (per situs) ----------
    // Disimpan sebagai selector CSS, satu per baris, di prefs "blk:<domain>".
    private fun hiddenKey() = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)

    private fun hiddenElements(): List<String> =
        (prefs.getString(hiddenKey(), "") ?: "").split("\n").filter { it.isNotBlank() }

    // Daftar elemen yang disembunyikan di situs ini; ketuk satu untuk memulihkan hanya elemen itu
    private fun showHiddenElements() {
        val list = hiddenElements()
        if (list.isEmpty()) return
        val labels = list.mapIndexed { i, sel ->
            (i + 1).toString() + ". " + (if (sel.length > 70) sel.take(70) + "…" else sel)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elemen tersembunyi (ketuk untuk memulihkan)")
            .setItems(labels) { _, i -> restoreHidden(list[i]) }
            .setNeutralButton("Pulihkan semua") { _, _ -> restoreAllHidden() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun restoreHidden(selector: String) {
        val cur = hiddenElements().toMutableList()
        cur.remove(selector)
        val key = hiddenKey()
        if (cur.isEmpty()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, cur.joinToString("\n")).apply()
        Toast.makeText(this, "Elemen dipulihkan", Toast.LENGTH_SHORT).show()
        if (!onErrorPage()) webView.reload()
    }

    private fun restoreAllHidden() {
        prefs.edit().remove(hiddenKey()).apply()
        Toast.makeText(this, "Semua elemen tersembunyi di situs ini dipulihkan", Toast.LENGTH_SHORT).show()
        if (!onErrorPage()) webView.reload()
    }

    private fun showSettings() {
        showSettingsSheet(this, "Pengaturan") {
            val infos = accountInfos()
            val g = infos.firstOrNull { it.site == "google.com" }
            val others = infos.count { it.session && it.site != "google.com" }
            val parts = ArrayList<String>()
            if (g != null && g.session) {
                parts.add(if (googleEmails.isNotEmpty()) googleEmails[0] else "Google: masuk")
                if (googleEmails.isEmpty()) refreshGoogleEmails()
            }
            if (others > 0) parts.add(others.toString() + " situs")
            listOf(
                SettingSection("Anda dan Google", listOf(
                    SettingRow.Profile(
                        if (googleName.isNotEmpty()) googleName else if (g != null && g.session) "Akun Google" else "Belum masuk",
                        if (parts.isEmpty()) "Ketuk untuk melihat akun dan situs" else parts.joinToString(" · "),
                        googleAvatar,
                        (if (googleName.isNotEmpty()) googleName else googleEmails.firstOrNull() ?: "G").take(1).uppercase()
                    ) { showAccountMenu() }
                )),
                SettingSection("Tampilan dan membaca", listOf(
                    SettingRow.Toggle("@tarik", "Tarik untuk menyegarkan", "Tarik halaman ke bawah dari bagian atas untuk memuat ulang", prefs.getBoolean("pull_refresh", true)) { on ->
                        prefs.edit().putBoolean("pull_refresh", on).apply()
                        swipe.isEnabled = on
                    },
                    SettingRow.Toggle("@pramuat", "Pramuat gambar", "Memuat gambar di depan posisi baca lebih awal", preloadOn()) { on ->
                        prefs.edit().putBoolean("preload", on).apply()
                        if (on) injectPreload(webView) else webView.evaluateJavascript("window.__preOff=1", null)
                    },
                    SettingRow.Level("@gambar_depan", "Gambar dimuat di depan", "Otomatis menyesuaikan koneksi dan RAM perangkat",
                        0, 12, preloadAhead(),
                        { v -> if (v == 0) "Otomatis" else v.toString() + " gambar" }
                    ) { v ->
                        prefs.edit().putInt("preload_ahead", v).apply()
                        if (preloadOn()) injectPreload(webView)
                    },
                    SettingRow.Level("@unduhan", "Unduhan bersamaan", "Jumlah gambar yang diunduh sekaligus oleh pramuat",
                        1, 4, preloadParallel(),
                        { v -> v.toString() + " sekaligus" }
                    ) { v ->
                        prefs.edit().putInt("preload_parallel", v).apply()
                        if (preloadOn()) injectPreload(webView)
                    },
                ) + edgeScrollRows()),
                SettingSection("Page", pageSettingRows()),
                SettingSection("Privasi dan pemblokiran", listOf(
                    SettingRow.Toggle("@blok_iklan", "Pemblokir iklan", "Saklar utama: blokir iklan, pelacak, dan jendela iklan", adBlockOn()) { on ->
                        setAdBlock(on)
                    },
                    SettingRow.Toggle("@blok_popup", "Pemblokir popup", "Cegah jendela popup yang tidak diminta", popupBlockOn()) { on ->
                        prefs.edit().putBoolean("popupblock", on).apply()
                    },
                    SettingRow.Toggle("@pelacak", "Pelacak", "Blokir pelacak analitik dan pengukuran (daftar bawaan)", trackerBlockOn()) { on ->
                        prefs.edit().putBoolean("trackerblock", on).apply()
                        if (!onErrorPage()) webView.reload()
                    },
                    SettingRow.Toggle("@cegah", "Cegah pengalihan domain", "Blokir pindah ke domain lain (berlaku per situs)", blockCross) { on ->
                        blockCross = on
                        Toast.makeText(this, if (on) "Pengalihan domain diblokir" else "Pengalihan domain diizinkan", Toast.LENGTH_SHORT).show()
                    },
                    SettingRow.Toggle("@sembunyi", "Penyembunyi elemen iklan", "Sembunyikan kotak iklan yang tersisa (butuh pemblokir iklan aktif)", prefs.getBoolean("cosmetic", false)) { on ->
                        prefs.edit().putBoolean("cosmetic", on).apply()
                        Toast.makeText(this, if (on) "Diaktifkan, memproses daftar..." else "Dimatikan", Toast.LENGTH_SHORT).show()
                        logEvent(this, "filter kosmetik " + (if (on) "ON" else "OFF"))
                        thread {
                            parseLists()
                            runOnUiThread { if (!isDestroyed) webView.reload() }
                        }
                    },
                    SettingRow.Action("@perbarui", "Perbarui daftar filter", "Unduh daftar iklan terbaru") {
                        Toast.makeText(this, "Mengunduh daftar filter...", Toast.LENGTH_SHORT).show()
                        refreshLists(true)
                    }
                )),
                SettingSection("Privasi lanjutan", listOf(
                    SettingRow.Toggle("@gpc", "Kontrol Privasi Global (GPC)", "Beri sinyal \"jangan jual data saya\" ke situs (lewat JavaScript)", gpcOn()) { on ->
                        prefs.edit().putBoolean("gpc", on).apply()
                        applyDocStartAll()
                    },
                    SettingRow.Toggle("@amp", "Perlindungan tautan AMP", "Lewati pengalihan Google, buka situs aslinya", ampOn()) { on ->
                        prefs.edit().putBoolean("amp_unwrap", on).apply()
                    },
                    SettingRow.Toggle("@url", "Pembersih pelacak URL", "Hapus parameter pelacak (utm_, fbclid, gclid, dll.) saat membuka tautan", urlCleanOn()) { on ->
                        prefs.edit().putBoolean("url_clean", on).apply()
                    },
                    SettingRow.Toggle("@sidik", "Perlindungan sidik jari (dasar)", "Menyamarkan bahasa dan spesifikasi perangkat. Beberapa situs bisa tampil berbahasa Inggris", fpOn()) { on ->
                        prefs.edit().putBoolean("fingerprint", on).apply()
                        applyDocStartAll()
                        Toast.makeText(this, "Berlaku mulai halaman berikutnya", Toast.LENGTH_SHORT).show()
                    },
                    SettingRow.Toggle("@cookie", "Blokir cookie pihak ketiga", "Blokir pelacakan lintas situs; dapat mengganggu login", block3pOn()) { on ->
                        prefs.edit().putBoolean("block3p", on).apply()
                        pages.forEach { pg -> pg.wv?.let { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !on) } }
                        Toast.makeText(this, "Muat ulang halaman agar berlaku", Toast.LENGTH_SHORT).show()
                    },
                    SettingRow.Toggle("@https", "Paksa HTTPS", "Naikkan tautan http:// ke https:// (situs yang hanya mendukung HTTP tidak akan terbuka)", httpsForceOn()) { on ->
                        prefs.edit().putBoolean("https_force", on).apply()
                    }
                )),
                SettingSection("Jaringan", listOf(
                    SettingRow.Action("@dns", "DNS aman (DoH)", dnsLabel()) { showDnsDialog() }
                )),
                SettingSection("Lanjutan", listOf(
                    SettingRow.Action("@diagnostik", "Diagnostik", "Ringkasan teknis untuk pelaporan masalah") {
                        showDiagDialog(this, prefs, sharedRules.summary())
                    }
                ))
            )
        }
    }

    // ---------- Akun dan sesi masuk ----------
    // WebView tidak menyimpan nama akun. Yang bisa dilihat hanya cookie per situs, jadi daftar ini menunjukkan situs
    // (situs tersimpan + situs yang sedang dibuka) yang tampak punya sesi masuk, dan menyediakan "keluar" (hapus cookie).
    private class AccountInfo(val site: String, val url: String, val cookies: Int, val session: Boolean)

    private val sessionCookie = Regex("(?i)(sess|sid|token|auth|login|logged|remember|jwt|user|member|account)")

    private fun accountInfos(): List<AccountInfo> {
        val cm = CookieManager.getInstance()
        val seen = LinkedHashMap<String, String>()
        fun add(u: String) {
            val h = Uri.parse(u).host
            if (h.isNullOrEmpty()) return
            val b = baseDomain(h)
            if (b.isNotEmpty() && !seen.containsKey(b)) seen[b] = u
        }
        add(currentUrl)
        loadSites().forEach { add(it.url) }
        seen.remove("google.com")   // Google ditampilkan tersendiri di paling atas (akun satu untuk semua situs)
        val out = ArrayList<AccountInfo>()
        val gUrl = "https://accounts.google.com/"
        val gNames = (cm.getCookie(gUrl) ?: "").split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        out.add(AccountInfo("google.com", gUrl, gNames.size,
            gNames.any { it == "SID" || it == "SAPISID" || it == "LSID" || it.endsWith("PSID") }))
        for ((b, u) in seen) {
            val names = (cm.getCookie(u) ?: "").split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            out.add(AccountInfo(b, u, names.size, names.any { sessionCookie.containsMatchIn(it) }))
        }
        return out
    }

    // Penyedia identitas (masuk dengan Google/Apple/Microsoft): pindah ke domain ini tidak diblokir oleh "Cegah pengalihan
    // domain", supaya alur "Masuk dengan Google" di situs mana pun tetap jalan. Cookie sesi dipakai bersama semua situs.
    private val googleLoginHost = Regex("^accounts\\.google\\.[a-z.]+$")

    private fun isLoginProvider(host: String?): Boolean {
        val h = (host ?: "").lowercase()
        return googleLoginHost.matches(h) || h == "accounts.youtube.com" || h == "appleid.apple.com" ||
            h == "idmsa.apple.com" || h == "login.microsoftonline.com" || h == "login.live.com"
    }

    @Volatile private var googleEmails: List<String> = emptyList()
    @Volatile private var googleName: String = ""
    @Volatile private var googleAvatar: android.graphics.Bitmap? = null
    @Volatile private var googleFetching = false
    @Volatile private var googleFetchAt = 0L

    private fun clearGoogleProfile() {
        googleEmails = emptyList()
        googleName = ""
        googleAvatar = null
    }

    // Isi teks JSON -> teks biasa (\uXXXX, \/, \")
    private fun unJson(s: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")

    // Profil akun Google yang sedang masuk (email, nama, foto): hanya di memori, tidak disimpan. Memakai cookie sesi aplikasi
    // sendiri; bila gagal/format berubah, tampilan akun kembali ke "sesi aktif" saja. Hasil (tanpa data pribadi) dicatat di log diagnosa.
    private fun refreshGoogleEmails() {
        val now = System.currentTimeMillis()
        if (googleFetching || now - googleFetchAt < 60000) return   // jangan menembak server berulang kali
        googleFetchAt = now
        googleFetching = true
        val cookie = CookieManager.getInstance().getCookie("https://accounts.google.com/") ?: ""
        val ua = webView.settings.userAgentString
        val appCtx = applicationContext
        thread {
            var emails: List<String> = emptyList()
            var name = ""
            var avatar: android.graphics.Bitmap? = null
            var code = -1
            try {
                val c = java.net.URL("https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Cookie", cookie)
                c.setRequestProperty("User-Agent", ua)
                code = c.responseCode
                if (code == 200) {
                    val body = c.inputStream.bufferedReader().use { it.readText().take(60000) }
                    emails = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(body).map { it.value }.distinct().take(5).toList()
                    // akun pertama: ["gaia.l.a",1,"Nama","email","foto",...
                    val m = Regex("\\[\"gaia\\.l\\.a\",\\s*\\d+,\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null),\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null)")
                        .find(body)
                    if (m != null) {
                        name = if (m.groupValues[1] == "null") "" else unJson(m.groupValues[1].trim('"'))
                        val mail = unJson(m.groupValues[2])
                        if (mail.contains("@")) emails = listOf(mail) + emails.filter { it != mail }
                        var photo = if (m.groupValues[3] == "null") "" else unJson(m.groupValues[3].trim('"'))
                        if (photo.startsWith("//")) photo = "https:" + photo
                        if (photo.startsWith("https://")) {
                            photo = photo.replace(Regex("=s\\d+(-[a-z0-9-]+)?$"), "=s160-c")
                            val pc = java.net.URL(photo).openConnection() as java.net.HttpURLConnection
                            pc.connectTimeout = 5000
                            pc.readTimeout = 5000
                            if (pc.responseCode == 200) {
                                val bytes = pc.inputStream.use { it.readBytes() }
                                if (bytes.size in 1..600000) {
                                    val raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (raw != null) avatar = circleBitmap(raw, 168)
                                }
                            }
                            pc.disconnect()
                        }
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
            }
            logEvent(appCtx, "profil Google: HTTP " + code + ", email=" + (if (emails.isNotEmpty()) "ya" else "tidak") +
                ", nama=" + (if (name.isNotEmpty()) "ya" else "tidak") + ", foto=" + (if (avatar != null) "ya" else "tidak"))
            googleFetching = false
            runOnUiThread {
                if (!isDestroyed && (emails != googleEmails || name != googleName || avatar != null)) {
                    googleEmails = emails
                    googleName = name
                    googleAvatar = avatar
                    refreshSettingsSheet()
                }
            }
        }
    }

    // Ketuk kartu profil: pilihan masuk/tambah akun, keluar (hapus akun), atau daftar semua sesi situs
    private fun showAccountMenu() {
        val g = accountInfos().firstOrNull { it.site == "google.com" }
        val signed = g != null && g.session
        if (signed && googleEmails.isEmpty()) refreshGoogleEmails()
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add(if (signed) "Masuk / tambah akun Google" else "Masuk ke Google")
        acts.add { startGoogleLogin(signed) }
        if (g != null && signed) {
            labels.add("Keluar (hapus akun dari aplikasi)")
            acts.add { confirmGoogleSignOut(g) }
        }
        labels.add("Semua sesi situs…")
        acts.add { showAccounts() }
        AlertDialog.Builder(this)
            .setTitle(
                if (!signed) "Akun Google"
                else "Akun Google\n" + (if (googleEmails.isNotEmpty()) googleEmails.joinToString("\n") else "Sesi masuk aktif")
            )
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // Masuk = membuka halaman login Google di aplikasi ini; akun yang berhasil masuk tersimpan sebagai sesi (cookie) aplikasi
    private fun startGoogleLogin(addAnother: Boolean) {
        clearGoogleProfile()      // profil dibaca ulang saat Pengaturan dibuka lagi setelah masuk
        googleFetchAt = 0L
        dismissSettingsSheet()
        Toast.makeText(this, "Masuk di halaman ini. Setelah selesai, buka Pengaturan lagi.", Toast.LENGTH_LONG).show()
        webView.loadUrl(if (addAnother) "https://accounts.google.com/AddSession" else "https://accounts.google.com/")
    }

    // Keluar = semua cookie Google dihapus dari aplikasi, sehingga akun hilang dari aplikasi
    private fun confirmGoogleSignOut(info: AccountInfo) {
        AlertDialog.Builder(this)
            .setTitle("Keluar dari akun Google?")
            .setMessage("Akun Google dihapus dari aplikasi ini. Situs lain yang masuk lewat Google bisa ikut keluar. Akun Anda di Google sendiri tidak dihapus.")
            .setPositiveButton("Keluar") { _, _ -> signOutSite(info) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAccounts() {
        val list = accountInfos()
        if (googleEmails.isEmpty() && list.firstOrNull()?.session == true) refreshGoogleEmails()
        val labels = list.map {
            if (it.site == "google.com") {
                "Akun Google\n" + (
                    if (it.session) (if (googleEmails.isNotEmpty()) googleEmails.joinToString(", ") else "Sesi masuk aktif") +
                        "\nMasuk sekali, berlaku di semua situs yang memakai Google"
                    else "Belum masuk. Ketuk untuk masuk ke Google")
            } else {
                it.site + "\n" + (if (it.session) "Sesi masuk aktif" else if (it.cookies > 0) "Ada cookie, belum tentu masuk" else "Belum masuk")
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Akun dan sesi masuk")
            .setItems(labels) { _, i ->
                val info = list[i]
                if (info.site == "google.com" && !info.session) {
                    // masuk ke Google di WebView ini; cookie-nya dipakai bersama semua situs
                    webView.loadUrl("https://accounts.google.com/")
                } else {
                    confirmSignOut(info)
                }
            }
            .setNeutralButton("Keluar dari semua") { _, _ -> confirmSignOutAll() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun confirmSignOut(info: AccountInfo) {
        AlertDialog.Builder(this)
            .setTitle("Keluar dari " + info.site + "?")
            .setMessage("Cookie situs ini dihapus dari aplikasi. Sebagian situs menyimpan sesi dengan cara lain sehingga bisa tetap masuk.")
            .setPositiveButton("Keluar") { _, _ -> signOutSite(info) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun signOutSite(info: AccountInfo) {
        val cm = CookieManager.getInstance()
        val host = Uri.parse(info.url).host ?: return
        val names = (cm.getCookie(info.url) ?: "").split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        val domains = LinkedHashSet<String>()
        domains.add(host); domains.add("." + host); domains.add(info.site); domains.add("." + info.site)
        for (n in names) {
            cm.setCookie(info.url, n + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            for (dm in domains) {
                cm.setCookie(info.url, n + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=" + dm)
            }
        }
        cm.flush()
        if (info.site == "google.com") clearGoogleProfile()
        val left = (cm.getCookie(info.url) ?: "").split(";").count { it.isNotBlank() }
        Toast.makeText(this,
            if (left == 0) "Sudah keluar dari " + info.site else "Sebagian cookie " + info.site + " masih tersisa",
            Toast.LENGTH_LONG).show()
        logEvent(this, "keluar dari situs: sisa cookie " + left)
        if (!onErrorPage() && baseDomain(Uri.parse(webView.url ?: "").host) == info.site) webView.reload()
        refreshSettingsSheet()
    }

    private fun confirmSignOutAll() {
        AlertDialog.Builder(this)
            .setTitle("Keluar dari semua situs?")
            .setMessage("Semua cookie di aplikasi dihapus. Anda perlu masuk lagi di setiap situs.")
            .setPositiveButton("Keluar semua") { _, _ ->
                clearGoogleProfile()
                CookieManager.getInstance().removeAllCookies { _ ->
                    LoginSupport.save()
                    runOnUiThread {
                        if (!isDestroyed) {
                            Toast.makeText(this, "Semua sesi dihapus", Toast.LENGTH_SHORT).show()
                            if (!onErrorPage()) webView.reload()
                            refreshSettingsSheet()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadSites(): MutableList<Site> {
        val out = mutableListOf<Site>()
        try {
            val arr = org.json.JSONArray(prefs.getString("sites", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Site(o.getString("n"), o.getString("u"), o.optBoolean("p", false)))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun storeSites(list: List<Site>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("n", it.name).put("u", it.url).put("p", it.pinned)) }
        prefs.edit().putString("sites", arr.toString()).apply()
    }

    private fun addSiteIfNew(url: String) {
        if (url.isBlank()) return
        val list = loadSites()
        if (list.any { it.url == url }) return
        val host = Uri.parse(url).host?.removePrefix("www.") ?: url
        list.add(Site(host, url))
        storeSites(list)
    }

    // Membuka situs tersimpan / alamat baru: bila fitur Page menyala, tanya page sekarang atau page baru
    private fun openSite(url: String) {
        if (pagesOn()) askPageTarget(url) else openInCurrent(url)
    }

    private fun openInCurrent(url: String) {
        currentUrl = url
        webView.clearHistory()
        load()
    }

    private fun askPageTarget(url: String) {
        val live = pages.count { it.isLive }
        val lim = PageLimits.limit(this, prefs)
        val info = if (live >= lim) "Page aktif sudah " + live + "/" + lim + ". Page yang paling lama tidak dilihat akan dihibernasi otomatis." else ""
        showPageTargetSheet(this, url, info, { openInCurrent(url) }, { openInNewPage(url) })
    }

    private fun showSites() {
        addSiteIfNew(currentUrl)
        showSitesSheet(
            this,
            SitesUi(
                load = { loadSites() },
                save = { storeSites(it) },
                current = currentUrl,
                grid = { prefs.getString("sites_view", "list") == "grid" },
                setGrid = { g -> prefs.edit().putString("sites_view", if (g) "grid" else "list").apply() },
                onOpen = { url -> openSite(url) },
                onNew = { askUrl(cancelable = true, choose = true) },
                onSaveCurrent = { saveCurrentPage() },
                onAddManual = { addSiteManually() },
                onEdit = { site, done -> editSite(site, done) }
            )
        )
    }

    // Ubah nama/alamat situs tersimpan (tombol Edit di sheet Situs)
    private fun editSite(site: Site, done: () -> Unit) {
        // Panel melayang hanya satu pada satu waktu: daftar Situs dibuka lagi setelah Simpan / Batal
        showFormPanel(
            this, "Ubah situs",
            listOf(FormField("Nama", site.name), FormField("Alamat", site.url, true)),
            "Simpan", true,
            onCancel = { showSites() }
        ) { v, fail, close ->
            val url = normalize(v[1])
            if (url == null) {
                fail("Alamat tidak valid")
                return@showFormPanel
            }
            val list = loadSites()
            val idx = list.indexOfFirst { it.url == site.url }
            if (url != site.url && list.any { it.url == url }) {
                fail("Alamat ini sudah ada di daftar")
                return@showFormPanel
            }
            if (idx >= 0) {
                val name = v[0].trim().take(40).ifEmpty { Uri.parse(url).host?.removePrefix("www.") ?: url }
                list[idx] = Site(name, url, site.pinned)
                storeSites(list)
            }
            close()
            showSites()
        }
    }

    // Simpan alamat halaman yang sedang dibuka (bukan hanya alamat awal) ke daftar Situs
    private fun saveCurrentPage() {
        val u = if (webViewDead) null else webView.url
        if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) {
            Toast.makeText(this, "Tidak ada halaman web yang sedang dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        val list = loadSites()
        if (list.any { it.url == u }) {
            Toast.makeText(this, "Alamat ini sudah tersimpan", Toast.LENGTH_SHORT).show()
        } else {
            val title = webView.title?.trim().orEmpty()
            val name = if (title.isNotEmpty()) title.take(40) else (Uri.parse(u).host?.removePrefix("www.") ?: u)
            list.add(Site(name, u))
            storeSites(list)
            Toast.makeText(this, "Alamat tersimpan", Toast.LENGTH_SHORT).show()
        }
        showSites()
    }

    // Tulis alamat (dan nama, opsional) sendiri; hanya disimpan ke daftar, tidak langsung dibuka
    private fun addSiteManually() {
        showFormPanel(
            this, "Simpan alamat",
            listOf(FormField("Nama (boleh kosong)"), FormField("Alamat, contoh: komiku.org/baca/abc", "", true)),
            "Simpan", true
        ) { v, fail, close ->
            val url = normalize(v[1])
            if (url == null) {
                fail("Alamat tidak valid")
            } else {
                val list = loadSites()
                val name = v[0].trim().take(40).ifEmpty { Uri.parse(url).host?.removePrefix("www.") ?: url }
                val idx = list.indexOfFirst { it.url == url }
                if (idx >= 0) list[idx] = Site(name, url, list[idx].pinned) else list.add(Site(name, url))
                storeSites(list)
                Toast.makeText(this, "Alamat tersimpan", Toast.LENGTH_SHORT).show()
                close()
                showSites()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pageId", curPage.id)
        if (!webViewDead) webView.saveState(outState)
    }
}
