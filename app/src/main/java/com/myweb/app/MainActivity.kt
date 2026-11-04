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
            configBase = baseDomain(Uri.parse(v).host)
        }

    private fun crossKey() = "blockCross:" + baseDomain(Uri.parse(currentUrl).host)
    private var blockCross: Boolean
    get() = prefs.getBoolean(crossKey(), prefs.getBoolean("blockCross", true))
    set(v) = prefs.edit().putBoolean(crossKey(), v).apply()

    @Volatile private var configBase: String = ""
    @Volatile private var siteBase: String = ""
    private var firstLoad = false
    private var pickerActive = false
    private var restoreY = 0
    private var restoreUrl: String? = null
    private var restoreAt = 0L
    @Volatile private var pickerToken: String? = null
    private var webViewDead = false
    private var lastSslToast = 0L
    private var lastUpgradeUrl = ""
    private var lastUpgradeAt = 0L
    private val warnedHttp = HashSet<String>()
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var failedUrl: String? = null     // halaman utama yang terakhir gagal dimuat (untuk "Coba lagi")
    private var dnsState = ""                 // kosong = DNS terpilih benar-benar dipasang; selain itu alasan tidak aktif

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    inner class Bridge {
        @JavascriptInterface
        fun retry() { runOnUiThread { if (onErrorPage()) retryFailed() } }

        @JavascriptInterface
        fun systemDns() { runOnUiThread { if (onErrorPage()) setDns("off") } }

        @JavascriptInterface
        fun changeUrl() { runOnUiThread { if (onErrorPage()) askUrl(true) } }

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
        if (renderGoneCount == 2) prefs.edit().remove("last_page").remove("last_scroll").apply()
        recreate()
    }

    private fun note(msg: String) {
        Log.w("AdBlock", msg)
        logEvent(this, msg)
    }

    private fun baseDomain(host: String?): String = PublicSuffix.registrableDomain(host)

    // Pihak pertama = situs yang sedang dibuka ATAU alamat yang disimpan pengguna. siteBase baru terisi setelah halaman
    // pertama selesai, jadi tanpa configBase situs utama sendiri bisa terblokir saat pemuatan pertama.
    private fun isFirstParty(host: String): Boolean {
        val b = baseDomain(host)
        return b.isNotEmpty() && (b == siteBase || b == configBase)
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
    private var docStartHandler: ScriptHandler? = null
    private fun docStartJs(): String {
        val sb = StringBuilder()
        if (gpcOn()) sb.append("try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true})}catch(e){}")
        if (fpOn()) sb.append("(function(){var d=function(k,v){try{Object.defineProperty(Navigator.prototype,k,{get:function(){return v},configurable:true})}catch(e){}};d('hardwareConcurrency',4);d('deviceMemory',4);d('languages',Object.freeze(['en-US','en']));d('language','en-US');})();")
        return sb.toString()
    }

    private fun applyDocStart() {
        try { docStartHandler?.remove() } catch (e: Exception) { }
        docStartHandler = null
        val js = docStartJs()
        if (js.isEmpty()) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                docStartHandler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
            } catch (e: Exception) {
                logEvent(this, "skrip awal dokumen gagal: " + e.javaClass.simpleName)
            }
        }
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

    private fun isAd(host: String?): Boolean {
        if (!adBlockOn()) return false
        if (host.isNullOrEmpty() || isFirstParty(host)) return false
        return sharedRules.isBlocked(host)
    }

    // Untuk jendela popup dan pengalihan otomatis: aturan $popup ikut berlaku
    private fun isPopupAd(host: String?): Boolean {
        if (!adBlockOn()) return false
        if (host.isNullOrEmpty() || isFirstParty(host)) return false
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
    // Hentikan pemrosesan tambahan WebView (video/audio/timer halaman) saat aplikasi tidak di layar
    if (!webViewDead) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDead) webView.onResume()
    }

    // Simpan halaman + posisi scroll terakhir supaya aplikasi dibuka lagi di tempat yang sama.
    // Dipanggil saat onPause dan, selama membaca, paling sering tiap 2 detik (scheduleSave) supaya posisi tetap ada
    // walau proses dimatikan sistem / renderer mati tanpa sempat onPause.
    private fun saveReadingPosition() {
        if (webViewDead) return
        // pemulihan menunggu halaman selesai (maks 20 detik): posisi 0 sementara jangan menimpa posisi tersimpan
        if (restoreY > 0 && System.currentTimeMillis() - restoreAt < 20000) return
        val u = webView.url ?: return
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        // halaman berganti: alamat baru disimpan LANGSUNG dengan posisi 0 (posisi lama bukan milik halaman ini)
        if (prefs.getString("last_page", null) != u) prefs.edit().putString("last_page", u).putInt("last_scroll", 0).apply()
        webView.evaluateJavascript(SAVE_POS_JS) { r ->
            // jawaban datang belakangan: abaikan bila halaman sudah berganti / WebView sudah mati
            if (webViewDead || webView.url != u) return@evaluateJavascript
            val y = r?.trim('"')?.toIntOrNull() ?: return@evaluateJavascript
            // -1 = halaman belum selesai atau pemulihan posisi sedang berjalan: posisi sementara tidak boleh menimpa
            if (y < 0) return@evaluateJavascript
            prefs.edit().putString("last_page", u).putInt("last_scroll", ReadingPos.clampY(y)).apply()
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

    // Buka halaman terakhir (kalau masih satu situs dengan alamat web yang dipilih), kalau tidak ke halaman awal
    private fun resumeOrLoad() {
        val last = prefs.getString("last_page", null)
        if (!last.isNullOrBlank() &&
            baseDomain(Uri.parse(last).host) == baseDomain(Uri.parse(currentUrl).host)) {
            restoreY = ReadingPos.clampY(prefs.getInt("last_scroll", 0))
            restoreUrl = last
            restoreAt = System.currentTimeMillis()
            firstLoad = true
            webView.loadUrl(last)
        } else load()
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
        configBase = baseDomain(Uri.parse(currentUrl).host)
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

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipe)
        LoginSupport.setup(webView)
        webView.setBackgroundColor(Color.BLACK)
        progress = findViewById(R.id.progress)
        // Tiga tombol setengah lonjong di tepi kanan: Menu, Penyesuaian warna, Gulir
        val sideTools = findViewById<LinearLayout>(R.id.menuBtn)
        QuickTools(sideTools, { showMenu() }, { showColorFilterSheet(this, webView, prefs) }, { showScrollPopup() })
        fabCtl = FabController(webView, sideTools, { pickerActive }, { prefs.getBoolean("edge_on", true) }, { autoScroller.stop() }, { scheduleSave() })

        webView.settings.apply {
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
        applyColorFilter(webView, prefs) 
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !block3pOn())
        applyDocStart()
        webView.addJavascriptInterface(Bridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
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
                if (isAd(u.host) || (!req.hasGesture() && isPopupAd(u.host))) return true
                val rewritten = rewriteUrl(u.toString())
                if (rewritten != null) {
                    v.loadUrl(rewritten)
                    return true
                }
                // Utamakan HTTPS: situs yang disimpan memakai https tidak boleh turun ke http (dengan pengaman agar tidak berputar)
                if (scheme == "http" && u.port == -1 && currentUrl.startsWith("https://") &&
                    baseDomain(u.host) == baseDomain(Uri.parse(currentUrl).host)) {
                    val now = System.currentTimeMillis()
                    if (!(u.toString() == lastUpgradeUrl && now - lastUpgradeAt < 5000)) {
                        lastUpgradeUrl = u.toString()
                        lastUpgradeAt = now
                        v.loadUrl(u.buildUpon().scheme("https").build().toString())
                        return true
                    }
                }
                if (!firstLoad && siteBase.isNotEmpty() && blockCross && baseDomain(u.host) != siteBase && !isLoginProvider(u.host)) {
                    Toast.makeText(this@MainActivity, "Pindah domain diblokir: " + u.host, Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && !request.isForMainFrame && trackerBlockOn()) {
                    val th = request.url.host
                    if (!th.isNullOrEmpty() && !isFirstParty(th) && Trackers.isTracker(th)) {
                        trackerBlocked.incrementAndGet()
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                if (request != null && isAd(request.url.host)) {
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
                    adBlocked.set(0)
                    trackerBlocked.set(0)
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
                failedUrl = null
                logEvent(this@MainActivity, "halaman selesai " + ramSnapshot(this@MainActivity))
                if (url.startsWith("http://")) {
                    val hh = Uri.parse(url).host ?: ""
                    if (hh.isNotEmpty() && warnedHttp.add(hh))
                        Toast.makeText(this@MainActivity, "Situs ini memakai HTTP (tidak terenkripsi)", Toast.LENGTH_LONG).show()
                }
                if (firstLoad) {
                    val h = Uri.parse(url).host
                    if (!h.isNullOrEmpty()) {
                        siteBase = baseDomain(h)
                        firstLoad = false
                    }
                }
                injectCss(v, true)
                injectPreload(v)
                // efek gambar (haluskan/ketajaman) tersimpan di pengaturan tapi hilang tiap pindah halaman
                if (prefs.getInt("fx_sm", 0) > 0 || prefs.getInt("fx_sh", 0) > 0) applyImageFx(v, prefs)
                if (restoreY > 0) {
                    if (System.currentTimeMillis() - restoreAt > 20000) {
                        restoreY = 0                                   // kedaluwarsa: halaman yang diharapkan tidak kunjung selesai
                    } else if (ReadingPos.sameDocument(url, restoreUrl)) {
                        val y = restoreY                               // hanya untuk halaman yang posisinya disimpan
                        restoreY = 0
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
                    if (u.startsWith("http://") || u.startsWith("https://")) failedUrl = u
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
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
    if (popupBlockOn() && blockCross) {
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
                if (baseDomain(u.host) == siteBase && !isPopupAd(u.host)) {
                    webView.loadUrl(u.toString())
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

        webView.setDownloadListener { url, _, _, _, _ ->
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
            if (savedInstanceState != null) {
                firstLoad = true
                restored = webView.restoreState(savedInstanceState) != null
            }
            if (!restored) {
                if (currentUrl.isBlank()) askUrl(cancelable = false) else resumeOrLoad()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 10/15 = memori menipis saat aplikasi di layar; >= 60 = proses kandidat dibuang sistem
        if (level == 10 || level == 15 || level >= 60) {
            logEvent(this, "memori sistem menipis (onTrimMemory=" + level + ") " + ramSnapshot(this))
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
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        DnsProxy.stop(this)   // hanya bila Activity ini pemilik proxy (Activity lama tidak boleh mematikan milik yang baru)
        super.onDestroy()
    }

    // ---------- DNS kustom (DNS-over-HTTPS lewat proxy lokal) ----------
    private fun dnsMode(): String = prefs.getString("dns", "off") ?: "off"

    // Alamat DoH untuk mode terpilih. null = DNS sistem, ATAU alamat kustom tersimpan tidak valid (dnsInvalid() membedakan).
    private fun dnsUrl(): String? {
        val m = dnsMode()
        return when (m) {
            "cloudflare", "google" -> DnsProxy.PRESETS[m]
            "custom" -> prefs.getString("dns_custom", "")?.trim()?.takeIf { DnsProxy.validate(it) == null }
            else -> null
        }
    }

    private fun dnsInvalid(): Boolean = dnsMode() == "custom" && dnsUrl() == null

    private fun dnsLabel(): String {
        val name = when (dnsMode()) {
            "cloudflare" -> "Cloudflare"
            "google" -> "Google"
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
        val names = arrayOf("Sistem (bawaan HP)", "Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Kustom (alamat DoH)")
        val keys = arrayOf("off", "cloudflare", "google", "custom")
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
            .setTitle("Alamat DoH (format JSON)")
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

    private fun askUrl(cancelable: Boolean) {
        val input = EditText(this).apply {
            hint = "contoh: komiku.org"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val m = (20 * resources.displayMetrics.density).toInt()
            setPadding(m, m / 2, m, 0); addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Alamat web")
            .setView(box)
            .setCancelable(cancelable)
            .setPositiveButton("Simpan", null)
        if (cancelable) builder.setNegativeButton("Batal", null)
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalize(input.text.toString())
                if (url == null) input.error = "Alamat tidak valid"
                else { currentUrl = url; webView.clearHistory(); load(); dlg.dismiss() }
            }
        }
        dlg.show()
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
                MenuAction("🛡️", "Perlindungan", adOn || trackerBlockOn(),
                    if (adOn || trackerBlockOn()) (adBlocked.get() + trackerBlocked.get()).toString() + " diblokir" else "OFF") { showProtection() },
                MenuAction("🔄", "Segarkan") { if (onErrorPage()) retryFailed() else webView.reload() },
                MenuAction("🏠", "Beranda") { load() },
                MenuAction("🔖", "Situs") { showSites() },
                MenuAction("⚙️", "Pengaturan") { showSettings() }
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
            adRows.add(SettingRow.Toggle("🛡️", "Pemblokir iklan", "Blokir iklan dari daftar filter", adBlockOn()) { on -> setAdBlock(on) })
            adRows.add(SettingRow.Toggle("🪟", "Pemblokir popup", "Cegah jendela popup dan pindah domain yang tidak diminta", popupBlockOn()) { on ->
                prefs.edit().putBoolean("popupblock", on).apply()
            })
            adRows.add(SettingRow.Action("🎯", "Tandai elemen iklan", "Sembunyikan bagian halaman yang masih mengganggu") {
                dismissMenuSheet()
                startPicker()
            })
            // hanya muncul bila memang ada elemen yang disembunyikan di situs ini
            if (hidden.isNotEmpty()) {
                adRows.add(SettingRow.Action("🧹", "Elemen tersembunyi", hidden.size.toString() + " elemen di situs ini, ketuk untuk memulihkan") {
                    dismissMenuSheet()
                    showHiddenElements()
                })
            }
            listOf(
                SettingSection("Pemblokir iklan", adRows),
                SettingSection("Perlindungan privasi", listOf(
                    SettingRow.Toggle("🕵️", "Pelacak",
                        if (trackerBlocked.get() > 0) trackerBlocked.get().toString() + " diblokir di halaman ini" else "Blokir pelacak analitik dan pengukuran",
                        trackerBlockOn()) { on ->
                        prefs.edit().putBoolean("trackerblock", on).apply()
                        if (!onErrorPage()) webView.reload()
                    },
                    SettingRow.Value("🚫", "GPC", "", if (gpcOn()) "Aktif" else "Mati", if (gpcOn()) 1 else 0) {
                        dismissMenuSheet()
                        showSettings()
                    },
                    SettingRow.Value("🔒", "Koneksi", "",
                        if (cur.startsWith("https://")) "HTTPS" else if (cur.startsWith("http://")) "HTTP (tidak aman)" else "-",
                        if (cur.startsWith("https://")) 1 else if (cur.startsWith("http://")) -1 else 0, null)
                ))
            )
        }
    }

    // Gulir otomatis dan gulir ketuk-tepi: satu menu, tetapi saklar on/off-nya terpisah
    private fun showScrollPopup() {
        showPopupCard(this, "Gulir", listOf(
            SettingRow.Toggle("⏬", "Gulir otomatis", "Halaman bergulir sendiri; panel kecepatan muncul di layar", autoPanel.modeOn) { on ->
                if (on != autoPanel.modeOn) autoPanel.toggle()
                if (on) dismissMenuSheet()
            },
            SettingRow.Toggle("👆", "Gulir ketuk-tepi", "Ketuk tepi layar untuk menggulir halaman", prefs.getBoolean("edge_on", true)) { on ->
                prefs.edit().putBoolean("edge_on", on).apply()
            }
        ))
    }

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
                SettingSection("Akun", listOf(
                    SettingRow.Action("👤", "Akun dan sesi masuk",
                        if (parts.isEmpty()) "Belum ada sesi masuk terdeteksi" else parts.joinToString(" · ")) {
                        showAccounts()
                    }
                )),
                SettingSection("Tampilan dan membaca", listOf(
                    SettingRow.Toggle("↻", "Tarik untuk menyegarkan", "Tarik halaman ke bawah dari bagian atas untuk memuat ulang", prefs.getBoolean("pull_refresh", true)) { on ->
                        prefs.edit().putBoolean("pull_refresh", on).apply()
                        swipe.isEnabled = on
                    },
                    SettingRow.Toggle("🖼️", "Pramuat gambar", "Memuat gambar di depan posisi baca lebih awal", preloadOn()) { on ->
                        prefs.edit().putBoolean("preload", on).apply()
                        if (on) injectPreload(webView) else webView.evaluateJavascript("window.__preOff=1", null)
                    },
                    SettingRow.Level(
                        "📚", "Gambar dimuat di depan", "Otomatis menyesuaikan koneksi dan RAM perangkat",
                        0, 12, preloadAhead(),
                        { v -> if (v == 0) "Otomatis" else v.toString() + " gambar" }
                    ) { v ->
                        prefs.edit().putInt("preload_ahead", v).apply()
                        if (preloadOn()) injectPreload(webView)
                    },
                    SettingRow.Level(
                        "⚡", "Unduhan bersamaan", "Jumlah gambar yang diunduh sekaligus oleh pramuat",
                        1, 4, preloadParallel(),
                        { v -> v.toString() + " sekaligus" }
                    ) { v ->
                        prefs.edit().putInt("preload_parallel", v).apply()
                        if (preloadOn()) injectPreload(webView)
                    },
                    SettingRow.Toggle("👆", "Gulir ketuk-tepi", "Ketuk tepi layar untuk menggulir halaman", prefs.getBoolean("edge_on", true)) { on ->
                        prefs.edit().putBoolean("edge_on", on).apply()
                    }
                )),
                SettingSection("Privasi dan pemblokiran", listOf(
                    SettingRow.Toggle("🛡️", "Pemblokir iklan", "Saklar utama: blokir iklan, pelacak, dan jendela iklan", adBlockOn()) { on ->
                        setAdBlock(on)
                    },
                    SettingRow.Toggle("🪟", "Pemblokir popup", "Cegah jendela popup yang tidak diminta", popupBlockOn()) { on ->
                        prefs.edit().putBoolean("popupblock", on).apply()
                    },
                    SettingRow.Toggle("🕵️", "Pelacak", "Blokir pelacak analitik dan pengukuran (daftar bawaan)", trackerBlockOn()) { on ->
                        prefs.edit().putBoolean("trackerblock", on).apply()
                        if (!onErrorPage()) webView.reload()
                    },
                    SettingRow.Toggle("🛑", "Cegah pengalihan domain", "Blokir pindah ke domain lain (berlaku per situs)", blockCross) { on ->
                        blockCross = on
                        Toast.makeText(this, if (on) "Pengalihan domain diblokir" else "Pengalihan domain diizinkan", Toast.LENGTH_SHORT).show()
                    },
                    SettingRow.Toggle("🧩", "Penyembunyi elemen iklan", "Sembunyikan kotak iklan yang tersisa (butuh pemblokir iklan aktif)", prefs.getBoolean("cosmetic", false)) { on ->
                        prefs.edit().putBoolean("cosmetic", on).apply()
                        Toast.makeText(this, if (on) "Diaktifkan, memproses daftar..." else "Dimatikan", Toast.LENGTH_SHORT).show()
                        logEvent(this, "filter kosmetik " + (if (on) "ON" else "OFF"))
                        thread {
                            parseLists()
                            runOnUiThread { if (!isDestroyed) webView.reload() }
                        }
                    },
                    SettingRow.Action("⬇️", "Perbarui daftar filter", "Unduh daftar iklan terbaru") {
                        Toast.makeText(this, "Mengunduh daftar filter...", Toast.LENGTH_SHORT).show()
                        refreshLists(true)
                    }
                )),
                SettingSection("Privasi lanjutan", listOf(
                    SettingRow.Toggle("🚫", "Kontrol Privasi Global (GPC)", "Beri sinyal \"jangan jual data saya\" ke situs (lewat JavaScript)", gpcOn()) { on ->
                        prefs.edit().putBoolean("gpc", on).apply()
                        applyDocStart()
                    },
                    SettingRow.Toggle("🔗", "Perlindungan tautan AMP", "Lewati pengalihan Google, buka situs aslinya", ampOn()) { on ->
                        prefs.edit().putBoolean("amp_unwrap", on).apply()
                    },
                    SettingRow.Toggle("🧼", "Pembersih pelacak URL", "Hapus parameter pelacak (utm_, fbclid, gclid, dll.) saat membuka tautan", urlCleanOn()) { on ->
                        prefs.edit().putBoolean("url_clean", on).apply()
                    },
                    SettingRow.Toggle("🖐️", "Perlindungan sidik jari (dasar)", "Menyamarkan bahasa dan spesifikasi perangkat. Beberapa situs bisa tampil berbahasa Inggris", fpOn()) { on ->
                        prefs.edit().putBoolean("fingerprint", on).apply()
                        applyDocStart()
                        Toast.makeText(this, "Berlaku mulai halaman berikutnya", Toast.LENGTH_SHORT).show()
                    },
                    SettingRow.Toggle("🍪", "Blokir cookie pihak ketiga", "Blokir pelacakan lintas situs; dapat mengganggu login", block3pOn()) { on ->
                        prefs.edit().putBoolean("block3p", on).apply()
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !on)
                        Toast.makeText(this, "Muat ulang halaman agar berlaku", Toast.LENGTH_SHORT).show()
                    },
                    SettingRow.Toggle("🔒", "Paksa HTTPS", "Naikkan tautan http:// ke https:// (situs yang hanya mendukung HTTP tidak akan terbuka)", httpsForceOn()) { on ->
                        prefs.edit().putBoolean("https_force", on).apply()
                    }
                )),
                SettingSection("Jaringan", listOf(
                    SettingRow.Action("🧭", "DNS aman (DoH)", dnsLabel()) { showDnsDialog() }
                )),
                SettingSection("Lanjutan", listOf(
                    SettingRow.Action("🩺", "Diagnostik", "Ringkasan teknis untuk pelaporan masalah") {
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
        loadSites().forEach { add(it.second) }
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
    @Volatile private var googleFetching = false
    @Volatile private var googleFetchAt = 0L

    // Alamat email akun Google yang sedang masuk (hanya di memori, tidak disimpan). Memakai cookie sesi aplikasi sendiri;
    // bila gagal/format berubah, daftar tetap kosong dan akun hanya ditampilkan sebagai "sesi aktif".
    private fun refreshGoogleEmails() {
        val now = System.currentTimeMillis()
        if (googleFetching || now - googleFetchAt < 60000) return   // jangan menembak server berulang kali
        googleFetchAt = now
        googleFetching = true
        val cookie = CookieManager.getInstance().getCookie("https://accounts.google.com/") ?: ""
        val ua = webView.settings.userAgentString
        thread {
            var found: List<String> = emptyList()
            try {
                val c = java.net.URL("https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Cookie", cookie)
                c.setRequestProperty("User-Agent", ua)
                if (c.responseCode == 200) {
                    val buf = CharArray(20000)
                    val n = c.inputStream.bufferedReader().use { it.read(buf) }
                    val body = if (n > 0) String(buf, 0, n) else ""
                    found = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(body).map { it.value }.distinct().take(5).toList()
                }
                c.disconnect()
            } catch (e: Exception) {
            }
            googleFetching = false
            runOnUiThread {
                if (!isDestroyed && found != googleEmails) {
                    googleEmails = found
                    refreshSettingsSheet()
                }
            }
        }
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
        if (info.site == "google.com") googleEmails = emptyList()
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
                googleEmails = emptyList()
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

    private fun loadSites(): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = org.json.JSONArray(prefs.getString("sites", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.getString("n") to o.getString("u"))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun storeSites(list: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("n", it.first).put("u", it.second)) }
        prefs.edit().putString("sites", arr.toString()).apply()
    }

    private fun addSiteIfNew(url: String) {
        if (url.isBlank()) return
        val list = loadSites()
        if (list.any { it.second == url }) return
        val host = Uri.parse(url).host?.removePrefix("www.") ?: url
        list.add(host to url)
        storeSites(list)
    }

    private fun openSite(url: String) {
        currentUrl = url
        webView.clearHistory()
        load()
    }

    private fun showSites() {
        addSiteIfNew(currentUrl)
        showSitesSheet(
            this,
            loadSites(),
            currentUrl,
            { url -> openSite(url) },
            { url -> storeSites(loadSites().filter { it.second != url }) },
            { askUrl(cancelable = true) },
            { saveCurrentPage() },
            { addSiteManually() }
        )
    }

    // Simpan alamat halaman yang sedang dibuka (bukan hanya alamat awal) ke daftar Situs
    private fun saveCurrentPage() {
        val u = if (webViewDead) null else webView.url
        if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) {
            Toast.makeText(this, "Tidak ada halaman web yang sedang dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        val list = loadSites()
        if (list.any { it.second == u }) {
            Toast.makeText(this, "Alamat ini sudah tersimpan", Toast.LENGTH_SHORT).show()
        } else {
            val title = webView.title?.trim().orEmpty()
            val name = if (title.isNotEmpty()) title.take(40) else (Uri.parse(u).host?.removePrefix("www.") ?: u)
            list.add(name to u)
            storeSites(list)
            Toast.makeText(this, "Alamat tersimpan", Toast.LENGTH_SHORT).show()
        }
        showSites()
    }

    // Tulis alamat (dan nama, opsional) sendiri; hanya disimpan ke daftar, tidak langsung dibuka
    private fun addSiteManually() {
        val dp = resources.displayMetrics.density
        val nameIn = EditText(this).apply {
            hint = "Nama (boleh kosong)"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val urlIn = EditText(this).apply {
            hint = "Alamat, contoh: komiku.org/baca/abc"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (20 * dp).toInt()
            setPadding(m, m / 2, m, 0)
            addView(nameIn)
            addView(urlIn)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Simpan alamat")
            .setView(box)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalize(urlIn.text.toString())
                if (url == null) {
                    urlIn.error = "Alamat tidak valid"
                } else {
                    val list = loadSites()
                    val name = nameIn.text.toString().trim().take(40).ifEmpty { Uri.parse(url).host?.removePrefix("www.") ?: url }
                    val idx = list.indexOfFirst { it.second == url }
                    if (idx >= 0) list[idx] = name to url else list.add(name to url)
                    storeSites(list)
                    Toast.makeText(this, "Alamat tersimpan", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    showSites()
                }
            }
        }
        dlg.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDead) webView.saveState(outState)
    }
}
