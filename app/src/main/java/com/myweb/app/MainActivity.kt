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
    @Volatile private var pickerToken: String? = null
    private var webViewDead = false
    private var lastSslToast = 0L
    private var lastUpgradeUrl = ""
    private var lastUpgradeAt = 0L
    private val warnedHttp = HashSet<String>()

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    inner class Bridge {
        @JavascriptInterface
        fun retry() { runOnUiThread { if (onErrorPage()) load() } }

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

    private fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty() || isFirstParty(host)) return false
        return sharedRules.isBlocked(host)
    }

    // Untuk jendela popup dan pengalihan otomatis: aturan $popup ikut berlaku
    private fun isPopupAd(host: String?): Boolean {
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
        val cosmeticOn = prefs.getBoolean("cosmetic", false)
        val listCss = if (host != null && cosmeticOn) sharedRules.cssFor(host) else ""
        // jumlah aturan yang disuntikkan ke halaman ini (tanpa host) supaya beban kosmetik per halaman bisa dinilai dari log
        if (logStats && cosmeticOn) logEvent(this, "kosmetik halaman: " + listCss.count { it == '\n' } + " aturan, " + listCss.length / 1024 + " KB")
        val css = listCss + "\n" + userCss() + "\n" + BASE_CSS
        val js = "(function(){if(document.getElementById('__adb'))return;var s=document.createElement('style');s.id='__adb';s.textContent=" +
            JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})();"
        v.evaluateJavascript(js, null)
    }
    
    override fun onPause() {
    super.onPause()
    LoginSupport.save()
    autoScroller.stop()
    saveReadingPosition()
    }

    // Simpan halaman + posisi scroll terakhir supaya aplikasi dibuka lagi di tempat yang sama
    private fun saveReadingPosition() {
        if (webViewDead) return
        val u = webView.url ?: return
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        val e = prefs.edit()
        if (prefs.getString("last_page", null) != u) e.putInt("last_scroll", 0)
        e.putString("last_page", u).apply()
        webView.evaluateJavascript(
            "(function(){return Math.round(window.scrollY||document.documentElement.scrollTop||0);})()"
        ) { r ->
            val y = r?.trim('"')?.toIntOrNull() ?: return@evaluateJavascript
            prefs.edit().putInt("last_scroll", y).apply()
        }
    }

    // Buka halaman terakhir (kalau masih satu situs dengan alamat web yang dipilih), kalau tidak ke halaman awal
    private fun resumeOrLoad() {
        val last = prefs.getString("last_page", null)
        if (!last.isNullOrBlank() &&
            baseDomain(Uri.parse(last).host) == baseDomain(Uri.parse(currentUrl).host)) {
            restoreY = prefs.getInt("last_scroll", 0)
            firstLoad = true
            webView.loadUrl(last)
        } else load()
    }

    private fun preloadOn() = prefs.getBoolean("preload", true)

    private fun injectPreload(v: WebView) {
        if (!preloadOn()) return
        v.evaluateJavascript("window.__preOff=0;" + PRELOAD_JS, null)
    }

    private fun restoreScroll(v: WebView, y: Int) {
        // gambar komik sering dimuat belakangan, jadi coba beberapa kali sampai halaman cukup panjang
        for (d in longArrayOf(150, 700, 1500, 2800)) {
            v.postDelayed({
                v.evaluateJavascript("(function(){if(window.scrollY<$y-5)window.scrollTo(0,$y);})()", null)
            }, d)
        }
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
        LoginSupport.setup(webView)
        webView.setBackgroundColor(Color.BLACK)
        progress = findViewById(R.id.progress)
        findViewById<ImageButton>(R.id.menuBtn).setOnClickListener { showMenu() }
        fabCtl = FabController(webView, findViewById<ImageButton>(R.id.menuBtn), { pickerActive }, { prefs.getBoolean("edge_on", true) }, { autoScroller.stop() })

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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
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
                if (!firstLoad && siteBase.isNotEmpty() && blockCross && baseDomain(u.host) != siteBase) {
                    Toast.makeText(this@MainActivity, "Pindah domain diblokir: " + u.host, Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && isAd(request.url.host)) {
                    val p = request.url.path ?: ""
                    if (p.endsWith(".js")) {
                        val js = "window.adsbygoogle=window.adsbygoogle||[];window.adsbygoogle.loaded=true;window.adsbygoogle.push=function(){};"
                        return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(js.toByteArray()))
                    }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (view != null && url != null && !url.startsWith("file:")) injectCss(view)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (url == null || url.startsWith("file:")) return
                renderGoneCount = 0
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
                    val y = restoreY
                    restoreY = 0
                    restoreScroll(v, y)
                }
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                // jangan memuat ulang halaman error bila halaman error itu sendiri yang gagal (cegah perulangan)
                if (req.isForMainFrame && !req.url.toString().startsWith("file:")) {
                    logEvent(this@MainActivity, "gagal memuat halaman: kode " + err.errorCode + " " + err.description)
                    v.loadUrl("file:///android_asset/error.html")
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
    if (blockCross) {
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
    closePopup()
    val popup = WebView(this@MainActivity)
    popup.settings.javaScriptEnabled = true
    popup.settings.domStorageEnabled = true
    popup.settings.allowFileAccess = false
    popup.settings.allowContentAccess = false
    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
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
        closePopup()
        fabCtl?.release()
        dismissMenuSheet()
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        DnsProxy.stop()
        super.onDestroy()
    }

    // ---------- DNS kustom (DNS-over-HTTPS lewat proxy lokal) ----------
    private fun dnsUrl(): String? {
        val m = prefs.getString("dns", "off") ?: "off"
        return when (m) {
            "cloudflare", "google" -> DnsProxy.PRESETS[m]
            "custom" -> prefs.getString("dns_custom", "")?.takeIf { it.startsWith("https://") }
            else -> null
        }
    }

    private fun dnsLabel(): String = when (prefs.getString("dns", "off")) {
        "cloudflare" -> "Cloudflare"
        "google" -> "Google"
        "custom" -> "Kustom"
        else -> "Sistem"
    }

    private fun applyDns(onReady: () -> Unit) {
        val url = dnsUrl()
        // callback bisa datang setelah Activity ditutup; jangan sentuh WebView/dialog saat itu
        val done: () -> Unit = { if (!isDestroyed) onReady() }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (url != null) Toast.makeText(this, "WebView di HP ini belum mendukung DNS kustom", Toast.LENGTH_LONG).show()
            done()
            return
        }
        val ex = ContextCompat.getMainExecutor(this)
        try {
            if (url == null) {
                ProxyController.getInstance().clearProxyOverride(ex) { DnsProxy.stop(); done() }
            } else {
                val port = DnsProxy.start(url)
                val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").addDirect().build()
                ProxyController.getInstance().setProxyOverride(cfg, ex) { done() }
            }
        } catch (e: Exception) {
            done()
        }
    }

    private fun setDns(mode: String) {
        prefs.edit().putString("dns", mode).apply()
        applyDns {
            Toast.makeText(this, "DNS: " + dnsLabel(), Toast.LENGTH_SHORT).show()
            if (onErrorPage()) load() else webView.reload()
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
                if (!u.startsWith("https://") || u.length < 12) input.error = "Harus diawali https://"
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
        if (currentUrl.isNotBlank()) {
            firstLoad = true
            webView.loadUrl(currentUrl)
        }
    }

    private fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.contains(" ")) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
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

    private fun showMenu() {
        showMenuSheet(
            this,
            listOf(
                MenuAction("🔄", "Muat ulang") { webView.reload() },
                MenuAction("🏠", "Awal") { load() },
                MenuAction("🎯", "Blok elemen") {
                    if (!onErrorPage()) {
                        val tk = pickerToken ?: newPickerToken().also { pickerToken = it }
                        pickerActive = true
                        webView.evaluateJavascript(PICKER_JS.replace("__PK_TOKEN__", tk), null)
                    }
                },
                MenuAction("🧭", "DNS: " + dnsLabel()) { showDnsDialog() }
            ),
            listOf(
                MenuAction("🌐", "Ganti alamat web") { askUrl(cancelable = true) },
                MenuAction("🔖", "Situs tersimpan") { showSites() },
                MenuAction("🎨", "Filter warna") { showColorFilterSheet(this, webView, prefs) },
                MenuAction("⏬", "Auto-scroll: " + (if (autoPanel.modeOn) "ON" else "OFF")) { autoPanel.toggle() },
                MenuAction("🖼️", "Muat gambar awal: " + (if (preloadOn()) "ON" else "OFF")) {
                    val on = !preloadOn()
                    prefs.edit().putBoolean("preload", on).apply()
                    if (on) injectPreload(webView) else webView.evaluateJavascript("window.__preOff=1", null)
                    Toast.makeText(this, if (on) "Muat gambar awal aktif" else "Muat gambar awal mati", Toast.LENGTH_SHORT).show()
                },
                MenuAction("🧩", "Filter kosmetik: " + (if (prefs.getBoolean("cosmetic", false)) "ON" else "OFF")) {
                    val on = !prefs.getBoolean("cosmetic", false)
                    prefs.edit().putBoolean("cosmetic", on).apply()
                    Toast.makeText(this, if (on) "Filter kosmetik aktif, memproses daftar..." else "Filter kosmetik mati", Toast.LENGTH_SHORT).show()
                    logEvent(this, "filter kosmetik " + (if (on) "ON" else "OFF"))
                    thread {
                        parseLists()
                        runOnUiThread { if (!isDestroyed) webView.reload() }
                    }
                },
                MenuAction("🩺", "Info diagnosa") { showDiagDialog(this, prefs, sharedRules.summary()) },
                MenuAction("👆", "Ketuk tepi: " + (if (prefs.getBoolean("edge_on", true)) "ON" else "OFF")) {
                    val on = !prefs.getBoolean("edge_on", true)
                    prefs.edit().putBoolean("edge_on", on).apply()
                    Toast.makeText(this, if (on) "Ketuk tepi aktif" else "Ketuk tepi mati", Toast.LENGTH_SHORT).show()
                },
                MenuAction("🧹", "Hapus blokir elemen situs ini") {
                    val key = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)
                    prefs.edit().remove(key).apply()
                    webView.reload()
                    Toast.makeText(this, "Blokir elemen situs ini dihapus", Toast.LENGTH_SHORT).show()
                },
                MenuAction("⬇️", "Perbarui daftar iklan") {
                    Toast.makeText(this, "Mengunduh daftar iklan...", Toast.LENGTH_SHORT).show()
                    refreshLists(true)
                }
            ),
            blockCross
        ) { on ->
            blockCross = on
            Toast.makeText(this, if (on) "Pindah domain diblokir" else "Pindah domain diizinkan", Toast.LENGTH_SHORT).show()
        }
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
            { askUrl(cancelable = true) }
        )
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDead) webView.saveState(outState)
    }
}
