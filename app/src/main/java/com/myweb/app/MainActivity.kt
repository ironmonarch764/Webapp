package com.myweb.app

import android.webkit.WebSettings
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var currentUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) { prefs.edit().putString("url", v).apply(); addSiteIfNew(v) }

    private fun crossKey() = "blockCross:" + baseDomain(Uri.parse(currentUrl).host)
    private var blockCross: Boolean
    get() = prefs.getBoolean(crossKey(), prefs.getBoolean("blockCross", true))
    set(v) = prefs.edit().putBoolean(crossKey(), v).apply()

    @Volatile private var adHosts: Set<String> = DEFAULT_AD_HOSTS
    @Volatile private var adCss: String = ""
    private var siteBase: String = ""
    private var firstLoad = false
    private var pickerActive = false

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
        fun addRule(sel: String) {
            runOnUiThread {
                if (!pickerActive) return@runOnUiThread
                pickerActive = false
                if (sel.length > 300 || sel.contains("{") || sel.contains("}") || sel.contains("\n")) return@runOnUiThread
                val key = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)
                val cur = prefs.getString(key, "") ?: ""
                prefs.edit().putString(key, if (cur.isEmpty()) sel else cur + "\n" + sel).apply()
                Toast.makeText(this@MainActivity, "Elemen diblokir", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun pickerDone() { runOnUiThread { pickerActive = false } }
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

    private fun baseDomain(host: String?): String {
        if (host.isNullOrBlank()) return ""
        val h = host.lowercase()
        val p = h.split(".")
        if (p.size <= 2) return h
        val sld = setOf("co", "com", "or", "web", "net", "org", "ac", "go", "my", "sch")
        return if (p.last().length == 2 && p[p.size - 2] in sld) p.takeLast(3).joinToString(".")
        else p.takeLast(2).joinToString(".")
    }

    private fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        if (siteBase.isNotEmpty() && baseDomain(host) == siteBase) return false
        val set = adHosts
        var h = host.lowercase()
        while (h.contains('.')) {
            if (set.contains(h)) return true
            h = h.substringAfter('.')
        }
        return false
    }

    private val domainRe = Regex("^[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}$")
    private val okOpts = setOf("third-party", "3p", "important", "popup", "all")
    private val badCss = listOf(":-abp-", ":has-text", ":xpath", ":matches", ":style(", ":remove(", ":upward", ":contains(", "+js(")

    private fun parseLists() {
        val hosts = HashSet<String>(DEFAULT_AD_HOSTS)
        val css = StringBuilder()
        var cssCount = 0
        for (i in LISTS.indices) {
            val f = File(filesDir, "list$i.txt")
            if (!f.exists()) continue
            val domainList = i <= 1
            try {
                f.useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                        if (line.startsWith("@@")) continue
                        if (line.startsWith("##")) {
                            if (cssCount < 0 && line.length < 200 && badCss.none { line.contains(it) }) {
                                css.append(line, 2, line.length).append("{display:none!important}\n")
                                cssCount++
                            }
                        } else if (line.startsWith("||")) {
                            val rest = line.substring(2)
                            val end = rest.indexOfFirst { it == '^' || it == '$' || it == '/' || it == '*' || it == ':' || it == '|' }
                            val dom = (if (end < 0) rest else rest.substring(0, end)).lowercase()
                            val opts: String? = when {
                                end < 0 -> ""
                                rest[end] == '^' -> {
                                    val t = rest.substring(end + 1)
                                    if (t.isEmpty()) "" else if (t[0] == '$') t.substring(1) else null
                                }
                                rest[end] == '$' -> rest.substring(end + 1)
                                else -> null
                            }
                            if (opts != null && domainRe.matches(dom) &&
                                (opts.isEmpty() || opts.split(",").all { it in okOpts })) {
                                hosts.add(dom)
                            }
                        } else if (domainList && line[0] != '#') {
                            val parts = line.split(Regex("\\s+"))
                            val cand = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1]
                            else if (parts.size == 1) parts[0] else null
                            if (cand != null && cand != "localhost" && domainRe.matches(cand.lowercase())) {
                                hosts.add(cand.lowercase())
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        adHosts = hosts
        adCss = css.toString()
    }

    private fun refreshLists(force: Boolean) {
        thread {
            parseLists()
            var downloaded = false
            for (i in LISTS.indices) {
                val f = File(filesDir, "list$i.txt")
                val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 3L * 24 * 3600 * 1000
                if (!force && !stale) continue
                try {
                    val c = URL(LISTS[i]).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    if (c.responseCode == 200) {
                        val tmp = File(filesDir, "list$i.tmp")
                        c.inputStream.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                        if (tmp.length() > 1000) {
                            tmp.copyTo(f, overwrite = true)
                            downloaded = true
                        }
                        tmp.delete()
                    }
                    c.disconnect()
                } catch (e: Exception) { }
            }
            if (downloaded) parseLists()
            if (force) runOnUiThread {
                Toast.makeText(this, "Daftar iklan: " + adHosts.size + " domain", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun userCss(): String {
        val key = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)
        val s = prefs.getString(key, "") ?: ""
        val rules = s.split("\n").filter { it.isNotBlank() }.joinToString("\n") { it + "{display:none!important}" }
        return if (rules.isEmpty()) "" else rules + "\nhtml,body{overflow:auto!important;position:static!important}"
    }

    private fun injectCss(v: WebView) {
        val css = adCss + "\n" + userCss() + "\n" + BASE_CSS
        val js = "(function(){if(document.getElementById('__adb'))return;var s=document.createElement('style');s.id='__adb';s.textContent=" +
            JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})();"
        v.evaluateJavascript(js, null)
    }
    
    override fun onPause() {
    super.onPause()
    LoginSupport.save()
    autoScroller.stop()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        goFullscreen()

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
                    if (scheme == "file") return false
                    if (req.hasGesture()) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                    }
                    return true
                }
                if (!req.isForMainFrame) return false
                if (isAd(u.host)) return true
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
                if (firstLoad) {
                    val h = Uri.parse(url).host
                    if (!h.isNullOrEmpty()) {
                        siteBase = baseDomain(h)
                        firstLoad = false
                    }
                }
                injectCss(v)
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) v.loadUrl("file:///android_asset/error.html")
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
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (baseDomain(u.host) == siteBase && !isAd(u.host)) {
                    webView.loadUrl(u.toString())
                } else {
                    Toast.makeText(this@MainActivity, "Popup diblokir", Toast.LENGTH_SHORT).show()
                }
                v.stopLoading()
                v.post { v.destroy() }
                return true
            }
        }
        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }
    closePopup()
    val popup = WebView(this@MainActivity)
    popup.settings.javaScriptEnabled = true
    popup.settings.domStorageEnabled = true
    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
    popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean = isAd(req.url.host)
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupView != null) closePopup()
                else if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        refreshLists(false)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else if (currentUrl.isBlank()) askUrl(cancelable = false) else load()
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

    private fun oldMenu() {
        val items = arrayOf(
            "Ganti alamat web",
            "Situs tersimpan",
            "Filter warna",
            "Auto-scroll & ketuk tepi",
            "Muat ulang",
            "Ke halaman awal",
            "Blok elemen (ketuk iklan)",
            "Hapus blokir elemen situs ini",
            "Perbarui daftar iklan",
            if (blockCross) "Pindah domain: DIBLOKIR (ketuk untuk izinkan)" else "Pindah domain: DIIZINKAN (ketuk untuk blokir)"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> askUrl(cancelable = true)
                    1 -> webView.reload()
                    2 -> load()
                    3 -> {
                        if (!onErrorPage()) {
                            pickerActive = true
                            webView.evaluateJavascript(PICKER_JS, null)
                        }
                    }
                    4 -> {
                        val key = "blk:" + baseDomain(Uri.parse(webView.url ?: "").host)
                        prefs.edit().remove(key).apply()
                        webView.reload()
                        Toast.makeText(this, "Blokir elemen situs ini dihapus", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        Toast.makeText(this, "Mengunduh daftar iklan...", Toast.LENGTH_SHORT).show()
                        refreshLists(true)
                    }
                    6 -> {
                        blockCross = !blockCross
                        Toast.makeText(this, if (blockCross) "Pindah domain diblokir" else "Pindah domain diizinkan", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }
    
    private var fabShown = true

    private fun setFab(show: Boolean) {
        if (show == fabShown) return
        fabShown = show
        val fab = findViewById<ImageButton>(R.id.menuBtn)
        val dist = 80 * resources.displayMetrics.density
        if (show) {
            fab.visibility = View.VISIBLE
            fab.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            fab.animate().alpha(0f).translationY(dist).setDuration(180)
                .withEndAction { if (!fabShown) fab.visibility = View.INVISIBLE }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFabAutoHide() {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldY ->
            val dy = scrollY - oldY
            if (scrollY < 10 || dy < -12) {
                setFab(true)
            } else if (dy > 12) {
                setFab(false)
            }
        }
        webView.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN) {
                downX = e.x
                downY = e.y
            } else if (e.action == android.view.MotionEvent.ACTION_UP) {
                if (Math.abs(e.x - downX) < slop && Math.abs(e.y - downY) < slop) setFab(true)
            }
            false
        }
    }

    private fun showMenu() {
        showMenuSheet(
            this,
            listOf(
                MenuAction("🔄", "Muat ulang") { webView.reload() },
                MenuAction("🏠", "Awal") { load() },
                MenuAction("🎯", "Blok elemen") {
                    if (!onErrorPage()) {
                        pickerActive = true
                        webView.evaluateJavascript(PICKER_JS, null)
                    }
                }
            ),
            listOf(
                MenuAction("🌐", "Ganti alamat web") { askUrl(cancelable = true) },
                MenuAction("🔖", "Situs tersimpan") { showSites() },
                MenuAction("🎨", "Filter warna") { showColorFilterSheet(this, webView, prefs) },
                MenuAction("⏬", "Auto-scroll: " + (if (autoPanel.modeOn) "ON" else "OFF")) { autoPanel.toggle() },
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
        webView.saveState(outState)
    }
}
